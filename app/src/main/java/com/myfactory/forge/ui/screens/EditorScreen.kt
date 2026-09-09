package com.myfactory.forge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myfactory.forge.R
import com.myfactory.forge.core.capability.Capabilities
import com.myfactory.forge.core.files.WorkspaceFs
import com.myfactory.forge.ui.components.SyntaxHighlighter
import com.myfactory.forge.ui.components.formatBytes
import com.myfactory.forge.ui.theme.CodeTextStyle
import com.myfactory.forge.ui.theme.LocalCodeColors

/**
 * The code editor.
 *
 * Highlighting runs through a VisualTransformation rather than by rewriting
 * the field's value, so the caret and selection stay on the original text and
 * an edit never moves the cursor unexpectedly. On the LIGHTWEIGHT tier live
 * highlighting is off, because re-tokenising on every keystroke is what makes
 * a cheap device feel broken.
 */
@Composable
fun EditorScreen(
    workspace: WorkspaceFs,
    relativePath: String,
    capabilities: Capabilities,
    fontScale: Float,
    softWrap: Boolean,
    showLineNumbers: Boolean,
    modifier: Modifier = Modifier,
    onSaved: () -> Unit = {},
) {
    val codeColors = LocalCodeColors.current
    var value by remember(relativePath) { mutableStateOf(TextFieldValue("")) }
    var loadError by remember(relativePath) { mutableStateOf<String?>(null) }
    var readOnly by remember(relativePath) { mutableStateOf(false) }
    var dirty by remember(relativePath) { mutableStateOf(false) }
    var savedNotice by remember { mutableStateOf(false) }

    val language = remember(relativePath) {
        SyntaxHighlighter.Language.forFileName(relativePath)
    }

    LaunchedEffect(relativePath) {
        runCatching { workspace.read(relativePath, capabilities.editorFileSizeLimitBytes) }
            .onSuccess { content ->
                when {
                    content.isBinary -> {
                        loadError = null
                        readOnly = true
                        value = TextFieldValue("")
                        loadError = "binary"
                    }
                    content.truncated -> {
                        readOnly = true
                        value = TextFieldValue(content.text)
                        loadError = "truncated:${content.sizeBytes}"
                    }
                    else -> {
                        readOnly = false
                        loadError = null
                        value = TextFieldValue(content.text)
                    }
                }
            }
            .onFailure { loadError = it.message }
    }

    val transformation = remember(language, capabilities.liveSyntaxHighlighting, codeColors) {
        VisualTransformation { text ->
            TransformedText(
                SyntaxHighlighter.highlight(
                    text = text.text,
                    language = language,
                    colors = codeColors,
                    enabled = capabilities.liveSyntaxHighlighting,
                ),
                // Offsets are unchanged: only spans are added, never characters.
                androidx.compose.ui.text.input.OffsetMapping.Identity,
            )
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        EditorBar(
            path = relativePath,
            dirty = dirty,
            saved = savedNotice,
            readOnly = readOnly,
            onSave = {
                runCatching { workspace.write(relativePath, value.text) }
                    .onSuccess {
                        dirty = false
                        savedNotice = true
                        onSaved()
                    }
            },
        )

        when {
            loadError == "binary" -> Text(
                text = stringResource(R.string.editor_binary),
                modifier = Modifier.padding(16.dp),
            )

            loadError?.startsWith("truncated:") == true -> {
                val size = loadError!!.substringAfter(':').toLongOrNull() ?: 0
                Text(
                    text = stringResource(R.string.editor_too_large, formatBytes(size)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                EditorField(
                    value = value,
                    onValueChange = {},
                    readOnly = true,
                    transformation = transformation,
                    fontScale = fontScale,
                    softWrap = softWrap,
                    showLineNumbers = showLineNumbers,
                    modifier = Modifier.weight(1f),
                )
            }

            loadError != null -> Text(
                text = loadError!!,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp),
            )

            else -> EditorField(
                value = value,
                onValueChange = {
                    value = it
                    dirty = true
                    savedNotice = false
                },
                readOnly = readOnly,
                transformation = transformation,
                fontScale = fontScale,
                softWrap = softWrap,
                showLineNumbers = showLineNumbers,
                modifier = Modifier.weight(1f),
            )
        }

        StatusBar(value)
    }
}

@Composable
private fun EditorBar(
    path: String,
    dirty: Boolean,
    saved: Boolean,
    readOnly: Boolean,
    onSave: () -> Unit,
) {
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = path.substringAfterLast('/'),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = when {
                        dirty -> stringResource(R.string.editor_unsaved)
                        saved -> stringResource(R.string.editor_saved)
                        else -> path
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!readOnly) {
                IconButton(
                    onClick = onSave,
                    enabled = dirty,
                    modifier = Modifier.semantics {
                        contentDescription = "Save this file"
                    },
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun EditorField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    readOnly: Boolean,
    transformation: VisualTransformation,
    fontScale: Float,
    softWrap: Boolean,
    showLineNumbers: Boolean,
    modifier: Modifier = Modifier,
) {
    val codeColors = LocalCodeColors.current
    val style = CodeTextStyle.copy(
        fontSize = CodeTextStyle.fontSize * fontScale,
        lineHeight = CodeTextStyle.lineHeight * fontScale,
        color = MaterialTheme.colorScheme.onSurface,
    )
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(codeColors.editorBackground)
            .verticalScroll(verticalScroll)
            .imePadding(),
    ) {
        if (showLineNumbers) {
            val lineCount = remember(value.text) { value.text.count { it == NEWLINE } + 1 }
            Column(
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp),
                horizontalAlignment = Alignment.End,
            ) {
                for (line in 1..lineCount) {
                    Text(text = "$line", style = style, color = codeColors.lineNumber)
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .then(if (softWrap) Modifier else Modifier.horizontalScroll(horizontalScroll)),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                readOnly = readOnly,
                textStyle = style,
                visualTransformation = transformation,
                cursorBrush = androidx.compose.ui.graphics.SolidColor(
                    MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
            )
        }
    }
}

@Composable
private fun StatusBar(value: TextFieldValue) {
    val position = remember(value.selection, value.text) { lineAndColumn(value) }
    Surface(tonalElevation = 1.dp) {
        Text(
            text = stringResource(R.string.editor_line_column, position.first, position.second),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

private fun lineAndColumn(value: TextFieldValue): Pair<Int, Int> {
    val offset = value.selection.start.coerceIn(0, value.text.length)
    var line = 1
    var lastBreak = -1
    for (index in 0 until offset) {
        if (value.text[index] == NEWLINE) {
            line++
            lastBreak = index
        }
    }
    return line to (offset - lastBreak)
}

private val NEWLINE: Char = 10.toChar()
