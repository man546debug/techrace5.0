package br.com.anderson.techrace

/**
 * Estimates only the ethanol share of the liters entered for one fueling.
 * The gasoline blend is configurable because its mandated composition can change.
 */
object FuelingEstimate {
    fun ethanolPercent(
        ethanolLiters: Double,
        gasolineLiters: Double,
        ethanolInGasolinePercent: Double = 32.0
    ): Double? {
        if (!ethanolLiters.isFinite() || !gasolineLiters.isFinite() ||
            !ethanolInGasolinePercent.isFinite() ||
            ethanolLiters < 0.0 || gasolineLiters < 0.0 ||
            ethanolInGasolinePercent !in 0.0..100.0) return null
        val total = ethanolLiters + gasolineLiters
        if (total <= 0.0) return null
        val ethanolEquivalent = ethanolLiters +
            gasolineLiters * ethanolInGasolinePercent / 100.0
        return (ethanolEquivalent * 100.0 / total).coerceIn(0.0, 100.0)
    }

    fun indexDelta(current: Int?, reference: Int?): Int? =
        if (current == null || reference == null) null else current - reference
}
