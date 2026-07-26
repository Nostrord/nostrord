package org.nostr.nostrord.ui.screens.login

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.auth.pomegranate.PomegranateService
import org.nostr.nostrord.nostr.Nip07
import org.nostr.nostrord.nostr.Nip55
import org.nostr.nostrord.ui.components.forms.AppSegmentedTabs
import org.nostr.nostrord.ui.components.forms.SegmentedTab
import org.nostr.nostrord.ui.screens.login.components.AmberLoginTab
import org.nostr.nostrord.ui.screens.login.components.BunkerLoginTab
import org.nostr.nostrord.ui.screens.login.components.ExtensionLoginTab
import org.nostr.nostrord.ui.screens.login.components.GoogleLoginTab
import org.nostr.nostrord.ui.screens.login.components.PrivateKeyLoginTab

/**
 * The tabbed credential picker shared by the login screen and the add-account modal:
 * Private Key / Bunker (NIP-46) / Extension (NIP-07, only when available). Owns the tab
 * selection and reuses the standalone login tabs, which each call [onLoginSuccess] on a
 * successful auth. Keeping this in one place is what keeps login and add-account identical.
 */
@Composable
fun LoginMethods(
    onLoginSuccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember { mutableStateOf<LoginTab>(LoginTab.PrivateKey) }
    val googleAvailable = remember { PomegranateService().isAvailable }
    val tabs =
        remember {
            buildList {
                add(LoginTab.PrivateKey)
                add(LoginTab.Bunker)
                if (Nip07.isAvailable()) add(LoginTab.Extension)
                if (Nip55.isAvailable()) add(LoginTab.Amber)
                if (googleAvailable) add(LoginTab.Google)
            }
        }

    Column(modifier = modifier) {
        AppSegmentedTabs(
            tabs = tabs.map { SegmentedTab(it.title, it.icon) },
            selectedIndex = tabs.indexOf(selectedTab),
            onSelect = { selectedTab = tabs[it] },
        )

        Spacer(modifier = Modifier.height(24.dp))

        when (selectedTab) {
            LoginTab.PrivateKey -> PrivateKeyLoginTab(onLoginSuccess)
            LoginTab.Bunker -> BunkerLoginTab(onLoginSuccess)
            LoginTab.Extension -> ExtensionLoginTab(onLoginSuccess)
            LoginTab.Google -> GoogleLoginTab(onLoginSuccess)
            LoginTab.Amber -> AmberLoginTab(onLoginSuccess)
        }
    }
}
