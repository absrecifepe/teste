package com.asciitype.app.ui

import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.asciitype.app.MainViewModel
import com.asciitype.app.R
import com.asciitype.app.UiState
import com.asciitype.app.VideoResult
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.onPhotoPicked(uri)
    }
    val pickPhoto = {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    // O botão "voltar" do celular também retorna à tela inicial.
    BackHandler(enabled = state.hasPhoto) { vm.startOver() }

    val messageText = state.message?.let { stringResource(it) }
    LaunchedEffect(messageText) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            vm.messageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.app_name), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.hasPhoto) {
                EmptyState(loading = state.loadingPhoto, onPick = pickPhoto)
            } else {
                PreviewArea(state)
                ActionArea(
                    state = state,
                    onGenerate = vm::generateVideo,
                    onCancel = vm::cancelExport,
                    onSave = vm::saveToGallery,
                    onSaveImage = vm::saveImage,
                    onShare = { vm.shareIntent()?.let(context::startActivity) },
                    onStartOver = vm::startOver,
                )
                SettingsPanel(
                    settings = state.settings,
                    customMusic = state.customMusic,
                    enabled = !state.exporting,
                    onChange = vm::updateSettings,
                    onMusicPicked = vm::onMusicPicked,
                )
                OutlinedButton(onClick = pickPhoto, enabled = !state.exporting, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.change_photo))
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun EmptyState(loading: Boolean, onPick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(
            text = LOGO,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(R.string.empty_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            stringResource(R.string.empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (loading) {
            CircularProgressIndicator()
        } else {
            Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.pick_photo))
            }
        }
    }
}

@Composable
private fun PreviewArea(state: UiState) {
    val shape = RoundedCornerShape(12.dp)
    val video = state.video
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when {
            video != null && !state.exporting -> LoopingVideo(video)
            state.preview != null -> {
                val bmp = state.preview
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = stringResource(R.string.preview_desc),
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(bmp.width.toFloat() / bmp.height),
                )
            }
            else -> Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }
}

@Composable
private fun LoopingVideo(video: VideoResult) {
    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                setOnPreparedListener { mp ->
                    mp.isLooping = true
                    start()
                }
            }
        },
        update = { view ->
            if (view.tag != video.file.path) {
                view.tag = video.file.path
                view.setVideoPath(video.file.absolutePath)
            }
        },
        onRelease = { it.stopPlayback() },
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(video.width.toFloat() / video.height),
    )
}

@Composable
private fun ActionArea(
    state: UiState,
    onGenerate: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onSaveImage: () -> Unit,
    onShare: () -> Unit,
    onStartOver: () -> Unit,
) {
    val saveImageButton: @Composable (Modifier) -> Unit = { modifier ->
        OutlinedButton(onClick = onSaveImage, enabled = state.preview != null && !state.savingImage, modifier = modifier) {
            Text(stringResource(if (state.savingImage) R.string.saving_image else R.string.save_image))
        }
    }
    when {
        state.exporting -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.exporting, (state.progress * 100).roundToInt()))
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.cancel))
            }
        }
        state.video != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onSave, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.save)) }
                Button(onClick = onShare, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.share)) }
            }
            saveImageButton(Modifier.fillMaxWidth())
            OutlinedButton(onClick = onStartOver, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.start_over))
            }
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onGenerate, enabled = state.preview != null, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.generate))
            }
            saveImageButton(Modifier.fillMaxWidth())
        }
    }
}

private val LOGO = """
   _   ___  ___ ___ ___
  /_\ / __|/ __|_ _|_ _|
 / _ \\__ \ (__ | | | |
/_/ \_\___/\___|___|___|
""".trimIndent()
