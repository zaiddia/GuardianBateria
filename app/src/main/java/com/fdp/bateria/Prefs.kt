package com.fdp.bateria

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("guardian", Context.MODE_PRIVATE)

    /** Apps que el modo automático nunca toca (mensajería, alarmas, sistema clave). */
    private val DEFAULT_PROTECTED = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.google.android.apps.messaging",
        "com.android.deskclock", "com.google.android.deskclock", "com.android.phone", "com.google.android.dialer",
        "com.android.systemui", "com.miui.home", "com.mi.android.globallauncher", "com.google.android.gms",
        "com.android.vending", "com.miui.securitycenter", "com.miui.powerkeeper", "com.android.settings",
        "com.google.android.inputmethod.latin", "com.android.incallui", "com.android.mms",
    )

    fun monitor(c: Context) = sp(c).getBoolean("monitor", false)
    fun setMonitor(c: Context, v: Boolean) = sp(c).edit().putBoolean("monitor", v).apply()

    fun auto(c: Context) = sp(c).getBoolean("auto", false)
    fun setAuto(c: Context, v: Boolean) = sp(c).edit().putBoolean("auto", v).apply()

    /** Temperatura de alerta en °C. */
    fun tempLimit(c: Context) = sp(c).getInt("temp", 41)
    fun setTempLimit(c: Context, v: Int) = sp(c).edit().putInt("temp", v).apply()

    /** Ritmo de descarga de alerta en % por hora. */
    fun drainLimit(c: Context) = sp(c).getInt("drain", 12)
    fun setDrainLimit(c: Context, v: Int) = sp(c).edit().putInt("drain", v).apply()

    fun userProtected(c: Context): Set<String> = sp(c).getStringSet("protected", emptySet()) ?: emptySet()
    fun isProtected(c: Context, pkg: String) = pkg in DEFAULT_PROTECTED || pkg in userProtected(c)
    fun toggleProtected(c: Context, pkg: String): Boolean {
        val s = userProtected(c).toMutableSet()
        val now = if (pkg in s) { s.remove(pkg); false } else { s.add(pkg); true }
        sp(c).edit().putStringSet("protected", s).apply()
        return now
    }

    fun log(c: Context, msg: String) {
        val old = sp(c).getString("log", "") ?: ""
        val entry = "${System.currentTimeMillis()}|${msg.replace("\n", " ")}"
        val lines = (listOf(entry) + old.split("\n").filter { it.isNotBlank() }).take(40)
        sp(c).edit().putString("log", lines.joinToString("\n")).apply()
    }

    fun logs(c: Context): List<Pair<Long, String>> =
        (sp(c).getString("log", "") ?: "").split("\n").mapNotNull {
            val i = it.indexOf('|')
            if (i <= 0) null else (it.substring(0, i).toLongOrNull() ?: 0L) to it.substring(i + 1)
        }

    fun clearLogs(c: Context) = sp(c).edit().remove("log").apply()
}
