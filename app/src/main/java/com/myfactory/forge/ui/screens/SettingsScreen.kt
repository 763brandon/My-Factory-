package com.myfactory.forge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.myfactory.forge.R
import com.myfactory.forge.core.ai.ProviderConfig
import com.myfactory.forge.core.capability.Capabilities
import com.myfactory.forge.core.capability.DeviceProfile
import com.myfactory.forge.core.capability.TierOverride
import com.myfactory.forge.core.settings.AppSettings
import com.myfactory.forge.ui.components.DetailRow
import com.myfactory.forge.ui.components.formatBytes

@Composable
fun SettingsScreen(
    settings: AppSettings,
    capabilities: Capabilities,
    profile: DeviceProfile,
    providers: List<ProviderConfig>,
    activeProviderId: String?,
    versionName: String,
    keystoreWarning: String?,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onAddProvider: () -> Unit,
    onEditProvider: (ProviderConfig) -> Unit,
    onSetActiveProvider: (ProviderConfig) -> Unit,
    onOpenAuditLog: () -> Unit,
    onClearKeys: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // ------------------------------------------------------- providers
        Section(stringResource(R.string.settings_providers)) {
            if (providers.isEmpty()) {
                Text(
                    text = stringResource(R.string.chat_no_provider_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            for (provider in providers) {
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(provider.displayName, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = "${provider.model}  ·  ${provider.destinationHost}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            FilterChip(
                                selected = provider.id == activeProviderId,
                                onClick = { onSetActiveProvider(provider) },
                                label = {
                                    Text(
                                        stringResource(
                                            if (provider.id == activeProviderId) {
                                                R.string.settings_active
                                            } else {
                                                R.string.settings_set_active
                                            },
                                        ),
                                    )
                                },
                            )
                        }
                        TextButton(onClick = { onEditProvider(provider) }) {
                            Text(stringResource(R.string.settings_save))
                        }
                    }
                }
            }
            OutlinedButton(onClick = onAddProvider) {
                Text(stringResource(R.string.settings_add_provider))
            }
            keystoreWarning?.let {
                Text(
                    text = stringResource(R.string.settings_keystore_warning, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // ---------------------------------------------------------- device
        Section(stringResource(R.string.settings_device)) {
            DetailRow(
                stringResource(R.string.settings_abi),
                capabilities.primaryAbi.id,
            )
            DetailRow(
                stringResource(R.string.settings_ram),
                formatBytes(profile.totalRamBytes),
            )
            DetailRow(
                stringResource(R.string.settings_storage),
                formatBytes(profile.freeDataBytes),
            )
            DetailRow(
                stringResource(R.string.settings_linux_runtime),
                capabilities.linuxStrategy.name,
            )

            Text(
                text = stringResource(R.string.settings_tier),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TierOverride.entries.forEach { option ->
                    FilterChip(
                        selected = settings.tierOverride == option,
                        onClick = { onUpdateSettings { it.copy(tierOverride = option) } },
                        label = {
                            Text(
                                stringResource(
                                    when (option) {
                                        TierOverride.AUTO -> R.string.settings_tier_auto
                                        TierOverride.FORCE_FULL -> R.string.settings_tier_full
                                        TierOverride.FORCE_LIGHTWEIGHT ->
                                            R.string.settings_tier_lightweight
                                    },
                                ),
                            )
                        },
                    )
                }
            }
            Text(
                text = stringResource(R.string.settings_tier_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // The detection trail, verbatim. On an unusual device this is what
            // makes a bug report actionable.
            if (capabilities.reasons.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.settings_detection_notes),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                for (reason in capabilities.reasons) {
                    Text(
                        text = "• $reason",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // --------------------------------------------------------- privacy
        Section(stringResource(R.string.settings_privacy)) {
            SwitchRow(
                title = stringResource(R.string.settings_auto_checkpoint),
                body = stringResource(R.string.settings_auto_checkpoint_body),
                checked = settings.autoCheckpointBeforeAgentWrites,
                onChange = { value ->
                    onUpdateSettings { it.copy(autoCheckpointBeforeAgentWrites = value) }
                },
            )
            SwitchRow(
                title = stringResource(R.string.settings_session_approvals),
                body = stringResource(R.string.settings_session_approvals_body),
                checked = settings.allowSessionApprovals,
                onChange = { value ->
                    onUpdateSettings { it.copy(allowSessionApprovals = value) }
                },
            )
            SwitchRow(
                title = stringResource(R.string.settings_diagnostics),
                body = stringResource(R.string.settings_diagnostics_body),
                checked = settings.diagnosticsEnabled,
                onChange = { value -> onUpdateSettings { it.copy(diagnosticsEnabled = value) } },
            )
            TextButton(onClick = onOpenAuditLog) {
                Text(stringResource(R.string.settings_audit_log))
            }
            Text(
                text = stringResource(R.string.settings_audit_log_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onClearKeys) {
                Text(stringResource(R.string.settings_clear_keys))
            }
        }

        // ------------------------------------------------------ appearance
        Section(stringResource(R.string.settings_appearance)) {
            SwitchRow(
                title = stringResource(R.string.settings_soft_wrap),
                body = "",
                checked = settings.editorSoftWrap,
                onChange = { value -> onUpdateSettings { it.copy(editorSoftWrap = value) } },
            )
            SwitchRow(
                title = stringResource(R.string.settings_line_numbers),
                body = "",
                checked = settings.editorShowLineNumbers,
                onChange = { value -> onUpdateSettings { it.copy(editorShowLineNumbers = value) } },
            )
        }

        // ----------------------------------------------------------- about
        Section(stringResource(R.string.settings_about)) {
            Text(stringResource(R.string.settings_version, versionName))
            Text(
                text = stringResource(R.string.settings_licence),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.settings_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        HorizontalDivider()
        content()
    }
}

@Composable
private fun SwitchRow(
    title: String,
    body: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (body.isNotEmpty()) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
