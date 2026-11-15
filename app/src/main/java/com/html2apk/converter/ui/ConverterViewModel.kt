package com.html2apk.converter.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.html2apk.converter.core.AppBuilder
import com.html2apk.converter.core.IconProcessor
import com.html2apk.converter.model.BuildProgress
import com.html2apk.converter.model.BuildRequest
import com.html2apk.converter.model.BuildStep
import com.html2apk.converter.model.Orientation
import com.html2apk.converter.model.SourceKind
import com.html2apk.converter.util.AppOutput
import com.html2apk.converter.util.PackageIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ConverterViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val sourceName: String? = null,
        val sourceSize: Long = 0L,
        val sourceKind: SourceKind = SourceKind.HTML,
        val sourceFile: File? = null,
        val appName: String = "",
        val packageId: String = "",
        val packageIdTouched: Boolean = false,
        val versionName: String = "1.0",
        val versionCode: String = "1",
        val iconFile: File? = null,
        val iconPreview: Bitmap? = null,
        val fullscreen: Boolean = true,
        val zoom: Boolean = false,
        val hardwareAcceleration: Boolean = true,
        val orientation: Orientation = Orientation.UNSPECIFIED,
        val isBuilding: Boolean = false,
        val currentStep: BuildStep? = null,
        val progress: Float = 0f,
        val error: String? = null,
        val info: String? = null,
        val resultFile: File? = null,
        val savedToDownloads: Boolean = false
    )

    var state by mutableStateOf(UiState())
        private set

    private val progressFlow = MutableSharedFlow<BuildProgress>(extraBufferCapacity = 64)

    init {
        viewModelScope.launch {
            progressFlow.collect { event ->
                state = state.copy(
                    currentStep = event.step,
                    progress = if (event.total == 0) 0f else event.index.toFloat() / event.total
                )
            }
        }
    }

    fun selectSource(uri: Uri, kind: SourceKind) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val name = queryDisplayName(uri) ?: "source.${if (kind == SourceKind.ZIP) "zip" else "html"}"
                    val target = File(cacheDir(), "src_${System.currentTimeMillis()}_${sanitize(name)}")
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
                    } ?: throw IllegalStateException("Не удалось открыть выбранный файл")
                    name to target
                }
            }.onSuccess { (name, file) ->
                val derivedName = name.substringBeforeLast('.', name).replace('_', ' ').trim()
                state = state.copy(
                    sourceName = name,
                    sourceSize = file.length(),
                    sourceKind = kind,
                    sourceFile = file,
                    appName = if (state.appName.isBlank()) derivedName else state.appName,
                    packageId = if (!state.packageIdTouched) {
                        PackageIds.suggestPackageId(if (state.appName.isBlank()) derivedName else state.appName)
                    } else {
                        state.packageId
                    },
                    error = null,
                    resultFile = null
                )
            }.onFailure { failure ->
                state = state.copy(error = failure.message ?: "Ошибка чтения файла")
            }
        }
    }

    fun selectIcon(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val target = File(cacheDir(), "icon_${System.currentTimeMillis()}.png")
                    resolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
                    } ?: throw IllegalStateException("Не удалось открыть изображение")
                    val bitmap = IconProcessor.decode(target)
                    target to bitmap
                }
            }.onSuccess { (file, bitmap) ->
                state = state.copy(iconFile = file, iconPreview = bitmap, error = null)
            }.onFailure { failure ->
                state = state.copy(error = failure.message ?: "Ошибка чтения изображения")
            }
        }
    }

    fun updateAppName(value: String) {
        state = state.copy(
            appName = value,
            packageId = if (!state.packageIdTouched) PackageIds.suggestPackageId(value) else state.packageId
        )
    }

    fun updatePackageId(value: String) {
        state = state.copy(packageId = value, packageIdTouched = true)
    }

    fun updateVersionName(value: String) {
        state = state.copy(versionName = value)
    }

    fun updateVersionCode(value: String) {
        if (value.all { it.isDigit() } && value.length <= 9) {
            state = state.copy(versionCode = value)
        }
    }

    fun setFullscreen(value: Boolean) {
        state = state.copy(fullscreen = value)
    }

    fun setZoom(value: Boolean) {
        state = state.copy(zoom = value)
    }

    fun setHardwareAcceleration(value: Boolean) {
        state = state.copy(hardwareAcceleration = value)
    }

    fun setOrientation(value: Orientation) {
        state = state.copy(orientation = value)
    }

    fun clearError() {
        state = state.copy(error = null)
    }

    fun consumeResult() {
        state = state.copy(resultFile = null, progress = 0f, currentStep = null, info = null)
    }

    fun build() {
        if (state.isBuilding) return

        val sourceFile = state.sourceFile
        if (sourceFile == null) {
            state = state.copy(error = "Сначала выберите HTML-файл или ZIP-архив")
            return
        }
        val appName = state.appName.trim()
        if (appName.isEmpty()) {
            state = state.copy(error = "Укажите название приложения")
            return
        }
        if (!PackageIds.isValid(state.packageId)) {
            state = state.copy(
                error = "Package ID некорректен. Пример: com.example.mygame (минимум два сегмента, латиница)"
            )
            return
        }
        val versionCode = state.versionCode.toIntOrNull()
        if (versionCode == null || versionCode <= 0) {
            state = state.copy(error = "Version Code должен быть положительным числом")
            return
        }

        val request = BuildRequest(
            sourceFile = sourceFile,
            sourceKind = state.sourceKind,
            appName = appName,
            packageId = state.packageId.trim(),
            versionName = state.versionName.ifBlank { "1.0" },
            versionCode = versionCode,
            iconFile = state.iconFile,
            fullscreen = state.fullscreen,
            zoom = state.zoom,
            hardwareAcceleration = state.hardwareAcceleration,
            orientation = state.orientation
        )

        state = state.copy(
            isBuilding = true,
            error = null,
            info = null,
            resultFile = null,
            progress = 0f,
            currentStep = BuildStep.UNPACK_TEMPLATE
        )

        viewModelScope.launch {
            val steps = BuildStep.entries
            try {
                val result = withContext(Dispatchers.IO) {
                    AppBuilder(getApplication()).build(request) { step ->
                        val index = steps.indexOf(step) + 1
                        progressFlow.tryEmit(BuildProgress(step, index, steps.size))
                    }
                }

                val saved = withContext(Dispatchers.IO) {
                    AppOutput.saveToDownloads(
                        getApplication(),
                        result,
                        PackageIds.suggestFileName(appName) + ".apk"
                    ) != null
                }

                state = state.copy(
                    isBuilding = false,
                    currentStep = BuildStep.DONE,
                    progress = 1f,
                    resultFile = result,
                    savedToDownloads = saved,
                    info = if (saved) {
                        "APK сохранён в Downloads/HTML2APK"
                    } else {
                        "APK сохранён во внутреннюю папку приложения"
                    },
                    packageIdTouched = state.packageIdTouched
                )
            } catch (failure: Throwable) {
                Log.e(TAG, "Сборка APK не удалась", failure)
                state = state.copy(
                    isBuilding = false,
                    error = failure.message ?: failure.javaClass.simpleName,
                    currentStep = null,
                    progress = 0f
                )
            }
        }
    }

    private fun cacheDir(): File {
        val context = getApplication<Application>()
        return File(context.cacheDir, "sources").apply { mkdirs() }
    }

    private fun sanitize(name: String): String {
        return name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64).ifEmpty { "source" }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
            getApplication<Application>().contentResolver
                .query(uri, projection, null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
                }
        } catch (_: Throwable) {
            uri.lastPathSegment
        }
    }

    private companion object {
        const val TAG = "ConverterViewModel"
    }
}
