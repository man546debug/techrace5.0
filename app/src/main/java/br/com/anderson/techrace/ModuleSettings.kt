package br.com.anderson.techrace

import java.util.Locale
import kotlin.math.roundToInt

/** Snapshot of EEPROM 0x01..0x19. */
class ModuleSettings(bytes: ByteArray) {
    val raw = bytes.copyOf()
    init { require(raw.size == 25) }
    private fun u(i: Int) = raw[i].toInt() and 255
    private fun word(i: Int) = u(i) + 256 * u(i + 1)
    fun temperature(i: Int): Int {
        val adc = u(i)
        val voltage = 5.0 * adc / 256.0
        val resistance = ((voltage * 1000.0) / (5.0 - voltage)).coerceAtLeast(0.01) / 1100.0
        val logarithm = kotlin.math.ln(resistance)
        val celsius = 3000.0 * (20.5 + 273.15) / (logarithm * (20.5 + 273.15) + 3000.0) - 273.15
        return if (celsius > 120) 125 else celsius.toInt()
    }
    private fun pct(i: Int) = fmt(u(i) * 100.0 / 64.0)
    private fun temp(i: Int) = "${temperature(i)} °C"
    private fun fmt(v: Double) = String.format(Locale.US, "%.2f", v)
    private fun enabled(mask: Int) = if (u(0) and mask != 0) "Ativado" else "Desativado"

    companion object {
        /**
         * Apply an offline draft's editable settings over a fresh EEPROM read.
         * Reserved bytes, current MIX, and the ECU's MAP/RPM programming flags stay intact.
         */
        fun mergeEditableDraft(current: ByteArray, draft: ByteArray): ByteArray {
            require(current.size == 25 && draft.size == 25)
            val merged = current.copyOf()
            intArrayOf(3, 4, 5, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 24)
                .forEach { merged[it] = draft[it] }
            merged[0] = ((current[0].toInt() and 0x03) or (draft[0].toInt() and 0xFC)).toByte()
            return merged
        }

        /** Edit-ready baseline from the Windows v3.2 screen; only used before the first ECU read. */
        fun editableDefaults(rpmFactor: Double): ModuleSettings {
            val data = ByteArray(25)
            data[0] = 0xC0.toByte() // heating and rapid acceleration enabled
            data[3] = 25 // 39.0625% maximum correction
            val rpm = rpmTicks(980, rpmFactor)
            data[4] = rpm.toByte(); data[5] = (rpm shr 8).toByte()
            val cranking = crankingDurationTicks(2460)
            data[12] = cranking.toByte(); data[13] = (cranking shr 8).toByte()
            data[14] = 13 // 20.3125% initial mixture
            data[15] = encodeTemperature(82.0).toByte()
            data[16] = encodeTemperature(47.0).toByte()
            data[17] = encodeTemperature(29.0).toByte()
            data[18] = 500.toByte(); data[19] = 1 // 400 µs minimum variation
            data[20] = 13 // 20.3125% extra at 20 ms
            data[21] = 4 // 6.25% cold extra injection
            data[22] = lambdaThresholdRaw(490).toByte()
            data[24] = lambdaAdjustmentRaw(14).toByte()
            return ModuleSettings(data)
        }

        fun crankingDurationTicks(milliseconds: Int): Int =
            (milliseconds / 0.82).roundToInt().coerceIn(0, 65535)

        fun crankingDurationMilliseconds(low: Int, high: Int): Int =
            ((low and 255) + 256 * (high and 255)).let { (it * 0.82).roundToInt() }

        fun lambdaAdjustmentRaw(seconds: Int): Int =
            (seconds / 0.21).roundToInt().coerceIn(0, 255)

        fun lambdaThresholdRaw(millivolts: Int): Int =
            (millivolts * 255.0 / 5000.0).roundToInt().coerceIn(0, 255)

        fun rapidVariationTicks(microseconds: Int): Int =
            (microseconds / 0.8).roundToInt().coerceIn(0, 65535)

        fun rapidVariationMicroseconds(low: Int, high: Int): Int =
            (((low and 255) + 256 * (high and 255)) * 0.8).roundToInt()

        fun rpmTicks(rpm: Int, factor: Double): Int {
            require(rpm in 600..1500 && factor in listOf(1.0, 2.0, 4.0, 8.0))
            return (60_000_000.0 / (rpm * 3.2 * factor)).roundToInt().coerceIn(1, 65535)
        }

        fun rpmTarget(ticks: Int, factor: Double): Int {
            require(ticks in 1..65535 && factor in listOf(1.0, 2.0, 4.0, 8.0))
            return (60_000_000.0 / (ticks * 3.2 * factor)).roundToInt()
        }

        /** Matches Principal.cpp::ad_calc(1100, 20.5, 3000, 1000, temperature). */
        fun encodeTemperature(celsius: Double): Int {
            val resistance = 1100.0 * kotlin.math.exp(3000.0 *
                (1.0 / (celsius + 273.15) - 1.0 / (20.5 + 273.15)))
            val voltage = 5.0 * resistance / (1000.0 + resistance)
            return (voltage * 255.0 / 5.0).toInt().coerceIn(0, 255)
        }
    }
    val mapFlag get() = u(0) and 1 != 0
    val rpmFlag get() = u(0) and 2 != 0

    fun describe(rpmFactor: Double): String = """
        Ajustes do módulo
        RPM alvo: ${if (word(4) == 0) "indisponível" else fmt(60_000_000.0 / (word(4) * 3.2 * rpmFactor))}
        RPM Cal.: ${rpmFactor.toInt()}x
        Tempo de injeção na partida: ${crankingDurationMilliseconds(u(12), u(13))} ms
        Partida externa: ${enabled(0x04)}
        Limitado pela lenta: ${enabled(0x08)}
        Mistura inicial: ${pct(14)} %
        Aquecimento do motor: ${enabled(0x80)}
        Injeção Extra: ${pct(21)} %
        Temperatura máx. do aquecimento: ${temp(16)}
        Limite máximo de correção: ${pct(3)} %
        Temperatura máxima da correção: ${temp(15)}
        Temperatura máxima da partida a frio: ${temp(17)}
        Aceleração rápida: ${enabled(0x40)}
        Variação mínima: ${fmt(rapidVariationMicroseconds(u(18), u(19)).toDouble())} µs
        Tempo extra a 20 ms: ${pct(20)} %
        Lambda lenta: ${enabled(0x10)}
        Tempo de ajuste Lambda: ${fmt(u(24) * 0.21)} s
        Valor Lambda: ${fmt(u(22) * 5000.0 / 255.0)} mV
        WideBand Sensor: ${enabled(0x20)}
        MIX manual: ${u(2)} (${pct(2)} %)

        EEPROM: ${TechRaceProtocol.toHex(raw)}
    """.trimIndent()
}
