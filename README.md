# Guardián de Batería (Android)

App para Android 8.0 o superior que te muestra qué apps gastan más batería y te da botones para frenarlas.

## Qué hace

**Pestaña Apps**
- Ranking de las apps que más consumen en las últimas 24 h, 3 días o 7 días, con un % estimado.
- Muestra por app: tiempo en pantalla, tiempo trabajando en segundo plano, cuántas veces se abrió y cuántas arrancó oculta.
- Avisa cuando una app **no tiene optimización de batería** o **trabaja más oculta que en pantalla**.
- Botones por app:
  - **Cerrar en 2º plano**: lo hace directamente.
  - **Restringir batería**: abre la pantalla de batería de la app para que elijas "Restringida".
  - **Forzar detención**.
  - **Notificaciones**.
  - **Desinstalar**.
  - **Congelar (avanzado)**: te da los comandos ADB listos para copiar.
- Botón **Cerrar todas en segundo plano**.

**Pestaña Batería**: nivel, corriente en mA en vivo, temperatura, voltaje, salud y horas estimadas que le quedan. También te da alertas: consumo alto, calor o batería baja.

**Pestaña Consejos**: atajos directos a los ajustes que más ahorran (pantalla, ubicación, sincronización, redes, ahorro de batería, apps no optimizadas) y enlaces a herramientas libres (Hail, Shizuku, BetterBatteryStats, Shappky).

## Por qué el % es "estimado"
Desde Android 6, solo el sistema puede leer el consumo real de batería de cada app (permiso `BATTERY_STATS`). Por eso la app calcula el peso de cada una con datos reales de uso (`UsageStatsManager`). Ese cálculo suele coincidir bien con el de Ajustes > Batería, y la app tiene un botón para abrir esa pantalla y comparar.

Por la misma razón, una app normal **no puede congelar otras apps** sin root o sin permisos de ADB. Con "Restringir batería" en Ajustes logras casi lo mismo. Para congelar de verdad, usa los comandos ADB que te da la app o la app **Hail** con **Shizuku**.

## Cómo obtener el APK

### Opción A: GitHub, sin instalar nada
1. Crea un repositorio en GitHub y sube el contenido de esta carpeta.
2. Ve a la pestaña **Actions**. El flujo "Compilar APK" se ejecuta solo y tarda unos 5 minutos.
3. Abre la ejecución y descarga **GuardianBateria-apk**. Es un zip con `app-debug.apk` dentro.

### Opción B: Android Studio
Abre la carpeta en Android Studio y pulsa **Run**. También puedes usar *Build > Build APK(s)*.

## Instalar en el celular
1. Pasa `app-debug.apk` al teléfono y ábrelo. Permite "instalar apps de origen desconocido" cuando te lo pida.
2. Abre **Guardián de Batería** > **Conceder acceso al uso**, busca la app en la lista y activa el permiso.
3. Ve a la pestaña Apps y toca las apps que están en rojo.

## Congelar apps sin root (opcional)
Desde una PC con ADB, o desde el propio teléfono con Shizuku:
```
adb shell cmd appops set PAQUETE RUN_ANY_IN_BACKGROUND ignore
adb shell am set-standby-bucket PAQUETE restricted
adb shell pm disable-user --user 0 PAQUETE      # congelar
adb shell pm enable PAQUETE                     # descongelar
```
Para ver el consumo **real** por app desde una PC: `adb shell dumpsys batterystats --charged | more`

## Xiaomi / Redmi / POCO (HyperOS)
En estos teléfonos la app detecta HyperOS y agrega:
- **Restringir batería**: abre directamente el "Ahorro de batería" de la app. Ahí elige **Restringir apps en segundo plano**.
- **Inicio automático**: abre la lista de apps que arrancan solas. Apágalo en todo lo que no necesites.
- Consejos específicos: modo Equilibrado, 60 Hz frente a 120 Hz, Pantalla Siempre Activa (AOD) y carga optimizada.
- Para usar los comandos ADB, en Opciones de desarrollador activa también **Depuración USB (ajustes de seguridad)**.
