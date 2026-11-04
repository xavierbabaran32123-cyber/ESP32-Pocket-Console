package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.game.local.ConsoleButton

@Composable
fun VirtualGamepadOverlay(
    isLandscape: Boolean,
    onButtonPress: (ConsoleButton) -> Unit,
    onButtonRelease: (ConsoleButton) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (isLandscape) 16.dp else 12.dp,
                vertical = if (isLandscape) 8.dp else 10.dp
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        // Left D-Pad cluster
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            DpadKey(
                icon = Icons.Filled.KeyboardArrowUp,
                description = stringResource(R.string.dpad_up),
                tag = "dpad_up_button",
                onPress = { onButtonPress(ConsoleButton.UP) },
                onRelease = { onButtonRelease(ConsoleButton.UP) }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(42.dp)) {
                DpadKey(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    description = stringResource(R.string.dpad_left),
                    tag = "dpad_left_button",
                    onPress = { onButtonPress(ConsoleButton.LEFT) },
                    onRelease = { onButtonRelease(ConsoleButton.LEFT) }
                )
                DpadKey(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    description = stringResource(R.string.dpad_right),
                    tag = "dpad_right_button",
                    onPress = { onButtonPress(ConsoleButton.RIGHT) },
                    onRelease = { onButtonRelease(ConsoleButton.RIGHT) }
                )
            }
            DpadKey(
                icon = Icons.Filled.KeyboardArrowDown,
                description = stringResource(R.string.dpad_down),
                tag = "dpad_down_button",
                onPress = { onButtonPress(ConsoleButton.DOWN) },
                onRelease = { onButtonRelease(ConsoleButton.DOWN) }
            )
        }

        // Right Action Buttons (PAUSE + B + A)
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ArcadeLabelKey(
                label = stringResource(R.string.btn_pause_label),
                shape = MaterialTheme.shapes.small,
                width = 76.dp,
                height = 48.dp,
                baseColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                activeColor = MaterialTheme.colorScheme.primaryContainer,
                borderColor = MaterialTheme.colorScheme.outline,
                textColor = MaterialTheme.colorScheme.onSurface,
                tag = "action_pause_button",
                onPress = { onButtonPress(ConsoleButton.PAUSE) },
                onRelease = { onButtonRelease(ConsoleButton.PAUSE) }
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(modifier = Modifier.height(18.dp))
                    ArcadeLabelKey(
                        label = stringResource(R.string.btn_b_label),
                        shape = CircleShape,
                        width = 58.dp,
                        height = 58.dp,
                        baseColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
                        activeColor = MaterialTheme.colorScheme.secondary,
                        borderColor = MaterialTheme.colorScheme.secondary,
                        textColor = MaterialTheme.colorScheme.secondary,
                        tag = "action_b_button",
                        onPress = { onButtonPress(ConsoleButton.B) },
                        onRelease = { onButtonRelease(ConsoleButton.B) }
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ArcadeLabelKey(
                        label = stringResource(R.string.btn_a_label),
                        shape = CircleShape,
                        width = 62.dp,
                        height = 62.dp,
                        baseColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.88f),
                        activeColor = MaterialTheme.colorScheme.tertiary,
                        borderColor = MaterialTheme.colorScheme.primary,
                        textColor = MaterialTheme.colorScheme.onPrimary,
                        tag = "action_a_button",
                        onPress = { onButtonPress(ConsoleButton.A) },
                        onRelease = { onButtonRelease(ConsoleButton.A) }
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                }
            }
        }
    }
}

@Composable
private fun DpadKey(
    icon: ImageVector,
    description: String,
    tag: String,
    onPress: () -> Unit,
    onRelease: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }

    val bgColor = if (pressed) {
        MaterialTheme.colorScheme.secondary.copy(alpha = 0.9f)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.86f)
    }
    val fgColor = if (pressed) {
        MaterialTheme.colorScheme.onSecondary
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(50.dp)
            .clip(MaterialTheme.shapes.small)
            .background(bgColor)
            .border(1.5.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
            .semantics {
                contentDescription = description
                role = Role.Button
            }
            .testTag(tag)
            .pointerInput(tag) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onPress()
                        try {
                            tryAwaitRelease()
                        } finally {
                            pressed = false
                            onRelease()
                        }
                    }
                )
            }
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = fgColor,
            modifier = Modifier.size(28.dp)
        )
    }
}

@Composable
private fun ArcadeLabelKey(
    label: String,
    shape: Shape,
    width: Dp,
    height: Dp,
    baseColor: Color,
    activeColor: Color,
    borderColor: Color,
    textColor: Color,
    tag: String,
    onPress: () -> Unit,
    onRelease: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(shape)
            .background(if (pressed) activeColor else baseColor)
            .border(1.5.dp, borderColor, shape)
            .semantics {
                contentDescription = label
                role = Role.Button
            }
            .testTag(tag)
            .pointerInput(tag) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onPress()
                        try {
                            tryAwaitRelease()
                        } finally {
                            pressed = false
                            onRelease()
                        }
                    }
                )
            }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (pressed) MaterialTheme.colorScheme.onTertiary else textColor
        )
    }
}
