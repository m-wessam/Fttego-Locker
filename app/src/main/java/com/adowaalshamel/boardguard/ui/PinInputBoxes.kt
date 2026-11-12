package com.adowaalshamel.boardguard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Reusable 4-digit PIN input component featuring four distinct boxes
 * with focus handling, backspace support, masked characters,
 * error state styling, and keyboard dismissal on Done.
 */
@Composable
fun PinInputBoxes(
    pinValue: String,
    onPinChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    boxSize: Dp = 64.dp,
    boxSpacing: Dp = 12.dp,
    useSoftKeyboard: Boolean = true
) {

    val hiddenTextFieldFocusRequester = remember {
        FocusRequester()
    }

    var isTextFieldFocused by remember {
        mutableStateOf(false)
    }

    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {

        if (useSoftKeyboard) {

            // Invisible TextField for handling numeric keyboard input
            BasicTextField(
                value = pinValue,

                onValueChange = { newValue ->
                    val filtered = newValue
                        .filter { it.isDigit() }
                        .take(4)

                    onPinChange(filtered)
                },

                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done
                ),

                // Hide the keyboard when the Done button is pressed
                keyboardActions = KeyboardActions(
                    onDone = {
                        keyboardController?.hide()
                        focusManager.clearFocus()
                    }
                ),

                modifier = Modifier
                    .focusRequester(hiddenTextFieldFocusRequester)
                    .onFocusChanged { focusState ->
                        isTextFieldFocused = focusState.isFocused
                    }
                    .size(1.dp)
            )
        }

        // Display four PIN boxes
        Row(
            horizontalArrangement = Arrangement.spacedBy(boxSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {

            for (index in 0 until 4) {

                val digit = pinValue.getOrNull(index)

                val isCurrentBoxTarget =
                    pinValue.length == index ||
                            (pinValue.length == 4 && index == 3)

                val isFocused = if (useSoftKeyboard) {
                    isTextFieldFocused && isCurrentBoxTarget
                } else {
                    isCurrentBoxTarget
                }

                val borderColor = when {
                    isError -> MaterialTheme.colorScheme.error
                    isFocused -> Color(0xFF1E88E5)
                    else -> Color(0xFFE0E0E0)
                }

                val backgroundColor = when {
                    isError -> Color(0xFFFFF0F0)
                    else -> Color.White
                }

                Box(
                    modifier = Modifier
                        .size(boxSize)
                        .clip(RoundedCornerShape(12.dp))
                        .background(backgroundColor)
                        .border(
                            width = if (isFocused || isError) {
                                2.dp
                            } else {
                                1.dp
                            },
                            color = borderColor,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable {
                            if (useSoftKeyboard) {
                                hiddenTextFieldFocusRequester.requestFocus()
                            }
                        },

                    contentAlignment = Alignment.Center
                ) {

                    Text(
                        text = if (digit != null) "•" else "",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            Color.Black
                        },
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}