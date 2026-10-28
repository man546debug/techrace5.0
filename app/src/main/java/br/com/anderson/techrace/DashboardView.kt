package br.com.anderson.techrace

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

class DashboardView(context: Context) : View(context) {

    enum class Nav { MONITOR, AJUSTES, PROGRAMACAO, DIAGNOSTICO, CONFIG }

    var onNavClick: ((Nav) -> Unit)? = null
    var onUsbClick: (() -> Unit)? = null
    var connectionLabel: String = "USB OTG"

    private val bg = Color.rgb(6, 9, 11)
    private val panel = Color.rgb(11, 15, 18)
    private val border = Color.rgb(38, 44, 49)
    private val white = Color.rgb(242, 244, 247)
    private val muted = Color.rgb(158, 164, 172)
    private val red = Color.rgb(255, 48, 48)
    private val green = Color.rgb(75, 210, 48)
    private val blue = Color.rgb(44, 108, 255)
    private val purple = Color.rgb(176, 72, 255)
    private val orange = Color.rgb(255, 153, 0)
    private val yellow = Color.rgb(255, 190, 24)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val graphRpm = ArrayDeque<Float>()
    private val graphMap = ArrayDeque<Float>()
    private val graphLambda = ArrayDeque<Float>()
    private val graphInjection = ArrayDeque<Float>()

    var connected: Boolean = false
        set(value) { field = value; invalidate() }
    var demoMode: Boolean = false
        set(value) { field = value; invalidate() }
    var autoReading: Boolean = false
        set(value) { field = value; invalidate() }
    private var hasData = false
    var rpm = 0.0
        private set
    var injectionMs = 0.0
        private set
    var correction = 0.0
        private set
    var mapVoltage = 0.0
        private set
    var lambdaMv = 0
        private set
    var temperatureC: Int? = null
        private set
    var mixtureIndex: Int? = null
        set(value) { field = value; invalidate() }
    var mixtureReferenceIndex: Int? = null
        set(value) { field = value; invalidate() }
    var mixtureReferenceEthanolPercent: Double? = null
        set(value) { field = value; invalidate() }
    var mixtureFactorPercentPerIndex: Double? = null
        set(value) { field = value; invalidate() }
    private var programmingFlagsKnown = false
    var mapProgrammed = false
        private set
    var sondaProgrammed = false
        private set
    var communicationOk = false
        private set
    var errorCount = 0
        private set

    private var rpmMin = Double.MAX_VALUE
    private var rpmMax = 0.0
    private var selectedNav = Nav.MONITOR

    fun updateData(data: TechRaceLiveData) {
        rpm = data.rpm
        injectionMs = data.injectionMs
        correction = data.correctionPercent
        mapVoltage = data.mapVoltage
        lambdaMv = data.lambdaMv
        hasData = true
        temperatureC = TechRaceDecoder.temperatureC(data.temperatureRaw)
        // RAM 49 is Y_PERCENT: show the raw internal index even when it is not
        // a display percentage. Hiding values above 100 made the card look stuck.
        mixtureIndex = data.mixtureIndex
        communicationOk = true

        if (rpm > 100) {
            rpmMin = min(rpmMin, rpm)
            rpmMax = max(rpmMax, rpm)
        }
        addHistory(graphRpm, rpm.toFloat(), 72)
        addHistory(graphMap, mapVoltage.toFloat(), 72)
        addHistory(graphLambda, lambdaMv.toFloat(), 72)
        addHistory(graphInjection, injectionMs.toFloat(), 72)
        invalidate()
    }

    fun updateDemoData(data: TechRaceLiveData, temperature: Int, mixture: Int) {
        updateData(data)
        temperatureC = temperature
        mixtureIndex = mixture
        programmingFlagsKnown = true
        mapProgrammed = true
        sondaProgrammed = true
        communicationOk = true
        invalidate()
    }

    fun clearDemoData() {
        hasData = false
        rpm = 0.0
        injectionMs = 0.0
        correction = 0.0
        mapVoltage = 0.0
        lambdaMv = 0
        temperatureC = null
        mixtureIndex = null
        programmingFlagsKnown = false
        mapProgrammed = false
        sondaProgrammed = false
        communicationOk = false
        rpmMin = Double.MAX_VALUE
        rpmMax = 0.0
        graphRpm.clear()
        graphMap.clear()
        graphLambda.clear()
        graphInjection.clear()
        invalidate()
    }

    fun markProgrammingFlagsUnknown() {
        programmingFlagsKnown = false
        invalidate()
    }

    fun updateProgrammingFlags(map: Boolean, sondaRpm: Boolean) {
        programmingFlagsKnown = true
        mapProgrammed = map
        sondaProgrammed = sondaRpm
        invalidate()
    }

    fun markCommunicationFailure() {
        communicationOk = false
        errorCount++
        invalidate()
    }

    fun setSelectedNav(nav: Nav) {
        selectedNav = nav
        invalidate()
    }

    private fun addHistory(q: ArrayDeque<Float>, value: Float, max: Int) {
        if (q.size >= max) q.removeFirst()
        q.addLast(value)
    }

    private var drawScale = 1f
    private var layout: DashboardLayout? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(1024, widthMeasureSpec), resolveSize(1536, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) { layout = null; return }
        // Recompute geometry only on resize, not for every telemetry sample.
        val wide = w > h
        drawScale = min(w / (if (wide) 1536f else 1024f),
            h / (if (wide) 1024f else 1536f))
        layout = DashboardLayout(w / drawScale, h / drawScale)
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        c.drawColor(bg)
        val geometry = layout ?: return
        // Uniform scale preserves gauge circles; all extra space belongs to panels.
        c.save()
        c.scale(drawScale, drawScale)
        drawResponsiveHeader(c, geometry)
        drawResponsiveConnection(c, geometry.connection)
        drawResponsiveGauge(c, geometry.gauge)
        drawResponsiveMetric(c, geometry.map, "MAP", format(mapVoltage, 2), "Volts", "Escala do EXE", blue, graphMap, 5f)
        drawResponsiveMetric(c, geometry.lambda, "LAMBDA (SONDA)", if (hasData) "$lambdaMv" else "--", "mV", lambdaText(), purple, graphLambda, 5000f)
        drawResponsiveCard(c, geometry.cards[0], "INJEÇÃO", if (injectionMs > 0) format(injectionMs, 2) else "--", "ms", "PW", green)
        drawResponsiveCard(c, geometry.cards[1], "CORREÇÃO", if (hasData) signed(correction, 1) else "--", "%", "Acréscimo final", orange)
        drawResponsiveCard(c, geometry.cards[2], "TEMP. MOTOR", temperatureC?.toString() ?: "--", "°C", "ECT", red)
        drawResponsiveCard(c, geometry.cards[3], "MISTURA", mixtureValue(), mixtureUnit(), mixtureSubtitle(), yellow)
        drawResponsiveGraph(c, geometry.graph)
        drawResponsiveStatuses(c, geometry.statuses)
        drawResponsiveNavigation(c, geometry)
        c.restore()
    }

    private fun DashboardBounds.rect() = RectF(left, top, right, bottom)
    private fun panel(c: Canvas, b: DashboardBounds) = roundPanel(c, b.left, b.top, b.width, b.height, 16f)

    /** Measure the actual typeface before drawing, preserving complete labels inside their bounds. */
    private fun fittedText(c: Canvas, value: String, bounds: RectF, size: Float, color: Int,
        align: Paint.Align = Paint.Align.LEFT, bold: Boolean = false, maxLines: Int = 1) {
        if (bounds.width() <= 0 || bounds.height() <= 0) return
        paint.typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        var fitted = size * resources.configuration.fontScale.coerceIn(1f, 1.5f)
        fun linesAt(fontSize: Float): List<String> {
            paint.textSize = fontSize
            if (maxLines == 1) return listOf(value)
            val lines = mutableListOf<String>()
            var rest = value.trim()
            while (rest.isNotEmpty()) {
                var count = paint.breakText(rest, true, bounds.width(), null).coerceAtLeast(1)
                if (count < rest.length) {
                    val wordEnd = rest.lastIndexOf(' ', count - 1)
                    if (wordEnd > 0) count = wordEnd
                }
                lines += rest.take(count).trim()
                rest = rest.drop(count).trimStart()
            }
            return lines.ifEmpty { listOf("") }
        }
        var lines = linesAt(fitted)
        while (fitted > 1f && (lines.size > maxLines || lines.any { paint.measureText(it) > bounds.width() } ||
                (paint.fontMetrics.descent - paint.fontMetrics.ascent) * lines.size > bounds.height())) {
            fitted *= .94f
            lines = linesAt(fitted)
        }
        paint.textSize = fitted
        val fm = paint.fontMetrics
        val lineHeight = fm.descent - fm.ascent
        var baseline = bounds.centerY() - lineHeight * lines.size / 2 - fm.ascent
        val x = when (align) { Paint.Align.CENTER -> bounds.centerX(); Paint.Align.RIGHT -> bounds.right; else -> bounds.left }
        c.save()
        c.clipRect(bounds)
        lines.forEach {
            text(c, it, x, baseline, fitted, color, align, bold)
            baseline += lineHeight
        }
        c.restore()
    }

    private fun drawResponsiveHeader(c: Canvas, g: DashboardLayout) {
        val b = g.header
        panel(c, b)
        val middle = b.left + b.width * .43f
        fittedText(c, "TECHRACE", RectF(b.left + 22, b.top + 6, middle - 16, b.top + b.height * .65f),
            if (g.landscape) 46f else 54f, white, bold = true)
        fittedText(c, "FLEX ECU  •  ${BuildConfig.VERSION_NAME}", RectF(b.left + 24, b.top + b.height * .65f, middle - 16, b.bottom - 6), 23f, red)
        val statusColor = if (demoMode) yellow else if (connected) green else red
        circle(c, middle + 6, b.top + b.height / 2, 8f, statusColor)
        val status = when {
            demoMode -> "MODO DEMONSTRAÇÃO"
            connected && communicationOk -> "LEITURA VALIDADA"
            connected -> "$connectionLabel SEM LEITURA VÁLIDA"
            else -> "MÓDULO DESCONECTADO"
        }
        fittedText(c, status, RectF(middle + 27, b.top + 8, b.right - 20, b.bottom - 8), 27f, statusColor, maxLines = 2)
    }

    private fun drawResponsiveConnection(c: Canvas, b: DashboardBounds) {
        panel(c, b)
        val label = when {
            demoMode -> "DEMO: DADOS SIMULADOS"
            connected && !communicationOk -> "LEITURA INVÁLIDA / ANTIGA"
            connected -> "$connectionLabel — CONECTADO"
            else -> "TOQUE PARA CONECTAR"
        }
        fittedText(c, label, RectF(b.left + 22, b.top + 4, b.left + b.width * .76f, b.bottom - 4), 25f,
            if (demoMode) yellow else if (connected) green else yellow)
        val now = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        fittedText(c, now, RectF(b.left + b.width * .79f, b.top + 4, b.right - 20, b.bottom - 4), 25f, muted, Paint.Align.RIGHT)
    }

    private fun drawResponsiveGauge(c: Canvas, b: DashboardBounds) {
        panel(c, b)
        val scale = min(b.width / 560f, b.height / 453f)
        c.save()
        c.clipRect(b.rect())
        c.translate(b.left + (b.width - 560 * scale) / 2, b.top + (b.height - 453 * scale) / 2)
        c.scale(scale, scale)
        c.translate(-12f, -205f)
        drawRpmGauge(c, background = false)
        c.restore()
    }

    private fun drawResponsiveMetric(c: Canvas, b: DashboardBounds, title: String, value: String,
        unit: String, subtitle: String, color: Int, history: ArrayDeque<Float>, maximum: Float) {
        panel(c, b)
        val pad = 20f
        fittedText(c, title, RectF(b.left + pad, b.top + 8, b.right - pad, b.top + b.height * .22f), 29f, color)
        // Values and sparklines have separate bounds; neither can cover the other.
        fittedText(c, value, RectF(b.left + pad, b.top + b.height * .25f, b.left + b.width * .50f, b.top + b.height * .65f), 64f, white, bold = true)
        fittedText(c, unit, RectF(b.left + pad, b.top + b.height * .65f, b.left + b.width * .50f, b.top + b.height * .79f), 26f, color)
        fittedText(c, subtitle, RectF(b.left + pad, b.top + b.height * .81f, b.right - pad, b.bottom - 6), 23f, muted)
        drawMiniGraph(c, RectF(b.left + b.width * .55f, b.top + b.height * .29f, b.right - pad, b.top + b.height * .75f), history, 0f, maximum, color)
    }

    private fun drawResponsiveCard(c: Canvas, b: DashboardBounds, title: String, value: String,
        unit: String, subtitle: String, color: Int) {
        panel(c, b)
        val left = b.left + 16
        val right = b.right - 16
        fittedText(c, title, RectF(left, b.top + 8, right, b.top + b.height * .23f), 28f, color, Paint.Align.CENTER, maxLines = 2)
        fittedText(c, value, RectF(left, b.top + b.height * .26f, right, b.top + b.height * .62f), 66f, white, Paint.Align.CENTER, true)
        fittedText(c, unit, RectF(left, b.top + b.height * .62f, right, b.top + b.height * .76f), 27f, color, Paint.Align.CENTER)
        fittedText(c, subtitle, RectF(left, b.top + b.height * .78f, right, b.bottom - 7), 24f, muted, Paint.Align.CENTER, maxLines = 2)
    }

    private fun drawResponsiveGraph(c: Canvas, b: DashboardBounds) {
        panel(c, b)
        val titleHeight = min(42f, b.height * .13f)
        fittedText(c, if (demoMode) "MONITOR SIMULADO" else "MONITOR / AMOSTRAS",
            RectF(b.left + 22, b.top + 4, b.right - 22, b.top + titleHeight), 27f, white)
        val colors = listOf(red, blue, purple, green)
        val names = listOf("RPM", "MAP", "LAMBDA", "INJEÇÃO")
        val legendTop = b.top + titleHeight
        names.forEachIndexed { i, label ->
            val cell = (b.width - 40) / 4
            fittedText(c, label, RectF(b.left + 20 + i * cell, legendTop, b.left + 20 + (i + 1) * cell, legendTop + 30), 23f, colors[i], Paint.Align.CENTER)
        }
        val axisWidth = min(280f, b.width * .25f)
        val gr = RectF(b.left + axisWidth, legendTop + 40, b.right - 28, b.bottom - 36)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f; paint.color = border
        for (i in 0..4) { val y = gr.top + gr.height() * i / 4; c.drawLine(gr.left, y, gr.right, y, paint) }
        for (i in 0..6) { val x = gr.left + gr.width() * i / 6; c.drawLine(x, gr.top, x, gr.bottom, paint) }
        paint.style = Paint.Style.FILL
        drawGraphLine(c, gr, graphRpm, 0f, 8000f, red)
        drawGraphLine(c, gr, graphMap, 0f, 5f, blue)
        drawGraphLine(c, gr, graphLambda, 0f, 5000f, purple)
        drawGraphLine(c, gr, graphInjection, 0f, 10f, green)
        for (i in 0..4) {
            val y = gr.top + gr.height() * i / 4
            val values = listOf((8000 - i * 2000).toString(), format(5.0 - i * 1.25, 2),
                (5000 - i * 1250).toString(), format(10.0 - i * 2.5, 1))
            values.forEachIndexed { j, value ->
                val cell = (axisWidth - 14) / 4
                fittedText(c, value, RectF(b.left + 6 + j * cell, y - 14, b.left + 6 + (j + 1) * cell, y + 14), 20f, colors[j], Paint.Align.CENTER)
            }
        }
        val footerTop = gr.bottom + 8
        val cell = gr.width() / 3
        listOf("Mais antiga", "${graphRpm.size} amostras", "Última").forEachIndexed { i, label ->
            fittedText(c, label, RectF(gr.left + i * cell, footerTop, gr.left + (i + 1) * cell, b.bottom - 4), 21f, muted, Paint.Align.CENTER)
        }
    }

    private fun drawResponsiveStatuses(c: Canvas, b: DashboardBounds) {
        panel(c, b)
        val labels = listOf("SONDA / RPM", "MAP", if (demoMode) "SIMULAÇÃO" else "COMUNICAÇÃO", "ERROS")
        val ok = listOf(sondaProgrammed, mapProgrammed, communicationOk, errorCount == 0)
        labels.forEachIndexed { i, label ->
            val left = b.left + i * b.width / 4 + 16
            val right = b.left + (i + 1) * b.width / 4 - 16
            fittedText(c, label, RectF(left, b.top + 5, right, b.top + b.height * .48f), 24f, muted, Paint.Align.CENTER)
            val unknownProgramming = i < 2 && !programmingFlagsKnown && !demoMode
            val state = if (i == 3) errorCount.toString() else if (demoMode) "SIMULADO" else if (unknownProgramming) "--" else if (ok[i]) "OK" else "--"
            fittedText(c, state, RectF(left, b.top + b.height * .5f, right, b.bottom - 5), 25f,
                if (demoMode && i != 3) yellow else if (unknownProgramming) muted else if (ok[i]) green else red, Paint.Align.CENTER)
        }
    }

    private fun drawResponsiveNavigation(c: Canvas, g: DashboardLayout) {
        panel(c, g.navigation)
        val labels = listOf("MONITOR", "AJUSTES", "PROGRAMAÇÃO", "DIAGNÓSTICO", "CONFIG.")
        val icons = listOf("◴", "☷", "▣", "⚠", "⚙")
        g.navCells.forEachIndexed { i, b ->
            val selected = Nav.entries[i] == selectedNav
            val color = if (selected) red else muted
            if (selected) { paint.color = red; c.drawRect(b.left + 5, b.top, b.right - 5, b.top + 5, paint) }
            fittedText(c, icons[i], RectF(b.left + 8, b.top + 8, b.right - 8, b.top + b.height * .55f), 35f, color, Paint.Align.CENTER)
            fittedText(c, labels[i], RectF(b.left + 8, b.top + b.height * .58f, b.right - 8, b.bottom - 6), 25f, color, Paint.Align.CENTER)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val geometry = layout ?: return true
        val x = event.x / drawScale
        val y = event.y / drawScale
        if (geometry.connection.contains(x, y)) { performClick(); onUsbClick?.invoke(); return true }
        geometry.navIndexAt(x, y)?.let { index ->
            performClick()
            selectedNav = Nav.entries[index]
            invalidate()
            onNavClick?.invoke(selectedNav)
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun drawRpmGauge(c: Canvas, background: Boolean = true) {
        if (background) roundPanel(c, 12f, 205f, 560f, 453f, 18f)
        val cx = 287f
        val cy = 453f
        val radius = 205f
        val start = 155f
        val sweep = 230f

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f
        paint.color = white
        c.drawArc(cx-radius, cy-radius, cx+radius, cy+radius, start, sweep, false, paint)
        paint.strokeWidth = 4f
        paint.color = red
        c.drawArc(cx-radius, cy-radius, cx+radius, cy+radius, 320f, 65f, false, paint)

        for (i in 0..40) {
            val frac = i / 40f
            val ang = Math.toRadians((start + sweep*frac).toDouble())
            val major = i % 5 == 0
            val r1 = radius - if (major) 22f else 12f
            val r2 = radius
            paint.strokeWidth = if (major) 4f else 2f
            paint.color = if (frac > .72f) red else white
            c.drawLine(
                cx + cos(ang).toFloat()*r1,
                cy + sin(ang).toFloat()*r1,
                cx + cos(ang).toFloat()*r2,
                cy + sin(ang).toFloat()*r2,
                paint
            )
        }
        for (i in 0..8) {
            val frac = i/8f
            val ang = Math.toRadians((start+sweep*frac).toDouble())
            val rr = radius - 55f
            text(c, i.toString(), cx + cos(ang).toFloat()*rr, cy + sin(ang).toFloat()*rr + 10f, 28f, white, Paint.Align.CENTER)
        }

        val scaled = (rpm / 1000.0).coerceIn(0.0, 8.0)
        val ang = Math.toRadians((start + sweep*(scaled/8.0)).toDouble())
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 8f
        paint.color = red
        c.drawLine(cx, cy, cx+cos(ang).toFloat()*145f, cy+sin(ang).toFloat()*145f, paint)
        paint.strokeCap = Paint.Cap.BUTT
        paint.style = Paint.Style.FILL

        text(c, "RPM", cx, 402f, 28f, muted, Paint.Align.CENTER)
        text(c, if (rpm > 0) "${rpm.roundToInt()}" else "---", cx, 510f, 78f, white, Paint.Align.CENTER, true)
        text(c, "RPM", cx, 558f, 31f, red, Paint.Align.CENTER, true)

        val minTxt = if (rpmMin == Double.MAX_VALUE) "---" else rpmMin.roundToInt().toString()
        val maxTxt = if (rpmMax <= 0) "---" else rpmMax.roundToInt().toString()
        tinyPill(c, 28f, 598f, 130f, 41f, "MÍN: $minTxt")
        tinyPill(c, 422f, 598f, 132f, 41f, "MÁX: $maxTxt")
        circle(c, 260f, 620f, 7f, red)
        circle(c, 289f, 620f, 7f, Color.DKGRAY)
        circle(c, 318f, 620f, 7f, Color.DKGRAY)
    }

    private fun drawMiniGraph(c:Canvas, r:RectF, q:ArrayDeque<Float>, minV:Float,maxV:Float,color:Int) {
        if (q.size < 2) return
        path.reset()
        q.forEachIndexed { idx, v ->
            val xx = r.left + r.width()*idx/(q.size-1).coerceAtLeast(1)
            val norm = ((v-minV)/(maxV-minV)).coerceIn(0f,1f)
            val yy = r.bottom - norm*r.height()
            if (idx==0) path.moveTo(xx,yy) else path.lineTo(xx,yy)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = color
        c.drawPath(path,paint)
        paint.style = Paint.Style.FILL
    }

    private fun estimatedMixturePercent(): Double? = FuelingEstimate.calibratedPercent(
        mixtureReferenceEthanolPercent,
        mixtureReferenceIndex,
        mixtureIndex,
        mixtureFactorPercentPerIndex
    )

    private fun mixtureValue(): String {
        val estimate = estimatedMixturePercent()
        return if (estimate != null) String.format(Locale.US, "%.0f", estimate)
            else mixtureIndex?.toString() ?: "--"
    }

    private fun mixtureUnit(): String = if (estimatedMixturePercent() != null) "%" else "idx"

    private fun mixtureSubtitle(): String {
        val current = mixtureIndex ?: return "Índice sonda + MAP"
        val reference = mixtureReferenceIndex ?: return "Registre abastecimento"
        val delta = FuelingEstimate.indexDelta(current, reference) ?: return "Registre abastecimento"
        val signed = if (delta >= 0) "+$delta" else delta.toString()
        return if (estimatedMixturePercent() != null) "Est. · idx $current Δ$signed"
            else "idx $current · Δ $signed"
    }

    private fun drawGraphLine(c:Canvas,r:RectF,q:ArrayDeque<Float>,minV:Float,maxV:Float,color:Int){
        if(q.size<2)return
        path.reset()
        q.forEachIndexed { idx,v ->
            val xx=r.left+r.width()*idx/(q.size-1).coerceAtLeast(1)
            val norm=((v-minV)/(maxV-minV)).coerceIn(0f,1f)
            val yy=r.bottom-norm*r.height()
            if(idx==0)path.moveTo(xx,yy) else path.lineTo(xx,yy)
        }
        paint.style=Paint.Style.STROKE; paint.strokeWidth=3f; paint.color=color
        c.drawPath(path,paint); paint.style=Paint.Style.FILL
    }

    private fun lambdaText():String {
        if(!hasData)return "--"
        return "Tensão da sonda"
    }
    private fun format(v:Double,n:Int)=String.format(Locale.US,"%.${n}f",v)
    private fun signed(v:Double,n:Int)=String.format(Locale.US,"%+.${n}f",v)

    private fun roundPanel(c:Canvas,x:Float,y:Float,w:Float,h:Float,r:Float){
        paint.style=Paint.Style.FILL; paint.color=panel; c.drawRoundRect(x,y,x+w,y+h,r,r,paint)
        paint.style=Paint.Style.STROKE; paint.strokeWidth=2f; paint.color=border; c.drawRoundRect(x,y,x+w,y+h,r,r,paint); paint.style=Paint.Style.FILL
    }
    private fun tinyPill(c:Canvas,x:Float,y:Float,w:Float,h:Float,label:String){ roundPanel(c,x,y,w,h,11f); text(c,label,x+w/2,y+28f,16f,muted,Paint.Align.CENTER) }
    private fun circle(c:Canvas,x:Float,y:Float,r:Float,color:Int){ paint.style=Paint.Style.FILL; paint.color=color; c.drawCircle(x,y,r,paint) }
    private fun text(c:Canvas,s:String,x:Float,y:Float,size:Float,color:Int,align:Paint.Align=Paint.Align.LEFT,bold:Boolean=false){
        paint.style=Paint.Style.FILL; paint.color=color; paint.textSize=size; paint.textAlign=align; paint.typeface=if(bold)Typeface.create(Typeface.DEFAULT,Typeface.BOLD) else Typeface.create(Typeface.DEFAULT,Typeface.NORMAL)
        c.drawText(s,x,y,paint)
    }
    private fun legend(c:Canvas,x:Float,y:Float,color:Int,label:String){
        paint.style=Paint.Style.STROKE; paint.strokeWidth=2f; paint.color=color; c.drawRect(x,y-18f,x+16f,y-2f,paint); paint.style=Paint.Style.FILL; text(c,label,x+27f,y-3f,17f,color)
    }
}
