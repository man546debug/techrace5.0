package br.com.anderson.techrace

/** One existing CSV history row, using the same units displayed by the dashboard. */
data class TelemetrySample(
    val timestampMillis: Long,
    val demo: Boolean,
    val firmware: String,
    val rpm: Double,
    val injectionMs: Double,
    val correctionPercent: Double,
    val mapVolts: Double,
    val lambdaMillivolts: Double,
    val temperatureCelsius: Double?
) {
    init {
        require(timestampMillis >= 0)
        require(listOf(rpm, injectionMs, correctionPercent, mapVolts, lambdaMillivolts).all { it.isFinite() })
        require(temperatureCelsius == null || temperatureCelsius.isFinite())
    }

    companion object {
        fun fromCsv(row: String): TelemetrySample {
            val cells = row.split(';')
            require(cells.size == 13) { "Amostra de histórico incompleta" }
            require(cells[1] == "DEMO" || cells[1] == "REAL") { "Modo da amostra inválido" }
            return TelemetrySample(cells[0].toLong(), cells[1] == "DEMO", cells[2],
                cells[4].toDouble(), cells[5].toDouble(), cells[6].toDouble(), cells[7].toDouble(),
                cells[8].toDouble(), cells[9].takeIf { it.isNotBlank() }?.toDouble())
        }
    }
}
