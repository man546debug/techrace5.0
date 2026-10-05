package br.com.anderson.techrace

import java.util.Locale

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
    private fun bit(mask: Int) = if (u(0) and mask != 0) "1" else "0"

    companion object {
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
        Limite máximo de correção: ${pct(3)} %
        Limiar da mistura: ${pct(14)} %
        Temperatura da mistura: ${temp(17)}
        Temperatura limite da injeção extra: ${temp(16)}
        Temperatura de liberação da correção: ${temp(15)}
        Extra de aceleração rápida: ${pct(20)} %
        Variação inicial: ${fmt(word(18) * 0.8)} µs
        Injeção extra a frio: ${pct(21)} %
        MIX manual: ${u(2)} (${pct(2)} %)

        EEPROM: ${TechRaceProtocol.toHex(raw)}
    """.trimIndent()
}
