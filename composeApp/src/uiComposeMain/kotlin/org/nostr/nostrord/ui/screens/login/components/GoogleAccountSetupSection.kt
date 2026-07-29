package org.nostr.nostrord.ui.screens.login.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nostr.nostrord.auth.pomegranate.pomegranateOperatorLabel
import org.nostr.nostrord.ui.Identifier
import org.nostr.nostrord.ui.components.IdentifierRow
import org.nostr.nostrord.ui.components.buttons.AppButton
import org.nostr.nostrord.ui.components.buttons.AppButtonSize
import org.nostr.nostrord.ui.components.buttons.AppButtonVariant
import org.nostr.nostrord.ui.components.cards.InfoCard
import org.nostr.nostrord.ui.components.forms.AppSearchField
import org.nostr.nostrord.ui.components.forms.InputSize
import org.nostr.nostrord.ui.screens.login.GoogleAccountSetup
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.utils.rememberTextFileSaver
import org.nostr.nostrord.utils.rememberTextSharer
import org.nostr.nostrord.utils.supportsNativeShare
import org.nostr.nostrord.utils.supportsTextFileSave

/**
 * "Create your account": the step the Google login stops at when that Google identity has
 * no pomegranate account yet. The key is generated client-side and shown here because the
 * shards are dealt from it — this is the only moment the whole key exists for the user to
 * back up. Operators and threshold stay behind Advanced options; the defaults are fine.
 */
@Composable
fun GoogleAccountSetupSection(
    setup: GoogleAccountSetup,
    busy: Boolean,
    createLabel: String,
    errorMessage: String?,
    onRegenerateKey: () -> Unit,
    onImportKey: (String) -> String?,
    onAddOperator: (String) -> String?,
    onRemoveOperator: (String) -> Unit,
    onThresholdChange: (Int) -> Unit,
    onCheckOperators: () -> Unit,
    onCreate: () -> Unit,
) {
    var acknowledged by remember { mutableStateOf(false) }
    var advancedOpen by remember { mutableStateOf(false) }

    // Probe on entry so a dead operator is visible before the user hits Create.
    LaunchedEffect(Unit) { onCheckOperators() }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Create your account",
            style = MaterialTheme.typography.titleMedium,
            color = NostrordColors.TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = setup.introLine,
            style = MaterialTheme.typography.bodySmall,
            color = NostrordColors.TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        KeyPanel(
            setup = setup,
            enabled = !busy,
            onRegenerate = onRegenerateKey,
            onImportKey = onImportKey,
        )

        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .clip(NostrordShapes.shapeSmall)
                .clickable(enabled = !busy) { acknowledged = !acknowledged }
                .pointerHoverIcon(PointerIcon.Hand),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = acknowledged,
                onCheckedChange = { acknowledged = it },
                enabled = !busy,
                colors = CheckboxDefaults.colors(checkedColor = NostrordColors.Primary),
            )
            Text(
                text = "I have safely backed up my private key",
                style = MaterialTheme.typography.bodyMedium,
                color = NostrordColors.TextContent,
            )
        }

        AdvancedDisclosure(
            open = advancedOpen,
            onToggle = { advancedOpen = !advancedOpen },
        ) {
            OperatorConfig(
                setup = setup,
                enabled = !busy,
                onAddOperator = onAddOperator,
                onRemoveOperator = onRemoveOperator,
                onThresholdChange = onThresholdChange,
            )
        }

        // Operator trouble is reported outside Advanced options, which is collapsed by
        // default: a quiet line while the account can still be created, a callout when it
        // cannot.
        if (setup.unreachableOperators.isNotEmpty()) {
            if (setup.tooFewOperators) {
                InfoCard(
                    title = "Not enough operators are responding",
                    titleColor = NostrordColors.Error,
                    content = setup.operatorWarning,
                    icon = Icons.Default.Warning,
                    isCompact = true,
                )
            } else {
                Text(
                    text = setup.operatorWarning,
                    style = MaterialTheme.typography.bodySmall,
                    color = NostrordColors.TextMuted,
                )
            }
            AppButton(
                text = "Check operators again",
                onClick = onCheckOperators,
                enabled = !busy,
                variant = AppButtonVariant.Ghost,
                size = AppButtonSize.Small,
                icon = Icons.Default.Refresh,
            )
        }

        if (errorMessage != null) {
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Create waits for the probe: the operator set is decided by its verdicts.
        AppButton(
            text = if (!busy && setup.operatorsPending) "Checking operators..." else createLabel,
            onClick = onCreate,
            enabled = !busy && acknowledged && !setup.tooFewOperators && !setup.operatorsPending,
            size = AppButtonSize.Large,
            fullWidth = true,
            loading = busy || setup.operatorsPending,
        )
    }
}

@Composable
private fun ImportKeyField(
    enabled: Boolean,
    onImportKey: (String) -> String?,
) {
    var open by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    if (!open) {
        AppButton(
            text = "Use a key I already have",
            onClick = { open = true },
            enabled = enabled,
            variant = AppButtonVariant.Ghost,
            size = AppButtonSize.Small,
            icon = Icons.Default.Key,
        )
        return
    }

    val submit = {
        if (draft.isNotBlank()) {
            error = onImportKey(draft)
            if (error == null) {
                draft = ""
                open = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Compact input-group with the action inside it, the same shape the web uses here.
        AppSearchField(
            value = draft,
            onValueChange = {
                draft = it
                error = null
            },
            placeholder = "nsec1... or 64-character hex",
            size = InputSize.Compact,
            icon = null,
            containerColor = NostrordColors.InputBackground,
            onDone = submit,
            trailing = {
                AppButton(
                    text = "Use key",
                    onClick = submit,
                    enabled = enabled && draft.isNotBlank(),
                    variant = AppButtonVariant.Secondary,
                    size = AppButtonSize.Small,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Same name the web download uses, so a user with both ends up with matching files. */
private const val BACKUP_FILE_NAME = "nostr-private-key.txt"

/**
 * Everything about the key in one bordered block: the value, why it matters, and the two
 * ways to keep it. The rest of the step stays loose so this reads as the part that needs
 * attention.
 */
@Composable
private fun KeyPanel(
    setup: GoogleAccountSetup,
    enabled: Boolean,
    onRegenerate: () -> Unit,
    onImportKey: (String) -> String?,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = NostrordShapes.shapeMedium,
        color = NostrordColors.BackgroundFloating,
        border = BorderStroke(1.dp, NostrordColors.Divider),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("YOUR PRIVATE KEY", color = NostrordColors.TextSecondary)
            IdentifierRow(
                ids = listOf(Identifier("nsec", setup.nsec)),
                containerColor = NostrordColors.InputBackground,
                trailing = {
                    IconButton(onClick = onRegenerate, enabled = enabled, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Generate new key",
                            tint = NostrordColors.TextMuted,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                },
            )
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = NostrordColors.WarningOrange,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = setup.keyWarning,
                    style = MaterialTheme.typography.bodySmall,
                    color = NostrordColors.TextMuted,
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (supportsTextFileSave) {
                    val scope = rememberCoroutineScope()
                    val saveFile = rememberTextFileSaver()
                    AppButton(
                        text = "Download backup",
                        onClick = { scope.launch { saveFile(setup.nsec, BACKUP_FILE_NAME) } },
                        enabled = enabled,
                        variant = AppButtonVariant.Secondary,
                        size = AppButtonSize.Small,
                        icon = Icons.Default.Download,
                    )
                }
                if (supportsNativeShare) {
                    val shareText = rememberTextSharer()
                    AppButton(
                        text = "Share backup",
                        onClick = { shareText(setup.nsec) },
                        enabled = enabled,
                        variant = AppButtonVariant.Secondary,
                        size = AppButtonSize.Small,
                        icon = Icons.Default.Share,
                    )
                }
                ImportKeyField(enabled = enabled, onImportKey = onImportKey)
            }
        }
    }
}

/** Operators holding the key shards, plus how many of them must sign. */
@Composable
private fun OperatorConfig(
    setup: GoogleAccountSetup,
    enabled: Boolean,
    onAddOperator: (String) -> String?,
    onRemoveOperator: (String) -> Unit,
    onThresholdChange: (Int) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }

    val submitDraft = {
        if (draft.isNotBlank()) {
            addError = onAddOperator(draft)
            if (addError == null) draft = ""
        }
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("OPERATORS")
        Text(
            text =
            "Independent servers that each hold a shard of your private key, so no single " +
                "operator can sign on its own.",
            style = MaterialTheme.typography.bodySmall,
            color = NostrordColors.TextMuted,
        )
        setup.operators.forEach { url ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = pomegranateOperatorLabel(url),
                    style = MaterialTheme.typography.bodySmall,
                    color = NostrordColors.TextContent,
                    modifier = Modifier.weight(1f),
                )
                when (setup.statusOf(url)) {
                    GoogleAccountSetup.OperatorStatus.Checking -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = NostrordColors.TextMuted,
                        )
                    }

                    GoogleAccountSetup.OperatorStatus.Reachable -> {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Responding",
                            tint = NostrordColors.Success,
                            modifier = Modifier.size(16.dp),
                        )
                    }

                    GoogleAccountSetup.OperatorStatus.Unreachable -> {
                        Text(
                            text = "not responding",
                            style = MaterialTheme.typography.bodySmall,
                            color = NostrordColors.Error,
                        )
                    }

                    GoogleAccountSetup.OperatorStatus.Unknown -> {}
                }
                IconButton(
                    onClick = { onRemoveOperator(url) },
                    enabled = enabled && setup.canRemoveOperator,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Cancel,
                        contentDescription = "Remove ${pomegranateOperatorLabel(url)}",
                        tint = NostrordColors.TextMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        AppSearchField(
            value = draft,
            onValueChange = {
                draft = it
                addError = null
            },
            placeholder = "Add operator URL",
            size = InputSize.Compact,
            icon = null,
            containerColor = NostrordColors.InputBackground,
            onDone = submitDraft,
            trailing = {
                AppButton(
                    text = "Add",
                    onClick = submitDraft,
                    enabled = enabled && draft.isNotBlank(),
                    variant = AppButtonVariant.Secondary,
                    size = AppButtonSize.Small,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        addError?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        if (setup.recommendedOperators.isNotEmpty()) {
            Text(
                text = "Recommended",
                style = MaterialTheme.typography.bodySmall,
                color = NostrordColors.TextMuted,
            )
            setup.recommendedOperators.forEach { url ->
                AppButton(
                    text = pomegranateOperatorLabel(url),
                    onClick = { addError = onAddOperator(url) },
                    enabled = enabled,
                    variant = AppButtonVariant.Ghost,
                    size = AppButtonSize.Small,
                    icon = Icons.Default.Add,
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        SectionLabel("SIGNING THRESHOLD")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(
                onClick = { onThresholdChange(setup.threshold - 1) },
                enabled = enabled && setup.canLowerThreshold,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Lower threshold", tint = NostrordColors.TextContent)
            }
            Text(
                text = setup.threshold.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = NostrordColors.TextPrimary,
            )
            IconButton(
                onClick = { onThresholdChange(setup.threshold + 1) },
                enabled = enabled && setup.canRaiseThreshold,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = "Raise threshold", tint = NostrordColors.TextContent)
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "of ${setup.usableOperators.size} operators are enough to sign",
                style = MaterialTheme.typography.bodySmall,
                color = NostrordColors.TextMuted,
            )
        }
    }
}

@Composable
private fun SectionLabel(
    text: String,
    color: Color = NostrordColors.TextMuted,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = color,
    )
}

/** Collapsed-by-default disclosure, matching the central-server one in [GoogleLoginTab]. */
@Composable
internal fun AdvancedDisclosure(
    open: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
            Modifier
                .clip(NostrordShapes.shapeSmall)
                .clickable { onToggle() }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector =
                if (open) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = NostrordColors.TextMuted,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Advanced options",
                style = MaterialTheme.typography.bodyMedium,
                color = NostrordColors.TextSecondary,
            )
        }
        if (open) content()
    }
}
