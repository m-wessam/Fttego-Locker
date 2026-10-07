package com.adowaalshamel.boardguard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adowaalshamel.boardguard.R

/**
 * Large 3x4 custom numeric keypad for LockActivity and Admin PIN Authentication.
 * Features touch targets sized for 75-inch displays and custom OK/Backspace buttons.
 */
@Composable
fun CustomNumericKeypad(
    onDigitClick: (Char) -> Unit,
    onDeleteClick: () -> Unit,
    onOkClick: () -> Unit,
    isOkEnabled: Boolean,
    modifier: Modifier = Modifier,
    buttonColor: Color = Color(0xFF333A42),
    okButtonColor: Color = Color(0xFF1E88E5),
    textColor: Color = Color.White,
    buttonHeight: Dp = 68.dp,
    spacing: Dp = 10.dp
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing)
    ) {
        val rows = listOf(
            listOf('1', '2', '3'),
            listOf('4', '5', '6'),
            listOf('7', '8', '9')
        )

        for (row in rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing)
            ) {
                for (digit in row) {
                    KeypadButton(
                        onClick = { onDigitClick(digit) },
                        modifier = Modifier
                            .weight(1f)
                            .height(buttonHeight),
                        backgroundColor = buttonColor
                    ) {
                        Text(
                            text = digit.toString(),
                            fontSize = 28.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = textColor
                        )
                    }
                }
            }
        }

        // Bottom row: Backspace, 0, OK
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing)
        ) {
            // Delete button
            KeypadButton(
                onClick = onDeleteClick,
                modifier = Modifier
                    .weight(1f)
                    .height(buttonHeight),
                backgroundColor = buttonColor
            ) {
                Text(
                    text = "⌫",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor
                )
            }

            // Zero button
            KeypadButton(
                onClick = { onDigitClick('0') },
                modifier = Modifier
                    .weight(1f)
                    .height(buttonHeight),
                backgroundColor = buttonColor
            ) {
                Text(
                    text = "0",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor
                )
            }

            // OK button
            KeypadButton(
                onClick = { if (isOkEnabled) onOkClick() },
                modifier = Modifier
                    .weight(1f)
                    .height(buttonHeight),
                backgroundColor = if (isOkEnabled) okButtonColor else okButtonColor.copy(alpha = 0.4f),
                enabled = isOkEnabled
            ) {
                Text(
                    text = "OK",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isOkEnabled) Color.White else Color.White.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun KeypadButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: Color,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
