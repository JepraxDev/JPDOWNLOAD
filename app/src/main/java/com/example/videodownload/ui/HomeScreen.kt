package com.example.videodownload.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.example.videodownload.R
import com.example.videodownload.data.model.DownloadUiState
import com.example.videodownload.data.model.LocalMediaFile
import com.example.videodownload.data.model.Platform
import com.example.videodownload.utils.InstagramCookieManager
import android.media.MediaPlayer
import android.widget.MediaController
import android.widget.VideoView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: MainViewModel) {
    val urlInput by viewModel.urlInput.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val detectedPlatform by viewModel.detectedPlatform.collectAsState()
    val historyList by viewModel.historyList.collectAsState()
    val downloadedFiles by viewModel.downloadedFiles.collectAsState()
    val context = LocalContext.current

    // Archivo que se está reproduciendo actualmente (null = cerrado)
    var playingFile by remember { mutableStateOf<LocalMediaFile?>(null) }

    // Estado de sesión de Instagram (cookies guardadas)
    var igLoggedIn by remember { mutableStateOf(InstagramCookieManager.hasCookies(context)) }
    var showIgLogin by remember { mutableStateOf(false) }

    if (showIgLogin) {
        InstagramLoginDialog(
            onDismiss = { showIgLogin = false },
            onSuccessLogin = {
                showIgLogin = false
                igLoggedIn = true
            }
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_app_logo),
                            contentDescription = "JPDOWNLOAD Logo",
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                        )
                        Text(
                            text = "JPDOWNLOAD",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 20.sp,
                            letterSpacing = 1.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                // Banner superior informativo
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Plataformas Soportadas:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            PlatformChip("TikTok", Color(0xFF000000))
                            PlatformChip("YouTube", Color(0xFFFF0000))
                            PlatformChip("Instagram", Color(0xFFE1306C))
                            PlatformChip("Facebook", Color(0xFF1877F2))
                            PlatformChip("Twitter/X", Color(0xFF1DA1F2))
                        }
                    }
                }
            }

            item {
                // Campo de texto de URL con botón de Pegar
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { viewModel.onUrlInputChanged(it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Pega el enlace del video aquí...") },
                    placeholder = { Text("https://www.tiktok.com/@user/video/...") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingIcon = {
                        Row {
                            if (urlInput.isNotEmpty()) {
                                IconButton(onClick = { viewModel.onUrlInputChanged("") }) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "Limpiar"
                                    )
                                }
                            }
                            IconButton(onClick = { viewModel.pasteFromClipboard() }) {
                                Icon(
                                    imageVector = Icons.Default.ContentPaste,
                                    contentDescription = "Pegar",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                )
            }

            item {
                // Botón de Analizar enlace
                Button(
                    onClick = { viewModel.fetchVideoInfo() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    enabled = urlInput.isNotBlank() && uiState !is DownloadUiState.Loading
                ) {
                    Icon(imageVector = Icons.Default.Search, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Analizar Video",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }

            // Indicador de plataforma detectada
            if (detectedPlatform != Platform.UNKNOWN) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(text = "Plataforma detectada:", fontSize = 12.sp)
                        PlatformBadge(platform = detectedPlatform)
                    }
                }
            }

            // Instagram exige sesión: ofrecer login si no hay cookies guardadas
            if (detectedPlatform == Platform.INSTAGRAM && !igLoggedIn) {
                item {
                    InstagramLoginPromptCard(
                        onLoginClick = { showIgLogin = true },
                        onLoginSuccess = { igLoggedIn = true }
                    )
                }
            }

            // Seccion de Estados (Loading, Loaded, Downloading, Success, Error) sin parpadeos
            item {
                val stateKey = when (uiState) {
                    is DownloadUiState.Idle -> "Idle"
                    is DownloadUiState.Loading -> "Loading"
                    is DownloadUiState.InfoLoaded -> "InfoLoaded"
                    is DownloadUiState.Downloading -> "Downloading"
                    is DownloadUiState.Success -> "Success"
                    is DownloadUiState.Error -> "Error"
                }

                AnimatedContent(
                    targetState = stateKey,
                    label = "UI State Transition"
                ) { key ->
                    when (key) {
                        "Idle" -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VideoLibrary,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Text(
                                        text = "Pega un enlace para descargar en HD o MP3 sin marcas de agua",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }

                        "Loading" -> {
                            val loadingMsg = (uiState as? DownloadUiState.Loading)?.message ?: "Analizando enlace..."
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(20.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                                    Text(
                                        text = loadingMsg,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }

                        "InfoLoaded" -> {
                            val loadedInfo = (uiState as? DownloadUiState.InfoLoaded)?.videoInfo
                            if (loadedInfo != null) {
                                VideoPreviewCard(
                                    videoInfo = loadedInfo,
                                    onDownloadClick = { selectedOption ->
                                        viewModel.downloadMedia(loadedInfo, selectedOption)
                                    }
                                )
                            }
                        }

                        "Downloading" -> {
                            val downloadingState = uiState as? DownloadUiState.Downloading
                            ProgressCard(
                                progress = downloadingState?.progress ?: 0,
                                title = downloadingState?.title ?: ""
                            )
                        }

                        "Success" -> {
                            val successState = uiState as? DownloadUiState.Success
                            if (successState != null) {
                                SuccessCard(
                                    videoInfo = successState.videoInfo,
                                    filePath = successState.filePath,
                                    onReset = { viewModel.resetState() }
                                )
                            }
                        }

                        "Error" -> {
                            val errorMsg = (uiState as? DownloadUiState.Error)?.message ?: "Error procesando el enlace"
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                ),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ErrorOutline,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                        Text(
                                            text = "Error al procesar",
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                    Text(
                                        text = errorMsg,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Button(
                                        onClick = { viewModel.fetchVideoInfo() },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error
                                        ),
                                        modifier = Modifier.align(Alignment.End)
                                    ) {
                                        Text("Reintentar")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Historial de descargas completadas
            if (historyList.isNotEmpty()) {
                item {
                    Text(
                        text = "Historial Reciente:",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                items(historyList) { item ->
                    DownloadHistoryItem(item = item)
                }
            }

            // ============================================================
            // APARTADO "DESCARGADOS": archivos reales guardados en la galería
            // ============================================================
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Descargados:",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = { viewModel.refreshDownloadedFiles() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Actualizar",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            if (downloadedFiles.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            text = "Aún no hay descargas. Los videos, imágenes y audios que descargues aparecerán aquí.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
            } else {
                items(downloadedFiles) { file ->
                    DownloadedFileItem(
                        file = file,
                        onClick = {
                            if (file.isPlayable) {
                                playingFile = file
                            }
                        }
                    )
                }
            }
        }
    }

    // Reproductor integrado (video / imagen / audio)
    playingFile?.let { file ->
        MediaPlaybackDialog(
            file = file,
            onDismiss = { playingFile = null }
        )
    }
}

@Composable
fun InstagramLoginPromptCard(
    onLoginClick: () -> Unit,
    onLoginSuccess: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF2A1B2E)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = Color(0xFFE1306C),
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Instagram requiere tu sesión",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
            Text(
                text = "Inicia sesión una vez en Instagram dentro de la app para poder extraer los Reels. Tus cookies se quedan solo en tu celular.",
                fontSize = 12.sp,
                color = Color(0xFFC9B8CE)
            )
            Button(
                onClick = onLoginClick,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFE1306C)
                ),
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Iniciar Sesión", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun DownloadedFileItem(file: LocalMediaFile, onClick: () -> Unit) {
    val context = LocalContext.current
    // Miniatura generada por el sistema (frame del video o imagen reducida)
    var thumbnail by remember(file.contentUri) { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(file.contentUri) {
        val uriStr = file.contentUri
        if (uriStr != null) {
            withContext(Dispatchers.IO) {
                try {
                    thumbnail = context.contentResolver.loadThumbnail(
                        android.net.Uri.parse(uriStr),
                        android.util.Size(160, 160),
                        null
                    )
                } catch (_: Exception) {
                    // Algunas versiones no soportan loadThumbnail para ciertos tipos
                }
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when {
                            file.isAudio -> Icons.Default.Audiotrack
                            file.isImage -> Icons.Default.Image
                            else -> Icons.Default.PlayCircle
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val kind = when {
                        file.isAudio -> "Audio"
                        file.isImage -> "Imagen"
                        else -> "Video"
                    }
                    Text(
                        text = kind,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "· ${file.prettySize}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Reproducir",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun MediaPlaybackDialog(file: LocalMediaFile, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(0.dp),
            color = Color.Black,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = file.name,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cerrar",
                            tint = Color.White
                        )
                    }
                }

                when {
                    file.isImage -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            coil.compose.AsyncImage(
                                model = File(file.path),
                                contentDescription = file.name,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }

                    else -> {
                        // Video y audio: reproductor nativo que ocupa toda la pantalla disponible
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            AndroidView(
                                factory = { ctx ->
                                    VideoView(ctx).apply {
                                        setVideoPath(file.path)
                                        if (file.isVideo) {
                                            val controller = MediaController(ctx)
                                            controller.setAnchorView(this)
                                            setMediaController(controller)
                                        }
                                        setOnCompletionListener { _ -> }
                                        start()
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight()
                                    .background(Color.Black)
                            )
                        }

                        if (file.isAudio) {
                            Text(
                                text = "🎵 ${file.name}",
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PlatformChip(name: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = CircleShape
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Text(
                text = name,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}
