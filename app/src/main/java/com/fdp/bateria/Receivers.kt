package com.fdp.bateria

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat

/** Botones de las alertas: cerrar la app culpable o dejar de vigilarla. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val pkg = i.getStringExtra("pkg") ?: return
        val label = i.getStringExtra("label") ?: pkg
        when (i.action) {
            Alerts.ACTION_KILL -> {
                Actions.killBackground(c, pkg)
                Prefs.log(c, "Cerraste $label en segundo plano desde una alerta")
                Actions.toast(c, "$label cerrada en segundo plano")
            }
            Alerts.ACTION_PROTECT -> {
                if (!Prefs.isProtected(c, pkg)) Prefs.toggleProtected(c, pkg)
                Prefs.log(c, "$label marcada como protegida: no volverá a señalarse")
                Actions.toast(c, "No volveré a señalar $label")
            }
        }
        NotificationManagerCompat.from(c).cancel(i.getIntExtra("nid", 0))
    }
}

/** Vuelve a encender el vigilante al reiniciar el teléfono o al actualizar la app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (Prefs.monitor(c)) {
            try {
                WatchdogService.start(c)
            } catch (e: Exception) {
                // HyperOS puede bloquearlo si falta "Inicio automático".
            }
        }
    }
}
