package br.com.anderson.techrace

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ProgrammingSettingsReaderTest {
    private fun reply(flags: Int, command: Int = 1): ByteArray {
        val payload = ByteArray(25).apply { this[0] = flags.toByte() }
        val body = byteArrayOf(0xF3.toByte(), command.toByte()) + payload
        return body + TechRaceProtocol.crc8(body)
    }

    @Test fun waitsForMapPersistenceAfterRuntimeCompletion() {
        var calls = 0
        val pauses = mutableListOf<Long>()
        val result = ProgrammingSettingsReader.read(1, { true }, { request ->
            assertArrayEquals(TechRaceProtocol.READ_SETTINGS, request)
            reply(if (++calls < 3) 2 else 3)
        }, { pauses += it })
        assertEquals(3, calls)
        assertEquals(listOf(500L, 500L), pauses)
        assertNull(result.error)
        assertTrue(result.settings!!.mapFlag)
        assertTrue(result.settings!!.rpmFlag)
    }

    @Test fun rpmChecksEepromBitTwoRatherThanRuntimeBitForty() {
        var calls = 0
        val result = ProgrammingSettingsReader.read(2, { true }, {
            reply(if (++calls == 1) 0x40 else 2)
        }, {})
        assertEquals(2, calls)
        assertNull(result.error)
        assertTrue(result.settings!!.rpmFlag)
        assertFalse(result.settings!!.mapFlag)
    }

    @Test fun neverReportsGreenWhenExpectedFlagRemainsClear() {
        var calls = 0
        val result = ProgrammingSettingsReader.read(1, { true }, { calls++; reply(2) }, {})
        assertEquals(4, calls)
        assertNotNull(result.error)
        assertFalse(result.settings!!.mapFlag)
        assertTrue(result.settings!!.rpmFlag)
    }

    @Test fun recoversFromTimeoutCorruptCrcAndWrongResponseFunction() {
        var calls = 0
        val result = ProgrammingSettingsReader.read(1, { true }, {
            when (++calls) {
                1 -> error("timeout")
                2 -> reply(3).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
                3 -> reply(3, command = 0)
                else -> reply(3)
            }
        }, {})
        assertEquals(4, calls)
        assertNull(result.error)
        assertTrue(result.settings!!.mapFlag)
    }

    @Test fun exhaustedReadFailuresDoNotReuseAnEarlierSnapshot() {
        var calls = 0
        val result = ProgrammingSettingsReader.read(1, { true }, {
            if (++calls == 1) reply(0) else byteArrayOf()
        }, {})
        assertEquals(4, calls)
        assertNull(result.settings)
        assertNotNull(result.error)
    }

    @Test fun ordinaryRefreshAcceptsConfirmedUnprogrammedFlagsWithoutDelay() {
        val result = ProgrammingSettingsReader.read(isCurrent = { true }, exchange = { reply(0) },
            pause = { fail("Valid EEPROM should finish immediately") })
        assertNull(result.error)
        assertFalse(result.settings!!.mapFlag)
        assertFalse(result.settings!!.rpmFlag)
    }

    @Test fun disconnectDuringBackoffStopsFurtherRequests() {
        var current = true
        var calls = 0
        try {
            ProgrammingSettingsReader.read(1, { current }, { calls++; reply(0) }, { current = false })
            fail("Disconnected refresh must be cancelled")
        } catch (_: CancellationException) { }
        assertEquals(1, calls)
    }

    @Test fun disconnectDuringExchangeRejectsTheReply() {
        var current = true
        try {
            ProgrammingSettingsReader.read(1, { current }, { current = false; reply(3) }, {})
            fail("A reply from the old connection must be rejected")
        } catch (_: CancellationException) { }
    }
}
