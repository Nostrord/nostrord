package org.nostr.nostrord.ui.screens.spell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.nostr.Spell
import org.nostr.nostrord.nostr.SpellDraft
import org.nostr.nostrord.nostr.toSpell
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.utils.Result

/**
 * Build a spell and pin it to the rail.
 *
 * One form rather than a mode switch: leaving authors empty and naming relays is already
 * fiatjaf's relay feed, so a second flow would only duplicate the same fields.
 */
@Composable
fun CreateSpellModal(
    onDismiss: () -> Unit,
    onCreated: (Spell) -> Unit,
) {
    var draft by remember { mutableStateOf(SpellDraft()) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New spell") },
        confirmButton = {
            TextButton(onClick = {
                when (val result = draft.toSpell()) {
                    is Result.Success -> {
                        AppModule.spellLibrary.add(result.data)
                        onCreated(result.data)
                    }
                    is Result.Error -> error = result.error.message
                }
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Name", draft.name, "Bitcoin from friends") { draft = draft.copy(name = it) }
                Field("Kinds", draft.kinds, "1, 30023 (blank = any)") { draft = draft.copy(kinds = it) }
                Field("Authors", draft.authors, "\$contacts, \$me or an npub") { draft = draft.copy(authors = it) }
                Field("Hashtags", draft.hashtags, "bitcoin, nostr") { draft = draft.copy(hashtags = it) }
                Field("Relays", draft.relays, "relay.damus.io (blank = your read relays)") {
                    draft = draft.copy(relays = it)
                }
                error?.let { Text(it, color = NostrordColors.Error) }
            }
        },
    )
}

@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
