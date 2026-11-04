package com.fdp.bateria

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import rikka.shizuku.Shizuku

/**
 * Asistente para conseguir "Acceso al uso". Android 13+ (y HyperOS) bloquea ese permiso para apps
 * instaladas desde un APK ("Ajuste restringido"); aquí están las tres formas de desbloquearlo.
 */
@Composable
fun PermissionHelp(onGranted: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var shizuku by remember { mutableStateOf(ShizukuHelper.state(ctx)) }
    var result by remember { mutableStateOf<String?>(null) }
    var step by remember { mutableIntStateOf(0) }

    fun check() {
        shizuku = ShizukuHelper.state(ctx)
        if (UsageRepo.hasUsagePermission(ctx)) onGranted()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { check() }

    fun grantWithShizuku() {
        result = ShizukuHelper.grantEverything(ctx)
        Prefs.log(ctx, "Permisos concedidos con Shizuku")
        check()
    }

    DisposableEffect(Unit) {
        val l = Shizuku.OnRequestPermissionResultListener { code, res ->
            if (code == ShizukuHelper.REQUEST_CODE && res == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                grantWithShizuku()
            } else check()
        }
        val alive = Shizuku.OnBinderReceivedListener { check() }
        try {
            Shizuku.addRequestPermissionResultListener(l)
            Shizuku.addBinderReceivedListenerSticky(alive)
        } catch (e: Throwable) {}
        onDispose {
            try {
                Shizuku.removeRequestPermissionResultListener(l)
                Shizuku.removeBinderReceivedListener(alive)
            } catch (e: Throwable) {}
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Falta el permiso \"Acceso al uso\"", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Lo necesito para saber qué apps gastan batería. Como la app se instaló desde un APK, " +
                    "Android/HyperOS lo bloquea como \"ajuste restringido\" hasta que lo desbloquees. Nada sale de tu teléfono.",
            style = MaterialTheme.typography.bodyMedium,
        )

        // ---------- Opción 1: automática con Shizuku ----------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Opción 1 · Automática (Shizuku)", fontWeight = FontWeight.Bold)
                Text(
                    "Con un toque la app se da sola todos los permisos y además podrá congelar apps de verdad.",
                    style = MaterialTheme.typography.bodySmall,
                )
                when (shizuku) {
                    ShizukuHelper.State.READY -> Button(onClick = { grantWithShizuku() }, Modifier.fillMaxWidth()) {
                        Text("Conceder todo automáticamente")
                    }
                    ShizukuHelper.State.NO_PERMISSION -> Button(onClick = { ShizukuHelper.requestPermission() }, Modifier.fillMaxWidth()) {
                        Text("Autorizar Guardián en Shizuku")
                    }
                    ShizukuHelper.State.NOT_RUNNING -> {
                        Text(
                            "Shizuku está instalado pero apagado. Ábrelo y pulsa \"Iniciar\" (vía Depuración inalámbrica), luego vuelve.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        FilledTonalButton(onClick = {
                            ctx.packageManager.getLaunchIntentForPackage(ShizukuHelper.SHIZUKU_PKG)?.let { ctx.startActivity(it) }
                        }) { Text("Abrir Shizuku") }
                    }
                    ShizukuHelper.State.NOT_INSTALLED -> {
                        Text(
                            "1) Instala Shizuku (gratis, código abierto).\n" +
                                    "2) Ajustes > Sobre el teléfono > toca 7 veces \"Versión de HyperOS\" para activar Opciones de desarrollador.\n" +
                                    "3) En Opciones de desarrollador activa \"Depuración inalámbrica\" (con Wi-Fi conectado).\n" +
                                    "4) En Shizuku: \"Iniciar vía depuración inalámbrica\" > Emparejar, y sigue la notificación.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        FilledTonalButton(onClick = { Actions.web(ctx, "https://shizuku.rikka.app/download/") }) {
                            Text("Descargar Shizuku")
                        }
                    }
                }
                result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }

        // ---------- Opción 2: manual, desbloqueando el ajuste restringido ----------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Opción 2 · Manual (2 minutos)", fontWeight = FontWeight.Bold)
                Text(
                    "Paso 1: abre la lista, entra en \"Guardián de Batería\" e intenta activarlo. " +
                            "Si sale \"Ajuste restringido\", pulsa Aceptar y continúa al paso 2.",
                    style = MaterialTheme.typography.bodySmall,
                )
                FilledTonalButton(onClick = { step = maxOf(step, 1); Actions.openUsageAccess(ctx) }) {
                    Text("1 · Abrir Acceso al uso")
                }
                Text(
                    "Paso 2: en Info de la app toca ⋮ (arriba a la derecha) > \"Permitir ajustes restringidos\" y confirma con tu huella o PIN.",
                    style = MaterialTheme.typography.bodySmall,
                )
                FilledTonalButton(onClick = {
                    step = maxOf(step, 2)
                    Actions.appDetails(ctx, ctx.packageName)
                }) { Text("2 · Desbloquear ajustes restringidos") }
                Text("Paso 3: vuelve a la lista y ahora sí actívalo.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { Actions.openUsageAccess(ctx) }, enabled = step >= 1) {
                    Text("3 · Activar Acceso al uso")
                }
                if (Actions.isXiaomi) {
                    Text(
                        "En HyperOS la lista también está en: Ajustes > Contraseñas y seguridad > Privacidad > Permisos especiales > Acceso a datos de uso.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // ---------- Opción 3: desde una PC ----------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val cmd = "adb shell appops set ${ctx.packageName} GET_USAGE_STATS allow"
                Text("Opción 3 · Desde una PC con ADB", fontWeight = FontWeight.Bold)
                Text(cmd, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(cmd))
                    Actions.toast(ctx, "Comando copiado")
                }) { Text("Copiar comando") }
            }
        }
    }
}
