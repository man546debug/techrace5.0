package br.com.anderson.techrace

import java.io.File
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class TelemetryPdfReportTest {
    private fun sample(i: Int = 0, demo: Boolean = true, temperature: Double? = 83.5) =
        TelemetrySample(1793534400000L + i * 250L, demo, "2.0", 900.0 + i * 21,
            3.25 + i * .02, 18.75 + i % 8 * 1.5625, .45 + i % 10 * .15, 65.0 + i % 8 * 100, temperature)

    @Test fun csvMappingPreservesUnitsAndUnavailableTemperature() {
        val sample = TelemetrySample.fromCsv("1793534400000;REAL;2.0;1;980.0;4.25;31.25;0.842;800;;12;3;100")
        assertEquals(31.25, sample.correctionPercent, 0.0)
        assertEquals(.842, sample.mapVolts, 0.0)
        assertEquals(800.0, sample.lambdaMillivolts, 0.0)
        assertNull(sample.temperatureCelsius)
        assertFalse(sample.demo)
    }

    @Test fun temperatureStatisticsDoNotTreatMissingAsZero() {
        val result = TelemetryPdfReport.summarize(listOf(null, 70.0, 90.0, null))
        assertEquals(2, result.count)
        assertEquals(70.0, result.minimum!!, 0.0)
        assertEquals(80.0, result.average!!, 0.0)
        assertEquals(90.0, result.maximum!!, 0.0)
        assertNull(TelemetryPdfReport.summarize(listOf(null)).average)
    }

    @Test fun tablePaginationIncludesAllFortyRowBoundaries() {
        assertEquals(1, TelemetryPdfReport.pageCount(10000, false))
        assertEquals(2, TelemetryPdfReport.pageCount(40, true))
        assertEquals(3, TelemetryPdfReport.pageCount(41, true))
        assertEquals(251, TelemetryPdfReport.pageCount(10000, true))
    }

    @Test(expected = IllegalArgumentException::class)
    fun reportRejectsMixedRealAndDemoInsteadOfBlendingStatistics() {
        TelemetryPdfReport.create(listOf(sample(demo = false), sample(demo = true)), "test")
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyReportIsNotExportedAsAnEmptyHistory() {
        TelemetryPdfReport.create(emptyList(), "test")
    }

    @Test(expected = IllegalArgumentException::class)
    fun csvRejectsNonFiniteReadings() {
        TelemetrySample.fromCsv("1793534400000;REAL;2.0;1;980;4.25;NaN;0.842;800;80;12;3;100")
    }

    @Test fun vectorPdfIncludesChronologicalRowsAndCorrectCrossReferenceOffsets() {
        val samples = (0..94).map { sample(it, temperature = if (it % 10 == 0) null else 80.0 + it % 8) }.reversed()
        val bytes = TelemetryPdfReport.create(samples, "5.0.13", true,
            TimeZone.getTimeZone("America/Sao_Paulo"), generatedAt = 1793534400000L)
        val text = bytes.toString(Charsets.US_ASCII)
        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue(text.endsWith("%%EOF\n"))
        assertTrue(text.contains("/Count 4"))
        assertEquals(4, Regex("/Type /Page ").findAll(text).count())
        val xref = text.substringAfter("startxref\n").substringBefore('\n').toInt()
        assertTrue(text.substring(xref).startsWith("xref\n"))
        val objectOffsets = text.substring(xref).substringAfter("0000000000 65535 f \n")
            .substringBefore("trailer").trim().lines()
        objectOffsets.forEachIndexed { index, row ->
            val offset = row.take(10).toInt()
            assertTrue(text.substring(offset).startsWith("${index + 1} 0 obj\n"))
        }
        assertTrue(text.indexOf("01/11/2026 09:00:00.000") < text.indexOf("01/11/2026 09:00:00.250"))
        val output = File("build/pdf-previews/TechRace-Historico-Demonstracao.pdf")
        output.parentFile?.mkdirs()
        output.writeBytes(bytes)
    }

    @Test fun singleSampleAndUnavailableTemperatureStillProduceOnePageSummary() {
        val result = TelemetryPdfReport.create(listOf(sample(temperature = null)), "test", false)
            .toString(Charsets.US_ASCII)
        assertTrue(result.contains("/Count 1"))
        assertTrue(result.contains("Sem leitura v"))
    }

    @Test fun reportHandlesFullTenThousandSampleHistoryWithoutDroppingRows() {
        val data = TelemetryPdfReport.create((0 until 10000).map { sample(it) }, "test", true)
            .toString(Charsets.US_ASCII)
        assertTrue(data.contains("/Count 251"))
        assertTrue(data.contains("Amostras 9961 a 10000"))
    }
}
