package br.com.anderson.techrace

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.*
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.util.concurrent.Executors
import java.util.concurrent.CancellationException
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin

class MainActivity : Activity() {
    companion object {
        private const val DRAIN_IDLE_READ_MS = 40
        private const val DRAIN_TIMEOUT_MS = 350L
        private const val SERIAL_READ_MS = 60
        private const val LIVE_RESPONSE_TIMEOUT_MS = 1400L
        private const val QUERY_RESPONSE_TIMEOUT_MS = 1400L
        private const val WRITE_RESPONSE_TIMEOUT_MS = 1200L
        private const val MAX_LIVE_FAILURES_BEFORE_PAUSE = 3
    }

    private enum class ProgrammingMode(val selector: Int, val flagMask: Int, val label: String) {
        SONDA_RPM(1, 0x40, "Sonda / RPM"),
        MAP(2, 0x01, "Sensor MAP")
    }

    private val actionUsbPermission = "br.com.anderson.techrace.USB_PERMISSION"
    private lateinit var usbManager: UsbManager
    private lateinit var dashboard: DashboardView
    // All port open/read/write/close operations belong to the same serial executor.
    private var transport: SerialTransport? = null
    private var activeBluetoothAddress: String? = null
    private var bluetoothListDialog: AlertDialog? = null
    private var bluetoothListAdapter: ArrayAdapter<String>? = null
    private val bluetoothDevices = linkedMapOf<String, BluetoothDevice>()
    private val bluetoothBondedAddresses = mutableSetOf<String>()
    private var bluetoothDiscoveryFinished = false
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0
    @Volatile private var foreground = false
    private var connected = false
    private var polling = false
    private var busy = false
    private var demoMode = false
    private var demoTick = 0L
    private var pendingDeviceId: Int? = null
    private var activeDeviceId: Int? = null
    private val connectionType: String get() = preferences.getString("connection_type", "usb") ?: "usb"
    private var firmwareVersion = "Não consultada"
    private var settingsSnapshot: ModuleSettings? = null
    private var settingsTime = "--"
    private var moduleReport = "Nenhuma consulta realizada."
    private var snapshotTime = "--"
    private val csvRows = java.util.ArrayDeque<String>()
    private var pendingExport: String? = null
    private var lastRx = byteArrayOf()
    private var lastError = "Nenhuma leitura realizada"
    private var lastValid = "--"
    private var lastData: TechRaceLiveData? = null
    private var pollingIntervalMs = 250L
    private var rpmCalibration = 1.0
    private var programmingMode: ProgrammingMode? = null
    private var programmingDialog: AlertDialog? = null
    private var programmingStatusView: TextView? = null
    private var programmingSensorsView: TextView? = null
    private var programmingStartedAt = 0L
    private var resumePollingAfterProgramming = false
    private var liveFailureStreak = 0
    private var mapSecondStageAcknowledged = false
    private var programmingSession: ProgrammingSession? = null
    private var programmingRuntimeFlags: Int? = null
    private var mapLoadDialog: AlertDialog? = null
    private var pendingSerialAction = false
    private var manualConfirmDialog: AlertDialog? = null
    private val programmingStatusTask = Runnable {
        programmingMode?.let { queryProgrammingStatus(it) }
    }
    private val preferences by lazy { getSharedPreferences("techrace_settings", MODE_PRIVATE) }
    private val pollTask = Runnable { if (polling) pollOnce() }
    private val demoTask = object : Runnable {
        override fun run() {
            if (!demoMode || !foreground) return
            updateDemoData()
            handler.postDelayed(this, 250L)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val device = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            }
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                if (device?.deviceId == activeDeviceId) {
                    disconnect()
                    toast("USB desconectado")
                }
                return
            }
            if (intent.action != actionUsbPermission || device?.deviceId != pendingDeviceId) return
            pendingDeviceId = null
            if (!foreground || demoMode) return
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                openDevice(device ?: return)
            else toast("Permissão USB negada")
        }
    }

    private val bluetoothDiscoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    } ?: return
                    val address = try { device.address } catch (_: SecurityException) { return }
                    if (bluetoothListDialog?.isShowing != true || bluetoothDevices.containsKey(address)) return
                    bluetoothDevices[address] = device
                    renderBluetoothDeviceList()
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    bluetoothDiscoveryFinished = true
                    updateBluetoothSearchMessage()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(6, 9, 11)
        window.navigationBarColor = Color.rgb(6, 9, 11)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val filter = IntentFilter(actionUsbPermission).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
        val bluetoothFilter = IntentFilter(BluetoothDevice.ACTION_FOUND).apply {
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        // Bluetooth discovery broadcasts are sent by the Bluetooth system app, so the
        // receiver must be exported to receive them on Android 13 and newer.
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(bluetoothDiscoveryReceiver, bluetoothFilter, RECEIVER_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(bluetoothDiscoveryReceiver, bluetoothFilter)
        }
        rpmCalibration = preferences.getInt("rpm_factor", 1).toDouble()
        pollingIntervalMs = preferences.getLong("poll_ms", 250).coerceIn(80, 2000)
        dashboard = DashboardView(this).apply {
            connectionLabel = if (connectionType == "bluetooth") "BLUETOOTH" else "USB OTG"
            onUsbClick = { if (demoMode) showConfig() else connectSelected() }
            onNavClick = { nav ->
                when (nav) {
                    DashboardView.Nav.MONITOR -> Unit
                    DashboardView.Nav.AJUSTES -> showModuleMenu()
                    DashboardView.Nav.PROGRAMACAO -> showProgrammingMenu()
                    DashboardView.Nav.DIAGNOSTICO -> showDiagnostics()
                    DashboardView.Nav.CONFIG -> showConfig()
                }
            }
        }
        syncFuelingCalibration()
        setContentView(dashboard)
        if (preferences.getBoolean("demo_mode", false)) {
            AlertDialog.Builder(this).setTitle("Retomar demonstração?")
                .setMessage("Serão exibidos dados simulados, sem comunicação USB.")
                .setPositiveButton("Retomar") { _, _ -> setDemoMode(true) }
                .setNegativeButton("Modo real") { _, _ ->
                    preferences.edit().putBoolean("demo_mode", false).apply()
                }.setCancelable(false).show()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Keep the USB session alive while the phone rotates; only the dashboard is reflowed.
        dashboard.requestLayout()
        dashboard.invalidate()
    }

    @Deprecated("Permission callback retained for minSdk 24")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 410) {
            if (grantResults.isNotEmpty() && grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED })
                showBluetoothDevices()
            else toast("Permissão para procurar dispositivos Bluetooth negada")
        }
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        if (demoMode) queueDemoStart()
    }

    override fun onStop() {
        foreground = false
        stopBluetoothDiscovery()
        handler.removeCallbacks(demoTask)
        disconnect()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        ioExecutor.shutdown() // finish the already queued close operation
        unregisterReceiver(receiver)
        unregisterReceiver(bluetoothDiscoveryReceiver)
        super.onDestroy()
    }

    private fun valid(token: Int) = token == generation && foreground
    private fun closePort() {
        try { transport?.close() } catch (_: Exception) {}
        transport = null
        activeBluetoothAddress = null
    }

    private fun disconnect() {
        generation++
        pendingSerialAction = false
        manualConfirmDialog?.dismiss(); manualConfirmDialog = null
        programmingSession = null
        programmingRuntimeFlags = null
        mapLoadDialog?.dismiss()
        mapLoadDialog = null
        polling = false
        busy = false
        connected = false
        pendingDeviceId = null
        activeDeviceId = null
        handler.removeCallbacks(pollTask)
        handler.removeCallbacks(programmingStatusTask)
        programmingMode = null
        resumePollingAfterProgramming = false
        liveFailureStreak = 0
        mapSecondStageAcknowledged = false
        programmingStatusView = null
        programmingSensorsView = null
        programmingDialog?.dismiss()
        programmingDialog = null
        dashboard.connected = false
        dashboard.autoReading = false
        dashboard.clearDemoData()
        lastRx = byteArrayOf()
        lastData = null
        lastValid = "--"
        lastError = "Desconectado"
        firmwareVersion = "Não consultada"
        settingsSnapshot = null
        settingsTime = "--"
        snapshotTime = "--"
        moduleReport = "Nenhuma consulta nesta conexão."
        ioExecutor.execute { closePort() }
    }

    private fun showProgrammingMenu() {
        val items = arrayOf(
            "Programar Sonda / RPM",
            "Programar Sensor MAP",
            "Programar mistura manual (barra de seleção)",
            "Encerrar operação"
        )
        AlertDialog.Builder(this).setTitle("Operações")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> beginProgramming(ProgrammingMode.SONDA_RPM)
                    1 -> beginProgramming(ProgrammingMode.MAP)
                    2 -> showManualMixtureMenu()
                    3 -> confirmProgrammingStop()
                }
            }.setNegativeButton("Fechar", null).show()
    }

    private fun showManualMixtureMenu() {
        if (demoMode) {
            toast("Demonstração: a mistura manual não será gravada")
            return
        }
        if (!connected || !foreground) {
            toast("Conecte o leitor antes de selecionar a mistura")
            return
        }
        if (programmingMode != null || pendingSerialAction) {
            toast("Conclua a programação atual primeiro")
            return
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 20, 36, 20)
        }
        val label = TextView(this)
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(preferences.getFloat("manual_level", 50f).toString())
        }
        val slider = SeekBar(this).apply { max = 1000 }
        fun updatePreview() {
            val level = input.text.toString().replace(',', '.').toDoubleOrNull()
            val limit = settingsSnapshot?.raw?.get(3)?.let { (it.toInt() and 255) * 100.0 / 64.0 }
            label.text = if (level != null && level in 0.0..100.0 && limit != null && limit in 9.375..60.0) {
                val raw = TechRaceProtocol.manualMixtureRaw(level, limit)
                "Correção gravável: %.4f%% (MIX %d). Limite atual: %.4f%%.".format(raw * 100.0 / 64, raw, limit)
            } else "O limite e o valor gravável serão conferidos na central antes do envio."
        }
        slider.progress = ((input.text.toString().toDoubleOrNull() ?: 50.0) * 10).toInt()
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) input.setText((value / 10.0).toString())
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                input.text.toString().replace(',', '.').toDoubleOrNull()?.let {
                    if (it in 0.0..100.0) slider.progress = (it * 10).toInt()
                }
                updatePreview()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        box.addView(TextView(this).apply {
            text = "Nível manual: 0–100% do limite de correção. Não representa o teor de etanol."
        })
        box.addView(slider); box.addView(input); box.addView(label)
        updatePreview()
        val dialog = AlertDialog.Builder(this).setTitle("Mistura manual")
            .setView(box).setPositiveButton("Conferir e aplicar", null)
            .setNegativeButton("Cancelar", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val level = input.text.toString().replace(',', '.').toDoubleOrNull()
                if (level == null || !level.isFinite() || level !in 0.0..100.0) {
                    input.error = "Informe um valor entre 0 e 100"
                } else {
                    dialog.dismiss()
                    programManualMixture(level)
                }
            }
        }
        dialog.show()
    }

    /** User actions wait for the current bounded read; no repeated tap is required. */
    private fun afterSerialIdle(action: () -> Unit) {
        if (pendingSerialAction) { toast("Já existe um comando aguardando envio"); return }
        pendingSerialAction = true
        handler.removeCallbacks(pollTask)
        handler.removeCallbacks(programmingStatusTask)
        val token = generation
        val started = SystemClock.elapsedRealtime()
        handler.post(object : Runnable {
            override fun run() {
                if (!valid(token) || !connected || demoMode) {
                    if (token == generation) pendingSerialAction = false
                    return
                }
                if (busy) {
                    if (SystemClock.elapsedRealtime() - started < 10_000) handler.postDelayed(this, 50)
                    else {
                        pendingSerialAction = false
                        toast("A comunicação não liberou o comando. Reconecte o módulo.")
                        if (programmingMode != null) handler.post(programmingStatusTask)
                        else if (polling) handler.post(pollTask)
                    }
                    return
                }
                pendingSerialAction = false
                action()
            }
        })
    }

    private fun programManualMixture(levelPercent: Double) {
        if (demoMode || !connected || !foreground || programmingMode != null || pendingSerialAction) {
            toast("Conecte o módulo em modo real e conclua outras operações primeiro")
            return
        }
        val token = generation
        afterSerialIdle {
            val resumePolling = polling
            polling = false
            dashboard.autoReading = false
            handler.removeCallbacks(pollTask)
            busy = true
            ioExecutor.execute {
                var result: ManualMixtureResult? = null
                var failure: String? = null
                var cancelled = false
                try {
                    val beforeTx = TechRaceProtocol.READ_SETTINGS
                    val beforeRx = rawExchange(beforeTx, token, attempts = 2)
                    val before = TechRaceProtocol.extractResponse(beforeRx, 1, 25)
                        ?: error("Leitura inicial da EEPROM inválida")

                    // READ_SETTINGS starts at address 0x01: offset 3 is FINAL (0x04),
                    // and offset 2 is MIX (0x03), matching Principal.cpp/Program.cpp.
                    val correctionLimitPercent = (before[3].toInt() and 0xFF) * 100.0 / 64.0
                    val encoded = TechRaceProtocol.manualMixtureRaw(levelPercent, correctionLimitPercent)
                    val confirmed = java.util.concurrent.CountDownLatch(1)
                    var accepted = false
                    handler.post {
                        if (!valid(token)) { confirmed.countDown(); return@post }
                        val dialog = AlertDialog.Builder(this)
                            .setTitle("Confirmar mistura manual")
                            .setMessage("Nível solicitado: $levelPercent%%.\nCorreção efetiva: %.4f%%.\nMIX: %d.\nLimite lido: %.4f%%.\nA resolução da central é de 1,5625 ponto percentual.".format(encoded * 100.0 / 64, encoded, correctionLimitPercent))
                            .setPositiveButton("Gravar") { _, _ -> accepted = true }
                            .setNegativeButton("Cancelar", null).create()
                        manualConfirmDialog = dialog
                        dialog.setOnDismissListener { manualConfirmDialog = null; confirmed.countDown() }
                        dialog.show()
                    }
                    while (!confirmed.await(100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        if (!valid(token)) throw CancellationException()
                    }
                    if (!accepted) throw CancellationException("Alteração cancelada; nenhum valor foi gravado")
                    val writeTx = TechRaceProtocol.writeEeprom(0x03, byteArrayOf(encoded.toByte()))
                    val writeRx = rawExchange(writeTx, token, attempts = 3)
                    check(TechRaceProtocol.isValidAck(writeRx)) { "A central não confirmou a gravação" }

                    val verifyTx = TechRaceProtocol.READ_MIXTURE
                    val verifyRx = rawExchange(verifyTx, token, attempts = 2)
                    val mixtureReply = TechRaceProtocol.extractResponse(verifyRx, 1, 1)
                        ?: error("Resposta de verificação da EEPROM inválida")
                    val readBack = mixtureReply[0].toInt() and 0xFF
                    check(readBack == encoded) {
                        "Valor lido de MIX ($readBack) diferente do gravado ($encoded)"
                    }
                    val verified = before.copyOf().also { it[2] = readBack.toByte() }
                    result = ManualMixtureResult(
                        levelPercent = levelPercent,
                        correctionLimitPercent = correctionLimitPercent,
                        encoded = encoded,
                        readBack = readBack,
                        settings = verified,
                        writeTx = writeTx,
                        writeRx = writeRx,
                        verifyRx = verifyRx
                    )
                } catch (e: CancellationException) {
                    cancelled = true
                } catch (e: Exception) {
                    failure = e.message ?: "Falha USB"
                }

                val completed = result
                val errorMessage = failure
                handler.post {
                    if (!valid(token) || demoMode) return@post
                    busy = false
                    if (cancelled) {
                        toast("Alteração cancelada; nenhum valor foi gravado")
                    } else if (errorMessage != null || completed == null) {
                        lastError = "Mistura manual: ${errorMessage ?: "falha de verificação"}"
                        dashboard.markCommunicationFailure()
                        showText("Falha ao aplicar mistura", lastError)
                    } else {
                        preferences.edit().putFloat("manual_level", completed.levelPercent.toFloat()).apply()
                        val updatedSettings = ModuleSettings(completed.settings)
                        settingsSnapshot = updatedSettings
                        settingsTime = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                            .format(java.util.Date())
                        lastError = "Nenhum"
                        lastRx = completed.verifyRx
                        moduleReport = updatedSettings.describe(rpmCalibration)
                        val report = "Mistura ${completed.levelPercent}% aplicada e confirmada.\n\n$moduleReport"
                        moduleReport = report
                        toast("Mistura manual ${completed.levelPercent}% gravada e confirmada")
                        showText("Mistura atualizada", report)
                    }
                    if (resumePolling) {
                        polling = true
                        dashboard.autoReading = true
                        handler.postDelayed(pollTask, pollingIntervalMs)
                    }
                }
            }
        }
    }

    private data class ManualMixtureResult(
        val levelPercent: Double,
        val correctionLimitPercent: Double,
        val encoded: Int,
        val readBack: Int,
        val settings: ByteArray,
        val writeTx: ByteArray,
        val writeRx: ByteArray,
        val verifyRx: ByteArray
    )

    private fun beginProgramming(mode: ProgrammingMode) {
        if (demoMode) {
            AlertDialog.Builder(this)
                .setTitle("${mode.label} — demonstração")
                .setMessage("Nenhum comando será enviado no modo demonstração.")
                .setPositiveButton("OK", null).show()
            return
        }
        if (!connected || !foreground) {
            toast("Conecte o leitor antes de iniciar")
            return
        }
        if (programmingMode != null || pendingSerialAction) {
            toast("Há uma programação em andamento")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Iniciar ${mode.label}?")
            .setMessage(if (mode == ProgrammingMode.MAP)
                "Mantenha o motor estabilizado em marcha lenta, inicialmente sem ar-condicionado ou outras cargas. O APK avisará quando a central solicitar carga na segunda etapa."
                else "Mantenha o motor em marcha lenta estável. A leitura contínua será pausada durante a calibração.")
            .setPositiveButton("Iniciar") { _, _ -> sendProgrammingStart(mode) }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun sendProgrammingStart(mode: ProgrammingMode) {
        if (demoMode || !connected || !foreground || programmingMode != null || pendingSerialAction) return
        afterSerialIdle { startProgrammingWhenIdle(mode) }
    }

    private fun startProgrammingWhenIdle(mode: ProgrammingMode) {
        resumePollingAfterProgramming = polling
        polling = false
        dashboard.autoReading = false
        handler.removeCallbacks(pollTask)
        handler.removeCallbacks(programmingStatusTask)
        busy = true
        val token = generation
        val tx = if (mode == ProgrammingMode.SONDA_RPM)
            TechRaceProtocol.PROGRAM_SONDA_RPM else TechRaceProtocol.PROGRAM_MAP
        ioExecutor.execute {
            var rx = byteArrayOf()
            var initialFlags: Int? = null
            var failure: String? = null
            try {
                val before = readProgrammingFlags(token)
                check(before and 0x41 == 0) { "A central já está calibrando. Encerre a operação anterior antes de iniciar outra." }
                // Selectors start calibration: never blindly resend after a lost ACK.
                rx = rawExchange(tx, token, attempts = 1)
                initialFlags = try { readProgrammingFlags(token) } catch (_: Exception) { null }
                check(TechRaceProtocol.isValidAck(rx) || ((initialFlags ?: 0) and mode.flagMask) != 0) {
                    "Início não confirmado. O comando não será reenviado automaticamente; confira o estado da central."
                }
            } catch (e: Exception) {
                failure = e.message ?: "Falha USB"
            }
            val err = failure
            handler.post {
                if (!valid(token) || demoMode) return@post
                busy = false
                if (err != null) {
                    lastError = "Programação ${mode.label}: $err"
                    dashboard.markCommunicationFailure()
                    toast(lastError)
                    if (resumePollingAfterProgramming) {
                        polling = true
                        dashboard.autoReading = true
                        handler.postDelayed(pollTask, pollingIntervalMs)
                    }
                    resumePollingAfterProgramming = false
                } else {
                    programmingMode = mode
                    programmingRuntimeFlags = initialFlags
                    programmingSession = ProgrammingSession(mode == ProgrammingMode.MAP).also {
                        initialFlags?.let(it::update)
                    }
                    mapSecondStageAcknowledged = false
                    programmingStartedAt = SystemClock.elapsedRealtime()
                    showProgrammingProgress(mode)
                    if (mode == ProgrammingMode.MAP && (initialFlags ?: 0) and 0x03 == 0x03) showMapLoadPrompt()
                    handler.postDelayed(programmingStatusTask, 700L)
                }
            }
        }
    }

    private fun showProgrammingProgress(mode: ProgrammingMode) {
        programmingDialog?.dismiss()
        val status = TextView(this).apply {
            setPadding(36, 24, 36, 24)
            text = buildString {
                append("${mode.label} iniciado.\n")
                append("Aguardando confirmação do módulo.")
                if (mode == ProgrammingMode.MAP) append("\n\n" + MapCalibrationDisplay.instructions(ProgrammingSession.Stage.WAITING_ACTIVE))
            }
            setTextIsSelectable(true)
        }
        programmingStatusView = status
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(TextView(this@MainActivity).apply {
                setPadding(36, 12, 36, 24)
                textSize = 20f
                text = MapCalibrationDisplay.sensors(null)
                programmingSensorsView = this
            })
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Programando ${mode.label}")
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Encerrar", null)
            .setNeutralButton("Atualizar", null)
            .setNegativeButton("Instruções", null)
            .setCancelable(false)
            .create()
        programmingDialog = dialog
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                sendProgrammingStop(closeDialog = true)
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                afterSerialIdle {
                    programmingStartedAt = SystemClock.elapsedRealtime()
                    programmingMode?.let { queryProgrammingStatus(it) }
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                showText("Instruções", if (mode == ProgrammingMode.MAP)
                    MapCalibrationDisplay.instructions(ProgrammingSession.Stage.RUNNING) + "\n\n" + MapCalibrationDisplay.instructions(ProgrammingSession.Stage.MAP_LOAD)
                    else "Mantenha o motor estabilizado em marcha lenta e aguarde a confirmação da central.")
            }
        }
        dialog.show()
    }

    private fun showMapLoadPrompt() {
        if (mapSecondStageAcknowledged || mapLoadDialog != null) return
        val dialog = AlertDialog.Builder(this)
            .setTitle("MAP — etapa 2: ligar carga")
            .setMessage(MapCalibrationDisplay.instructions(ProgrammingSession.Stage.MAP_LOAD) + "\n\n" + programmingSensorsView?.text?.toString().orEmpty())
            .setPositiveButton("Carga ligada") { _, _ -> mapSecondStageAcknowledged = true }
            .setCancelable(false).create()
        mapLoadDialog = dialog
        dialog.show()
    }

    private fun readProgrammingFlags(token: Int): Int {
        val reply = rawExchange(TechRaceProtocol.READ_PROGRAMMING_STATUS, token, attempts = 2)
        val bytes = TechRaceProtocol.extractResponse(reply, 0, 2)
            ?: error("Estado RAM 0x63 inválido")
        return bytes[0].toInt() and 255
    }

    private fun queryProgrammingStatus(mode: ProgrammingMode) {
        if (programmingMode != mode || demoMode || !connected || !foreground || pendingSerialAction) return
        handler.removeCallbacks(programmingStatusTask)
        if (busy) {
            handler.postDelayed(programmingStatusTask, 300L)
            return
        }
        busy = true
        val token = generation
        ioExecutor.execute {
            val observation = ProgrammingMonitor.read { request, attempts ->
                rawExchange(request, token, attempts)
            }
            val sample = observation.sensors
            val data = observation.flags
            val err = observation.statusError
            handler.post {
                if (!valid(token) || programmingMode != mode) return@post
                busy = false
                val elapsed = (SystemClock.elapsedRealtime() - programmingStartedAt) / 1000
                programmingSensorsView?.text = MapCalibrationDisplay.sensors(sample)
                if (mode == ProgrammingMode.MAP) {
                    mapLoadDialog?.setMessage(MapCalibrationDisplay.instructions(ProgrammingSession.Stage.MAP_LOAD) +
                        "\n\n" + MapCalibrationDisplay.sensors(sample) +
                        if (data == null) "\nFalha ao consultar a central. Etapa atual não confirmada." else "")
                }
                if (data == null) {
                    programmingStatusView?.text = "Falha ao consultar a central: $err.\nA conclusão ainda não foi confirmada."
                } else {
                    programmingRuntimeFlags = data
                    val stage = programmingSession?.update(data)
                    programmingStatusView?.text = when (stage) {
                        ProgrammingSession.Stage.MAP_LOAD -> "MAP — etapa 2: ligue a carga e mantenha a lenta estável. • ${elapsed}s"
                        ProgrammingSession.Stage.RUNNING -> "${mode.label}: ${if (mode == ProgrammingMode.MAP) "etapa 1, sem carga" else "calibrando"} • ${elapsed}s"
                        ProgrammingSession.Stage.COMPLETE -> "${mode.label}: programação concluída e confirmada pela central."
                        else -> "Aguardando confirmação de início da central. • ${elapsed}s"
                    }
                    programmingStatusView?.append("\n\n" + if (mode == ProgrammingMode.MAP)
                        MapCalibrationDisplay.instructions(stage)
                    else "Mantenha o motor em marcha lenta estável e aguarde a confirmação da central.")
                    if (stage == ProgrammingSession.Stage.MAP_LOAD) {
                        showMapLoadPrompt()
                        mapLoadDialog?.setMessage(MapCalibrationDisplay.instructions(stage) +
                            "\n\n" + MapCalibrationDisplay.sensors(sample))
                    } else {
                        mapLoadDialog?.dismiss(); mapLoadDialog = null
                    }
                    if (stage == ProgrammingSession.Stage.COMPLETE) {
                        finishProgramming(mode)
                        return@post
                    }
                }
                if (elapsed < 90) {
                    handler.postDelayed(programmingStatusTask, if (data == null) 1500L else 500L)
                } else {
                    programmingStatusView?.append("\nTempo de acompanhamento excedido. Use Atualizar ou Encerrar. Não há confirmação de conclusão.")
                    mapLoadDialog?.dismiss(); mapLoadDialog = null
                    programmingSensorsView?.text = MapCalibrationDisplay.sensors(null)
                }
            }
        }
    }

    private fun finishProgramming(mode: ProgrammingMode) {
        handler.removeCallbacks(programmingStatusTask)
        programmingMode = null
        programmingSession = null
        mapLoadDialog?.dismiss(); mapLoadDialog = null
        programmingDialog?.dismiss(); programmingDialog = null
        programmingStatusView = null
        programmingSensorsView = null
        polling = resumePollingAfterProgramming
        resumePollingAfterProgramming = false
        dashboard.autoReading = polling
        showText("Programação concluída", "${mode.label} programado com sucesso. A central confirmou o encerramento da calibração.")
        // Refresh persisted flags only after runtime calibration has finished.
        queryModule(1, showResult = false)
    }

    private fun confirmProgrammingStop() {
        if (demoMode) {
            toast("Demonstração: nenhum comando enviado")
            return
        }
        if (!connected || !foreground) {
            toast("Conecte o módulo por USB")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Encerrar modo de programação?")
            .setMessage("Deseja encerrar esta operação?")
            .setPositiveButton("Encerrar") { _, _ -> sendProgrammingStop(closeDialog = false) }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun sendProgrammingStop(closeDialog: Boolean) {
        if (demoMode || !connected || !foreground) return
        afterSerialIdle { stopProgrammingWhenIdle(closeDialog) }
    }

    private fun stopProgrammingWhenIdle(closeDialog: Boolean) {
        handler.removeCallbacks(programmingStatusTask)
        val resumeLive = polling
        polling = false
        val token = generation
        val tx = TechRaceProtocol.PROGRAM_STOP
        busy = true
        ioExecutor.execute {
            var rx = byteArrayOf()
            var failure: String? = null
            try {
                rx = rawExchange(tx, token, attempts = 3)
                check(readProgrammingFlags(token) and 0x41 == 0) { "A central ainda informa calibração ativa" }
            } catch (e: Exception) {
                failure = e.message ?: "Falha USB"
            }
            val err = failure
            handler.post {
                if (!valid(token) || demoMode) return@post
                busy = false
                if (err != null) {
                    programmingStatusView?.text = "Falha ao enviar encerramento: $err\n\nA programação não foi marcada como encerrada no APK."
                    toast("Falha ao encerrar programação")
                    if (programmingMode != null) handler.postDelayed(programmingStatusTask, 1500)
                    else if (resumeLive) { polling = true; handler.post(pollTask) }
                    return@post
                }
                programmingMode = null
                programmingSession = null
                mapLoadDialog?.dismiss(); mapLoadDialog = null
                programmingSensorsView?.text = MapCalibrationDisplay.sensors(null)
                programmingStatusView?.text = "Modo de programação encerrado.\n\nTX: ${TechRaceProtocol.toHex(tx)}\nRX: ${TechRaceProtocol.toHex(rx)}"
                if (closeDialog) {
                    programmingDialog?.dismiss()
                    programmingDialog = null
                    programmingStatusView = null
                    programmingSensorsView = null
                }
                toast("Modo de programação encerrado")
                if (resumePollingAfterProgramming || resumeLive) {
                    polling = true
                    dashboard.autoReading = true
                    handler.postDelayed(pollTask, pollingIntervalMs)
                }
                resumePollingAfterProgramming = false
            }
        }
    }

    /** Replicates Porta_serial.cpp::Send_command timing/retry behavior for write commands. */
    private fun rawExchange(tx: ByteArray, token: Int, attempts: Int = 3): ByteArray {
        val p = transport ?: error("Porta fechada")
        val buffer = ByteArray(128)
        var last = byteArrayOf()
        repeat(attempts.coerceIn(1, 3)) {
            val drainDeadline = SystemClock.elapsedRealtime() + DRAIN_TIMEOUT_MS
            while (p.read(buffer, DRAIN_IDLE_READ_MS) > 0) {
                if (!valid(token)) throw CancellationException()
                check(SystemClock.elapsedRealtime() < drainDeadline) { "USB sem intervalo ocioso" }
            }
            if (!valid(token)) throw CancellationException()
            p.write(tx, 500)
            val out = ByteArrayOutputStream()
            val deadline = SystemClock.elapsedRealtime() + WRITE_RESPONSE_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                if (!valid(token)) throw CancellationException()
                val n = p.read(buffer, SERIAL_READ_MS)
                if (n > 0) {
                    out.write(buffer, 0, n)
                    if (out.size() > 128) break
                    val command = tx[1].toInt() and 255
                    val count = tx[4].toInt() and 255
                    val snapshot = out.toByteArray()
                    if (command in 0..3) {
                        if (TechRaceProtocol.extractResponse(snapshot, command, count) != null) return snapshot
                        if (snapshot.size > count + 3) break
                    }
                } else if (out.size() > 0 && (tx[1].toInt() and 255) !in 0..3 &&
                    TechRaceProtocol.isValidAck(out.toByteArray())) {
                    break
                }
            }
            last = out.toByteArray()
            val command = tx[1].toInt() and 255
            val validReply = if (command in 0..3)
                TechRaceProtocol.extractResponse(last, command, tx[4].toInt() and 255) != null
                else TechRaceProtocol.isValidAck(last)
            if (validReply) return last
        }
        return last
    }

    private fun showLocalAdjustments() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 16, 36, 16)
        }
        val factors = listOf(1, 2, 4, 8)
        val factor = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, factors)
            setSelection(factors.indexOf(rpmCalibration.toInt()).coerceAtLeast(0))
        }
        val interval = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(pollingIntervalMs.toString())
        }
        box.addView(TextView(this).apply { text = "Fator RPM (como no EXE):" })
        box.addView(factor)
        box.addView(TextView(this).apply { text = "Pausa entre leituras (80–2000 ms):" })
        box.addView(interval)
        AlertDialog.Builder(this).setTitle("Ajustes do aplicativo")
            .setView(box).setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar") { _, _ ->
                rpmCalibration = factors[factor.selectedItemPosition].toDouble()
                pollingIntervalMs = interval.text.toString().toLongOrNull()?.coerceIn(80, 2000) ?: 250
                preferences.edit().putInt("rpm_factor", rpmCalibration.toInt())
                    .putLong("poll_ms", pollingIntervalMs).apply()
            }.show()
    }

    private fun showAdjustments() {
        if (busy || pendingSerialAction || programmingMode != null) { toast("Conclua a operação atual primeiro"); return }
        val draft = loadLocalSettingsDraft()
        if (demoMode || !connected || !foreground) {
            val resume = polling
            if (resume) {
                polling = false
                dashboard.autoReading = false
                handler.removeCallbacks(pollTask)
            }
            val settings = draft?.let(::ModuleSettings) ?: ModuleSettings.editableDefaults(rpmCalibration)
            settingsSnapshot = settings
            settingsTime = if (draft != null) "Rascunho salvo neste telefone" else "Valores iniciais editáveis"
            showModuleAdjustmentEditor(settings, resume)
            return
        }
        val token = generation
        val resume = polling
        polling = false
        dashboard.autoReading = false
        handler.removeCallbacks(pollTask)
        busy = true
        ioExecutor.execute {
            var bytes: ByteArray? = null
            var failure: String? = null
            try {
                val response = rawExchange(TechRaceProtocol.READ_SETTINGS, token, attempts = 2)
                bytes = TechRaceProtocol.extractResponse(response, 1, 25)
                    ?: error("EEPROM inválida")
            } catch (e: Exception) { failure = e.message ?: "Falha de leitura" }
            val data = bytes
            val err = failure
            handler.post {
                if (!valid(token)) return@post
                busy = false
                if (err != null || data == null) {
                    toast("Não foi possível ler a EEPROM: ${err ?: "resposta inválida"}. Você pode salvar no telefone e tentar ler novamente.")
                    val fallback = draft?.let(::ModuleSettings) ?: ModuleSettings.editableDefaults(rpmCalibration)
                    settingsSnapshot = fallback
                    settingsTime = if (draft != null) "Rascunho local; ECU ainda não lida" else "Falha de leitura da ECU"
                    showModuleAdjustmentEditor(fallback, resume, canWriteToEcu = false)
                    return@post
                }
                val current = ModuleSettings(data)
                val settings = draft?.let {
                    ModuleSettings(ModuleSettings.mergeEditableDraft(current.raw, it))
                } ?: current
                settingsSnapshot = settings
                settingsTime = if (draft != null) "EEPROM atual + rascunho local" else
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                dashboard.updateProgrammingFlags(settings.mapFlag, settings.rpmFlag)
                showModuleAdjustmentEditor(settings, resume)
            }
        }
    }

    private fun readSettingsForEditor(resumeLiveRead: Boolean, fallback: ModuleSettings) {
        if (demoMode || !connected || !foreground) {
            toast("Conecte a central em modo real para ler a programação")
            return
        }
        if (busy || pendingSerialAction || programmingMode != null) { toast("Conclua a operação atual primeiro"); return }
        val token = generation
        busy = true
        polling = false
        dashboard.autoReading = false
        handler.removeCallbacks(pollTask)
        ioExecutor.execute {
            var data: ByteArray? = null
            var failure: String? = null
            try {
                val response = rawExchange(TechRaceProtocol.READ_SETTINGS, token, attempts = 2)
                data = TechRaceProtocol.extractResponse(response, 1, 25)
                    ?: error("Resposta de EEPROM inválida")
            } catch (e: Exception) { failure = e.message ?: "Falha de leitura" }
            val result = data
            val errorMessage = failure
            handler.post {
                if (!valid(token)) return@post
                busy = false
                if (result == null || errorMessage != null) {
                    toast("Falha ao ler programação: ${errorMessage ?: "resposta inválida"}. O rascunho continua salvo no telefone.")
                    showModuleAdjustmentEditor(fallback, resumeLiveRead, canWriteToEcu = false)
                    return@post
                }
                val settings = ModuleSettings(result)
                settingsSnapshot = settings
                settingsTime = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date())
                dashboard.updateProgrammingFlags(settings.mapFlag, settings.rpmFlag)
                toast("Programação lida da central")
                showModuleAdjustmentEditor(settings, resumeLiveRead)
            }
        }
    }

    private fun showModuleAdjustmentEditor(
        settings: ModuleSettings,
        resumeLiveRead: Boolean,
        canWriteToEcu: Boolean = connected && foreground && !demoMode
    ) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 12, 28, 12) }
        val fields = mutableMapOf<String, EditText>()
        fun section(label: String) {
            box.addView(TextView(this).apply {
                text = label
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, 20, 0, 4)
            })
        }
        fun field(key: String, label: String, initial: String, decimals: Boolean = false): EditText {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text = label
                textSize = 14f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val edit = EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    (if (decimals) android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
                setSingleLine(true)
                gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                minWidth = (88 * resources.displayMetrics.density).toInt()
                maxWidth = (112 * resources.displayMetrics.density).toInt()
                setText(initial)
            }
            row.addView(edit, LinearLayout.LayoutParams(
                (104 * resources.displayMetrics.density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            box.addView(row)
            fields[key] = edit
            return edit
        }
        fun check(label: String, mask: Int): CheckBox = CheckBox(this).apply {
            text = label
            isChecked = settings.raw[0].toInt() and mask != 0
            box.addView(this)
        }
        fun number(key: String): Double = fields.getValue(key).text.toString().trim()
            .replace(',', '.').toDoubleOrNull() ?: error("Preencha todos os campos com números válidos")

        val raw = settings.raw
        fun u(i: Int) = raw[i].toInt() and 255
        fun pct(i: Int) = String.format(java.util.Locale.US, "%.4f", u(i) * 100.0 / 64.0)
        section("Partida à frio")
        field("coldTime", "Tempo de injeção (ms)",
            ModuleSettings.crankingDurationMilliseconds(u(12), u(13)).toString())
        field("coldTemp", "Temperatura partida (°C)", settings.temperature(17).toString())
        field("initialMix", "Mistura inicial (%)", pct(14), true)
        val externalStart = check("Partida externa", 0x04)
        val limitedByIdle = check("Limitado pela lenta", 0x08)

        section("Aquecimento do motor")
        val heatingEnabled = check("Aquecimento do motor ativado", 0x80)
        val heatInjection = field("heatInjection", "Injeção extra fria (%)", pct(21), true)
        val heatTemperature = field("heatTemperature", "Temperatura aquecimento (°C)", settings.temperature(16).toString())
        fun updateHeatEnabled() {
            heatInjection.isEnabled = heatingEnabled.isChecked
            heatTemperature.isEnabled = heatingEnabled.isChecked
        }
        heatingEnabled.setOnCheckedChangeListener { _, _ -> updateHeatEnabled() }
        updateHeatEnabled()

        section("Correção")
        field("maxCorrection", "Correção máxima (%)", pct(3), true)
        field("correctionTemperature", "Temperatura correção (°C)", settings.temperature(15).toString())

        section("Aceleração rápida")
        val rapidEnabled = check("Aceleração Rápida ativada", 0x40)
        val rapidVariation = field("rapidVariation", "Variação mínima (µs)",
            ModuleSettings.rapidVariationMicroseconds(u(18), u(19)).toString(), true)
        val rapidExtra = field("rapidExtra", "Extra a 20 ms (%)", pct(20), true)
        fun updateRapidEnabled() {
            rapidVariation.isEnabled = rapidEnabled.isChecked
            rapidExtra.isEnabled = rapidEnabled.isChecked
        }
        rapidEnabled.setOnCheckedChangeListener { _, _ -> updateRapidEnabled() }
        updateRapidEnabled()

        section("Ajuste do RPM marcha-lenta")
        val ticks = u(4) + 256 * u(5)
        val rpmValue = field("rpm", "RPM de lenta",
            if (ticks == 0) "850" else ModuleSettings.rpmTarget(ticks, rpmCalibration).toString())
        val rpmButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val rpmDown = Button(this).apply { text = "RPM −25" }
        val rpmUp = Button(this).apply { text = "RPM +25" }
        rpmButtons.addView(rpmDown, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        rpmButtons.addView(rpmUp, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(rpmButtons)
        fun stepRpm(delta: Int) {
            val current = rpmValue.text.toString().toIntOrNull() ?: 850
            rpmValue.setText((current + delta).coerceIn(600, 1500).toString())
        }
        rpmDown.setOnClickListener { stepRpm(-25) }
        rpmUp.setOnClickListener { stepRpm(25) }
        box.addView(TextView(this).apply { text = "RPM Cal. (equivalente ao EXE):" })
        val rpmFactors = listOf(1, 2, 4, 8)
        val rpmFactor = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, rpmFactors)
            setSelection(rpmFactors.indexOf(rpmCalibration.toInt()).coerceAtLeast(0))
        }
        box.addView(rpmFactor)

        section("Lambda lenta")
        val lambdaEnabled = check("Lambda Lenta ativada", 0x10)
        val lambdaTime = field("lambdaTime", "Tempo Lambda (s)", (u(24) * 0.21).toInt().toString())
        val lambdaValue = field("lambdaValue", "Valor Lambda (mV)",
            ((u(22) * 5000.0 / 255.0).toInt()).toString())
        val widebandEnabled = check("WideBand Sensor", 0x20)
        fun updateLambdaEnabled() {
            lambdaTime.isEnabled = lambdaEnabled.isChecked
            lambdaValue.isEnabled = lambdaEnabled.isChecked
            widebandEnabled.isEnabled = lambdaEnabled.isChecked
        }
        lambdaEnabled.setOnCheckedChangeListener { _, _ -> updateLambdaEnabled() }
        updateLambdaEnabled()

        val scroll = ScrollView(this).apply { addView(box) }
        val canWriteNow = canWriteToEcu && connected && foreground && !demoMode
        val infoMessage = if (canWriteNow) {
            if (loadLocalSettingsDraft() != null) "Rascunho local. Revise os valores e grave na ECU."
            else "Leitura da ECU: $settingsTime."
        } else if (connected && foreground && !demoMode) {
            "Leitura da EEPROM não confirmada. Você pode salvar no telefone; tente ler novamente antes de gravar na ECU."
        } else "Edição disponível sem conexão. Salvará os valores neste telefone; conecte a ECU para gravá-los."
        val readButton = Button(this).apply { text = "Ler programação da central" }
        val saveButton = Button(this).apply {
            text = if (canWriteNow) "Gravar alterações na central" else "Salvar ajustes no celular"
        }
        val editorLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 0, 20, 0)
            addView(readButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(saveButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
        }
        val dialog = AlertDialog.Builder(this).setTitle("Ajustar ECU")
            .setMessage(infoMessage)
            .setView(editorLayout).setNegativeButton("Cancelar") { _, _ -> if (resumeLiveRead) resumePolling() }
            .create()
        dialog.setOnShowListener {
            readButton.setOnClickListener {
                if (demoMode || !connected || !foreground) {
                    toast("Conecte a central em modo real para ler a programação")
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Ler programação da central?")
                        .setMessage("Os valores digitados nesta tela serão descartados. A leitura mostrará o que está gravado agora na ECU.")
                        .setPositiveButton("Ler agora") { _, _ ->
                            dialog.dismiss()
                            readSettingsForEditor(resumeLiveRead, settings)
                        }
                        .setNegativeButton("Continuar editando", null)
                        .show()
                }
            }
            saveButton.setOnClickListener {
                try {
                    val coldTime = number("coldTime")
                    val coldTemp = number("coldTemp")
                    val initialMix = number("initialMix")
                    val heatInjectionPercent = number("heatInjection")
                    val heatTemp = number("heatTemperature")
                    val maxCorrection = number("maxCorrection")
                    val correctionTemp = number("correctionTemperature")
                    val variationUs = number("rapidVariation")
                    val extraAt20Ms = number("rapidExtra")
                    val rpm = number("rpm")
                    val lambdaSeconds = number("lambdaTime")
                    val lambdaMv = number("lambdaValue")
                    require(rpm in 600.0..1500.0) { "RPM alvo deve estar entre 600 e 1500" }
                    require(coldTime in 0.0..4000.0) { "Tempo de injeção deve ficar entre 0 e 4000 ms" }
                    require(coldTemp in 15.0..35.0) { "Temp. máx. da partida a frio deve ficar entre 15 e 35 °C" }
                    require(maxCorrection in 10.0..60.0) { "Limite máximo deve estar entre 10% e 60%" }
                    require(initialMix in 10.0..maxCorrection) { "Mistura inicial deve ficar entre 10% e a correção máxima" }
                    require(correctionTemp in 0.0..100.0) { "Temperatura máxima da correção deve ficar entre 0 e 100 °C" }

                    val changed = settings.raw.copyOf()
                    fun putPct(index: Int, value: Double) { changed[index] = TechRaceProtocol.encodePercent(value).toByte() }
                    fun setFlag(mask: Int, enabled: Boolean) {
                        val current = changed[0].toInt() and 255
                        changed[0] = (if (enabled) current or mask else current and mask.inv()).toByte()
                    }

                    val coldTicks = ModuleSettings.crankingDurationTicks(coldTime.toInt())
                    changed[12] = coldTicks.toByte(); changed[13] = (coldTicks shr 8).toByte()
                    putPct(14, initialMix)
                    changed[17] = ModuleSettings.encodeTemperature(coldTemp).toByte()
                    if (heatingEnabled.isChecked) {
                        require(heatInjectionPercent in 0.0..10.0) { "Injeção extra deve ficar entre 0% e 10%" }
                        require(heatTemp in 35.0..60.0) { "Temp. máx. do aquecimento deve ficar entre 35 e 60 °C" }
                        putPct(21, heatInjectionPercent)
                        changed[16] = ModuleSettings.encodeTemperature(heatTemp).toByte()
                    }
                    putPct(3, maxCorrection)
                    changed[15] = ModuleSettings.encodeTemperature(correctionTemp).toByte()
                    val rpmCalibrationToSave = rpmFactors[rpmFactor.selectedItemPosition].toDouble()
                    val rpmTicks = ModuleSettings.rpmTicks(rpm.toInt(), rpmCalibrationToSave)
                    changed[4] = rpmTicks.toByte(); changed[5] = (rpmTicks shr 8).toByte()
                    if (rapidEnabled.isChecked) {
                        require(variationUs in 200.0..15000.0) { "Variação mínima deve ficar entre 200 e 15000 µs" }
                        require(extraAt20Ms in 0.0..30.0) { "Tempo extra a 20 ms deve ficar entre 0% e 30%" }
                        val variationTicks = ModuleSettings.rapidVariationTicks(variationUs.toInt())
                        changed[18] = variationTicks.toByte(); changed[19] = (variationTicks shr 8).toByte()
                        putPct(20, extraAt20Ms)
                    }
                    if (lambdaEnabled.isChecked) {
                        require(lambdaSeconds in 10.0..50.0) { "Tempo de ajuste deve ficar entre 10 e 50 s" }
                        require(lambdaMv in 0.0..1000.0) { "Valor Lambda deve ficar entre 0 e 1000 mV" }
                        changed[24] = ModuleSettings.lambdaAdjustmentRaw(lambdaSeconds.toInt()).toByte()
                        changed[22] = ModuleSettings.lambdaThresholdRaw(lambdaMv.toInt()).toByte()
                    }
                    setFlag(0x04, externalStart.isChecked)
                    setFlag(0x08, limitedByIdle.isChecked)
                    setFlag(0x10, lambdaEnabled.isChecked)
                    setFlag(0x20, widebandEnabled.isChecked)
                    setFlag(0x40, rapidEnabled.isChecked)
                    setFlag(0x80, heatingEnabled.isChecked)
                    if (connected && foreground && !demoMode) {
                        AlertDialog.Builder(this)
                            .setTitle("Gravar alterações na central?")
                            .setMessage("Os ajustes serão enviados à EEPROM e lidos novamente para confirmar a gravação.")
                            .setPositiveButton("Gravar agora") { _, _ ->
                                dialog.dismiss()
                                saveLocalSettingsDraft(changed)
                                writeModuleSettings(changed, resumeLiveRead, rpmCalibrationToSave)
                            }
                            .setNegativeButton("Cancelar", null)
                            .show()
                    } else {
                        saveLocalSettingsDraft(changed)
                        rpmCalibration = rpmCalibrationToSave
                        preferences.edit().putInt("rpm_factor", rpmCalibration.toInt()).apply()
                        settingsSnapshot = ModuleSettings(changed)
                        settingsTime = "Rascunho salvo neste telefone"
                        toast("Rascunho salvo. Conecte a ECU e abra Ajustar ECU para gravar.")
                        dialog.dismiss()
                    }
                } catch (e: Exception) { toast(e.message ?: "Valores inválidos") }
            }
        }
        dialog.setOnCancelListener { if (resumeLiveRead) resumePolling() }
        dialog.show()
    }

    private fun resumePolling() {
        if (connected && !demoMode) {
            polling = true
            dashboard.autoReading = true
            handler.removeCallbacks(pollTask)
            handler.postDelayed(pollTask, pollingIntervalMs)
        }
    }

    private fun loadLocalSettingsDraft(): ByteArray? {
        val encoded = preferences.getString("ecu_settings_draft", null) ?: return null
        val values = encoded.trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull(16)?.toByte() }
        return values.takeIf { it.size == 25 }?.toByteArray()
    }

    private fun saveLocalSettingsDraft(settings: ByteArray) {
        require(settings.size == 25)
        val saved = preferences.edit().putString("ecu_settings_draft", TechRaceProtocol.toHex(settings)).commit()
        check(saved) { "Não foi possível salvar os ajustes neste telefone" }
    }

    private fun writeModuleSettings(settings: ByteArray, resume: Boolean, rpmFactorToSave: Double) {
        val token = generation
        busy = true
        polling = false
        dashboard.autoReading = false
        ioExecutor.execute {
            var failure: String? = null
            var verified: ByteArray? = null
            try {
                val tx = TechRaceProtocol.writeEeprom(0x01, settings)
                val ack = rawExchange(tx, token, attempts = 3)
                check(TechRaceProtocol.isValidAck(ack)) { "Sem confirmação da central" }
                val rx = rawExchange(TechRaceProtocol.READ_SETTINGS, token, attempts = 2)
                verified = TechRaceProtocol.extractResponse(rx, 1, 25)
                check(verified?.contentEquals(settings) == true) { "A releitura difere dos valores gravados" }
            } catch (e: Exception) { failure = e.message ?: "Falha de gravação" }
            val err = failure; val result = verified
            handler.post {
                if (!valid(token)) return@post
                busy = false
                if (err != null || result == null) {
                    toast("Falha ao gravar ajustes: ${err ?: "verificação inválida"}")
                } else {
                    rpmCalibration = rpmFactorToSave
                    preferences.edit().putInt("rpm_factor", rpmCalibration.toInt()).apply()
                    preferences.edit().remove("ecu_settings_draft").apply()
                    settingsSnapshot = ModuleSettings(result)
                    settingsTime = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                    moduleReport = settingsSnapshot!!.describe(rpmCalibration)
                    toast("Ajustes gravados e conferidos")
                }
                if (resume) resumePolling()
            }
        }
    }

    private fun showConfig() {
        val items = arrayOf(
            "Tipo de conexão: ${if (connectionType == "bluetooth") "Bluetooth" else "USB OTG"}",
            if (demoMode) "Desativar modo demonstração" else "Ativar modo demonstração",
            if (connected) "Reconectar" else "Conectar",
            if (polling) "Parar leitura contínua" else "Iniciar leitura contínua",
            "Ler uma vez", "Desconectar", "Sobre",
            "Consultar versão do firmware", "Ler configurações EEPROM",
            "Ver última configuração lida", "RAM adicional (experimental, valores brutos)",
            "Fator RPM e intervalo de leitura", "Exportar telemetria CSV", "Limpar histórico CSV"
        )
        AlertDialog.Builder(this).setTitle("Configurações").setItems(items) { _, index ->
            when (index) {
                0 -> chooseConnectionType()
                1 -> if (demoMode) setDemoMode(false) else AlertDialog.Builder(this)
                    .setTitle("Ativar demonstração?")
                    .setMessage("Os valores serão simulados. A conexão atual será encerrada.")
                    .setPositiveButton("Ativar") { _, _ -> setDemoMode(true) }
                    .setNegativeButton("Cancelar", null).show()
                2 -> connectSelected()
                3 -> {
                    if (demoMode || !connected) toast("Conecte o leitor em modo real")
                    else {
                        polling = !polling
                        dashboard.autoReading = polling
                        handler.removeCallbacks(pollTask)
                        if (polling && !busy) handler.post(pollTask)
                    }
                }
                4 -> if (demoMode || !connected) toast("Conecte o leitor em modo real") else pollOnce()
                5 -> if (!demoMode) disconnect()
                6 -> AlertDialog.Builder(this).setTitle("TechRace V5.0")
                    .setMessage("Monitoramento, ajustes e exportação de dados.")
                    .setPositiveButton("OK", null).show()
                7 -> queryModule(0)
                8 -> queryModule(1)
                9 -> showText("EEPROM — $settingsTime", settingsSnapshot?.describe(rpmCalibration)
                    ?: "Nenhuma configuração foi lida nesta conexão.")
                10 -> showRamQueryConfirmation()
                11 -> showLocalAdjustments()
                12 -> exportDocument("techrace-telemetria.csv", "text/csv", csvContent())
                13 -> { csvRows.clear(); toast("Histórico CSV limpo") }
            }
        }.show()
    }

    private fun showFuelingDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 12, 28, 12)
        }
        fun addField(label: String, initial: String): EditText {
            box.addView(TextView(this).apply { text = label; textSize = 14f })
            val input = EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                setSingleLine(true)
                setText(initial)
                hint = "0,0"
            }
            box.addView(input)
            return input
        }
        val ethanol = addField(
            "Etanol abastecido (litros)",
            preferences.getString("fuel_last_ethanol_l", "0") ?: "0"
        )
        val gasoline = addField(
            "Gasolina abastecida (litros)",
            preferences.getString("fuel_last_gasoline_l", "0") ?: "0"
        )
        val gasolineBlend = addField(
            "Etanol anidro na gasolina (%)",
            preferences.getString("fuel_gasoline_ethanol_percent", "32") ?: "32"
        )
        val manualFactor = CheckBox(this).apply {
            text = "Usar fator manual"
            isChecked = preferences.getBoolean("fuel_factor_manual", false)
        }
        val initialEthanol = preferences.getString("fuel_last_ethanol_l", "0")?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
        val initialGasoline = preferences.getString("fuel_last_gasoline_l", "0")?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
        val initialGasolineBlend = preferences.getString("fuel_gasoline_ethanol_percent", "32")?.replace(',', '.')?.toDoubleOrNull() ?: 32.0
        val suggestedFactor = FuelingEstimate.ethanolPercent(
            initialEthanol, initialGasoline, initialGasolineBlend
        )?.let { FuelingEstimate.suggestedFactor(it, lastData?.mixtureIndex) }
        val storedFactor = preferences.getString("fuel_factor_percent_per_idx", null)?.toDoubleOrNull()
        val factorInitial = String.format(
            java.util.Locale.US, "%.3f", suggestedFactor ?: storedFactor ?: 1.0
        )
        val factorInput = addField("Fator manual (pontos percentuais por idx)", factorInitial)
        factorInput.isEnabled = manualFactor.isChecked
        box.addView(manualFactor)
        box.addView(TextView(this).apply {
            text = "Estimativa = composição salva + (idx atual − idx do abastecimento) × fator."
            textSize = 12f
        })
        manualFactor.setOnCheckedChangeListener { _, checked -> factorInput.isEnabled = checked }

        val savedAt = preferences.getLong("fuel_saved_at", 0L)
        val lastEstimate = preferences.getString("fuel_last_estimate", null)
        val note = if (savedAt > 0L) {
            "Último abastecimento: estimativa ${lastEstimate ?: "--"}% de etanol. O painel usa essa composição como âncora. Se o fator manual estiver desligado, o fator é recalculado ao salvar com os litros informados e o idx atual."
        } else {
            "Informe os litros deste abastecimento. A composição estimada ancora o percentual; o idx acompanha as variações. O fator automático é estimativa e pode ser ajustado manualmente."
        }
        box.addView(TextView(this).apply {
            text = note
            textSize = 13f
            setPadding(0, 12, 0, 0)
        })
        val dialog = AlertDialog.Builder(this)
            .setTitle("Registrar abastecimento")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar abastecimento", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                fun value(edit: EditText): Double? =
                    edit.text.toString().trim().replace(',', '.').toDoubleOrNull()
                val ethanolLiters = value(ethanol)
                val gasolineLiters = value(gasoline)
                val gasolineEthanolPercent = value(gasolineBlend)
                if (ethanolLiters == null || gasolineLiters == null ||
                    gasolineEthanolPercent == null ||
                    ethanolLiters < 0.0 || gasolineLiters < 0.0 ||
                    gasolineEthanolPercent !in 0.0..100.0 ||
                    ethanolLiters + gasolineLiters <= 0.0) {
                    toast("Informe litros válidos e um total maior que zero")
                    return@setOnClickListener
                }
                val estimate = FuelingEstimate.ethanolPercent(
                    ethanolLiters, gasolineLiters, gasolineEthanolPercent
                ) ?: run {
                    toast("Não foi possível calcular a mistura")
                    return@setOnClickListener
                }
                val reference = lastData?.mixtureIndex ?: -1
                val useManualFactor = manualFactor.isChecked
                val factor = if (useManualFactor) value(factorInput)
                    else FuelingEstimate.suggestedFactor(estimate, reference)
                if (useManualFactor && (factor == null || factor !in 0.0..100.0)) {
                    toast("Fator manual inválido. Use um valor entre 0 e 100 pontos percentuais por idx.")
                    return@setOnClickListener
                }
                val editor = preferences.edit()
                    .putString("fuel_last_ethanol_l", ethanolLiters.toString())
                    .putString("fuel_last_gasoline_l", gasolineLiters.toString())
                    .putString("fuel_gasoline_ethanol_percent", gasolineEthanolPercent.toString())
                    .putString("fuel_last_estimate", String.format(java.util.Locale.getDefault(), "%.1f", estimate))
                    .putString("fuel_base_ethanol_percent", estimate.toString())
                    .putBoolean("fuel_factor_manual", useManualFactor)
                    .putLong("fuel_saved_at", System.currentTimeMillis())
                    .putInt("fuel_idx_baseline", reference)
                if (factor != null) editor.putString("fuel_factor_percent_per_idx", factor.toString())
                else editor.remove("fuel_factor_percent_per_idx")
                editor.apply()
                syncFuelingCalibration()
                val factorText = factor?.let {
                    " Fator: " + String.format(java.util.Locale.getDefault(), "%.3f", it) + " pp/idx."
                } ?: " O fator será calculado quando houver leitura do idx."
                toast(
                    "Abastecimento salvo: estimativa ${String.format(java.util.Locale.getDefault(), "%.1f", estimate)}% de etanol." +
                        factorText + " Variação reiniciada."
                )
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun updateFuelIndexReference(data: TechRaceLiveData) {
        if (preferences.getLong("fuel_saved_at", 0L) <= 0L) return
        var reference = preferences.getInt("fuel_idx_baseline", -1)
        if (reference < 0) {
            reference = data.mixtureIndex ?: return
            val editor = preferences.edit().putInt("fuel_idx_baseline", reference)
            if (!preferences.getBoolean("fuel_factor_manual", false)) {
                val knownPercent = preferences.getString("fuel_base_ethanol_percent", null)?.toDoubleOrNull()
                FuelingEstimate.suggestedFactor(knownPercent, reference)?.let {
                    editor.putString("fuel_factor_percent_per_idx", it.toString())
                }
            }
            editor.apply()
        }
        syncFuelingCalibration()
    }

    private fun syncFuelingCalibration() {
        dashboard.mixtureReferenceIndex =
            preferences.getInt("fuel_idx_baseline", -1).takeIf { it >= 0 }
        dashboard.mixtureReferenceEthanolPercent =
            preferences.getString("fuel_base_ethanol_percent", null)?.toDoubleOrNull()
        dashboard.mixtureFactorPercentPerIndex =
            preferences.getString("fuel_factor_percent_per_idx", null)?.toDoubleOrNull()
    }

    private fun setDemoMode(enabled: Boolean) {
        handler.removeCallbacks(demoTask)
        disconnect()
        demoMode = enabled
        dashboard.demoMode = false
        preferences.edit().putBoolean("demo_mode", enabled).apply()
        demoTick = 0
        if (enabled) queueDemoStart()
    }

    private fun queueDemoStart() {
        val token = generation
        // Place demo start AFTER pending IO/close: no in-flight USB operation in demo.
        ioExecutor.execute {
            handler.post {
                if (valid(token) && demoMode) {
                    handler.removeCallbacks(demoTask)
                    dashboard.demoMode = true
                    demoTask.run()
                }
            }
        }
    }

    private fun updateDemoData() {
        val t = demoTick++ * 0.25
        val cycle = t % 18
        val throttle = when {
            cycle < 3 -> 0.0
            cycle < 7 -> (cycle - 3) / 4
            cycle < 11 -> 1.0
            cycle < 15 -> (15 - cycle) / 4
            else -> 0.0
        }
        val wave = sin(t * 2 * PI / 3)
        val simulated = TechRaceLiveData(
                850 + throttle * 3550 + wave * 25,
                2.15 + throttle * 4.7 + wave * 0.12,
                30 + sin(t * 0.65) * 3,
                0, 80, 100,
                0.72 + throttle * 1.6 + wave * 0.04,
                (450 + sin(t * 2.8) * 340).toInt()
            )
        dashboard.updateDemoData(simulated, (35 + t * 0.9).toInt().coerceAtMost(88), 80)
        appendCsv(simulated, true)
    }

    private fun findAndConnect() {
        if (demoMode) { toast("Desative a demonstração para conectar"); return }
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) { toast("Nenhum USB-serial detectado"); return }
        if (drivers.size == 1) requestDevice(drivers.first().device)
        else AlertDialog.Builder(this).setTitle("Escolha o conversor USB")
            .setItems(drivers.map { "${it.device.deviceName} (VID ${it.device.vendorId})" }.toTypedArray()) { _, i ->
                requestDevice(drivers[i].device)
            }.show()
    }

    private fun chooseConnectionType() {
        val names = arrayOf("USB OTG", "Bluetooth (SPP)")
        AlertDialog.Builder(this).setTitle("Tipo de conexão")
            .setSingleChoiceItems(names, if (connectionType == "bluetooth") 1 else 0) { dialog, which ->
                preferences.edit().putString("connection_type", if (which == 1) "bluetooth" else "usb").apply()
                dashboard.connectionLabel = if (which == 1) "BLUETOOTH" else "USB OTG"
                dashboard.invalidate()
                dialog.dismiss()
                if (connected) disconnect()
                toast("Conexão: ${names[which]}")
                if (which == 1) showBluetoothDevices()
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun bluetoothAdapter(): BluetoothAdapter? =
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private fun showBluetoothDevices() {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missingPermissions = requiredPermissions.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            requestPermissions(missingPermissions.toTypedArray(), 410)
            return
        }
        val adapter = bluetoothAdapter()
        if (adapter == null || !adapter.isEnabled) {
            toast("Ative o Bluetooth do Android para procurar leitores próximos")
            return
        }
        try {
            bluetoothDevices.clear()
            bluetoothBondedAddresses.clear()
            adapter.bondedDevices.forEach { device ->
                bluetoothDevices[device.address] = device
                bluetoothBondedAddresses += device.address
            }
        }
        catch (_: SecurityException) { toast("Permissão Bluetooth necessária"); return }
        bluetoothDiscoveryFinished = false
        val initialItems = bluetoothListLabels()
        val listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, initialItems)
        bluetoothListAdapter = listAdapter
        val dialog = AlertDialog.Builder(this)
            .setTitle("Escolher leitor Bluetooth")
            .setMessage("Procurando dispositivos próximos… Toque em um leitor para conectar.")
            .setAdapter(listAdapter) { _, index ->
                val device = sortedBluetoothDevices().getOrNull(index) ?: return@setAdapter
                stopBluetoothDiscovery()
                connectBluetooth(device)
            }
            .setNegativeButton("Cancelar", null)
            .create()
        bluetoothListDialog = dialog
        dialog.setOnDismissListener {
            stopBluetoothDiscovery()
            bluetoothListDialog = null
            bluetoothListAdapter = null
        }
        dialog.show()
        renderBluetoothDeviceList()
        try {
            if (!adapter.startDiscovery()) {
                bluetoothDiscoveryFinished = true
                updateBluetoothSearchMessage()
                toast("Não foi possível iniciar a busca Bluetooth")
            }
        } catch (_: SecurityException) {
            bluetoothDiscoveryFinished = true
            updateBluetoothSearchMessage()
            toast("Permissão para procurar dispositivos Bluetooth necessária")
        }
    }

    private fun safeBluetoothName(device: BluetoothDevice): String =
        try { device.name ?: "Leitor" } catch (_: SecurityException) { "Leitor" }

    private fun sortedBluetoothDevices(): List<BluetoothDevice> = bluetoothDevices.values
        .sortedWith(compareBy({ safeBluetoothName(it).lowercase() }, { it.address }))

    private fun bluetoothListLabels(): List<String> = sortedBluetoothDevices()
        .map { device ->
            val state = if (device.address in bluetoothBondedAddresses) "pareado" else "disponível"
            "${safeBluetoothName(device)} • ${device.address} ($state)"
        }

    private fun renderBluetoothDeviceList() {
        bluetoothListAdapter?.let { list ->
            list.clear()
            list.addAll(bluetoothListLabels())
        }
        updateBluetoothSearchMessage()
    }

    private fun updateBluetoothSearchMessage() {
        val dialog = bluetoothListDialog ?: return
        if (!dialog.isShowing) return
        dialog.setMessage(when {
            !bluetoothDiscoveryFinished -> "Procurando dispositivos próximos… Toque em um leitor para conectar."
            bluetoothDevices.isEmpty() -> "Nenhum dispositivo encontrado. Verifique se o leitor está ligado e visível."
            else -> "Busca concluída. Selecione o leitor ao qual deseja conectar."
        })
    }

    @Suppress("MissingPermission")
    private fun stopBluetoothDiscovery() {
        try { bluetoothAdapter()?.takeIf { it.isDiscovering }?.cancelDiscovery() }
        catch (_: SecurityException) { }
    }

    @Suppress("MissingPermission")
    private fun connectBluetooth(device: BluetoothDevice) {
        if (demoMode || !foreground) return
        disconnect()
        val token = generation
        ioExecutor.execute {
            var socket: BluetoothSocket? = null
            try {
                socket = device.createRfcommSocketToServiceRecord(
                    UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
                )
                socket.connect()
                if (!valid(token)) { socket.close(); return@execute }
                transport = BluetoothSppTransport(socket)
                activeBluetoothAddress = device.address
                handler.post {
                    if (valid(token) && !demoMode) {
                        connected = true
                        dashboard.connected = true
                        polling = true
                        dashboard.autoReading = true
                        queryModule(0)
                    }
                }
            } catch (e: Exception) {
                try { socket?.close() } catch (_: Exception) {}
                handler.post {
                    if (valid(token)) {
                        lastError = "Erro Bluetooth: ${e.message}"
                        dashboard.markCommunicationFailure()
                        toast(lastError)
                    }
                }
            }
        }
    }

    private fun connectSelected() {
        if (connectionType == "bluetooth") showBluetoothDevices() else findAndConnect()
    }

    private fun requestDevice(device: UsbDevice) {
        if (demoMode || !foreground) return
        if (usbManager.hasPermission(device)) openDevice(device)
        else {
            pendingDeviceId = device.deviceId
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            usbManager.requestPermission(device, PendingIntent.getBroadcast(
                this, 0, Intent(actionUsbPermission).setPackage(packageName), flags
            ))
        }
    }

    private fun openDevice(device: UsbDevice) {
        if (demoMode || !foreground) return
        disconnect()
        val token = generation
        activeDeviceId = device.deviceId
        ioExecutor.execute {
            if (!valid(token)) return@execute
            try {
                val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
                    ?: error("Conversor não reconhecido")
                val connection = usbManager.openDevice(device) ?: error("USB indisponível")
                try {
                    val usbPort = driver.ports.first()
                    transport = UsbSerialTransport(usbPort)
                    usbPort.open(connection)
                    usbPort.setParameters(19200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                } catch (e: Exception) {
                    closePort()
                    connection.close()
                    throw e
                }
                if (!valid(token)) { closePort(); return@execute }
                handler.post {
                    if (valid(token) && !demoMode) {
                        connected = true
                        dashboard.connected = true
                        polling = true
                        dashboard.autoReading = true
                        queryModule(0) // Read firmware version before first live query.
                    }
                }
            } catch (e: Exception) {
                closePort()
                handler.post {
                    if (valid(token)) {
                        lastError = "Erro USB: ${e.message}"
                        dashboard.markCommunicationFailure()
                        toast(lastError)
                    }
                }
            }
        }
    }

    private fun pollOnce() {
        if (busy || pendingSerialAction || programmingMode != null || demoMode || !connected || !foreground) return
        handler.removeCallbacks(pollTask)
        busy = true
        val token = generation
        ioExecutor.execute {
            if (!valid(token)) return@execute
            val response = LiveResponseBuffer()
            var failure: String? = null
            try {
                val p = transport ?: error("Porta fechada")
                val buffer = ByteArray(128)
                // Drain stale bytes to an idle gap before the next request, bounded in time.
                val drainDeadline = SystemClock.elapsedRealtime() + DRAIN_TIMEOUT_MS
                while (p.read(buffer, DRAIN_IDLE_READ_MS) > 0) {
                    if (!valid(token)) throw CancellationException()
                    check(SystemClock.elapsedRealtime() < drainDeadline) { "USB sem intervalo ocioso" }
                }
                if (!valid(token)) throw CancellationException()
                p.write(TechRaceProtocol.READ_LIVE_10, 500)
                val deadline = SystemClock.elapsedRealtime() + LIVE_RESPONSE_TIMEOUT_MS
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (!valid(token)) throw CancellationException()
                    val n = p.read(buffer, SERIAL_READ_MS)
                    if (n > 0) {
                        response.append(buffer.copyOf(n))
                        if (response.snapshot().size >= 13) break
                    }
                }
                val rx = response.snapshot()
                failure = when {
                    rx.isEmpty() -> "Sem resposta (timeout)"
                    rx.size != 13 -> "Resposta com ${rx.size} bytes; esperado: 13"
                    response.payload() == null -> "CRC inválido"
                    else -> null
                }
            } catch (e: Exception) {
                failure = e.message ?: "Falha de leitura"
            }
            handler.post {
                if (valid(token) && !demoMode) {
                    busy = false
                    lastRx = response.snapshot()
                    val payload = response.payload()
                    if (failure == null && payload != null) {
                        liveFailureStreak = 0
                        val data = TechRaceDecoder.decode(payload, rpmCalibration)
                        lastData = data
                        dashboard.updateData(data)
                        updateFuelIndexReference(data)
                        appendCsv(data, false)
                        lastError = "Nenhum"
                        lastValid = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                            .format(java.util.Date())
                    } else {
                        lastError = failure ?: "Resposta inválida"
                        dashboard.markCommunicationFailure()
                        liveFailureStreak++
                        if (liveFailureStreak >= MAX_LIVE_FAILURES_BEFORE_PAUSE) {
                            polling = false
                            dashboard.autoReading = false
                            toast("Leitura pausada após $liveFailureStreak falhas: $lastError")
                        }
                    }
                    if (polling) handler.postDelayed(pollTask, pollingIntervalMs)
                }
            }
        }
    }

    private fun showDiagnostics() {
        val data = lastData
        val body = """
            Modo: ${if (demoMode) "DEMO" else "REAL"}
            Conexão: ${if (connectionType == "bluetooth") "Bluetooth SPP" else "USB OTG"}
            APK: ${BuildConfig.VERSION_NAME}
            Programação ativa: ${programmingMode?.label ?: "não"}
            Último estado RAM 0x63: ${programmingRuntimeFlags?.let { "0x%02X".format(it) } ?: "não consultado"}
            Firmware: $firmwareVersion
            Porta: ${if (connected) "ABERTA" else "FECHADA"}
            Serial: 19200 8N1
            Leitura contínua: $polling
            Última leitura válida: $lastValid
            Erros: ${dashboard.errorCount}
            Último erro: $lastError
            Intervalo interno (RAM 47): ${data?.raw4 ?: "--"}
            Byte bruto Y_PERCENT (RAM 49): ${data?.raw6 ?: "--"}
            Índice estimado MAP + sonda: ${data?.mixtureIndex?.let { "$it idx" } ?: "--"}
            ADC temperatura: ${data?.temperatureRaw ?: "--"}

            TX (${TechRaceProtocol.READ_LIVE_10.size} bytes):
            ${TechRaceProtocol.toHex(TechRaceProtocol.READ_LIVE_10)}

            RX (${lastRx.size} bytes):
            ${TechRaceProtocol.toHex(lastRx)}

            Resposta esperada: 2 bytes iniciais + 10 dados + CRC.
            Cabeçalho: endereço do módulo + função (Comunicação PC.xls).
            Endereço de resposta registrado, sem valor fixo presumido.
            CRC e tamanho válidos não substituem validação com o módulo.

            Consulta adicional ($snapshotTime):
            $moduleReport
        """.trimIndent()
        val view = TextView(this).apply {
            text = body
            setPadding(32, 20, 32, 20)
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(this).setTitle("Diagnóstico USB")
            .setView(ScrollView(this).apply { addView(view) })
            .setPositiveButton("Fechar", null)
            .setNeutralButton("Exportar TXT") { _, _ -> exportDocument("techrace-diagnostico.txt", "text/plain", body) }.show()
    }

    private fun showModuleMenu() {
        val items = arrayOf(
            "Ajustar ECU: partida a frio, temperaturas, aceleração, RPM e Lambda",
            "Registrar abastecimento"
        )
        AlertDialog.Builder(this).setTitle("Ajustes").setItems(items) { _, index ->
            when (index) {
                0 -> showAdjustments()
                1 -> showFuelingDialog()
            }
        }.show()
    }

    private fun showRamQueryConfirmation() {
        AlertDialog.Builder(this).setTitle("Mapa RAM a validar")
            .setMessage("Consulta 0x4D..0x56 segundo a planilha FLEX V10.0. A compatibilidade com firmware 2.0 não foi confirmada. Os dados serão mostrados como valores brutos.")
            .setPositiveButton("Consultar") { _, _ -> queryModule(2) }
            .setNegativeButton("Cancelar", null).show()
    }

    /** All additional requests use the selected serial transport on the single I/O executor. */
    private fun queryModule(kind: Int, showResult: Boolean = true) {
        if (demoMode || !connected || !foreground) { toast("Conecte o leitor em modo real"); return }
        if (busy || pendingSerialAction || programmingMode != null) { toast("Conclua a operação atual primeiro"); return }
        val tx = when (kind) {
            0 -> TechRaceProtocol.READ_VERSION
            1 -> TechRaceProtocol.READ_SETTINGS
            else -> TechRaceProtocol.READ_EXTENDED
        }
        val expected = when (kind) { 0 -> 2; 1 -> 25; else -> 10 }
        val command = tx[1].toInt() and 255
        val token = generation
        busy = true
        handler.removeCallbacks(pollTask)
        ioExecutor.execute {
            val received = LiveResponseBuffer()
            var queryFailure: String? = null
            var payload: ByteArray? = null
            try {
                val p = transport ?: error("Porta fechada")
                val buf = ByteArray(128)
                val drainDeadline = SystemClock.elapsedRealtime() + DRAIN_TIMEOUT_MS
                while (p.read(buf, DRAIN_IDLE_READ_MS) > 0) {
                    if (!valid(token)) throw CancellationException()
                    check(SystemClock.elapsedRealtime() < drainDeadline) { "USB sem intervalo ocioso" }
                }
                if (!valid(token)) throw CancellationException()
                p.write(tx, 500)
                val deadline = SystemClock.elapsedRealtime() + QUERY_RESPONSE_TIMEOUT_MS
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (!valid(token)) throw CancellationException()
                    val n = p.read(buf, SERIAL_READ_MS)
                    if (n > 0) {
                        received.append(buf.copyOf(n))
                        if (received.snapshot().size >= expected + 3) break
                    }
                }
                payload = TechRaceProtocol.extractResponse(received.snapshot(), command, expected)
                check(payload != null) { "Resposta inválida: tamanho, função ou CRC; esperado ${expected + 3} bytes" }
            } catch (e: Exception) { queryFailure = e.message ?: "Falha de comunicação" }
            val result = payload
            val failure = queryFailure
            handler.post {
                if (!valid(token) || demoMode) return@post
                busy = false
                snapshotTime = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                val wire = "TX: ${TechRaceProtocol.toHex(tx)}\nRX: ${TechRaceProtocol.toHex(received.snapshot())}"
                if (failure != null || result == null) {
                    moduleReport = "Falha na consulta: $failure\n$wire"
                    if (kind == 0) firmwareVersion = "Consulta falhou"
                    if (kind == 1) settingsSnapshot = null
                    dashboard.markCommunicationFailure()
                    toast("Consulta falhou; veja Diagnóstico.")
                    if (polling) handler.postDelayed(pollTask, pollingIntervalMs)
                } else {
                    when (kind) {
                        0 -> {
                            firmwareVersion = "${result[0].toInt() and 255}.${result[1].toInt() and 255}"
                            moduleReport = "Firmware informado: $firmwareVersion\n$wire"
                            toast("Firmware do módulo: $firmwareVersion")
                            // Load the EXE-equivalent option flags immediately after connecting.
                            queryModule(1, showResult = false)
                        }
                        1 -> {
                            settingsSnapshot = ModuleSettings(result)
                            settingsTime = snapshotTime
                            dashboard.updateProgrammingFlags(settingsSnapshot!!.mapFlag, settingsSnapshot!!.rpmFlag)
                            moduleReport = settingsSnapshot!!.describe(rpmCalibration) + "\n" + wire
                            if (showResult) showText("EEPROM — firmware $firmwareVersion", moduleReport)
                        }
                        else -> {
                            fun u(i: Int) = result[i].toInt() and 255
                            moduleReport = """
                                RAM adicional — valores brutos, mapa a validar
                                POT_ATUAL 0x4D: ${u(0)}
                                MAP_LENTA 0x4E: ${u(1)}
                                MAP_CARGA 0x4F: ${u(2)}
                                BICO_LENTA 0x50..51: ${u(3) + 256 * u(4)}
                                BICO_CARGA 0x52..53: ${u(5) + 256 * u(6)}
                                FATOR_MAP 0x54: ${u(7)}
                                LEITURAS_SONDA 0x55: ${u(8)}
                                FINAL_RAM 0x56: ${u(9)}
                                $wire
                            """.trimIndent()
                            showText("RAM adicional", moduleReport)
                        }
                    }
                    if (polling && !busy) handler.postDelayed(pollTask, pollingIntervalMs)
                }
            }
        }
    }

    private fun showText(title: String, body: String) {
        val view = TextView(this).apply { text = body; setPadding(32, 20, 32, 20); setTextIsSelectable(true) }
        AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(view) })
            .setPositiveButton("Fechar", null)
            .setNeutralButton("Exportar TXT") { _, _ -> exportDocument("techrace-consulta.txt", "text/plain", body) }.show()
    }

    private fun appendCsv(data: TechRaceLiveData, demo: Boolean) {
        if (csvRows.size >= 10000) csvRows.removeFirst()
        val temp = if (demo) dashboard.temperatureC else TechRaceDecoder.temperatureC(data.temperatureRaw)
        csvRows.addLast(listOf(System.currentTimeMillis(), if (demo) "DEMO" else "REAL",
            if (demo) "SIMULADO" else firmwareVersion.replace(';', '_'), rpmCalibration,
            data.rpm, data.injectionMs, data.correctionPercent, data.mapVoltage, data.lambdaMv,
            temp ?: "", data.raw6, data.raw4, data.temperatureRaw).joinToString(";"))
    }

    private fun csvContent() = "timestamp_unix_ms;modo;firmware;fator_rpm;rpm;injecao_ms;correcao_pct;map_v;sonda_mv;temperatura_c;y_percent;intervalo_raw;temperatura_adc\n" + csvRows.joinToString("\n")

    @Suppress("DEPRECATION")
    private fun exportDocument(name: String, mime: String, content: String) {
        if (pendingExport != null) { toast("Exportação já em andamento"); return }
        pendingExport = content
        try {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mime
                putExtra(Intent.EXTRA_TITLE, name)
            }, 230)
        } catch (e: Exception) { pendingExport = null; toast("Não foi possível abrir o seletor de arquivos") }
    }

    @Deprecated("Activity callback retained for minSdk 24")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 230) return
        val content = pendingExport
        pendingExport = null
        if (resultCode != RESULT_OK || content == null) return
        val uri = data?.data ?: return
        try {
            val stream = contentResolver.openOutputStream(uri, "wt") ?: error("Arquivo indisponível")
            stream.bufferedWriter(Charsets.UTF_8).use { it.write(content) }
            toast("Arquivo exportado")
        } catch (e: Exception) { toast("Falha ao exportar: ${e.message}") }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
