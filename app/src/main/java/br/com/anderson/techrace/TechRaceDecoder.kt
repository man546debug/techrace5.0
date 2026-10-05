package br.com.anderson.techrace

import kotlin.math.ln

data class TechRaceLiveData(
    val rpm: Double,
    val injectionMs: Double,
    val correctionPercent: Double,
    val raw4: Int,
    val raw6: Int,
    val temperatureRaw: Int,
    val mapVoltage: Double,
    val lambdaMv: Int
)

object TechRaceDecoder {
    fun decode(data: ByteArray, rpmCalibration: Double = 1.0): TechRaceLiveData {
        require(data.size == 10) { "Payload deve ter exatamente 10 bytes" }
        require(rpmCalibration.isFinite() && rpmCalibration > 0)
        fun u(i: Int) = data[i].toInt() and 0xFF

        val rpmRaw = (u(1) shl 8) or u(0)
        val injectionRaw = (u(3) shl 8) or u(2)

        val rpm = if (rpmRaw == 0 || rpmCalibration == 0.0) 0.0
        else 60_000_000.0 / (rpmRaw * 3.2 * rpmCalibration)

        return TechRaceLiveData(
            rpm = rpm,
            injectionMs = injectionRaw * 0.0008,
            correctionPercent = (u(5) * 100 / 64).toDouble(),
            raw4 = u(4),
            raw6 = u(6),
            temperatureRaw = u(7),
            mapVoltage = u(8) * 2.5 / 255.0,
            lambdaMv = u(9) * 5000 / 255
        )
    }

    /** Principal.cpp temperature_calc(1100, 20.5, 3000, 1000, adc).
     * ADC rails deliberately show unavailable instead of a valid temperature.
     */
    fun temperatureC(adc: Int): Int? {
        if (adc !in 1..254) return null
        val voltage = (5f * adc) / 256f
        var resistance = voltage * 1000f / (5f - voltage)
        resistance /= 1100f
        resistance = ln(resistance.toDouble()).toFloat()
        resistance = (3000.0 * (20.5 + 273.15) /
            (resistance * (20.5 + 273.15) + 3000.0)).toFloat()
        val celsius = (resistance - 273.15).toFloat()
        if (!celsius.isFinite()) return null
        val truncated = celsius.toInt()
        return if (truncated > 120) 125 else truncated
    }
}
