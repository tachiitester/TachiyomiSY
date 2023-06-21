package eu.kanade.presentation.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.R
import kotlin.math.roundToInt

@Composable
fun ChapterNavigator(
    isRtl: Boolean,
    isVerticalSlider: Boolean,
    onNextChapter: () -> Unit,
    enabledNext: Boolean,
    onPreviousChapter: () -> Unit,
    enabledPrevious: Boolean,
    currentPage: Int,
    // SY -->
    currentPageText: String,
    // SY <--
    totalPages: Int,
    onSliderValueChange: (Int) -> Unit,
) {
    // SY -->
    if (isVerticalSlider) {
        ChapterNavigatorVert(
            onNextChapter = onNextChapter,
            enabledNext = enabledNext,
            onPreviousChapter = onPreviousChapter,
            enabledPrevious = enabledPrevious,
            currentPage = currentPage,
            currentPageText = currentPageText,
            totalPages = totalPages,
            onSliderValueChange = onSliderValueChange,
        )
        return
    }
    // SY <--
    val isTabletUi = isTabletUi()
    val horizontalPadding = if (isTabletUi) 24.dp else 16.dp
    val layoutDirection = if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    val haptic = LocalHapticFeedback.current

    // We explicitly handle direction based on the reader viewer rather than the system direction
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Match with toolbar background color set in ReaderActivity
            val backgroundColor = MaterialTheme.colorScheme
                .surfaceColorAtElevation(3.dp)
                .copy(alpha = if (isSystemInDarkTheme()) 0.9f else 0.95f)
            val buttonColor = IconButtonDefaults.filledIconButtonColors(
                containerColor = backgroundColor,
                disabledContainerColor = backgroundColor,
            )
            FilledIconButton(
                enabled = if (isRtl) enabledNext else enabledPrevious,
                onClick = if (isRtl) onNextChapter else onPreviousChapter,
                colors = buttonColor,
            ) {
                Icon(
                    imageVector = Icons.Outlined.SkipPrevious,
                    contentDescription = stringResource(if (isRtl) R.string.action_next_chapter else R.string.action_previous_chapter),
                )
            }

            if (totalPages > 1) {
                CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(24.dp))
                            .background(backgroundColor)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // SY -->
                        Text(text = currentPageText)
                        // SY <--

                        val interactionSource = remember { MutableInteractionSource() }
                        val sliderDragged by interactionSource.collectIsDraggedAsState()
                        LaunchedEffect(currentPage) {
                            if (sliderDragged) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                        Slider(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                            value = currentPage.toFloat(),
                            valueRange = 1f..totalPages.toFloat(),
                            steps = totalPages - 2,
                            onValueChange = {
                                onSliderValueChange(it.roundToInt() - 1)
                            },
                            interactionSource = interactionSource,
                        )

                        Text(text = totalPages.toString())
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }

            FilledIconButton(
                enabled = if (isRtl) enabledPrevious else enabledNext,
                onClick = if (isRtl) onPreviousChapter else onNextChapter,
                colors = buttonColor,
            ) {
                Icon(
                    imageVector = Icons.Outlined.SkipNext,
                    contentDescription = stringResource(if (isRtl) R.string.action_previous_chapter else R.string.action_next_chapter),
                )
            }
        }
    }
}

@Composable
fun ChapterNavigatorVert(
    onNextChapter: () -> Unit,
    enabledNext: Boolean,
    onPreviousChapter: () -> Unit,
    enabledPrevious: Boolean,
    currentPage: Int,
    // SY -->
    currentPageText: String,
    // SY <--
    totalPages: Int,
    onSliderValueChange: (Int) -> Unit,
) {
    val isTabletUi = isTabletUi()
    val verticalPadding = if (isTabletUi) 24.dp else 16.dp

    val haptic = LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .padding(vertical = verticalPadding, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Match with toolbar background color set in ReaderActivity
        val backgroundColor = MaterialTheme.colorScheme
            .surfaceColorAtElevation(3.dp)
            .copy(alpha = if (isSystemInDarkTheme()) 0.9f else 0.95f)

        val buttonColor = IconButtonDefaults.filledIconButtonColors(
            containerColor = backgroundColor,
            disabledContainerColor = backgroundColor,
        )
        FilledIconButton(
            enabled = enabledPrevious,
            onClick = onPreviousChapter,
            colors = buttonColor,
        ) {
            Icon(
                imageVector = Icons.Outlined.SkipPrevious,
                contentDescription = stringResource(R.string.action_previous_chapter),
                modifier = Modifier.rotate(90f),
            )
        }

        if (totalPages > 1) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(backgroundColor)
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // SY -->
                Text(text = currentPageText)
                // SY <--

                val interactionSource = remember { MutableInteractionSource() }
                val sliderDragged by interactionSource.collectIsDraggedAsState()
                LaunchedEffect(currentPage) {
                    if (sliderDragged) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }
                Slider(
                    modifier = Modifier
                        .padding(vertical = 8.dp)
                        .graphicsLayer {
                            rotationZ = 90f
                            transformOrigin = TransformOrigin(0f, 0f)
                        }
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(
                                Constraints(
                                    minWidth = constraints.minHeight,
                                    maxWidth = constraints.maxHeight,
                                    minHeight = constraints.minWidth,
                                    maxHeight = constraints.maxWidth,
                                ),
                            )
                            layout(placeable.height, placeable.width) {
                                placeable.place(0, -placeable.height)
                            }
                        }
                        .weight(1f),
                    value = currentPage.toFloat(),
                    valueRange = 1f..totalPages.toFloat(),
                    steps = totalPages,
                    onValueChange = {
                        onSliderValueChange(it.roundToInt() - 1)
                    },
                    interactionSource = interactionSource,
                )

                Text(text = totalPages.toString())
            }
        } else {
            Spacer(Modifier.weight(1f))
        }

        FilledIconButton(
            enabled = enabledNext,
            onClick = onNextChapter,
            colors = buttonColor,
        ) {
            Icon(
                imageVector = Icons.Outlined.SkipNext,
                contentDescription = stringResource(R.string.action_next_chapter),
                modifier = Modifier.rotate(90f),
            )
        }
    }
}
