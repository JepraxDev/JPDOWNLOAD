# JPDOWNLOAD

App Android para descargar videos, audio e imágenes de redes sociales de forma 100% local en el celular. Sin servidores intermedios, sin cuentas, sin anuncios.

## Plataformas soportadas

| Plataforma | Video | Audio (MP3) | Imágenes |
|------------|:-----:|:-----------:|:--------:|
| TikTok     | ✅    | ✅          | ✅       |
| YouTube    | ✅    | ✅          | ❌       |
| Facebook   | ✅    | ✅          | ❌       |
| Twitter/X  | ✅    | ✅          | ❌       |

## Cómo funciona

1. **Pega el enlace** del video (o compártelo desde cualquier app usando el menú "Compartir" de Android).
2. La app detecta automáticamente la plataforma y analiza el enlace.
3. Elige la calidad que prefieras (Full HD, HD, audio MP3...) y descarga.
4. El archivo queda en tu galería, listo para ver o compartir.

### ¿Cómo consigue los videos?

La app combina varios métodos, todos ejecutándose en el propio teléfono:

- **Extractores nativos**: para cada plataforma existe un extractor que consulta las APIs públicas/internas y obtiene las URLs directas de descarga (con referers y cabeceras apropiadas para cada sitio).
- **yt-dlp local**: como respaldo, usa un ejecutable de [yt-dlp](https://github.com/yt-dlp/yt-dlp) empaquetado dentro de la app (vía [youtubedl-android](https://github.com/yausername.youtubedl-android)) junto con Python nativo. No depende de ningún servicio externo.
- **Descarga por fragmentos**: para streams DASH (video y audio por separado), descarga ambos por trozos con reintentos automáticos y los une en un solo MP4 con `MediaMuxer`.
- **Validación multimedia**: cada archivo descargado se verifica por firma binaria (ftyp/WebM) para descartar respuestas HTML o datos corruptos.

## Funciones

- 🌓 **Modo oscuro / claro / sistema** — configurable en Ajustes (⚙️).
- 📁 **Carpeta de descargas personalizada** — elige cualquier carpeta y almacenamiento (memoria interna, tarjeta SD) con el selector nativo de Android. Por defecto se guarda en `Movies/VideoDownload` (audio en `Music/`, imágenes en `Pictures/`).
- 📥 **Historial y lista de descargas** con miniaturas y reproductor integrado.
- 📋 **Pegado inteligente**: lee el portapapeles y analiza el enlace con un toque.
- 🔗 **Compartir desde otras apps**: comparte un enlace desde TikTok/YouTube/etc. y la app lo procesa directo.

## Tecnología

- Kotlin + Jetpack Compose (Material 3)
- MVVM con ViewModel + StateFlow
- OkHttp para red, Coil para imágenes
- yt-dlp + FFmpeg nativos (youtubedl-android)
- Min SDK 24 (Android 7.0) · Target SDK 37

## Estructura del proyecto

```
app/src/main/java/com/example/videodownload/
├── MainActivity.kt                  # Entrada, tema y enlaces compartidos
├── ui/
│   ├── HomeScreen.kt                # Pantalla principal + Ajustes
│   ├── MainViewModel.kt             # Estado, descargas, MediaStore
│   └── Components.kt                # Componentes reutilizables
├── data/
│   ├── model/                       # Platform, VideoInfo, DownloadOption...
│   └── repository/
│       └── VideoExtractorRepository.kt  # Extractores por plataforma
├── downloader/
│   ├── AndroidVideoDownloader.kt    # DownloadManager + descarga fragmentada + SAF
│   └── AudioVideoMuxer.kt           # Unión de video + audio (DASH)
└── utils/
    └── SettingsManager.kt           # Preferencias (tema, carpeta)
```

## Compilar

1. Clona el repo y ábrelo en Android Studio.
2. Sincroniza Gradle (requisitos: JDK 17, Android SDK 37).
3. `Build > Generate Signed App Bundle / APK` para generar el APK de release, o simplemente ejecuta la app en un dispositivo para generar el debug.

## Instalación en el teléfono

Descarga el APK desde la sección [Releases](../../releases) de este repositorio, ábrelo y acepta "Instalar apps desconocidas" si Android lo pide (solo la primera vez).
