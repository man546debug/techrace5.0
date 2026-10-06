package br.com.anderson.techrace

/**
 * Estimates the ethanol share of entered refueling volumes and calibrates the live
 * MAP/lambda index around the last known refueling composition.
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

    /** Suggested slope, in ethanol percentage points per live index point. */
    fun suggestedFactor(knownEthanolPercent: Double?, referenceIndex: Int?): Double? {
        if (knownEthanolPercent == null || !knownEthanolPercent.isFinite() ||
            knownEthanolPercent !in 0.0..100.0 || referenceIndex == null || referenceIndex < 0) return null
        if (referenceIndex == 0) return 0.0
        return knownEthanolPercent / referenceIndex
    }

    /** Keep the known refueling composition as the anchor and scale index movement around it. */
    fun calibratedPercent(
        knownEthanolPercent: Double?,
        referenceIndex: Int?,
        currentIndex: Int?,
        factorPercentPointsPerIndex: Double?
    ): Double? {
        if (knownEthanolPercent == null || !knownEthanolPercent.isFinite() ||
            knownEthanolPercent !in 0.0..100.0 ||
            referenceIndex == null || currentIndex == null ||
            factorPercentPointsPerIndex == null ||
            !factorPercentPointsPerIndex.isFinite() ||
            factorPercentPointsPerIndex !in 0.0..100.0) return null
        return (knownEthanolPercent +
            (currentIndex - referenceIndex) * factorPercentPointsPerIndex).coerceIn(0.0, 100.0)
    }

    fun indexDelta(current: Int?, reference: Int?): Int? =
        if (current == null || reference == null) null else current - reference
}
