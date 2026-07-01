package com.epubreader.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.epubreader.app.R
import com.epubreader.app.data.prefs.ScrollMode
import com.epubreader.app.data.prefs.ThemeMode

/**
 * Reader settings panel (ModalBottomSheet).
 *
 * Sections: Font, Background, Layout, Page turn.
 * Each section uses a [titleSmall] header followed by a [HorizontalDivider]
 * for clear visual separation, matching the established bottom-sheet pattern
 * of [TtsControlPanel].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReaderSettingsPanel(
    fontSize: Float,
    fontFamily: String,
    theme: ThemeMode,
    lineSpacing: Float,
    paragraphSpacing: Float,
    paragraphIndent: Float,
    pageMargins: Float,
    scrollMode: ScrollMode,
    onFontSizeChange: (Float) -> Unit,
    onFontFamilyChange: (String) -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onLineSpacingChange: (Float) -> Unit,
    onParagraphSpacingChange: (Float) -> Unit,
    onParagraphIndentChange: (Float) -> Unit,
    onPageMarginsChange: (Float) -> Unit,
    onScrollModeChange: (ScrollMode) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.Start
        ) {
            // Title
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Tune, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.reader_settings_title),
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(Modifier.height(16.dp))

            // ---- Section 1: Font ----
            Text(
                text = stringResource(R.string.reader_settings_font),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(8.dp))

            // Font family
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = (fontFamily == "sans-serif"),
                    onClick = { onFontFamilyChange("sans-serif") },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) {
                    Text(stringResource(R.string.reader_settings_font_family_sans))
                }
                SegmentedButton(
                    selected = (fontFamily == "serif"),
                    onClick = { onFontFamilyChange("serif") },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) {
                    Text(stringResource(R.string.reader_settings_font_family_serif))
                }
            }

            Spacer(Modifier.height(12.dp))

            // Font size
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.reader_settings_font_size),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${fontSize.toInt()} sp",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Slider(
                value = fontSize,
                onValueChange = onFontSizeChange,
                valueRange = 12f..48f,
                steps = 35
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // ---- Section 2: Background ----
            Text(
                text = stringResource(R.string.reader_settings_theme),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(8.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = (theme == ThemeMode.LIGHT),
                    onClick = { onThemeChange(ThemeMode.LIGHT) },
                    label = { Text(stringResource(R.string.reader_settings_theme_light)) }
                )
                FilterChip(
                    selected = (theme == ThemeMode.DARK),
                    onClick = { onThemeChange(ThemeMode.DARK) },
                    label = { Text(stringResource(R.string.reader_settings_theme_dark)) }
                )
                FilterChip(
                    selected = (theme == ThemeMode.SEPIA),
                    onClick = { onThemeChange(ThemeMode.SEPIA) },
                    label = { Text(stringResource(R.string.reader_settings_theme_sepia)) }
                )
                FilterChip(
                    selected = (theme == ThemeMode.SYSTEM),
                    onClick = { onThemeChange(ThemeMode.SYSTEM) },
                    label = { Text(stringResource(R.string.reader_settings_theme_system)) }
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // ---- Section 3: Layout ----
            Text(
                text = stringResource(R.string.reader_settings_layout),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(8.dp))

            // Line spacing
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.reader_settings_line_spacing),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "%.1f".format(lineSpacing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Slider(
                value = lineSpacing,
                onValueChange = onLineSpacingChange,
                valueRange = 1.0f..2.0f,
                steps = 9
            )

            // Paragraph spacing
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.reader_settings_paragraph_spacing),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${(paragraphSpacing * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Slider(
                value = paragraphSpacing,
                onValueChange = onParagraphSpacingChange,
                valueRange = 0f..2f,
                steps = 19
            )

            // Paragraph indent
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.reader_settings_paragraph_indent),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${(paragraphIndent * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Slider(
                value = paragraphIndent,
                onValueChange = onParagraphIndentChange,
                valueRange = 0f..3f,
                steps = 29
            )

            // Page margins
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.reader_settings_page_margins),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "%.1f".format(pageMargins),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Slider(
                value = pageMargins,
                onValueChange = onPageMarginsChange,
                valueRange = 0f..4f,
                steps = 39
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // ---- Section 4: Page turn ----
            Text(
                text = stringResource(R.string.reader_settings_page_turn),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(8.dp))

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = (scrollMode == ScrollMode.PAGINATED),
                    onClick = { onScrollModeChange(ScrollMode.PAGINATED) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
                ) {
                    Text(stringResource(R.string.reader_settings_pagination))
                }
                SegmentedButton(
                    selected = (scrollMode == ScrollMode.SCROLLED_PER_CHAPTER),
                    onClick = { onScrollModeChange(ScrollMode.SCROLLED_PER_CHAPTER) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
                ) {
                    Text(stringResource(R.string.reader_settings_scroll))
                }
                SegmentedButton(
                    selected = (scrollMode == ScrollMode.CONTINUOUS),
                    onClick = { onScrollModeChange(ScrollMode.CONTINUOUS) },
                    shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
                ) {
                    // Small primary-colored dot flags the newly-introduced option.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(stringResource(R.string.reader_settings_continuous))
                        Spacer(Modifier.width(4.dp))
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = CircleShape
                                )
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
