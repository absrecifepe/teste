package com.asciitype.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.asciitype.app.CustomMusic
import com.asciitype.app.MusicMode
import com.asciitype.app.R
import com.asciitype.app.Settings
import com.asciitype.app.ascii.Charset
import com.asciitype.app.ascii.ColorStyle
import com.asciitype.app.audio.MusicStyle
import kotlin.math.roundToInt

private const val MAX_TEXT = 80

/** Configurações agrupadas em seções recolhíveis, com controles compactos. */
@Composable
fun SettingsPanel(
    settings: Settings,
    customMusic: CustomMusic?,
    enabled: Boolean,
    onChange: ((Settings) -> Settings) -> Unit,
    onMusicPicked: (Uri) -> Unit,
) {
    val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onMusicPicked(uri)
    }
    val pickMusic = { musicPicker.launch("audio/*") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ---------------------------------------------------------------- Texto
        Section(
            title = R.string.section_text,
            summary = settings.headerText.ifBlank { null }?.let { "“${it.take(24)}${if (it.length > 24) "…" else ""}”" },
            initiallyExpanded = false,
        ) {
            OutlinedTextField(
                value = settings.headerText,
                onValueChange = { v -> onChange { it.copy(headerText = v.take(MAX_TEXT)) } },
                label = { Text(stringResource(R.string.setting_text)) },
                placeholder = { Text(stringResource(R.string.setting_text_hint)) },
                supportingText = { Text("${settings.headerText.length}/$MAX_TEXT") },
                textStyle = MaterialTheme.typography.bodyMedium,
                enabled = enabled,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ---------------------------------------------------------------- Aparência
        Section(
            title = R.string.section_look,
            summary = "${stringResource(settings.style.label)} · ${stringResource(settings.charset.label)} · ${settings.columns}",
            initiallyExpanded = true,
        ) {
            SmallLabel(stringResource(R.string.setting_style))
            ChipGroup(ColorStyle.entries, settings.style, { it.label }, enabled) { v -> onChange { it.copy(style = v) } }
            SmallLabel(stringResource(R.string.setting_charset))
            ChipGroup(Charset.entries, settings.charset, { it.label }, enabled) { v -> onChange { it.copy(charset = v) } }
            CompactSlider(
                label = stringResource(R.string.setting_detail, settings.columns),
                value = settings.columns.toFloat(),
                range = 30f..160f,
                enabled = enabled,
            ) { v -> onChange { it.copy(columns = v.roundToInt()) } }
            SwitchRow(stringResource(R.string.setting_invert), settings.invert, enabled) { v -> onChange { it.copy(invert = v) } }
        }

        // ---------------------------------------------------------------- Animação
        Section(
            title = R.string.section_animation,
            summary = "${settings.typingSeconds} s" + if (settings.zoomIntro) " · zoom" else "",
            initiallyExpanded = false,
        ) {
            SwitchRow(stringResource(R.string.setting_zoom), settings.zoomIntro, enabled) { v -> onChange { it.copy(zoomIntro = v) } }
            CompactSlider(
                label = stringResource(R.string.setting_duration, settings.typingSeconds),
                value = settings.typingSeconds.toFloat(),
                range = 3f..30f,
                enabled = enabled,
            ) { v -> onChange { it.copy(typingSeconds = v.roundToInt()) } }
        }

        // ---------------------------------------------------------------- Som
        val musicSummary = when (settings.musicMode) {
            MusicMode.NONE -> stringResource(R.string.music_none)
            MusicMode.GENERATED -> stringResource(settings.musicStyle.label)
            MusicMode.CUSTOM -> customMusic?.name ?: stringResource(R.string.music_custom)
        }
        Section(
            title = R.string.section_sound,
            summary = musicSummary,
            initiallyExpanded = false,
        ) {
            SwitchRow(stringResource(R.string.setting_typing_sound), settings.typingSound, enabled) { v ->
                onChange { it.copy(typingSound = v) }
            }
            SmallLabel(stringResource(R.string.music_label))
            ChipGroup(
                MusicMode.entries,
                settings.musicMode,
                {
                    when (it) {
                        MusicMode.NONE -> R.string.music_none
                        MusicMode.GENERATED -> R.string.music_generated
                        MusicMode.CUSTOM -> R.string.music_custom
                    }
                },
                enabled,
            ) { v ->
                if (v == MusicMode.CUSTOM && customMusic == null) pickMusic()
                else onChange { it.copy(musicMode = v) }
            }

            when (settings.musicMode) {
                MusicMode.NONE -> Unit
                MusicMode.GENERATED -> {
                    ChipGroup(MusicStyle.entries, settings.musicStyle, { it.label }, enabled) { v ->
                        onChange { it.copy(musicStyle = v) }
                    }
                    Hint(stringResource(R.string.music_variation))
                }
                MusicMode.CUSTOM -> {
                    if (customMusic == null) {
                        OutlinedButton(onClick = pickMusic, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.pick_music))
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "♪ ${customMusic.name}",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = pickMusic, enabled = enabled) { Text(stringResource(R.string.change_music)) }
                        }
                        val maxStart = (customMusic.durationSec - 5f).coerceAtLeast(0f)
                        if (maxStart >= 1f) {
                            CompactSlider(
                                label = stringResource(R.string.music_start, formatTime(settings.musicStartSec)),
                                value = settings.musicStartSec.toFloat().coerceAtMost(maxStart),
                                range = 0f..maxStart,
                                enabled = enabled,
                            ) { v -> onChange { it.copy(musicStartSec = v.roundToInt()) } }
                        }
                    }
                    Hint(stringResource(R.string.music_copyright))
                }
            }
        }
    }
}

/** Cartão com título e resumo, que abre e fecha ao tocar. */
@Composable
private fun Section(
    @StringRes title: Int,
    summary: String?,
    initiallyExpanded: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                if (summary != null && !expanded) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(if (expanded) "▴" else "▾", style = MaterialTheme.typography.titleMedium)
        }
        AnimatedVisibility(expanded) {
            Column(
                Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                content = content,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipGroup(
    items: List<T>,
    selected: T,
    label: (T) -> Int,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { item ->
            FilterChip(
                selected = item == selected,
                onClick = { onSelect(item) },
                label = { Text(stringResource(label(item)), style = MaterialTheme.typography.labelMedium) },
                enabled = enabled,
                modifier = Modifier.height(30.dp),
            )
        }
    }
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled, modifier = Modifier.scale(0.8f))
    }
}

@Composable
private fun CompactSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            enabled = enabled,
            modifier = Modifier.height(32.dp),
        )
    }
}

@Composable
private fun SmallLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun formatTime(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)
