package com.fdp.bateria

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * Vigilante automático. Consume muy poco: no consulta nada en bucle, reacciona a eventos del
 * sistema (cambios de batería, pantalla, estado térmico) y hace una revisión ligera cada 5 min.
 *
 * Detecta:
 *  1. Calor: temperatura sobre el límite, estado térmico del sistema o predicción de margen térmico.
 *  2. Descarga rápida: % por hora por encima del límite.
 *  3. Drenaje en reposo: batería perdida con la pantalla apagada (wake locks / servicios ocultos).
 *  4. Calor mientras carga.
 * Y en cada caso identifica las apps culpables y ofrece (o aplica) la solución.
 */
class WatchdogService : Service() {

    companion object {
        @Volatile var running = false
        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, WatchdogService::class.java))
        }
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, WatchdogService::class.java))
        }
        private const val TICK_MS = 5 * 60_000L
        private const val MIN = 60_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val samples = ArrayDeque<Pair<Long, Int>>()   // (hora, nivel %) mientras descarga
    private val lastAlert = HashMap<String, Long>()
    private var level = -1
    private var tempC = 0f
    private var charging = false
    private var screenOffAt = 0L
    private var screenOffLevel = -1
    private var thermalStatus = 0
    private var thermalListener: Any? = null
    private var lastStatusText = ""
    private var lastHeatCheck = 0L

    private val tick = object : Runnable {
        override fun run() {
            evaluate()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_BATTERY_CHANGED -> onBattery(i)
                Intent.ACTION_SCREEN_OFF -> {
                    screenOffAt = System.currentTimeMillis()
                    screenOffLevel = level
                }
                Intent.ACTION_SCREEN_ON -> checkStandbyDrain()
                Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED -> {
                    samples.clear()
                    screenOffAt = 0L
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Alerts.channels(this)
        val n = Alerts.status(this, "Iniciando…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Alerts.ID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Alerts.ID_STATUS, n)
        }
        running = true

        val f = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(this, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = getSystemService(PowerManager::class.java)
            thermalStatus = pm.currentThermalStatus
            val l = PowerManager.OnThermalStatusChangedListener { st ->
                thermalStatus = st
                if (st >= PowerManager.THERMAL_STATUS_MODERATE) evaluate()
            }
            pm.addThermalStatusListener(ContextCompat.getMainExecutor(this), l)
            thermalListener = l
        }
        handler.postDelayed(tick, TICK_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(receiver) } catch (e: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val l = thermalListener as? PowerManager.OnThermalStatusChangedListener
            if (l != null) getSystemService(PowerManager::class.java).removeThermalStatusListener(l)
        }
        super.onDestroy()
    }

    /* ------------------------------------------------------------------ */

    private fun onBattery(i: Intent) {
        val lv = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val newLevel = if (lv >= 0 && sc > 0) lv * 100 / sc else -1
        tempC = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL

        val now = System.currentTimeMillis()
        if (newLevel != level) {
            level = newLevel
            if (!charging) {
                samples.addLast(now to level)
                while (samples.size > 2 && now - samples.first().first > 3 * 60 * MIN) samples.removeFirst()
            }
        }
        updateStatus()
        // El calor se revisa en cuanto cambia la temperatura (como mucho una vez por minuto).
        if (tempC >= Prefs.tempLimit(this) && now - lastHeatCheck > MIN) {
            lastHeatCheck = now
            evaluate()
        }
    }

    /** % por hora con la batería descargándose, o null si aún no hay datos suficientes. */
    private fun drainRate(): Float? {
        if (charging || samples.size < 2) return null
        val (t0, l0) = samples.first()
        val (t1, l1) = samples.last()
        val h = (t1 - t0) / 3_600_000f
        if (h < 0.33f) return null
        return (l0 - l1) / h
    }

    private fun updateStatus() {
        val rate = drainRate()
        val text = buildString {
            append("$level % · ${"%.1f".format(tempC)} °C")
            if (charging) append(" · cargando")
            else if (rate != null) append(" · gasta ${"%.0f".format(rate)} %/h")
        }
        if (text != lastStatusText) {
            lastStatusText = text
            getSystemService(NotificationManager::class.java)
                .notify(Alerts.ID_STATUS, Alerts.status(this, text))
        }
    }

    private fun cooldownOk(kind: String, minutes: Int): Boolean {
        val now = System.currentTimeMillis()
        val last = lastAlert[kind] ?: 0L
        if (now - last < minutes * MIN) return false
        lastAlert[kind] = now
        return true
    }

    private fun headroomForecast(): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val v = getSystemService(PowerManager::class.java).getThermalHeadroom(30)
            if (v.isNaN()) null else v
        } catch (e: Exception) { null }
    }

    private fun evaluate() {
        val now = System.currentTimeMillis()
        val limit = Prefs.tempLimit(this)
        val headroom = headroomForecast()

        // 1 + 4) Calor
        val hotByTemp = tempC >= limit
        val hotBySystem = thermalStatus >= 2 // THERMAL_STATUS_MODERATE
        val hotSoon = headroom != null && headroom >= 1.0f
        if ((hotByTemp || hotBySystem || hotSoon) && cooldownOk("heat", 20)) {
            val culprits = CulpritFinder.find(this, now - 30 * MIN)
            val why = when {
                hotByTemp -> "La batería está a ${"%.1f".format(tempC)} °C."
                hotBySystem -> "Android reporta que el teléfono se está calentando."
                else -> "Android predice que el teléfono se calentará en los próximos segundos."
            }
            val advice = if (charging) "Está cargando: quita la funda, no lo uses mientras carga o desconéctalo un rato."
            else "Baja el brillo y cierra lo que no uses."
            report("heat", "🌡️ Teléfono caliente", why, advice, culprits)
        }

        // 2) Descarga rápida
        val rate = drainRate()
        if (rate != null && rate >= Prefs.drainLimit(this) && cooldownOk("drain", 45)) {
            val from = samples.first().first
            val culprits = CulpritFinder.find(this, from)
            report(
                "drain", "🔋 Batería bajando rápido",
                "Está gastando ${"%.0f".format(rate)} % por hora (a este ritmo dura ${"%.1f".format(level / rate)} h).",
                "Revisa la pantalla (brillo, 120 Hz) y las apps señaladas.",
                culprits,
            )
        }
    }

    /** 3) Drenaje con la pantalla apagada: el síntoma típico de wake locks y servicios ocultos. */
    private fun checkStandbyDrain() {
        val now = System.currentTimeMillis()
        val offAt = screenOffAt
        val offLevel = screenOffLevel
        screenOffAt = 0L
        if (offAt == 0L || offLevel < 0 || charging || level < 0) return
        val hours = (now - offAt) / 3_600_000f
        val lost = offLevel - level
        if (hours < 0.5f || lost < 3) return
        val rate = lost / hours
        if (rate < 2f) return // normal: menos de ~2 %/h en reposo
        if (!cooldownOk("standby", 60)) return
        val culprits = CulpritFinder.find(this, offAt, now).filter { !it.inForeground || it.bgMs > 0 }
        report(
            "standby", "🌙 Gasto con la pantalla apagada",
            "Perdiste $lost % en ${"%.1f".format(hours)} h bloqueado (${"%.1f".format(rate)} %/h; lo normal es menos de 2 %/h).",
            "Algo mantuvo el teléfono despierto.",
            culprits,
        )
    }

    private fun report(kind: String, title: String, why: String, advice: String, culprits: List<Culprit>) {
        val candidates = culprits.filter { !Prefs.isProtected(this, it.pkg) }
        val closed = ArrayList<String>()
        if (Prefs.auto(this)) {
            candidates.filter { !it.inForeground }.forEach {
                Actions.killBackground(this, it.pkg)
                closed += it.label
            }
        }
        val body = buildString {
            append(why)
            if (candidates.isNotEmpty()) {
                append("\nCausa probable: ")
                append(candidates.joinToString("; ") { it.describe() })
                append('.')
            } else if (!UsageRepo.hasUsagePermission(this@WatchdogService)) {
                append("\nConcede \"acceso al uso\" para saber qué app es.")
            }
            if (closed.isNotEmpty()) append("\n✅ Cerré automáticamente: ${closed.joinToString(", ")}.")
            append("\n").append(advice)
        }
        val target = candidates.firstOrNull { !it.inForeground } ?: candidates.firstOrNull()
        Alerts.post(this, kind, title, body, target)
    }
}
