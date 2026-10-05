package br.com.anderson.techrace

import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    private fun response(payload: ByteArray = byteArrayOf(
        0x3E, 0x49, 0x88.toByte(), 0x13, 9, 22, 80, 128.toByte(), 153.toByte(), 46
    )): ByteArray {
        // Synthetic header: provided C++ does not define header contents.
        val body = byteArrayOf(0xF3.toByte(), 0) + payload
        return body + byteArrayOf(TechRaceProtocol.crc8(body))
    }

    @Test fun crcKnownVector() {
        assertEquals(0xF4.toByte(), TechRaceProtocol.crc8("123456789".toByteArray()))
    }

    @Test fun fullReadRequestMatchesDesktopShape() {
        val frame = TechRaceProtocol.READ_LIVE_10
        assertEquals("F3 00 00 43 0A 00 00 00 00 00 00 00 00 00 00 FD", TechRaceProtocol.toHex(frame))
        assertEquals(0.toByte(), TechRaceProtocol.crc8(frame))
        frame[0] = 0
        assertEquals(0xF3.toByte(), TechRaceProtocol.READ_LIVE_10[0]) // fresh frame
    }

    @Test fun extractsPayloadFromOffsetTwoWithoutCrc() {
        val rx = response()
        assertArrayEquals(rx.copyOfRange(2, 12), TechRaceProtocol.extractLivePayload(rx))
    }

    @Test fun everySingleBitCorruptionIsRejected() {
        val rx = response()
        for (index in rx.indices) for (bit in 0..7) {
            val corrupt = rx.copyOf()
            corrupt[index] = (corrupt[index].toInt() xor (1 shl bit)).toByte()
            assertNull(TechRaceProtocol.extractLivePayload(corrupt))
        }
    }

    @Test fun rejectsTruncationRawPayloadEchoAndTrailingBytes() {
        val rx = response()
        for (length in 0 until rx.size)
            assertNull(TechRaceProtocol.extractLivePayload(rx.copyOf(length)))
        assertNull(TechRaceProtocol.extractLivePayload(rx.copyOfRange(2, 12)))
        assertNull(TechRaceProtocol.extractLivePayload(rx + byteArrayOf(0)))
        assertNull(TechRaceProtocol.extractLivePayload(TechRaceProtocol.READ_LIVE_10))
        assertNull(TechRaceProtocol.extractLivePayload(rx + rx))
    }

    @Test fun allFragmentationBoundariesWork() {
        val rx = response()
        for (split in 1 until rx.size) {
            val buffer = LiveResponseBuffer()
            buffer.append(rx.copyOfRange(0, split))
            assertNull(buffer.payload())
            buffer.append(rx.copyOfRange(split, rx.size))
            assertArrayEquals(rx.copyOfRange(2, 12), buffer.payload())
        }
        val oneByte = LiveResponseBuffer()
        rx.forEach { oneByte.append(byteArrayOf(it)) }
        assertNotNull(oneByte.payload())
        oneByte.append(byteArrayOf(0))
        assertNull(oneByte.payload())
    }

    @Test fun conversionsMatchPrincipalCpp() {
        val payload = TechRaceProtocol.extractLivePayload(response())!!
        val data = TechRaceDecoder.decode(payload)
        assertEquals(1000.0, data.rpm, 0.0001)
        assertEquals(4.0, data.injectionMs, 0.0001)
        assertEquals(34.0, data.correctionPercent, 0.0) // C++ integer arithmetic
        assertEquals(1.5, data.mapVoltage, 0.0001)
        assertEquals(901, data.lambdaMv)
        assertEquals(80, data.raw6) // Y_PERCENT, never interpreted as flags
        assertEquals(9, data.raw4)
        assertEquals(500.0, TechRaceDecoder.decode(payload, 2.0).rpm, 0.0001)
    }

    @Test fun zeroAndUnsignedValues() {
        val zero = TechRaceDecoder.decode(ByteArray(10))
        assertEquals(0.0, zero.rpm, 0.0)
        assertEquals(0, zero.lambdaMv)
        val maximum = TechRaceDecoder.decode(ByteArray(10) { 255.toByte() })
        assertEquals(2.5, maximum.mapVoltage, 0.0)
        assertEquals(5000, maximum.lambdaMv)
        assertEquals(398.0, maximum.correctionPercent, 0.0)
    }

    @Test fun temperatureMatchesCppReference() {
        assertEquals(23, TechRaceDecoder.temperatureC(128))
        assertEquals(89, TechRaceDecoder.temperatureC(35))
        assertEquals(20, TechRaceDecoder.temperatureC(134))
        assertEquals(125, TechRaceDecoder.temperatureC(10))
        assertNull(TechRaceDecoder.temperatureC(0))
        assertNull(TechRaceDecoder.temperatureC(255))
        val temperatures = (1..254).map { TechRaceDecoder.temperatureC(it)!! }
        temperatures.zipWithNext().forEach { (a, b) -> assertTrue(a >= b) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidCalibrationIsRejected() { TechRaceDecoder.decode(ByteArray(10), Double.NaN) }

    @Test(expected = IllegalArgumentException::class)
    fun wrongPayloadSizeIsRejected() { TechRaceDecoder.decode(ByteArray(11)) }
    @Test fun programmingFramesMatchProgramCppSelectors() {
        assertEquals("F3 0D 00 01 01 00 CE", TechRaceProtocol.toHex(TechRaceProtocol.PROGRAM_STOP))
        assertEquals("F3 0D 00 01 01 01 C9", TechRaceProtocol.toHex(TechRaceProtocol.PROGRAM_SONDA_RPM))
        assertEquals("F3 0D 00 01 01 02 C0", TechRaceProtocol.toHex(TechRaceProtocol.PROGRAM_MAP))
        assertEquals(0.toByte(), TechRaceProtocol.crc8(TechRaceProtocol.PROGRAM_STOP))
        assertEquals(0.toByte(), TechRaceProtocol.crc8(TechRaceProtocol.PROGRAM_SONDA_RPM))
        assertEquals(0.toByte(), TechRaceProtocol.crc8(TechRaceProtocol.PROGRAM_MAP))
    }

    @Test fun genericAckUsesDesktopCrcRule() {
        val body = byteArrayOf(0xF3.toByte(), 13, 1)
        val ack = body + TechRaceProtocol.crc8(body)
        assertTrue(TechRaceProtocol.isValidAck(ack))
        val bad = ack.copyOf().also { it[1] = 12 }
        assertFalse(TechRaceProtocol.isValidAck(bad))
    }

    @Test fun manualMixtureChoicesMatchDesktopScalingAtSixtyPercentLimit() {
        assertEquals(1, TechRaceProtocol.manualMixtureRaw(0, 60.0))
        assertEquals(10, TechRaceProtocol.manualMixtureRaw(25, 60.0))
        assertEquals(19, TechRaceProtocol.manualMixtureRaw(50, 60.0))
        assertEquals(29, TechRaceProtocol.manualMixtureRaw(75, 60.0))
        assertEquals(38, TechRaceProtocol.manualMixtureRaw(100, 60.0))
    }

    @Test fun manualMixtureUsesCorrectionLimitAndKeepsLegacyEndpointOffsets() {
        assertEquals(1, TechRaceProtocol.manualMixtureRaw(0, 10.0))
        assertEquals(2, TechRaceProtocol.manualMixtureRaw(25, 10.0))
        assertEquals(3, TechRaceProtocol.manualMixtureRaw(50, 10.0))
        assertEquals(5, TechRaceProtocol.manualMixtureRaw(75, 10.0))
        assertEquals(6, TechRaceProtocol.manualMixtureRaw(100, 10.0))
    }

    @Test fun mixtureReadAndWriteUseEepromAddressThree() {
        assertEquals("F3 01 00 03 01 00 8E", TechRaceProtocol.toHex(TechRaceProtocol.READ_MIXTURE))
        assertEquals("F3 04 00 03 01 16 01", TechRaceProtocol.toHex(
            TechRaceProtocol.writeEeprom(3, byteArrayOf(22))
        ))
    }

    @Test fun adjustmentPercentEncodingMatchesEepromScale() {
        assertEquals(38, TechRaceProtocol.encodePercent(60.0))
        assertEquals(16, TechRaceProtocol.encodePercent(25.0))
        assertEquals(0, TechRaceProtocol.encodePercent(0.0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun manualMixtureRejectsUnsupportedLevel() {
        TechRaceProtocol.manualMixtureRaw(30, 60.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun manualMixtureRejectsInvalidCorrectionLimit() {
        TechRaceProtocol.manualMixtureRaw(50, 75.0)
    }

}
