package com.fdp.bateria

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Shizuku da a la app los mismos poderes que un comando ADB, sin PC y sin root.
 * Con eso la app puede concederse sola "acceso al uso" y congelar/restringir apps de verdad.
 */
object ShizukuHelper {
    const val REQUEST_CODE = 4242
    const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

    enum class State { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, READY }

    fun installed(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(SHIZUKU_PKG, 0); true
    } catch (e: PackageManager.NameNotFoundException) { false }

    fun state(ctx: Context): State {
        val running = try { Shizuku.pingBinder() } catch (e: Throwable) { false }
        if (!running) return if (installed(ctx)) State.NOT_RUNNING else State.NOT_INSTALLED
        return try {
            if (!Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) State.READY
            else State.NO_PERMISSION
        } catch (e: Throwable) { State.NO_PERMISSION }
    }

    fun requestPermission() {
        try { Shizuku.requestPermission(REQUEST_CODE) } catch (e: Throwable) {}
    }

    /** Ejecuta un comando de shell con identidad ADB. Devuelve (código de salida, salida). */
    fun run(cmd: String): Pair<Int, String> = try {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        val out = p.inputStream.bufferedReader().readText() + p.errorStream.bufferedReader().readText()
        p.waitFor() to out.trim()
    } catch (e: Throwable) {
        -1 to (e.cause?.message ?: e.message ?: e.toString())
    }

    /** Se concede a sí misma todo lo que necesita. Devuelve un resumen legible. */
    fun grantEverything(ctx: Context): String {
        val pkg = ctx.packageName
        val steps = listOf(
            "Acceso al uso" to "appops set $pkg GET_USAGE_STATS allow",
            "Notificaciones" to "pm grant $pkg android.permission.POST_NOTIFICATIONS",
            "Sin restricción de batería" to "cmd deviceidle whitelist +$pkg",
            "Segundo plano permitido" to "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow",
        )
        return steps.joinToString("\n") { (name, cmd) ->
            val (code, _) = run(cmd)
            (if (code == 0) "✅ " else "⚠️ ") + name
        }
    }

    fun restrictBackground(pkg: String): Boolean {
        val a = run("cmd appops set $pkg RUN_ANY_IN_BACKGROUND ignore").first
        run("am set-standby-bucket $pkg restricted")
        run("am force-stop $pkg")
        return a == 0
    }

    fun freeze(pkg: String): Boolean {
        run("am force-stop $pkg")
        return run("pm disable-user --user 0 $pkg").first == 0
    }

    fun unfreeze(pkg: String): Boolean {
        val a = run("pm enable $pkg").first
        run("cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow")
        run("am set-standby-bucket $pkg active")
        return a == 0
    }
}
