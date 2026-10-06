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

    @Test fun adjustmentTemperatureEncodingMatchesPrincipalCpp() {
        val bytes = ByteArray(25)
        val expected = TechRaceDecoder.temperatureC(ModuleSettings.encodeTemperature(35.0))
        assertNotNull(expected)
        assertTrue(kotlin.math.abs(expected!! - 35) <= 2)
        bytes[17] = ModuleSettings.encodeTemperature(25.0).toByte()
        assertTrue(kotlin.math.abs(ModuleSettings(bytes).temperature(17) - 25) <= 2)
    }

    @Test fun windowsFlexFieldsUseTheSameEepromScales() {
        // Values visible in the Windows Flex Programável screen.
        assertEquals(3000, ModuleSettings.crankingDurationTicks(2460))
        assertEquals(2460, ModuleSettings.crankingDurationMilliseconds(0xB8, 0x0B))
        assertEquals(13, TechRaceProtocol.encodePercent(20.3125)) // Mist. Inicial, EEPROM offset 14
        assertEquals(25, ModuleSettings.lambdaThresholdRaw(490))
        assertEquals(67, ModuleSettings.lambdaAdjustmentRaw(14))
        assertEquals(500, ModuleSettings.rapidVariationTicks(400))
        assertEquals(400, ModuleSettings.rapidVariationMicroseconds(0xF4, 1))
        val rpmTicks = ModuleSettings.rpmTicks(980, 1.0)
        assertEquals(980, ModuleSettings.rpmTarget(rpmTicks, 1.0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectPartialSettings() { ModuleSettings(ByteArray(24)) }
}
