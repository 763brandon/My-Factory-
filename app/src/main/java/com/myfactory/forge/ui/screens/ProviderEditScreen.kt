package com.myfactory.forge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.myfactory.forge.R
import com.myfactory.forge.core.ai.ProviderConfig
import com.myfactory.forge.core.ai.ProviderId
import java.util.UUID

/** What the user typed, plus the key, which is handled separately on save. */
data class ProviderDraft(
    val config: ProviderConfig,
    val apiKey: String,
    val keyAlreadyStored: Boolean,
)

/**
 * Add or edit one AI endpoint.
 *
 * The API key field is write-only by design. An existing key is never read
 * back into the form: the screen says one is stored and leaves the field
 * blank, so a shoulder-surfer or a screenshot cannot capture it, and leaving
 * the field empty on save keeps the stored key rather than clearing it.
 */
@Composable
fun ProviderEditScreen(
    existing: ProviderConfig?,
    hasStoredKey: Boolean,
    defaultBaseUrlFor: (ProviderId) -> String,
    suggestedModelsFor: (ProviderId) -> List<String>,
    requiresKeyFor: (ProviderId) -> Boolean,
    testResult: String?,
    onTest: (ProviderDraft) -> Unit,
    onSave: (ProviderDraft) -> Unit,
    onDelete: (() -> Unit)?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var providerId by remember {
        mutableStateOf(existing?.providerId ?: ProviderId.ANTHROPIC)
    }
    var displayName by remember { mutableStateOf(existing?.displayName ?: "") }
    var baseUrl by remember {
        mutableStateOf(existing?.baseUrl ?: defaultBaseUrlFor(ProviderId.ANTHROPIC))
    }
    var model by remember { mutableStateOf(existing?.model ?: "") }
    var apiKey by remember { mutableStateOf("") }
    var maxTokens by remember {
        mutableStateOf((existing?.maxOutputTokens ?: 4096).toString())
    }

    fun draft(): ProviderDraft {
        val alias = existing?.apiKeyAlias ?: "key-${UUID.randomUUID()}"
        return ProviderDraft(
            config = ProviderConfig(
                id = existing?.id ?: UUID.randomUUID().toString(),
                providerId = providerId,
                displayName = displayName.ifBlank { providerId.displayName },
                baseUrl = baseUrl.trim().trimEnd('/'),
                model = model.trim(),
                apiKeyAlias = alias,
                maxOutputTokens = maxTokens.toIntOrNull()?.coerceIn(256, 200_000) ?: 4096,
                extraHeaders = existing?.extraHeaders ?: emptyMap(),
            ),
            apiKey = apiKey,
            keyAlreadyStored = hasStoredKey,
        )
    }

    val keyRequired = requiresKeyFor(providerId)
    val complete = baseUrl.isNotBlank() &&
        model.isNotBlank() &&
        (!keyRequired || apiKey.isNotBlank() || hasStoredKey)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_provider_type),
            style = MaterialTheme.typography.titleSmall,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ProviderId.entries.forEach { option ->
                FilterChip(
                    selected = providerId == option,
                    onClick = {
                        providerId = option
                        // Only overwrite fields the user has not typed into,
                        // so switching type to compare does not lose work.
                        if (baseUrl.isBlank() || baseUrl in ProviderId.entries.map(defaultBaseUrlFor)) {
                            baseUrl = defaultBaseUrlFor(option)
                        }
                        if (displayName.isBlank()) displayName = option.displayName
                    },
                    label = { Text(option.displayName) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text(stringResource(R.string.settings_provider_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text(stringResource(R.string.settings_base_url)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text(stringResource(R.string.settings_model)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val suggestions = suggestedModelsFor(providerId)
        if (suggestions.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                suggestions.forEach { suggestion ->
                    TextButton(onClick = { model = suggestion }) { Text(suggestion) }
                }
            }
        }

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text(stringResource(R.string.settings_api_key)) },
            placeholder = { Text(stringResource(R.string.settings_api_key_hint)) },
            singleLine = true,
            // Never echoed: a key in plain view is a key in a screenshot.
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = when {
                hasStoredKey -> stringResource(R.string.settings_api_key_saved)
                !keyRequired -> stringResource(R.string.settings_api_key_optional)
                else -> ""
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = maxTokens,
            onValueChange = { maxTokens = it.filter(Char::isDigit) },
            label = { Text(stringResource(R.string.settings_max_tokens)) },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Number,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        testResult?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onSave(draft()) },
                enabled = complete,
            ) {
                Text(stringResource(R.string.settings_save))
            }
            OutlinedButton(
                onClick = { onTest(draft()) },
                enabled = complete,
            ) {
                Text(stringResource(R.string.settings_test))
            }
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.action_cancel))
            }
        }

        if (onDelete != null) {
            TextButton(onClick = onDelete) {
                Text(stringResource(R.string.settings_delete))
            }
        }
    }
}
