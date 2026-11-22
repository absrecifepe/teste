package com.asciitype.app

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.asciitype.app.ascii.AsciiConverter
import com.asciitype.app.ascii.AsciiRenderer
import com.asciitype.app.ascii.Charset
import com.asciitype.app.ascii.ColorStyle
import com.asciitype.app.ascii.FrameLayout
import com.asciitype.app.ascii.Timeline
import com.asciitype.app.ascii.VideoExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

data class Settings(
    val columns: Int = 80,
    val charset: Charset = Charset.CLASSIC,
    val style: ColorStyle = ColorStyle.PAPER,
    val typingSeconds: Int = 8,
    val invert: Boolean = false,
)

data class VideoResult(val file: File, val width: Int, val height: Int)

data class UiState(
    val hasPhoto: Boolean = false,
    val loadingPhoto: Boolean = false,
    val settings: Settings = Settings(),
    val preview: Bitmap? = null,
    val exporting: Boolean = false,
    val progress: Float = 0f,
    val video: VideoResult? = null,
    @StringRes val message: Int? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private var source: Bitmap? = null
    private var previewJob: Job? = null
    private var exportJob: Job? = null

    private val videoDir get() = File(getApplication<Application>().cacheDir, "videos").apply { mkdirs() }

    fun onPhotoPicked(uri: Uri) {
        exportJob?.cancel()
        _state.update { it.copy(loadingPhoto = true, video = null) }
        viewModelScope.launch {
            val bmp = try {
                withContext(Dispatchers.IO) { decode(uri) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
            if (bmp == null) {
                _state.update { it.copy(loadingPhoto = false, message = R.string.msg_load_error) }
                return@launch
            }
            source?.recycle()
            source = bmp
            _state.update { it.copy(hasPhoto = true, loadingPhoto = false) }
            refreshPreview(immediate = true)
        }
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        val new = transform(_state.value.settings)
        if (new == _state.value.settings) return
        exportJob?.cancel()
        _state.update { it.copy(settings = new, video = null, exporting = false) }
        refreshPreview(immediate = false)
    }

    private fun refreshPreview(immediate: Boolean) {
        val src = source ?: return
        val s = _state.value.settings
        previewJob?.cancel()
        previewJob = viewModelScope.launch(Dispatchers.Default) {
            if (!immediate) delay(150)
            val layout = FrameLayout.compute(src.width, src.height, s.columns, 540, 960)
            val art = convert(src, layout, s)
            val renderer = AsciiRenderer(art, layout, s.style)
            val bmp = renderer.renderFinal()
            renderer.release()
            _state.update { it.copy(preview = bmp) }
        }
    }

    fun generateVideo() {
        val src = source ?: return
        if (_state.value.exporting) return
        val s = _state.value.settings
        exportJob = viewModelScope.launch {
            _state.update { it.copy(exporting = true, progress = 0f, video = null) }
            try {
                val result = withContext(Dispatchers.Default) {
                    videoDir.listFiles()?.forEach { it.delete() }
                    val layout = VideoExporter.chooseLayout(src.width, src.height, s.columns)
                    val art = convert(src, layout, s)
                    val renderer = AsciiRenderer(art, layout, s.style)
                    val timeline = Timeline(renderer.typedCount, s.typingSeconds.toFloat())
                    val out = File(videoDir, "ascii_${System.currentTimeMillis()}.mp4")
                    try {
                        VideoExporter.export(renderer, layout, timeline, out) { p ->
                            _state.update { it.copy(progress = p) }
                        }
                    } catch (e: Throwable) {
                        out.delete()
                        throw e
                    } finally {
                        renderer.release()
                    }
                    VideoResult(out, layout.width, layout.height)
                }
                _state.update { it.copy(exporting = false, video = result) }
            } catch (e: CancellationException) {
                _state.update { it.copy(exporting = false) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(exporting = false, message = R.string.msg_export_error) }
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
    }

    fun saveToGallery() {
        val video = _state.value.video ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, video.file.name)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/ASCII Typer")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                    val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    val uri = resolver.insert(collection, values) ?: error("insert falhou")
                    try {
                        resolver.openOutputStream(uri)!!.use { os -> video.file.inputStream().use { it.copyTo(os) } }
                        values.clear()
                        values.put(MediaStore.Video.Media.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                    } catch (e: Exception) {
                        resolver.delete(uri, null, null)
                        throw e
                    }
                }.isSuccess
            }
            _state.update { it.copy(message = if (ok) R.string.msg_saved else R.string.msg_save_error) }
        }
    }

    fun shareIntent(): Intent? {
        val video = _state.value.video ?: return null
        val app = getApplication<Application>()
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", video.file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, app.getString(R.string.share_title))
    }

    fun messageShown() {
        _state.update { it.copy(message = null) }
    }

    override fun onCleared() {
        source?.recycle()
    }

    private fun convert(src: Bitmap, layout: FrameLayout, s: Settings) = AsciiConverter.convert(
        src = src,
        cols = layout.cols,
        rows = layout.rows,
        charset = s.charset,
        denseIsDark = s.style.denseIsDark != s.invert,
    )

    /** Decodifica a foto já com a rotação EXIF aplicada, limitada a 1600 px no maior lado. */
    private fun decode(uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(getApplication<Application>().contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val maxSide = max(info.size.width, info.size.height)
            if (maxSide > 1600) {
                val k = 1600f / maxSide
                decoder.setTargetSize((info.size.width * k).toInt(), (info.size.height * k).toInt())
            }
        }.let { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false) }
    }
}
