package com.fdp.bateria

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Alerts {
    const val CH_STATUS = "estado"
    const val CH_ALERT = "alertas"
    const val ID_STATUS = 1

    const val ACTION_KILL = "com.fdp.bateria.KILL"
    const val ACTION_PROTECT = "com.fdp.bateria.PROTECT"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Vigilante activo", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Aviso fijo mientras el vigilante está encendido"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Alertas de batería y calor", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Avisos cuando algo calienta el teléfono o gasta la batería muy rápido"
            }
        )
    }

    private fun flags() = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    fun openApp(ctx: Context, extraKey: String? = null, pkg: String? = null, label: String? = null, rc: Int = 0): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (extraKey != null) i.putExtra(extraKey, pkg ?: "1").putExtra("label", label)
        return PendingIntent.getActivity(ctx, rc, i, flags())
    }

    fun status(ctx: Context, text: String): Notification =
        NotificationCompat.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Guardián vigilando")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openApp(ctx, rc = 1))
            .build()

    /**
     * Publica una alerta con botones de solución. [target] es la app que se puede cerrar/restringir.
     */
    fun post(ctx: Context, kind: String, title: String, body: String, target: Culprit?) {
        val id = 100 + (kind.hashCode() and 0xFFFF)
        val b = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, "tab", "3", rc = id))

        if (target != null) {
            if (!target.inForeground) {
                val kill = Intent(ctx, ActionReceiver::class.java).setAction(ACTION_KILL)
                    .putExtra("pkg", target.pkg).putExtra("label", target.label).putExtra("nid", id)
                b.addAction(0, "Cerrar ${target.label.take(14)}", PendingIntent.getBroadcast(ctx, id + 1, kill, flags()))
            }
            b.addAction(0, "Restringir", openApp(ctx, "restrict_pkg", target.pkg, target.label, rc = id + 2))
            val protect = Intent(ctx, ActionReceiver::class.java).setAction(ACTION_PROTECT)
                .putExtra("pkg", target.pkg).putExtra("label", target.label).putExtra("nid", id)
            b.addAction(0, "Ignorar app", PendingIntent.getBroadcast(ctx, id + 3, protect, flags()))
        } else {
            b.addAction(0, "Ahorro de batería", openApp(ctx, "open_saver", rc = id + 4))
        }

        try {
            NotificationManagerCompat.from(ctx).notify(id, b.build())
        } catch (e: SecurityException) {
            // Sin permiso de notificaciones: queda registrado en el historial de la app.
        }
        Prefs.log(ctx, "$title — $body")
    }
}
