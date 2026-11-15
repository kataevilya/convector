package com.html2apk.converter.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.html2apk.converter.model.Orientation
import com.html2apk.converter.model.SourceKind
import com.html2apk.converter.util.AppOutput
import com.html2apk.converter.util.PackageIds
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConverterScreen(viewModel: ConverterViewModel = viewModel()) {
    val state = viewModel.state
    val context = LocalContext.current
    var showAdvanced by remember { mutableStateOf(false) }

    val htmlPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.selectSource(uri, SourceKind.HTML)
    }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.selectSource(uri, SourceKind.ZIP)
    }
    val iconPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.selectIcon(uri)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("HTML → APK", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "локальный конвертер, без интернета",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            state.error?.let { message ->
                ErrorCard(message = message, onDismiss = viewModel::clearError)
            }

            SourceCard(
                sourceName = state.sourceName,
                sourceSize = state.sourceSize,
                sourceKind = state.sourceKind,
                onPickHtml = { htmlPicker.launch(arrayOf("*/*")) },
                onPickZip = { zipPicker.launch(arrayOf("*/*")) }
            )

            SettingsCard(
                appName = state.appName,
                packageId = state.packageId,
                versionName = state.versionName,
                versionCode = state.versionCode,
                iconPreview = state.iconPreview,
                fullscreen = state.fullscreen,
                zoom = state.zoom,
                hardwareAcceleration = state.hardwareAcceleration,
                orientation = state.orientation,
                showAdvanced = showAdvanced,
                onToggleAdvanced = { showAdvanced = !showAdvanced },
                onAppNameChange = viewModel::updateAppName,
                onPackageIdChange = viewModel::updatePackageId,
                onVersionNameChange = viewModel::updateVersionName,
                onVersionCodeChange = viewModel::updateVersionCode,
                onPickIcon = { iconPicker.launch("image/*") },
                onFullscreenChange = viewModel::setFullscreen,
                onZoomChange = viewModel::setZoom,
                onHardwareAccelerationChange = viewModel::setHardwareAcceleration,
                onOrientationChange = viewModel::setOrientation
            )

            Button(
                onClick = viewModel::build,
                enabled = !state.isBuilding,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = if (state.isBuilding) "Сборка…" else "Скомпилировать APK",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (state.isBuilding || state.resultFile != null) {
                BuildStatusCard(
                    isBuilding = state.isBuilding,
                    stepLabel = state.currentStep?.label ?: "",
                    progress = state.progress,
                    resultName = state.resultFile?.name,
                    resultSize = state.resultFile?.length() ?: 0L,
                    info = state.info,
                    onInstall = {
                        val file = state.resultFile
                        if (file == null) {
                            Toast.makeText(context, "APK ещё не готов", Toast.LENGTH_SHORT).show()
                        } else if (!AppOutput.canInstallPackages(context)) {
                            val intent = AppOutput.unknownSourcesSettings(context)
                            Toast.makeText(
                                context,
                                "Разрешите установку приложений из этого источника",
                                Toast.LENGTH_LONG
                            ).show()
                            if (intent != null) runCatching { context.startActivity(intent) }
                        } else {
                            runCatching { context.startActivity(AppOutput.installIntent(context, file)) }
                                .onFailure {
                                    Toast.makeText(
                                        context,
                                        "Не удалось открыть установщик: ${it.message}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                        }
                    },
                    onShare = {
                        val file = state.resultFile
                        if (file != null) {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent.createChooser(
                                        AppOutput.shareIntent(context, file),
                                        "Поделиться APK"
                                    )
                                )
                            }
                        }
                    },
                    onReset = viewModel::consumeResult
                )
            }

            Text(
                text = "Шаблон: WebView + JavaScript + LocalStorage + assets/web. " +
                    "Подпись: APK Signature Scheme v1 + v2, ключ генерируется на устройстве.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = "Ошибка",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onDismiss) { Text("Понятно") }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

@Composable
private fun SourceCard(
    sourceName: String?,
    sourceSize: Long,
    sourceKind: SourceKind,
    onPickHtml: () -> Unit,
    onPickZip: () -> Unit
) {
    SectionCard(title = "1. Исходники") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onPickHtml, modifier = Modifier.weight(1f)) {
                Text("HTML-файл")
            }
            OutlinedButton(onClick = onPickZip, modifier = Modifier.weight(1f)) {
                Text("ZIP сайта")
            }
        }

        if (sourceName != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(12.dp)
            ) {
                Column {
                    Text(
                        text = sourceName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${formatSize(sourceSize)} · " +
                            if (sourceKind == SourceKind.ZIP) "архив → assets/web/" else "один файл → assets/web/index.html",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Text(
                text = "Выберите index.html для одностраничных проектов или ZIP со всеми css/js/картинками.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsCard(
    appName: String,
    packageId: String,
    versionName: String,
    versionCode: String,
    iconPreview: android.graphics.Bitmap?,
    fullscreen: Boolean,
    zoom: Boolean,
    hardwareAcceleration: Boolean,
    orientation: Orientation,
    showAdvanced: Boolean,
    onToggleAdvanced: () -> Unit,
    onAppNameChange: (String) -> Unit,
    onPackageIdChange: (String) -> Unit,
    onVersionNameChange: (String) -> Unit,
    onVersionCodeChange: (String) -> Unit,
    onPickIcon: () -> Unit,
    onFullscreenChange: (Boolean) -> Unit,
    onZoomChange: (Boolean) -> Unit,
    onHardwareAccelerationChange: (Boolean) -> Unit,
    onOrientationChange: (Orientation) -> Unit
) {
    val packageIdValid = packageId.isBlank() || PackageIds.isValid(packageId)

    SectionCard(title = "2. Параметры приложения") {
        OutlinedTextField(
            value = appName,
            onValueChange = onAppNameChange,
            label = { Text("Название приложения") },
            placeholder = { Text("My Game") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = packageId,
            onValueChange = onPackageIdChange,
            label = { Text("Package ID") },
            placeholder = { Text("com.example.mygame") },
            singleLine = true,
            isError = !packageIdValid,
            supportingText = {
                Text(
                    if (packageIdValid) "Уникальный идентификатор установки"
                    else "Нужно минимум два сегмента: com.example.app"
                )
            },
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = versionName,
                onValueChange = onVersionNameChange,
                label = { Text("Version Name") },
                singleLine = true,
                modifier = Modifier.weight(1.4f)
            )
            OutlinedTextField(
                value = versionCode,
                onValueChange = onVersionCodeChange,
                label = { Text("Version Code") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                val preview = iconPreview
                if (preview != null) {
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = "Иконка приложения",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(76.dp)
                            .clip(RoundedCornerShape(20.dp))
                    )
                } else {
                    Text(
                        text = "иконка",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = onPickIcon) { Text("Аватарка") }
                Text(
                    text = "PNG/JPG → mipmap 48…192 px",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        ToggleRow("Полноэкранный режим", fullscreen, onFullscreenChange)
        ToggleRow("Разрешить зумирование", zoom, onZoomChange)
        ToggleRow("Аппаратное ускорение", hardwareAcceleration, onHardwareAccelerationChange)

        Text(
            text = "Ориентация экрана",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Orientation.entries.forEach { option ->
                FilterChip(
                    selected = orientation == option,
                    onClick = { onOrientationChange(option) },
                    label = { Text(option.title) }
                )
            }
        }

        OutlinedButton(onClick = onToggleAdvanced, modifier = Modifier.fillMaxWidth()) {
            Text(if (showAdvanced) "Скрыть детали" else "Детали сборки")
        }
        if (showAdvanced) {
            Text(
                text = "minSdk 24 · targetSdk 34 · assets/web/index.html\n" +
                    "Патчится: package, versionCode, versionName, screenOrientation, hardwareAccelerated\n" +
                    "resources.arsc: app_name\nПодпись: v1 + v2 (apksig), ключ RSA-2048 на устройстве",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun BuildStatusCard(
    isBuilding: Boolean,
    stepLabel: String,
    progress: Float,
    resultName: String?,
    resultSize: Long,
    info: String?,
    onInstall: () -> Unit,
    onShare: () -> Unit,
    onReset: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = if (isBuilding) "Сборка APK" else "APK готов",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            if (isBuilding) {
                Text(
                    text = stepLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = resultName ?: "app.apk",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatSize(resultSize) + (info?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onInstall, modifier = Modifier.weight(1f)) {
                        Text("Установить")
                    }
                    OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                        Text("Поделиться")
                    }
                }
                OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                    Text("Собрать ещё раз")
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 Б"
    val units = arrayOf("Б", "КБ", "МБ", "ГБ")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return String.format(Locale.US, if (value >= 100 || index == 0) "%.0f %s" else "%.1f %s", value, units[index])
}
