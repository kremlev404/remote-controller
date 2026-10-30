package com.remotecontroller.client.core

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.remotecontroller.shared.model.typingEdit

/**
 * Soft keyboards report no key when the field is empty, so backspace would stop reaching the PC.
 * The field keeps this invisible character at the start: losing it is that backspace.
 */
private const val ANCHOR = "​"

private val EmptyField = TextFieldValue(ANCHOR, TextRange(ANCHOR.length))

/** Every edit reaches the PC as it happens. Enter is sent as a key and starts a fresh line. */
@Composable
fun TypingRow(
    onType: (text: String, backspaces: Int) -> Unit,
    onEnter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var field by remember { mutableStateOf(EmptyField) }
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)

    BasicTextField(
        value = field,
        onValueChange = { next ->
            val before = field.text.removePrefix(ANCHOR)
            val anchored = next.text.startsWith(ANCHOR)
            val after = next.text.removePrefix(ANCHOR)
            val edit = typingEdit(before, after)
            when {
                edit != null -> onType(edit.text, edit.backspaces)
                !anchored -> onType("", 1)
            }
            field = if (anchored) {
                next.copy(selection = next.selection.afterAnchor())
            } else {
                TextFieldValue(ANCHOR + after, TextRange(ANCHOR.length + after.length))
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(shape)
            .border(1.dp, colors.outline, shape)
            .background(colors.surface),
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
        singleLine = true,
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(
            onSend = {
                onEnter()
                field = EmptyField
            },
        ),
        decorationBox = { innerField ->
            Box(
                modifier = Modifier.padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (field.text == ANCHOR) {
                    Text(
                        text = "Type on the PC",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurfaceVariant,
                    )
                }
                innerField()
            }
        },
    )
}

private fun TextRange.afterAnchor(): TextRange =
    TextRange(start.coerceAtLeast(ANCHOR.length), end.coerceAtLeast(ANCHOR.length))
