package com.fdp.bateria

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast

object Actions {

    private fun start(ctx: Context, vararg intents: Intent): Boolean {
        for (i in intents) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
                return true
            } catch (e: ActivityNotFoundException) {
                // probamos la siguiente opción
            } catch (e: SecurityException) {
            }
        }
        toast(ctx, "Tu teléfono no tiene esta pantalla de ajustes")
        return false
    }

    fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

    fun openUsageAccess(ctx: Context) = start(
        ctx,
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${ctx.packageName}")),
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
    )

    /** Cierra los procesos en segundo plano de la app (no afecta la que está en pantalla). */
    fun killBackground(ctx: Context, pkg: String) {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.killBackgroundProcesses(pkg)
    }

    fun appDetails(ctx: Context, pkg: String) = start(
        ctx,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")),
    )

    /** Xiaomi / Redmi / POCO (MIUI y HyperOS). */
    val isXiaomi: Boolean = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
            Build.BRAND.lowercase() in setOf("xiaomi", "redmi", "poco")

    private fun component(pkg: String, cls: String) = Intent().setComponent(ComponentName(pkg, cls))

    /** Pantalla de batería de la app. En Xiaomi abre "Ahorro de batería" de esa app (Sin restricciones / Restringir). */
    fun appBattery(ctx: Context, pkg: String, label: String = pkg): Boolean {
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        val generic = Intent("android.settings.APP_BATTERY_SETTINGS", Uri.parse("package:$pkg"))
        val ok = if (isXiaomi) {
            start(
                ctx,
                component("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                    .putExtra("package_name", pkg)
                    .putExtra("package_label", label),
                generic, details,
            )
        } else start(ctx, generic, details)
        if (ok) {
            toast(
                ctx,
                if (isXiaomi) "Elige \"Restringir apps en segundo plano\". Si se abrió Info de la app, entra en \"Ahorro de batería\""
                else "Elige \"Restringida\" (o \"Optimizada\") en el uso de batería"
            )
        }
        return ok
    }

    /** Xiaomi: lista de apps con "Inicio automático" (arrancan solas al encender o en segundo plano). */
    fun xiaomiAutostart(ctx: Context) {
        val ok = start(
            ctx,
            component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
            Intent(Settings.ACTION_APPLICATION_SETTINGS),
        )
        if (ok) toast(ctx, "Desactiva el inicio automático de todo lo que no sea mensajería o alarmas")
    }

    /** Xiaomi: centro de batería de Seguridad (modos de ahorro, consumo, rendimiento). */
    fun xiaomiBatteryCenter(ctx: Context) = start(
        ctx,
        component("com.miui.securitycenter", "com.miui.powercenter.PowerMainActivity"),
        component("com.miui.securitycenter", "com.miui.powercenter.PowerSettings"),
        Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
    )

    fun forceStop(ctx: Context, pkg: String) {
        if (appDetails(ctx, pkg)) toast(ctx, "Pulsa \"Forzar detención\"")
    }

    fun notifications(ctx: Context, pkg: String) = start(
        ctx,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")),
    )

    fun uninstall(ctx: Context, pkg: String) = start(
        ctx, Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")),
    )

    fun batterySaver(ctx: Context) = start(
        ctx, Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS), Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
    )

    fun systemBatteryUsage(ctx: Context) = start(
        ctx, Intent(Intent.ACTION_POWER_USAGE_SUMMARY), Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
    )

    fun optimizationList(ctx: Context) = start(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    fun display(ctx: Context) = start(ctx, Intent(Settings.ACTION_DISPLAY_SETTINGS))
    fun location(ctx: Context) = start(ctx, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    fun sync(ctx: Context) = start(ctx, Intent(Settings.ACTION_SYNC_SETTINGS))
    fun wireless(ctx: Context) = start(ctx, Intent(Settings.ACTION_WIRELESS_SETTINGS))
    fun developer(ctx: Context) = start(
        ctx, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), Intent(Settings.ACTION_DEVICE_INFO_SETTINGS),
    )
    fun web(ctx: Context, url: String) = start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    /** Comandos ADB (desde una PC o con Shizuku/LADB) que sí congelan o bloquean de verdad sin root. */
    fun adbCommands(pkg: String) = (if (isXiaomi) """
# Xiaomi/HyperOS: en Opciones de desarrollador activa también
# "Depuración USB (ajustes de seguridad)" o los comandos fallarán.

""" else "") + """
# 1) Prohibir que corra en segundo plano
adb shell cmd appops set $pkg RUN_ANY_IN_BACKGROUND ignore

# 2) Ponerla en el "cubo" más restringido (Android 12+)
adb shell am set-standby-bucket $pkg restricted

# 3) Detenerla ahora
adb shell am force-stop $pkg

# 4) Congelarla por completo (no se abre ni notifica; reversible)
adb shell pm disable-user --user 0 $pkg

# --- Para revertir ---
adb shell pm enable $pkg
adb shell cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow
adb shell am set-standby-bucket $pkg active
""".trim()
}
