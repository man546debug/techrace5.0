package br.com.anderson.techrace

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/** Small vector PDF writer: standard font references, WinAnsi text, no external SDK. */
object TelemetryPdfReport {
    const val ROWS_PER_PAGE = 40
    data class Summary(val minimum: Double?, val average: Double?, val maximum: Double?, val count: Int)

    fun summarize(values: List<Double?>): Summary {
        val valid = values.filterNotNull().filter { it.isFinite() }
        if (valid.isEmpty()) return Summary(null, null, null, 0)
        // Sum normalized terms so a long history cannot overflow an otherwise valid mean.
        return Summary(valid.minOrNull(), valid.sumOf { it / valid.size }, valid.maxOrNull(), valid.size)
    }

    fun pageCount(sampleCount: Int, includeTable: Boolean): Int {
        require(sampleCount >= 0)
        return 1 + if (includeTable) (sampleCount + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE else 0
    }

    fun create(samples: List<TelemetrySample>, appVersion: String, includeTable: Boolean = true,
        timeZone: TimeZone = TimeZone.getDefault(), generatedAt: Long = System.currentTimeMillis()): ByteArray {
        require(samples.isNotEmpty()) { "Não há leituras para exportar" }
        require(samples.map { it.demo }.distinct().size == 1) { "Exporte leituras reais e demonstração separadamente" }
        val ordered = samples.sortedBy { it.timestampMillis }
        val zone = timeZone.clone() as TimeZone
        fun date(ms: Long, pattern: String = "dd/MM/yyyy HH:mm:ss.SSS"): String =
            SimpleDateFormat(pattern, Locale.US).apply { this.timeZone = zone }.format(Date(ms))
        val mode = if (ordered.first().demo) "DEMONSTRAÇÃO - DADOS SIMULADOS" else "LEITURAS REAIS"
        val totalPages = pageCount(ordered.size, includeTable)
        val period = "${date(ordered.first().timestampMillis)} até ${date(ordered.last().timestampMillis)}"
        val pdf = PdfFile()
        fun footer(page: PdfPage, number: Int) {
            page.line(42.0, 798.0, 553.0, 798.0, GRAY, .5)
            page.text(42.0, 816.0, "TechRace $appVersion | ${date(generatedAt, "dd/MM/yyyy HH:mm:ss")}", 8.0, GRAY)
            page.textRight(553.0, 816.0, "$number / $totalPages", 8.0, GRAY)
        }
        val first = pdf.page()
        first.rect(0.0, 0.0, 595.0, 8.0, NAVY)
        first.text(42.0, 50.0, "TechRace | Histórico de telemetria", 20.0, NAVY, "F2")
        first.text(42.0, 74.0, mode, 10.0, if (ordered.first().demo) RED else BLUE, "F2")
        first.text(42.0, 97.0, "Período: $period", 9.0)
        first.text(42.0, 114.0, "Fuso horário: ${zone.id} | ${ordered.size} amostras da sessão", 9.0)
        val firmware = ordered.map { it.firmware }.distinct().joinToString(", ").take(80)
        first.text(42.0, 131.0, "Firmware informado: $firmware", 9.0)
        first.text(42.0, 151.0, "Correção informada pelo módulo; não representa o teor de etanol.", 8.0, GRAY)
        first.text(42.0, 165.0, "MAP em volts, sem conversão para pressão. Sonda em milivolts. Temperatura em °C.", 8.0, GRAY)

        first.rect(42.0, 187.0, 511.0, 24.0, LIGHT)
        first.text(50.0, 203.0, "Métrica", 9.0, NAVY, "F2")
        listOf("Mínimo", "Média", "Máximo").forEachIndexed { i, label ->
            first.textRight(315.0 + i * 110, 203.0, label, 9.0, NAVY)
        }
        val metrics = listOf(
            Metric("Correção (%)", ordered.map { it.correctionPercent }, ORANGE, 2),
            Metric("MAP (V)", ordered.map { it.mapVolts }, BLUE, 3),
            Metric("Sonda (mV)", ordered.map { it.lambdaMillivolts }, PURPLE, 0),
            Metric("Temperatura (°C)", ordered.map { it.temperatureCelsius }, RED, 1)
        )
        metrics.forEachIndexed { i, metric ->
            val y = 229.0 + i * 23
            first.text(50.0, y, metric.label, 10.0, metric.color)
            val stats = summarize(metric.values)
            listOf(stats.minimum, stats.average, stats.maximum).forEachIndexed { column, value ->
                first.textRight(315.0 + column * 110, y, number(value, metric.decimals), 9.0)
            }
        }
        first.text(42.0, 325.0, "Temperaturas indisponíveis ficam como '--' e não entram nas estatísticas.", 8.0, GRAY)
        first.text(42.0, 339.0, "Gráficos preservam os horários reais; lacunas acima de 5 s não são ligadas.", 8.0, GRAY)
        metrics.forEachIndexed { i, metric ->
            chart(first, 42.0, 356.0 + i * 104, 511.0, 94.0, ordered, metric, ::date)
        }
        footer(first, 1)

        if (includeTable) ordered.chunked(ROWS_PER_PAGE).forEachIndexed { index, rows ->
            val page = pdf.page()
            page.rect(0.0, 0.0, 595.0, 8.0, NAVY)
            page.text(42.0, 43.0, "Histórico completo de leituras", 16.0, NAVY, "F2")
            page.text(42.0, 63.0, mode, 9.0, if (ordered.first().demo) RED else BLUE, "F2")
            page.text(42.0, 82.0, period, 8.0)
            page.text(42.0, 99.0, "Fuso: ${zone.id} | Amostras ${index * ROWS_PER_PAGE + 1} a ${index * ROWS_PER_PAGE + rows.size}", 8.0, GRAY)
            page.rect(42.0, 116.0, 511.0, 28.0, LIGHT)
            val widths = listOf(122.0, 52.0, 64.0, 72.0, 54.0, 64.0, 83.0)
            val titles = listOf("Data / hora", "RPM", "Injeção", "Correção", "MAP", "Sonda", "Temperatura")
            val units = listOf("com milissegundos", "rpm", "ms", "%", "V", "mV", "°C")
            var x = 42.0
            titles.forEachIndexed { col, title ->
                page.text(x + 5, 127.0, title, 8.0, NAVY, "F2")
                page.text(x + 5, 139.0, units[col], 7.0, GRAY)
                x += widths[col]
            }
            rows.forEachIndexed { rowIndex, sample ->
                val top = 144.0 + rowIndex * 16
                if (rowIndex % 2 == 1) page.rect(42.0, top, 511.0, 16.0, PALE)
                val cells = listOf(date(sample.timestampMillis, "dd/MM/yyyy HH:mm:ss.SSS"), number(sample.rpm, 0),
                    number(sample.injectionMs, 2), number(sample.correctionPercent, 2), number(sample.mapVolts, 3),
                    number(sample.lambdaMillivolts, 0), number(sample.temperatureCelsius, 1))
                x = 42.0
                cells.forEachIndexed { col, cell ->
                    page.text(x + 5, top + 11, cell, if (col == 0) 7.5 else 8.0, INK, "F3")
                    x += widths[col]
                }
            }
            footer(page, index + 2)
        }
        return pdf.bytes()
    }

    private data class Metric(val label: String, val values: List<Double?>, val color: Rgb, val decimals: Int)
    private fun number(value: Double?, decimals: Int): String {
        if (value == null) return "--"
        val plain = String.format(Locale.US, "%.${decimals}f", value)
        return if (plain.length <= 12) plain else String.format(Locale.US, "%.3e", value)
    }

    private fun chart(page: PdfPage, x: Double, y: Double, width: Double, height: Double,
        samples: List<TelemetrySample>, metric: Metric, date: (Long, String) -> String) {
        page.text(x, y + 11, metric.label, 10.0, metric.color, "F2")
        val values = metric.values.filterNotNull()
        if (values.isEmpty()) {
            page.text(x + 70, y + 46, "Sem leitura válida nesta sessão", 9.0, GRAY)
            return
        }
        var low = values.minOrNull()!!
        var high = values.maxOrNull()!!
        if (high == low) {
            val pad = maxOf(abs(low) * .05, if (metric.label.startsWith("MAP")) .01 else 1.0)
            low -= pad; high += pad
        }
        val left = x + 70; val right = x + width
        val top = y + 23; val bottom = y + height - 17
        listOf(top, (top + bottom) / 2, bottom).forEach { row -> page.line(left, row, right, row, LIGHT, .5) }
        page.textRight(left - 8, top + 3, number(high, metric.decimals), 7.0, GRAY)
        page.textRight(left - 8, bottom + 3, number(low, metric.decimals), 7.0, GRAY)
        val start = samples.first().timestampMillis
        val span = (samples.last().timestampMillis - start).coerceAtLeast(1)
        var previous: Pair<Double, Double>? = null
        var previousTime = start
        samples.forEachIndexed { index, sample ->
            val value = metric.values[index]
            if (value == null) { previous = null; return@forEachIndexed }
            val px = left + (right - left) * (sample.timestampMillis - start).toDouble() / span
            val py = bottom - (bottom - top) * ((value - low) / (high - low)).coerceIn(0.0, 1.0)
            val prev = previous
            if (prev != null && sample.timestampMillis - previousTime <= 5000) {
                page.line(prev.first, prev.second, px, py, metric.color, .8)
            } else page.rect(px - .8, py - .8, 1.6, 1.6, metric.color)
            previous = px to py
            previousTime = sample.timestampMillis
        }
        page.text(left, y + height - 3, date(start, "HH:mm:ss.SSS"), 7.0, GRAY, "F3")
        page.textRight(right, y + height - 3, date(samples.last().timestampMillis, "HH:mm:ss.SSS"), 7.0, GRAY)
    }

    private data class Rgb(val r: Double, val g: Double, val b: Double) {
        fun command() = "${decimal(r)} ${decimal(g)} ${decimal(b)}"
    }
    private val INK = Rgb(.12, .15, .20)
    private val NAVY = Rgb(.07, .16, .28)
    private val GRAY = Rgb(.38, .43, .49)
    private val LIGHT = Rgb(.87, .90, .94)
    private val PALE = Rgb(.96, .97, .98)
    private val BLUE = Rgb(.10, .35, .76)
    private val PURPLE = Rgb(.46, .19, .68)
    private val ORANGE = Rgb(.79, .34, .04)
    private val RED = Rgb(.74, .14, .17)
    private fun decimal(value: Double) = String.format(Locale.US, "%.3f", value)

    private class PdfPage {
        val content = StringBuilder()
        fun text(x: Double, top: Double, value: String, size: Double, color: Rgb = INK, font: String = "F1") {
            content.append("${color.command()} rg BT /$font ${decimal(size)} Tf 1 0 0 1 ${decimal(x)} ${decimal(842 - top)} Tm (${literal(value)}) Tj ET\n")
        }
        fun textRight(right: Double, top: Double, value: String, size: Double, color: Rgb = INK) =
            text(right - value.length * size * .6, top, value, size, color, "F3")
        fun line(x1: Double, y1: Double, x2: Double, y2: Double, color: Rgb, width: Double) {
            content.append("q ${color.command()} RG ${decimal(width)} w ${decimal(x1)} ${decimal(842 - y1)} m ${decimal(x2)} ${decimal(842 - y2)} l S Q\n")
        }
        fun rect(x: Double, top: Double, width: Double, height: Double, color: Rgb) {
            content.append("${color.command()} rg ${decimal(x)} ${decimal(842 - top - height)} ${decimal(width)} ${decimal(height)} re f\n")
        }
    }

    private class PdfFile {
        private val pages = mutableListOf<PdfPage>()
        fun page(): PdfPage = PdfPage().also { pages += it }
        fun bytes(): ByteArray {
            val objects = mutableListOf(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Count ${pages.size} /Kids [${pages.indices.joinToString(" ") { "${6 + it * 2} 0 R" }}] >>",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Courier /Encoding /WinAnsiEncoding >>"
            )
            pages.forEachIndexed { index, page ->
                objects += "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R /F2 4 0 R /F3 5 0 R >> >> /Contents ${7 + index * 2} 0 R >>"
                val content = page.content.toString()
                objects += "<< /Length ${content.toByteArray(Charsets.US_ASCII).size} >>\nstream\n${content}endstream"
            }
            val out = ByteArrayOutputStream()
            fun write(value: String) { out.write(value.toByteArray(Charsets.US_ASCII)) }
            write("%PDF-1.4\n")
            val offsets = mutableListOf(0)
            objects.forEachIndexed { index, value ->
                offsets += out.size()
                write("${index + 1} 0 obj\n$value\nendobj\n")
            }
            val xref = out.size()
            write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
            offsets.drop(1).forEach { write(String.format(Locale.US, "%010d 00000 n \n", it)) }
            write("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
            return out.toByteArray()
        }
    }

    private fun literal(value: String): String = buildString {
        for (byte in value.toByteArray(Charset.forName("windows-1252"))) {
            val code = byte.toInt() and 255
            when {
                code == 40 || code == 41 || code == 92 -> { append('\\'); append(code.toChar()) }
                code < 32 || code > 126 -> { append('\\'); append(code.toString(8).padStart(3, '0')) }
                else -> append(code.toChar())
            }
        }
    }
}
