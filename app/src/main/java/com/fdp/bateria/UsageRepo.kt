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
