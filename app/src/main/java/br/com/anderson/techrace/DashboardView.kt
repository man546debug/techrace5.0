package br.com.anderson.techrace

import android.content.Context
import android.graphics.*
import android.graphics.drawable.ColorDrawable
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

    fun updateProgrammingFlags(map: Boolean, sondaRpm: Boolean) {
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

    private var logicalWidth = 1024f
    private var logicalHeight = 1536f
    private var drawScale = 1f
    private var drawOffsetX = 0f
    private var drawOffsetY = 0f
    private var landscapeLayout = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val measuredW = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) 1024 else w
        val measuredH = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) 1536 else h
        setMeasuredDimension(measuredW, measuredH)
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        c.drawColor(bg)

        landscapeLayout = width > height
        logicalWidth = if (landscapeLayout) 1536f else 1024f
        logicalHeight = if (landscapeLayout) 1024f else 1536f

        // Uniform scale preserves circular gauges and text proportions on phones,
        // tablets, split-screen and both orientations.
        drawScale = min(width / logicalWidth, height / logicalHeight)
        drawOffsetX = (width - logicalWidth * drawScale) / 2f
        drawOffsetY = (height - logicalHeight * drawScale) / 2f

        c.save()
        c.translate(drawOffsetX, drawOffsetY)
        c.scale(drawScale, drawScale)
        if (landscapeLayout) drawDashboardLandscape(c) else drawDashboard(c)
        c.restore()
    }

    private fun drawDashboard(c: Canvas) {
        drawHeader(c)
        drawConnectionBar(c)
        drawRpmGauge(c)
        drawMetricPanel(c, 585f, 205f, 425f, 220f, "MAP", format(mapVoltage, 2), "Volts", blue) {
            drawMiniGraph(c, it, graphMap, 0f, 5f, blue)
        }
        drawMetricPanel(c, 585f, 438f, 425f, 220f, "LAMBDA (SONDA)", if (hasData) "$lambdaMv" else "--", "mV", purple) {
            drawMiniGraph(c, it, graphLambda, 0f, 5000f, purple)
        }
        drawFourCards(c)
        drawMainGraph(c)
        drawStatuses(c)
        drawBottomNav(c)
    }

    /** Dedicated wide layout. It reflows the same information instead of stretching
     * the portrait canvas, so circular gauges remain circular in landscape. */
    private fun drawDashboardLandscape(c: Canvas) {
        drawHeaderLandscape(c)
        drawConnectionBarLandscape(c)

        // Reuse the proven RPM drawing, moved upward into the wide layout.
        c.save()
        c.translate(0f, -45f)
        drawRpmGauge(c)
        c.restore()

        drawMetricPanel(c, 585f, 160f, 425f, 210f, "MAP", format(mapVoltage, 2), "Volts", blue) {
            drawMiniGraph(c, it, graphMap, 0f, 5f, blue)
        }
        drawMetricPanel(c, 585f, 385f, 425f, 210f, "LAMBDA (SONDA)", if (hasData) "$lambdaMv" else "--", "mV", purple) {
            drawMiniGraph(c, it, graphLambda, 0f, 5000f, purple)
        }

        compactCard(c, 1023f, 160f, 245f, 210f, "INJEÇÃO", if (injectionMs > 0) format(injectionMs, 2) else "--", "ms", "PW", green)
        compactCard(c, 1279f, 160f, 245f, 210f, "CORREÇÃO", if (hasData) signed(correction, 1) else "--", "%", "Acréscimo final", orange)
        compactCard(c, 1023f, 385f, 245f, 210f, "TEMP. MOTOR", temperatureC?.toString() ?: "--", "°C", "ECT", red)
        compactCard(c, 1279f, 385f, 245f, 210f, "MISTURA", mixtureValue(), mixtureUnit(), mixtureSubtitle(), yellow)

        drawMainGraphLandscape(c)
        drawStatusesLandscape(c)
        drawBottomNavLandscape(c)
    }

    private fun drawHeaderLandscape(c: Canvas) {
        roundPanel(c, 12f, 10f, 1512f, 75f, 16f)
        text(c, "☰", 40f, 61f, 38f, muted)
        text(c, "TECH", 112f, 57f, 43f, white, Paint.Align.LEFT, true)
        text(c, "RACE", 224f, 57f, 43f, red, Paint.Align.LEFT, true)
        text(c, "FLEX ECU", 335f, 56f, 20f, muted, Paint.Align.LEFT, true)
        circle(c, 935f, 47f, 8f, if (demoMode) yellow else if (connected) green else red)
        val status = when {
            demoMode -> "MODO DEMO"
            connected && communicationOk -> "LEITURA VALIDADA"
            connected -> "$connectionLabel SEM LEITURA VÁLIDA"
            else -> "MÓDULO DESCONECTADO"
        }
        text(c, status, 956f, 55f, 18f, if (demoMode) yellow else if (connected) green else red)
        text(c, "TechRace V5.0", 1490f, 55f, 18f, muted, Paint.Align.RIGHT)
    }

    private fun drawConnectionBarLandscape(c: Canvas) {
        roundPanel(c, 12f, 95f, 1512f, 50f, 13f)
        val connectionText = when {
            demoMode -> "DEMO: DADOS SIMULADOS"
            connected && !communicationOk -> "LEITURA INVÁLIDA / ANTIGA"
            connected -> "$connectionLabel — CONECTADO"
            else -> "TOQUE PARA CONECTAR"
        }
        text(c, connectionText, 45f, 128f, 18f, if (demoMode) yellow else if (connected) green else yellow)
        val now = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        text(c, now, 1490f, 128f, 18f, muted, Paint.Align.RIGHT)
    }

    private fun compactCard(c: Canvas, x: Float, y: Float, w: Float, h: Float, title: String, value: String, unit: String, subtitle: String, color: Int) {
        roundPanel(c, x, y, w, h, 16f)
        text(c, title, x + 20f, y + 35f, 18f, color)
        text(c, value, x + w / 2f, y + 112f, 48f, white, Paint.Align.CENTER, true)
        text(c, unit, x + w / 2f, y + 146f, 18f, color, Paint.Align.CENTER)
        text(c, subtitle, x + w / 2f, y + h - 18f, 16f, muted, Paint.Align.CENTER)
    }

    private fun drawMainGraphLandscape(c: Canvas) {
        val x = 12f; val y = 625f; val w = 1512f; val h = 255f
        roundPanel(c, x, y, w, h, 16f)
        text(c, if (demoMode) "MONITOR SIMULADO" else "MONITOR / AMOSTRAS", x + 26f, y + 37f, 19f, white)
        legend(c, 490f, y + 38f, red, "RPM")
        legend(c, 600f, y + 38f, blue, "MAP")
        legend(c, 703f, y + 38f, purple, "LAMBDA")
        legend(c, 837f, y + 38f, green, "INJEÇÃO")

        val gr = RectF(x + 245f, y + 55f, x + w - 30f, y + h - 35f)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f; paint.color = Color.rgb(35, 40, 45)
        for (i in 0..4) { val yy = gr.top + gr.height() * i / 4f; c.drawLine(gr.left, yy, gr.right, yy, paint) }
        for (i in 0..6) { val xx = gr.left + gr.width() * i / 6f; c.drawLine(xx, gr.top, xx, gr.bottom, paint) }
        paint.style = Paint.Style.FILL
        drawGraphLine(c, gr, graphRpm, 0f, 8000f, red)
        drawGraphLine(c, gr, graphMap, 0f, 5f, blue)
        drawGraphLine(c, gr, graphLambda, 0f, 5000f, purple)
        drawGraphLine(c, gr, graphInjection, 0f, 10f, green)
        for (i in 0..4) {
            val yy = gr.top + gr.height() * i / 4f + 5f
            text(c, (8000 - i * 2000).toString(), 35f, yy, 14f, red)
            text(c, format(5.0 - i * 1.25, 2), 90f, yy, 14f, blue)
            text(c, (5000 - i * 1250).toString(), 145f, yy, 14f, purple)
            text(c, format(10.0 - i * 2.5, 1), 205f, yy, 14f, green)
        }
        text(c, "Mais antiga", gr.left, gr.bottom + 25f, 14f, muted, Paint.Align.CENTER)
        text(c, "${graphRpm.size} amostras", gr.centerX(), gr.bottom + 25f, 14f, muted, Paint.Align.CENTER)
        text(c, "Última", gr.right, gr.bottom + 25f, 14f, muted, Paint.Align.CENTER)
    }

    private fun drawStatusesLandscape(c: Canvas) {
        roundPanel(c, 12f, 890f, 1512f, 50f, 13f)
        landscapeStatus(c, 42f, 921f, "SONDA/RPM", sondaProgrammed)
        landscapeStatus(c, 350f, 921f, "MAP", mapProgrammed)
        landscapeStatus(c, 610f, 921f, if (demoMode) "SIMULAÇÃO" else "COMUNICAÇÃO", communicationOk)
        text(c, "ERROS: $errorCount", 1490f, 922f, 17f, if (errorCount == 0) muted else red, Paint.Align.RIGHT)
    }

    private fun landscapeStatus(c: Canvas, x: Float, y: Float, label: String, ok: Boolean) {
        circle(c, x, y - 6f, 7f, if (ok) green else red)
        text(c, "$label  ${if (demoMode) "SIMULADO" else if (ok) "OK" else "--"}", x + 18f, y, 16f, if (ok) green else muted)
    }

    private fun drawBottomNavLandscape(c: Canvas) {
        roundPanel(c, 12f, 950f, 1512f, 64f, 13f)
        val items = listOf(
            Triple(Nav.MONITOR, "◴", "MONITOR"), Triple(Nav.AJUSTES, "☷", "AJUSTES"),
            Triple(Nav.PROGRAMACAO, "▣", "PROGRAMAÇÃO"), Triple(Nav.DIAGNOSTICO, "⚠", "DIAGNÓSTICO"),
            Triple(Nav.CONFIG, "⚙", "CONFIG.")
        )
        val cell = 1512f / 5f
        items.forEachIndexed { i, (nav, icon, label) ->
            val left = 12f + cell * i
            val cx = left + cell / 2f
            val sel = nav == selectedNav
            if (sel) { paint.color = red; c.drawRect(left, 950f, left + cell, 956f, paint) }
            text(c, icon, cx - 48f, 992f, 27f, if (sel) red else muted, Paint.Align.CENTER)
            text(c, label, cx + 12f, 991f, 16f, if (sel) red else muted, Paint.Align.CENTER)
        }
    }

    private fun drawHeader(c: Canvas) {
        roundPanel(c, 12f, 10f, 1000f, 115f, 18f)
        text(c, "☰", 42f, 78f, 46f, muted, Paint.Align.LEFT, false)
        text(c, "TECH", 137f, 73f, 55f, white, Paint.Align.LEFT, true)
        text(c, "RACE", 279f, 73f, 55f, red, Paint.Align.LEFT, true)
        text(c, "FLEX ECU", 305f, 104f, 24f, muted, Paint.Align.LEFT, true)
        circle(c, 642f, 70f, 9f, if (demoMode) yellow else if (connected) green else red)
        val headerStatus = when { demoMode -> "MODO DEMO"; connected && communicationOk -> "LEITURA VALIDADA"; connected -> "$connectionLabel SEM LEITURA VÁLIDA"; else -> "MÓDULO DESCONECTADO" }
        text(c, headerStatus, 663f, 78f, 20f, if (demoMode) yellow else if (connected) green else red)
        text(c, "⋮", 960f, 78f, 48f, white, Paint.Align.CENTER)
    }

    private fun drawConnectionBar(c: Canvas) {
        roundPanel(c, 12f, 138f, 1000f, 55f, 14f)
        val connectionText = when { demoMode -> "DEMO: DADOS SIMULADOS"; connected && !communicationOk -> "LEITURA INVÁLIDA / ANTIGA"; connected -> "$connectionLabel — CONECTADO"; else -> "TOQUE PARA CONECTAR" }
        text(c, connectionText, 87f, 176f, 20f, if (demoMode) yellow else if (connected) green else yellow)
        text(c, "TechRace V5.0", 680f, 176f, 19f, muted, Paint.Align.CENTER)
        val now = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        text(c, now, 974f, 176f, 19f, muted, Paint.Align.RIGHT)
    }

    private fun drawRpmGauge(c: Canvas) {
        roundPanel(c, 12f, 205f, 560f, 453f, 18f)
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

    private fun drawMetricPanel(c: Canvas, x:Float,y:Float,w:Float,h:Float,title:String,value:String,unit:String,color:Int, extra:(RectF)->Unit) {
        roundPanel(c,x,y,w,h,18f)
        text(c,title,x+26f,y+42f,23f,color)
        text(c,value,x+26f,y+116f,58f,white,Paint.Align.LEFT,true)
        text(c,unit,x+26f,y+156f,20f,color)
        if (title.startsWith("MAP")) text(c, "Escala do EXE", x+26f, y+198f, 17f, muted)
        if (title.startsWith("LAMBDA")) text(c, lambdaText(), x+26f, y+198f, 19f, muted)
        val graphRect = RectF(x+155f,y+50f,x+w-20f,y+h-35f)
        extra(graphRect)
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

    private fun drawFourCards(c: Canvas) {
        val y=670f; val h=242f; val gap=12f; val w=(1000f-gap*3)/4f
        card(c,12f,y,w,h,"INJEÇÃO",if(injectionMs>0) format(injectionMs,2) else "--","ms","PW",green,"▰")
        card(c,12f+w+gap,y,w,h,"CORREÇÃO",if(hasData) signed(correction,1) else "--","%","Acréscimo final",orange,"◴")
        card(c,12f+(w+gap)*2,y,w,h,"TEMP. MOTOR",temperatureC?.toString() ?: "--","°C","ECT",red,"♨")
        card(c,12f+(w+gap)*3,y,w,h,"MISTURA",mixtureValue(),mixtureUnit(),mixtureSubtitle(),yellow,"⛽")
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

    private fun card(c:Canvas,x:Float,y:Float,w:Float,h:Float,title:String,value:String,unit:String,subtitle:String,color:Int,icon:String) {
        roundPanel(c,x,y,w,h,17f)
        text(c,title,x+25f,y+43f,20f,color)
        text(c,icon,x+w-28f,y+48f,27f,color,Paint.Align.RIGHT)
        text(c,value,x+w/2,y+139f,55f,white,Paint.Align.CENTER,true)
        text(c,unit,x+w/2,y+177f,20f,color,Paint.Align.CENTER)
        text(c,subtitle,x+w/2,y+221f,18f,muted,Paint.Align.CENTER)
    }

    private fun drawMainGraph(c: Canvas) {
        val x=12f; val y=925f; val w=1000f; val h=350f
        roundPanel(c,x,y,w,h,18f)
        text(c,if(demoMode)"MONITOR SIMULADO" else "MONITOR / AMOSTRAS",x+28f,y+43f,21f,white)
        legend(c,493f,y+43f,red,"RPM")
        legend(c,603f,y+43f,blue,"MAP")
        legend(c,706f,y+43f,purple,"LAMBDA")
        legend(c,840f,y+43f,green,"INJEÇÃO")

        val gr=RectF(x+290f,y+75f,x+w-70f,y+h-45f)
        paint.style=Paint.Style.STROKE; paint.strokeWidth=1f; paint.color=Color.rgb(35,40,45)
        for(i in 0..4){ val yy=gr.top+gr.height()*i/4f; c.drawLine(gr.left,yy,gr.right,yy,paint) }
        for(i in 0..3){ val xx=gr.left+gr.width()*i/3f; c.drawLine(xx,gr.top,xx,gr.bottom,paint) }
        paint.style=Paint.Style.FILL
        text(c,"Mais antiga",gr.left,gr.bottom+31f,17f,muted,Paint.Align.CENTER)
        text(c,"${graphRpm.size} amostras",gr.centerX(),gr.bottom+31f,17f,muted,Paint.Align.CENTER)
        text(c,"Última",gr.right,gr.bottom+31f,17f,muted,Paint.Align.CENTER)

        drawGraphLine(c,gr,graphRpm,0f,8000f,red)
        drawGraphLine(c,gr,graphMap,0f,5f,blue)
        drawGraphLine(c,gr,graphLambda,0f,5000f,purple)
        drawGraphLine(c,gr,graphInjection,0f,10f,green)

        for (i in 0..4) {
            val yy = gr.top + gr.height() * i / 4f + 6f
            text(c,(8000-i*2000).toString(),40f,yy,16f,red)
            text(c,format(5.0-i*1.25,2),110f,yy,16f,blue)
            text(c,(5000-i*1250).toString(),183f,yy,16f,purple)
            text(c,format(10.0-i*2.5,1),250f,yy,16f,green)
        }
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

    private fun drawStatuses(c: Canvas) {
        roundPanel(c,12f,1288f,1000f,95f,16f)
        statusItem(c,42f,1319f,"SONDA / RPM",sondaProgrammed)
        statusItem(c,315f,1319f,"MAP",mapProgrammed)
        statusItem(c,548f,1319f,if(demoMode)"SIMULAÇÃO" else "COMUNICAÇÃO",communicationOk)
        text(c,"ERROS",878f,1327f,18f,muted,Paint.Align.CENTER)
        text(c,errorCount.toString(),878f,1365f,20f,white,Paint.Align.CENTER)
    }

    private fun statusItem(c:Canvas,x:Float,y:Float,label:String,ok:Boolean){
        circle(c,x,y+6f,8f,if(ok)green else red)
        text(c,label,x+24f,y+13f,18f,muted)
        text(c,if(demoMode)"SIMULADO" else if(ok)"OK" else "--",x+24f,y+47f,18f,if(demoMode)yellow else if(ok)green else red)
    }

    private fun drawBottomNav(c: Canvas) {
        roundPanel(c,12f,1395f,1000f,128f,16f)
        val items=listOf(
            Triple(Nav.MONITOR,"◴","MONITOR"), Triple(Nav.AJUSTES,"☷","AJUSTES"),
            Triple(Nav.PROGRAMACAO,"▣","PROGRAMAÇÃO"), Triple(Nav.DIAGNOSTICO,"⚠","DIAGNÓSTICO"),
            Triple(Nav.CONFIG,"⚙","CONFIG.")
        )
        val cell=1000f/5f
        items.forEachIndexed { i,(nav,icon,label) ->
            val cx=12f+cell*(i+.5f)
            val sel=nav==selectedNav
            if(sel){ paint.color=red; c.drawRect(12f+cell*i,1395f,12f+cell*(i+1),1402f,paint) }
            text(c,icon,cx,1455f,34f,if(sel)red else muted,Paint.Align.CENTER)
            text(c,label,cx,1500f,17f,if(sel)red else muted,Paint.Align.CENTER)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        if (drawScale <= 0f) return true
        val lx = (event.x - drawOffsetX) / drawScale
        val ly = (event.y - drawOffsetY) / drawScale
        if (lx !in 0f..logicalWidth || ly !in 0f..logicalHeight) return true

        if (landscapeLayout) {
            if (ly in 95f..145f) { onUsbClick?.invoke(); return true }
            if (ly in 950f..1024f) {
                val idx = ((lx - 12f) / (1512f / 5f)).toInt().coerceIn(0, 4)
                val nav = Nav.entries[idx]
                selectedNav = nav
                invalidate()
                onNavClick?.invoke(nav)
                return true
            }
        } else {
            if (ly in 138f..193f) { onUsbClick?.invoke(); return true }
            if (ly in 1395f..1536f) {
                val idx = ((lx - 12f) / (1000f / 5f)).toInt().coerceIn(0, 4)
                val nav = Nav.entries[idx]
                selectedNav = nav
                invalidate()
                onNavClick?.invoke(nav)
                return true
            }
        }
        return true
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
