package com.fdp.bateria

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WatchdogScreen() {
    val ctx = LocalContext.current
    var monitor by remember { mutableStateOf(Prefs.monitor(ctx)) }
    var auto by remember { mutableStateOf(Prefs.auto(ctx)) }
    var temp by remember { mutableFloatStateOf(Prefs.tempLimit(ctx).toFloat()) }
    var drain by remember { mutableFloatStateOf(Prefs.drainLimit(ctx).toFloat()) }
    var logs by remember { mutableStateOf(Prefs.logs(ctx)) }
    var unrestricted by remember { mutableStateOf(false) }
    var usage by remember { mutableStateOf(UsageRepo.hasUsagePermission(ctx)) }

    fun refresh() {
        logs = Prefs.logs(ctx)
        usage = UsageRepo.hasUsagePermission(ctx)
        unrestricted = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
        // Si HyperOS lo cerró, lo volvemos a encender al abrir la app.
        if (monitor && !WatchdogService.running) WatchdogService.start(ctx)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun setMonitor(on: Boolean) {
        monitor = on
        Prefs.setMonitor(ctx, on)
        if (on) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            WatchdogService.start(ctx)
            Prefs.log(ctx, "Vigilante encendido")
        } else {
            WatchdogService.stop(ctx)
            Prefs.log(ctx, "Vigilante apagado")
        }
        logs = Prefs.logs(ctx)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Vigilante automático", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "Detecta calor, descarga rápida y gasto con la pantalla apagada, encuentra la app culpable y te avisa.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = monitor, onCheckedChange = { setMonitor(it) })
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Solución automática", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Cierra en segundo plano las apps culpables sin preguntar (nunca toca mensajería, alarmas ni apps protegidas).",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = auto, onCheckedChange = { auto = it; Prefs.setAuto(ctx, it) })
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Avisarme cuando…", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text("La temperatura supere ${temp.toInt()} °C")
                    Slider(
                        value = temp, onValueChange = { temp = it },
                        onValueChangeFinished = { Prefs.setTempLimit(ctx, temp.toInt()) },
                        valueRange = 37f..46f, steps = 8,
                    )
                    Text("La batería baje más de ${drain.toInt()} % por hora")
                    Slider(
                        value = drain, onValueChange = { drain = it },
                        onValueChangeFinished = { Prefs.setDrainLimit(ctx, drain.toInt()) },
                        valueRange = 6f..30f, steps = 23,
                    )
                    Text(
                        "También avisa siempre si pierdes más de 2 %/h con la pantalla apagada, o si Android predice sobrecalentamiento.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (unrestricted && usage) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.errorContainer
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (Actions.isXiaomi) "Para que HyperOS no lo cierre" else "Para que funcione en segundo plano",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        (if (usage) "✅" else "❌") + " Acceso al uso (para encontrar culpables)\n" +
                                (if (unrestricted) "✅" else "❌") + " Sin restricción de batería para el Guardián" +
                                if (Actions.isXiaomi) "\n• En Xiaomi activa también \"Inicio automático\" y, en Recientes, mantén pulsada la app y toca el candado 🔒." else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!usage) FilledTonalButton(onClick = { Actions.openUsageAccess(ctx) }) { Text("Acceso al uso") }
                        if (!unrestricted) FilledTonalButton(onClick = {
                            try {
                                ctx.startActivity(
                                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                                )
                            } catch (e: Exception) {
                                Actions.optimizationList(ctx)
                            }
                        }) { Text("Quitar restricción") }
                        if (Actions.isXiaomi) {
                            FilledTonalButton(onClick = { Actions.xiaomiAutostart(ctx) }) { Text("Inicio automático") }
                            OutlinedButton(onClick = { Actions.appBattery(ctx, ctx.packageName, "Guardián de Batería") }) {
                                Text("Batería del Guardián")
                            }
                        }
                    }
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Historial", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    Alerts.channels(ctx)
                    val c = CulpritFinder.find(ctx, System.currentTimeMillis() - 30 * 60_000L)
                    Alerts.post(
                        ctx, "test", "🔔 Prueba del vigilante",
                        "Así se verán las alertas." +
                                (if (c.isNotEmpty()) "\nÚltimos 30 min: " + c.joinToString("; ") { it.describe() } else ""),
                        c.firstOrNull { !it.inForeground },
                    )
                    logs = Prefs.logs(ctx)
                }) { Text("Probar alerta") }
                TextButton(onClick = { Prefs.clearLogs(ctx); logs = emptyList() }) { Text("Borrar") }
            }
        }
        if (logs.isEmpty()) {
            item { Text("Sin eventos todavía.", style = MaterialTheme.typography.bodySmall) }
        }
        items(logs) { (time, msg) ->
            val f = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(f.format(Date(time)), style = MaterialTheme.typography.labelSmall)
                    Text(msg, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
