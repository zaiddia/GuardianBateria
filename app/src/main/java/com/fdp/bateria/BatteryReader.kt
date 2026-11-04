package com.fdp.bateria

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlin.math.abs

data class BatterySnapshot(
    val level: Int,
    val tempC: Float,
    val voltageMv: Int,
    val status: String,
    val charging: Boolean,
    val health: String,
    val plug: String,
    val currentMa: Int?,     // positivo = entra, negativo = sale (normalizado)
    val remainingMah: Int?,
    val hoursLeft: Float?,
)

object BatteryReader {

    fun read(ctx: Context): BatterySnapshot {
        val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val volt = i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING ||
                st == BatteryManager.BATTERY_STATUS_FULL
        val status = when (st) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Cargando"
            BatteryManager.BATTERY_STATUS_FULL -> "Completa"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Descargando"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Sin cargar"
            else -> "Desconocido"
        }
        val health = when (i?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Buena"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Sobrecalentada"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Agotada (reemplazar)"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Sobrevoltaje"
            BatteryManager.BATTERY_HEALTH_COLD -> "Fría"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Falla"
            else -> "Desconocida"
        }
        val plug = when (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "Cargador de pared"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Inalámbrico"
            else -> "Desconectado"
        }

        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

        // Algunos fabricantes reportan µA y otros mA; normalizamos a mA.
        val rawCur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        var currentMa: Int? = when {
            rawCur == Int.MIN_VALUE || rawCur == 0 -> null
            abs(rawCur) > 20_000 -> rawCur / 1000
            else -> rawCur
        }
        // Signo coherente: negativo cuando se descarga.
        if (currentMa != null) {
            currentMa = if (charging) abs(currentMa) else -abs(currentMa)
        }

        val rawCounter = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val remaining: Int? = when {
            rawCounter == Int.MIN_VALUE || rawCounter <= 0 -> null
            rawCounter > 20_000 -> rawCounter / 1000
            else -> rawCounter
        }

        val hoursLeft = if (!charging && currentMa != null && remaining != null && currentMa != 0) {
            remaining / abs(currentMa).toFloat()
        } else null

        return BatterySnapshot(pct, temp, volt, status, charging, health, plug, currentMa, remaining, hoursLeft)
    }
}
