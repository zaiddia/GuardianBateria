package com.fdp.bateria

import android.os.Build
import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private val tab = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent { AppTheme { Root(tab) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Acciones que llegan desde los botones de las alertas. */
    private fun handle(i: Intent?) {
        if (i == null) return
        i.getStringExtra("restrict_pkg")?.let {
            Actions.appBattery(this, it, i.getStringExtra("label") ?: it)
            i.removeExtra("restrict_pkg")
        }
        if (i.hasExtra("open_saver")) {
            Actions.batterySaver(this)
            i.removeExtra("open_saver")
        }
        i.getStringExtra("tab")?.toIntOrNull()?.let {
            tab.intValue = it
            i.removeExtra("tab")
        }
    }
}

private val Green = Color(0xFF2E7D32)
private val Danger = Color(0xFFC62828)
private val Amber = Color(0xFFEF6C00)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        if (dark) darkColorScheme(primary = Color(0xFF81C784)) else lightColorScheme(primary = Green)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Root(tabState: MutableIntState) {
    var tab by tabState
    val tabs = listOf(
        "Apps" to Icons.AutoMirrored.Filled.List,
        "Batería" to Icons.Filled.Info,
        "Consejos" to Icons.Filled.Settings,
        "Vigilante" to Icons.Filled.Notifications,
    )
    Scaffold(
        topBar = { TopAppBar(title = { Text("Guardián de Batería", fontWeight = FontWeight.Bold) }) },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, (title, icon) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(icon, contentDescription = title) },
                        label = { Text(title) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> AppsScreen()
                1 -> BatteryScreen()
                2 -> TipsScreen()
                else -> WatchdogScreen()
            }
        }
    }
}

/* ----------------------------- APPS ----------------------------- */

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppsScreen() {
    val ctx = LocalContext.current
    var hasPerm by remember { mutableStateOf(UsageRepo.hasUsagePermission(ctx)) }
    var hours by rememberSaveable { mutableIntStateOf(24) }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var apps by remember { mutableStateOf<List<AppUsage>?>(null) }
    var confirmKillAll by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasPerm = UsageRepo.hasUsagePermission(ctx)
        reload++
    }
    LaunchedEffect(hasPerm, hours, showSystem, reload) {
        if (hasPerm) {
            apps = withContext(Dispatchers.IO) { UsageRepo.load(ctx, hours, showSystem) }
        }
    }

    if (!hasPerm) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("Falta un permiso", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "Para saber qué apps gastan más batería necesito ver su tiempo de uso. " +
                        "En la pantalla que se abrirá, busca \"Guardián de Batería\" y activa " +
                        "\"Permitir acceso al uso\". Nada sale de tu teléfono."
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = { Actions.openUsageAccess(ctx) }) { Text("Conceder acceso al uso") }
        }
        return
    }

    val list = apps
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("Apps que más consumen", style = MaterialTheme.typography.titleLarge)
            Text(
                "Estimado a partir del tiempo en pantalla, servicios activos y arranques en segundo plano.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(24 to "24 h", 72 to "3 días", 168 to "7 días").forEach { (h, t) ->
                    FilterChip(selected = hours == h, onClick = { hours = h }, label = { Text(t) })
                }
                FilterChip(
                    selected = showSystem,
                    onClick = { showSystem = !showSystem },
                    label = { Text("Incluir sistema") },
                )
                IconButton(onClick = { reload++ }) { Icon(Icons.Filled.Refresh, "Actualizar") }
            }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { confirmKillAll = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = !list.isNullOrEmpty(),
            ) { Text("Cerrar todas en segundo plano") }
        }

        when {
            list == null -> item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            list.isEmpty() -> item { Text("Aún no hay datos de uso en este periodo.") }
            else -> items(list, key = { it.pkg }) { AppCard(it) }
        }
    }

    if (confirmKillAll && list != null) {
        AlertDialog(
            onDismissRequest = { confirmKillAll = false },
            title = { Text("Cerrar en segundo plano") },
            text = {
                Text(
                    "Se cerrarán los procesos en segundo plano de ${list.size} apps. " +
                            "Es seguro: no borra datos. Algunas apps (mensajería, música) pueden " +
                            "volver a abrirse solas; para esas usa \"Restringir batería\"."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    list.forEach { Actions.killBackground(ctx, it.pkg) }
                    Actions.toast(ctx, "Listo: ${list.size} apps cerradas en segundo plano")
                    confirmKillAll = false
                }) { Text("Cerrar") }
            },
            dismissButton = { TextButton(onClick = { confirmKillAll = false }) { Text("Cancelar") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppCard(app: AppUsage) {
    val ctx = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var showAdb by remember { mutableStateOf(false) }
    var protected by remember(app.pkg) { mutableStateOf(Prefs.isProtected(ctx, app.pkg)) }
    val barColor = when {
        app.percent >= 15f -> Danger
        app.percent >= 6f -> Amber
        else -> Green
    }

    Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (app.icon != null) {
                    Image(app.icon, contentDescription = null, modifier = Modifier.size(40.dp))
                } else {
                    Box(Modifier.size(40.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(app.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "Pantalla ${fmt(app.foregroundMs)} · 2º plano ${fmt(app.fgServiceMs)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "${app.opens} aperturas · ${app.bgStarts} arranques ocultos",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "%.0f%%".format(app.percent),
                    fontWeight = FontWeight.Bold, fontSize = 18.sp, color = barColor,
                )
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { (app.percent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = barColor,
            )
            if (app.unrestricted) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, null, tint = Amber, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Sin optimización de batería: puede correr libre en segundo plano",
                        style = MaterialTheme.typography.bodySmall, color = Amber,
                    )
                }
            }
            if (app.fgServiceMs > app.foregroundMs * 2 && app.fgServiceMs > 30 * 60_000L) {
                Text(
                    "Trabaja mucho más tiempo oculta que en pantalla: buena candidata a restringir.",
                    style = MaterialTheme.typography.bodySmall, color = Danger,
                )
            }

            if (expanded) {
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilledTonalButton(onClick = {
                        Actions.killBackground(ctx, app.pkg)
                        Actions.toast(ctx, "${app.label}: procesos en segundo plano cerrados")
                    }) { Text("Cerrar en 2º plano") }
                    FilledTonalButton(onClick = { Actions.appBattery(ctx, app.pkg, app.label) }) { Text("Restringir batería") }
                    if (Actions.isXiaomi) {
                        OutlinedButton(onClick = { Actions.xiaomiAutostart(ctx) }) { Text("Inicio automático") }
                    }
                    FilledTonalButton(onClick = { Actions.forceStop(ctx, app.pkg) }) { Text("Forzar detención") }
                    OutlinedButton(onClick = { Actions.notifications(ctx, app.pkg) }) { Text("Notificaciones") }
                    OutlinedButton(onClick = { showAdb = true }) { Text("Congelar (avanzado)") }
                    OutlinedButton(onClick = { protected = Prefs.toggleProtected(ctx, app.pkg) || Prefs.isProtected(ctx, app.pkg) }) {
                        Text(if (protected) "🛡 Protegida" else "Proteger del vigilante")
                    }
                    if (!app.isSystem) {
                        OutlinedButton(onClick = { Actions.uninstall(ctx, app.pkg) }) {
                            Text("Desinstalar", color = Danger)
                        }
                    }
                }
            } else {
                Text(
                    "Toca para ver opciones",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    if (showAdb) {
        val clipboard = LocalClipboardManager.current
        val cmds = Actions.adbCommands(app.pkg)
        AlertDialog(
            onDismissRequest = { showAdb = false },
            title = { Text("Congelar ${app.label}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Android no permite que una app normal congele a otra. Estos comandos sí lo " +
                                "hacen sin root: ejecútalos desde una PC con ADB, o en el teléfono con " +
                                "Shizuku + una terminal (o usa la app Hail). Son reversibles.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(cmds, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(cmds))
                    Actions.toast(ctx, "Comandos copiados")
                    showAdb = false
                }) { Text("Copiar") }
            },
            dismissButton = { TextButton(onClick = { showAdb = false }) { Text("Cerrar") } },
        )
    }
}

fun fmt(ms: Long): String {
    val min = ms / 60_000
    return when {
        min < 1 -> "<1 min"
        min < 60 -> "$min min"
        else -> "${min / 60} h ${min % 60} min"
    }
}

/* ---------------------------- BATERÍA ---------------------------- */

@Composable
fun BatteryScreen() {
    val ctx = LocalContext.current
    var snap by remember { mutableStateOf(BatteryReader.read(ctx)) }
    LaunchedEffect(Unit) {
        while (true) {
            snap = BatteryReader.read(ctx)
            delay(2000)
        }
    }
    val s = snap

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${s.level}%", fontSize = 56.sp, fontWeight = FontWeight.Bold)
                Text("${s.status} · ${s.plug}")
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (s.level / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(10.dp),
                    color = if (s.level <= 20) Danger else Green,
                )
                s.hoursLeft?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Al ritmo actual dura ≈ ${"%.1f".format(it)} h más",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoRow("Corriente", s.currentMa?.let { (if (it > 0) "+" else "") + "$it mA" } ?: "No disponible")
                InfoRow("Temperatura", "%.1f °C".format(s.tempC))
                InfoRow("Voltaje", "${s.voltageMv} mV")
                InfoRow("Salud", s.health)
                InfoRow("Carga restante", s.remainingMah?.let { "$it mAh" } ?: "No disponible")
            }
        }

        val tips = buildList {
            val drain = s.currentMa?.let { if (it < 0) abs(it) else 0 } ?: 0
            if (drain > 800) add("Consumo muy alto ahora mismo ($drain mA). Baja el brillo, cierra juegos/cámara y revisa la pestaña Apps.")
            else if (drain > 400) add("Consumo moderado-alto ($drain mA). Con la pantalla apagada debería bajar de ~100 mA.")
            if (s.tempC >= 40f) add("El teléfono está caliente (${"%.0f".format(s.tempC)} °C). El calor gasta batería y la desgasta: quita la funda, evita usarlo cargando.")
            if (s.health != "Buena" && s.health != "Desconocida") add("La batería reporta salud \"${s.health}\". Puede que necesite reemplazo.")
            if (s.level in 0..20 && !s.charging) add("Batería baja: activa el ahorro de batería.")
        }
        if (tips.isNotEmpty()) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    tips.forEach { Text("• $it") }
                }
            }
        }

        Text(
            "Truco: mira la corriente con la pantalla encendida sin hacer nada. Luego abre una app " +
                    "sospechosa y compara: verás cuánto suma.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = { Actions.systemBatteryUsage(ctx) }, modifier = Modifier.fillMaxWidth()) {
            Text("Ver consumo oficial del sistema")
        }
        OutlinedButton(onClick = { Actions.batterySaver(ctx) }, modifier = Modifier.fillMaxWidth()) {
            Text("Ahorro de batería")
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

/* ---------------------------- CONSEJOS ---------------------------- */

private data class Tip(val title: String, val body: String, val button: String, val action: (android.content.Context) -> Unit)

@Composable
fun TipsScreen() {
    val ctx = LocalContext.current
    val xiaomiTips = if (Actions.isXiaomi) listOf(
        Tip("Xiaomi: Inicio automático", "HyperOS deja que muchas apps arranquen solas. Apágalo para todo excepto WhatsApp, alarmas y lo que necesites recibir al instante.", "Inicio automático") { Actions.xiaomiAutostart(it) },
        Tip("Xiaomi: Centro de batería", "En Seguridad > Batería: usa el modo \"Equilibrado\" (no \"Rendimiento\"), activa \"Ahorro de batería\" automático y revisa qué apps marca como de alto consumo.", "Abrir batería de Xiaomi") { Actions.xiaomiBatteryCenter(it) },
        Tip("Xiaomi: Tasa de refresco", "La pantalla AMOLED a 120 Hz gasta bastante más. En Pantalla > Tasa de actualización elige 60 Hz o \"Predeterminada\" (dinámica). Apaga también la Pantalla Siempre Activa (AOD) si no la usas.", "Ajustes de pantalla") { Actions.display(it) },
        Tip("Xiaomi: Escaneo Wi-Fi y Bluetooth", "Aunque tengas Wi-Fi y Bluetooth apagados, HyperOS sigue buscando redes y dispositivos para mejorar la ubicación. En Ubicación > Servicios de ubicación desactiva \"Búsqueda de Wi-Fi\" y \"Búsqueda de Bluetooth\".", "Ajustes de ubicación") { Actions.location(it) },
        Tip("Xiaomi: VoLTE / 5G y señal", "Tu Redmi usa un procesador MediaTek; en algunas versiones de HyperOS el módem mantiene el teléfono despierto. Si el vigilante te avisa de gasto con la pantalla apagada sin ninguna app culpable, prueba a usar 4G en vez de 5G o desactivar VoLTE en Tarjetas SIM y redes móviles.", "Redes") { Actions.wireless(it) },
        Tip("Xiaomi: Anuncios y recomendaciones", "Las apps del sistema (Seguridad, Archivos, Música, Temas) muestran recomendaciones que consumen datos y batería. Desactívalas en los ajustes de cada una y en Ajustes > Contraseñas y seguridad > Autorización y revocación, revoca \"msa\".", "Seguridad") { Actions.security(it) },
        Tip("Xiaomi: Carga", "Activa \"Protección de batería / Carga optimizada\" en Batería para reducir el desgaste; evita usar el modo turbo de carga si el teléfono se calienta.", "Abrir batería de Xiaomi") { Actions.xiaomiBatteryCenter(it) },
    ) else emptyList()
    val tips = xiaomiTips + listOf(
        Tip("Pantalla", "Es lo que más gasta. Brillo automático, tiempo de apagado de 30 s, tema oscuro (en pantallas OLED ahorra mucho) y bajar la tasa de refresco a 60 Hz.", "Ajustes de pantalla") { Actions.display(it) },
        Tip("Apps sin optimización", "Revisa la lista \"No optimizadas\": cualquier app ahí que no sea de mensajería o alarmas, ponla en \"Optimizar\".", "Ver lista") { Actions.optimizationList(it) },
        Tip("Ubicación", "Las apps que piden GPS todo el tiempo vacían la batería. Deja la ubicación en \"Solo mientras se usa\" y apágala si no la necesitas.", "Ajustes de ubicación") { Actions.location(it) },
        Tip("Sincronización de cuentas", "Varias cuentas sincronizando correo, fotos y contactos cada pocos minutos se nota. Desactiva las que no uses.", "Cuentas") { Actions.sync(it) },
        Tip("Redes y señal", "Con mala señal el teléfono gasta mucho buscando red. Usa Wi-Fi cuando puedas; apaga Bluetooth, NFC y 5G si no los necesitas.", "Redes") { Actions.wireless(it) },
        Tip("Ahorro de batería", "Actívalo automáticamente al 20-30 %. Limita procesos en segundo plano y efectos visuales.", "Ahorro de batería") { Actions.batterySaver(it) },
        Tip("Consumo oficial", "Android sí guarda el consumo real por app; solo él puede leerlo. Compáralo con la pestaña Apps.", "Abrir") { Actions.systemBatteryUsage(it) },
        Tip("Modo avanzado (sin root)", "Activa Opciones de desarrollador (toca 7 veces \"Número de compilación\") y la Depuración inalámbrica. Con Shizuku y Hail puedes congelar apps de verdad.", "Opciones de desarrollador") { Actions.developer(it) },
    )
    val links = listOf(
        "Hail — congela apps (código abierto)" to "https://github.com/aistra0528/Hail",
        "Shizuku — permisos de ADB sin PC" to "https://github.com/RikkaApps/Shizuku",
        "BetterBatteryStats — qué despierta el teléfono" to "https://f-droid.org/packages/com.asksven.betterbatterystats/",
        "Shappky — cierra apps en 2º plano con Shizuku" to "https://github.com/YasserNull/shappky",
    )

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(tips) { t ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(t.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(t.body, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = { t.action(ctx) }) { Text(t.button) }
                }
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            Text("Herramientas libres recomendadas", style = MaterialTheme.typography.titleMedium)
        }
        items(links) { (title, url) ->
            OutlinedButton(onClick = { Actions.web(ctx, url) }, modifier = Modifier.fillMaxWidth()) {
                Text(title)
            }
        }
    }
}
