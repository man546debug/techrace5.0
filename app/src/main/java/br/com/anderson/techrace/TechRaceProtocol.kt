package br.com.anderson.techrace

import java.util.Locale
import kotlin.math.round

/** Protocol implementation based on Porta_serial.cpp::Send_command. */
object TechRaceProtocol {
    const val BAUD_RATE = 19200
    const val LIVE_BYTES = 10

    val READ_LIVE_10: ByteArray get() = readRam(0x43, LIVE_BYTES)
    val READ_VERSION: ByteArray get() = readRequest(3, 1, 1)
    val READ_SETTINGS: ByteArray get() = readRequest(1, 1, 25)
    val READ_MIXTURE: ByteArray get() = readRequest(1, 3, 1)
    val READ_EXTENDED: ByteArray get() = readRam(0x4D, 10)

    /** Program.cpp selectors sent with command 13, address 1, one data byte. */
    val PROGRAM_STOP: ByteArray get() = programSelector(0)
    val PROGRAM_SONDA_RPM: ByteArray get() = programSelector(1)
    val PROGRAM_MAP: ByteArray get() = programSelector(2)

    fun readRam(address: Int, count: Int): ByteArray = readRequest(0, address, count)

    fun writeEeprom(address: Int, data: ByteArray): ByteArray = writeRequest(4, address, data)

    /** Matches Principal.cpp's printf("%.0f", value * 64 / 100) conversion. */
    fun encodePercent(value: Double): Int {
        require(value.isFinite() && value in 0.0..100.0)
        return round(value * 64.0 / 100.0).toInt().coerceIn(0, 255)
    }

    /**
     * Encodes the five manual-mixture choices exactly like Program.cpp.
     * The selected level is relative to the EEPROM correction limit (FINAL),
     * not a direct measurement of ethanol concentration.
     */
    fun manualMixtureRaw(levelPercent: Int, correctionLimitPercent: Double): Int {
        require(levelPercent == 0 || levelPercent == 25 || levelPercent == 50 ||
            levelPercent == 75 || levelPercent == 100) {
            "Selecione 0%, 25%, 50%, 75% ou 100%"
        }
        require(correctionLimitPercent.isFinite() && correctionLimitPercent in 10.0..60.0) {
            "Limite de correção fora da faixa do programa original (10% a 60%)"
        }
        val effectivePercent = when (levelPercent) {
            0 -> 1.0
            25 -> correctionLimitPercent * 0.25
            50 -> correctionLimitPercent * 0.50
            75 -> correctionLimitPercent * 0.75
            else -> correctionLimitPercent - 1.0
        }
        // printf("%.0f") in Program.cpp uses the default nearest-even rounding.
        return round(effectivePercent * 64.0 / 100.0).toInt().coerceIn(0, 255)
    }

    fun programSelector(selector: Int): ByteArray {
        require(selector in 0..2)
        return writeRequest(13, 1, byteArrayOf(selector.toByte()))
    }

    private fun readRequest(command: Int, address: Int, count: Int): ByteArray {
        require(address in 0..65535 && count in 1..64)
        // Desktop sends count bytes even for reads; zero-fill for deterministic frames.
        return request(command, address, ByteArray(count))
    }

    fun writeRequest(command: Int, address: Int, data: ByteArray): ByteArray {
        require(address in 0..65535 && data.isNotEmpty() && data.size <= 64)
        return request(command, address, data)
    }

    private fun request(command: Int, address: Int, data: ByteArray): ByteArray {
        val frame = ByteArray(data.size + 6)
        frame[0] = 0xF3.toByte()
        frame[1] = command.toByte()
        frame[2] = (address shr 8).toByte()
        frame[3] = address.toByte()
        frame[4] = data.size.toByte()
        data.copyInto(frame, destinationOffset = 5)
        frame[frame.lastIndex] = crc8(frame.copyOf(frame.size - 1))
        return frame
    }

    fun crc8(data: ByteArray): Byte {
        var crc = 0
        for (value in data) {
            crc = crc xor (value.toInt() and 255)
            repeat(8) {
                crc = if ((crc and 128) != 0) ((crc shl 1) xor 7) and 255
                else (crc shl 1) and 255
            }
        }
        return crc.toByte()
    }

    fun toHex(data: ByteArray): String = data.joinToString(" ") {
        String.format(Locale.US, "%02X", it.toInt() and 255)
    }

    /** Desktop Send_command accepts any non-empty reply whose CRC residual is zero. */
    fun isValidAck(response: ByteArray): Boolean = response.size >= 3 && crc8(response) == 0.toByte()

    fun extractLivePayload(response: ByteArray): ByteArray? {
        return extractResponse(response, 0, LIVE_BYTES)
    }

    fun extractResponse(response: ByteArray, command: Int, count: Int): ByteArray? {
        if (response.size != count + 3 || crc8(response) != 0.toByte()) return null
        // Comunicação PC.xls identifies byte 1 as function. Slave address is
        // not checked: sheet says 0x15/0x16 while the supplied EXE uses 0xF3.
        if ((response[1].toInt() and 255) != command) return null
        return response.copyOfRange(2, 2 + count)
    }
}

/** Accumulate fragmented reads, refusing extra/truncated data. */
class LiveResponseBuffer {
    private var bytes = byteArrayOf()
    fun append(chunk: ByteArray) { bytes += chunk }
    fun snapshot(): ByteArray = bytes.copyOf()
    fun payload(): ByteArray? = TechRaceProtocol.extractLivePayload(bytes)
}
