package com.fdp.bateria

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

data class AppUsage(
    val pkg: String,
    val label: String,
    val icon: ImageBitmap?,
    val foregroundMs: Long,      // tiempo en pantalla
    val fgServiceMs: Long,       // tiempo con servicio en primer plano (música, GPS, sincronización...)
    val opens: Int,              // veces que se abrió
    val bgStarts: Int,           // veces que arrancó un servicio en segundo plano
    val lastUsed: Long,
    val isSystem: Boolean,
    val unrestricted: Boolean,   // ignora la optimización de batería
    val weight: Double,
    var percent: Float = 0f,     // % estimado del consumo atribuible a apps
)

object UsageRepo {

    fun hasUsagePermission(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Android no deja a apps normales leer el consumo real por app (permiso BATTERY_STATS
     * reservado al sistema). Estimamos el peso de cada app con datos reales de uso:
     * tiempo en pantalla, tiempo con servicios activos, aperturas y arranques en segundo plano.
     */
    fun load(ctx: Context, hours: Int, includeSystem: Boolean): List<AppUsage> {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val pm = ctx.packageManager
        val power = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val end = System.currentTimeMillis()
        val start = end - hours * 3_600_000L

        val stats = usm.queryAndAggregateUsageStats(start, end)

        val opens = HashMap<String, Int>()
        val bgStarts = HashMap<String, Int>()
        val events = usm.queryEvents(start, end)
        val ev = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            val p = ev.packageName ?: continue
            when (ev.eventType) {
                1 -> opens[p] = (opens[p] ?: 0) + 1          // ACTIVITY_RESUMED / MOVE_TO_FOREGROUND
                19 -> bgStarts[p] = (bgStarts[p] ?: 0) + 1   // FOREGROUND_SERVICE_START
            }
        }

        val packages = HashSet<String>().apply {
            addAll(stats.keys); addAll(opens.keys); addAll(bgStarts.keys)
        }

        val result = ArrayList<AppUsage>()
        for (pkg in packages) {
            if (pkg == ctx.packageName) continue
            val ai: ApplicationInfo = try {
                pm.getApplicationInfo(pkg, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                continue
            }
            val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            // Componentes del sistema sin icono en el menú (teclado, launcher, servicios) se ocultan salvo que se pidan
            val hasLauncher = pm.getLaunchIntentForPackage(pkg) != null
            if (!includeSystem && system && !hasLauncher) continue

            val s = stats[pkg]
            val fg = s?.totalTimeInForeground ?: 0L
            val fgs = if (s != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                s.totalTimeForegroundServiceUsed
            } else 0L
            val o = opens[pkg] ?: 0
            val b = bgStarts[pkg] ?: 0

            val weight = fg / 60_000.0 * 1.0 +
                    fgs / 60_000.0 * 0.6 +
                    o * 0.3 +
                    b * 0.5
            if (weight < 0.5) continue

            val icon = try {
                pm.getApplicationIcon(ai).toBitmap(96, 96).asImageBitmap()
            } catch (e: Exception) {
                null
            }

            result += AppUsage(
                pkg = pkg,
                label = pm.getApplicationLabel(ai).toString(),
                icon = icon,
                foregroundMs = fg,
                fgServiceMs = fgs,
                opens = o,
                bgStarts = b,
                lastUsed = s?.lastTimeUsed ?: 0L,
                isSystem = system,
                unrestricted = power.isIgnoringBatteryOptimizations(pkg),
                weight = weight,
            )
        }

        val total = result.sumOf { it.weight }.takeIf { it > 0 } ?: 1.0
        result.forEach { it.percent = (it.weight / total * 100).toFloat() }
        return result.sortedByDescending { it.weight }
    }
}

/** App sospechosa de causar calor o drenaje en una ventana de tiempo concreta. */
data class Culprit(
    val pkg: String,
    val label: String,
    val fgMs: Long,          // tiempo en pantalla dentro de la ventana
    val bgMs: Long,          // tiempo con servicio activo en segundo plano dentro de la ventana
    val starts: Int,         // arranques de servicios ocultos
    val inForeground: Boolean,
) {
    val score: Double get() = fgMs * 1.0 + bgMs * 0.8 + starts * 60_000.0
    fun describe(): String = buildString {
        append(label)
        val parts = ArrayList<String>()
        if (fgMs >= 60_000) parts += "${fmt(fgMs)} en pantalla"
        if (bgMs >= 60_000) parts += "${fmt(bgMs)} oculta"
        if (starts >= 3) parts += "$starts arranques"
        if (inForeground) parts += "abierta ahora"
        if (parts.isNotEmpty()) append(" (").append(parts.joinToString(", ")).append(")")
    }
}

object CulpritFinder {

    private fun excluded(ctx: Context): Set<String> {
        val pm = ctx.packageManager
        val home = try {
            pm.resolveActivity(
                android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_HOME), 0
            )?.activityInfo?.packageName
        } catch (e: Exception) { null }
        val ime = android.provider.Settings.Secure.getString(
            ctx.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD
        )?.substringBefore('/')
        return setOfNotNull(ctx.packageName, home, ime, "android", "com.android.systemui")
    }

    /**
     * Reconstruye, a partir de los eventos del sistema, qué apps estuvieron en pantalla y cuáles
     * mantuvieron servicios activos entre [start] y [end]. Devuelve las más pesadas primero.
     */
    fun find(ctx: Context, start: Long, end: Long = System.currentTimeMillis(), max: Int = 3): List<Culprit> {
        if (!UsageRepo.hasUsagePermission(ctx)) return emptyList()
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val skip = excluded(ctx)
        // Leemos desde antes para conocer el estado (qué estaba abierto) al inicio de la ventana.
        val scanFrom = minOf(start, end - 3 * 3_600_000L)

        val resumedAt = HashMap<String, Long>()
        val fgsOpenAt = HashMap<String, Long>()
        val fg = HashMap<String, Long>()
        val bg = HashMap<String, Long>()
        val starts = HashMap<String, Int>()
        var lastResumed: String? = null

        fun addClamped(map: HashMap<String, Long>, p: String, from: Long, to: Long) {
            val a = maxOf(from, start)
            if (to > a) map[p] = (map[p] ?: 0L) + (to - a)
        }

        val events = usm.queryEvents(scanFrom, end)
        val ev = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            val p = ev.packageName ?: continue
            val t = ev.timeStamp
            when (ev.eventType) {
                1 -> { resumedAt[p] = t; lastResumed = p }                 // ACTIVITY_RESUMED
                2, 23 -> resumedAt.remove(p)?.let { addClamped(fg, p, it, t) } // PAUSED / STOPPED
                19 -> {                                                     // FOREGROUND_SERVICE_START
                    if (p !in fgsOpenAt) fgsOpenAt[p] = t
                    if (t >= start) starts[p] = (starts[p] ?: 0) + 1
                }
                20 -> fgsOpenAt.remove(p)?.let { addClamped(bg, p, it, t) } // FOREGROUND_SERVICE_STOP
            }
        }
        resumedAt.forEach { (p, t) -> addClamped(fg, p, t, end) }
        fgsOpenAt.forEach { (p, t) -> addClamped(bg, p, t, end) }

        val pm = ctx.packageManager
        val all = HashSet<String>().apply { addAll(fg.keys); addAll(bg.keys); addAll(starts.keys) }
        return all.asSequence()
            .filter { it !in skip }
            .mapNotNull { p ->
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString()
                } catch (e: Exception) { return@mapNotNull null }
                Culprit(
                    pkg = p, label = label,
                    fgMs = fg[p] ?: 0L, bgMs = bg[p] ?: 0L, starts = starts[p] ?: 0,
                    inForeground = p == lastResumed && p in resumedAt,
                )
            }
            .filter { it.fgMs >= 2 * 60_000 || it.bgMs >= 2 * 60_000 || it.starts >= 3 || it.inForeground }
            .sortedByDescending { it.score }
            .take(max)
            .toList()
    }
}
