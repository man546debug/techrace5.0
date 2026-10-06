package br.com.anderson.techrace

import org.junit.Assert.*
import org.junit.Test

class ModuleQueryTest {
    private fun reply(function: Int, data: ByteArray): ByteArray {
        val body = byteArrayOf(0xF3.toByte(), function.toByte()) + data
        return body + TechRaceProtocol.crc8(body)
    }

    @Test fun versionRequestAndTwoByteResponse() {
        // Desktop requests count=1 but consumes TWO version bytes.
        assertEquals("F3 03 00 01 01 00 9C", TechRaceProtocol.toHex(TechRaceProtocol.READ_VERSION))
        val rx = reply(3, byteArrayOf(2, 0))
        assertArrayEquals(byteArrayOf(2, 0), TechRaceProtocol.extractResponse(rx, 3, 2))
        assertNull(TechRaceProtocol.extractResponse(rx, 3, 1))
        assertNull(TechRaceProtocol.extractResponse(rx, 0, 2))
    }

    @Test fun settingsRequestUsesAbsoluteAddressOneAnd25Bytes() {
        val tx = TechRaceProtocol.READ_SETTINGS
        assertEquals(31, tx.size)
        assertEquals(1, tx[1].toInt())
        assertEquals(1, tx[3].toInt())
        assertEquals(25, tx[4].toInt())
        assertEquals(0x57.toByte(), tx.last())
        val payload = ByteArray(25) { it.toByte() }
        assertArrayEquals(payload, TechRaceProtocol.extractResponse(reply(1, payload), 1, 25))
    }

    @Test fun validCrcWithWrongFunctionMustBeRejected() {
        assertNull(TechRaceProtocol.extractLivePayload(reply(1, ByteArray(10))))
        val rx = reply(1, ByteArray(25))
        assertNull(TechRaceProtocol.extractResponse(rx.copyOf(27), 1, 25))
        assertNull(TechRaceProtocol.extractResponse(rx + 0.toByte(), 1, 25))
        rx[12] = 1
        assertNull(TechRaceProtocol.extractResponse(rx, 1, 25))
    }

    @Test fun eepromOffsetsAndUnitsMatchDesktop() {
        val bytes = ByteArray(25)
        bytes[0] = 3
        bytes[2] = 16 // absolute 0x03 MIX, 25% on the module's 0..64 scale
        bytes[3] = 22 // absolute 0x04 FINAL
        bytes[12] = 100 // absolute 0x0D, 82 ms
        bytes[18] = 0xF4.toByte() // absolute 0x13, raw 500 -> 400 us
        bytes[19] = 1
        bytes[24] = 100
        val settings = ModuleSettings(bytes)
        bytes[0] = 0 // decoder must own an immutable input snapshot
        assertTrue(settings.mapFlag)
        assertTrue(settings.rpmFlag)
        val text = settings.describe(1.0)
        assertTrue(text.contains("34.38 %"))
        assertTrue(text.contains("400.00 µs"))
        assertTrue(text.contains("MIX manual: 16 (25.00 %)"))
        assertTrue(text.contains("Limite máximo de correção: 34.38 %"))
    }

    @Test fun mergingDraftPreservesReservedBytesAndProgrammingFlags() {
        val current = ByteArray(25) { (it + 10).toByte() }.also { it[0] = 0x03 }
        val draft = ByteArray(25) { (it + 100).toByte() }.also { it[0] = 0xFC.toByte() }
        val merged = ModuleSettings.mergeEditableDraft(current, draft)
        val editable = setOf(3, 4, 5, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 24)
        for (i in 1 until 25) {
            if (i in editable) assertEquals("editable offset $i", draft[i], merged[i])
            else assertEquals("preserved offset $i", current[i], merged[i])
        }
        assertEquals(0x03, merged[0].toInt() and 0x03)
        assertEquals(0xFC, merged[0].toInt() and 0xFC)
        assertEquals(current[2], merged[2]) // current manual mixture is preserved
    }

    @Test fun estimatedMixtureIndexUsesMapAndLambdaSignals() {
        assertEquals(30, TechRaceDecoder.estimatedMixtureIndex(1.0, 1000))
        assertEquals(70, TechRaceDecoder.estimatedMixtureIndex(2.0, 3000))
        assertEquals(50, TechRaceDecoder.estimatedMixtureIndex(1.25, 2500))
        assertNull(TechRaceDecoder.estimatedMixtureIndex(Double.NaN, 1000))
        assertNull(TechRaceDecoder.estimatedMixtureIndex(1.0, 5001))
    }

    @Test fun adjustmentTemperatureEncodingMatchesPrincipalCpp() {
        val bytes = ByteArray(25)
        val expected = TechRaceDecoder.temperatureC(ModuleSettings.encodeTemperature(35.0))
        assertNotNull(expected)
        assertTrue(kotlin.math.abs(expected!! - 35) <= 2)
        bytes[17] = ModuleSettings.encodeTemperature(25.0).toByte()
        assertTrue(kotlin.math.abs(ModuleSettings(bytes).temperature(17) - 25) <= 2)
    }

    @Test fun disconnectedEditorDefaultsMatchWindowsExample() {
        val settings = ModuleSettings.editableDefaults(1.0)
        assertEquals(2460, ModuleSettings.crankingDurationMilliseconds(
            settings.raw[12].toInt() and 255, settings.raw[13].toInt() and 255))
        assertEquals(980, ModuleSettings.rpmTarget(
            (settings.raw[4].toInt() and 255) + 256 * (settings.raw[5].toInt() and 255), 1.0))
        assertEquals(0xC0, settings.raw[0].toInt() and 255)
        assertEquals(20, settings.raw[14].toInt() * 100 / 64)
        assertTrue(kotlin.math.abs(settings.temperature(17) - 29) <= 2)
        assertTrue(kotlin.math.abs(settings.temperature(16) - 47) <= 2)
        assertTrue(kotlin.math.abs(settings.temperature(15) - 82) <= 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectPartialSettings() { ModuleSettings(ByteArray(24)) }
}
