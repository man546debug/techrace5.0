package br.com.anderson.techrace

import org.junit.Assert.*
import org.junit.Test

class ProgrammingMonitorTest {
    private fun reply(vararg payload: Int, command: Int = 0): ByteArray {
        val bytes = byteArrayOf(0xF3.toByte(), command.toByte()) + payload.map { it.toByte() }.toByteArray()
        return bytes + TechRaceProtocol.crc8(bytes)
    }

    @Test fun readsTheSameSensorBlockThenRuntimeFlagsAsWindows() {
        val requests = mutableListOf<ByteArray>()
        val result = ProgrammingMonitor.read { request, _ ->
            requests += request
            if ((request[3].toInt() and 255) == 0x48)
                reply(5, 6, 7, 128, 51, 11, 12, 13, 14, 15)
            else reply(3, 0xFF)
        }
        assertEquals(listOf(0x48, 0x63), requests.map { it[3].toInt() and 255 })
        assertTrue(requests.all { it[1].toInt() == 0 && TechRaceProtocol.crc8(it).toInt() == 0 })
        assertEquals(listOf(10, 2), requests.map { it[4].toInt() })
        assertEquals(128 * 2.5 / 255, result.sensors!!.mapVoltage, 0.000001)
        assertEquals(1000, result.sensors!!.lambdaMv)
        assertEquals(3, result.flags)
    }

    @Test fun corruptTelemetryDoesNotHideCompletionFlagsOrReuseOldValues() {
        val result = ProgrammingMonitor.read { request, _ ->
            if (request[3].toInt() == 0x48) reply(0, 0, 0, 255, 255, 0, 0, 0, 0, 0).also {
                it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
            } else reply(0, 0)
        }
        assertNull(result.sensors)
        assertEquals(0, result.flags)
        assertNull(result.statusError)
    }

    @Test fun statusFailureDoesNotDiscardFreshSensorsAndCannotCompleteSession() {
        val session = ProgrammingSession(true)
        session.update(1)
        val result = ProgrammingMonitor.read { request, _ ->
            if (request[3].toInt() == 0x48) reply(0, 0, 0, 255, 255, 0, 0, 0, 0, 0)
            else error("timeout")
        }
        assertEquals(2.5, result.sensors!!.mapVoltage, 0.0)
        assertNull(result.flags)
        assertEquals("timeout", result.statusError)
        assertTrue(session.observedActive)
    }

    @Test fun telemetryExceptionStillAllowsStatusReadAndWrongFunctionIsRejected() {
        var reads = 0
        val result = ProgrammingMonitor.read { _, _ ->
            if (reads++ == 0) error("telemetry timeout") else reply(0x40, 0, command = 1)
        }
        assertEquals(2, reads)
        assertNull(result.flags)
        assertNull(result.sensors)
        assertNotNull(result.statusError)
    }

    @Test fun rejectsWrongLengthAndTreatsSensorBytesAsUnsigned() {
        try {
            ProgrammingSensors.decode(ByteArray(9))
            fail("Incomplete block accepted")
        } catch (_: IllegalArgumentException) { }
        val sample = ProgrammingSensors.decode(ByteArray(10) { 0xFF.toByte() })
        assertEquals(2.5, sample.mapVoltage, 0.0)
        assertEquals(5000, sample.lambdaMv)
    }
}
