package org.nostr.nostrord.ui.screens.login

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nostr.nostrord.ui.components.buttons.AppButton
import org.nostr.nostrord.ui.components.buttons.AppButtonSize
import org.nostr.nostrord.ui.components.buttons.AppButtonVariant
import org.nostr.nostrord.ui.screens.login.components.AmberLoginTab
import org.nostr.nostrord.ui.screens.login.components.BunkerLoginTab
import org.nostr.nostrord.ui.screens.login.components.ExtensionLoginTab
import org.nostr.nostrord.ui.screens.login.components.GoogleLoginTab
import org.nostr.nostrord.ui.screens.login.components.LoginMethodList
import org.nostr.nostrord.ui.screens.login.components.PrivateKeyLoginTab
import org.nostr.nostrord.ui.screens.login.components.icon
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes

/**
 * The credential picker shared by the login screen and the add-account modal: a list of
 * the available [LoginMethod]s, then the chosen method's form behind a back header.
 * "Generate New Key" sits at the list level because creating an identity is a separate
 * path from presenting one. Each form calls [onLoginSuccess] on a successful auth;
 * keeping this in one place is what keeps login and add-account identical.
 */
@Composable
fun LoginMethods(
    onLoginSuccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // null shows the method list; a value shows that method's form
    var selected by remember { mutableStateOf<LoginMethod?>(null) }
    // Entered through "Generate New Key": the private key form opens on the wizard
    var generating by remember { mutableStateOf(false) }
    val methods = remember { availableLoginMethods() }

    Column(modifier = modifier) {
        when (val method = selected) {
            null -> {
                LoginMethodList(
                    methods = methods,
                    onSelect = {
                        generating = false
                        selected = it
                    },
                )
                OrDivider()
                AppButton(
                    text = "Generate New Key",
                    onClick = {
                        generating = true
                        selected = LoginMethod.PrivateKey
                    },
                    variant = AppButtonVariant.Secondary,
                    size = AppButtonSize.Large,
                    fullWidth = true,
                    icon = Icons.Default.AutoAwesome,
                )
            }

            else -> {
                val back = { selected = null }
                MethodHeader(method = method, onBack = back)
                Spacer(modifier = Modifier.height(20.dp))
                when (method) {
                    LoginMethod.PrivateKey ->
                        PrivateKeyLoginTab(
                            onLoginSuccess = onLoginSuccess,
                            startInWizard = generating,
                            onBack = back,
                        )
                    LoginMethod.Bunker -> BunkerLoginTab(onLoginSuccess)
                    LoginMethod.Extension -> ExtensionLoginTab(onLoginSuccess)
                    LoginMethod.Amber -> AmberLoginTab(onLoginSuccess)
                    LoginMethod.Google -> GoogleLoginTab(onLoginSuccess)
                }
            }
        }
    }
}

/** Back arrow + method name above the chosen method's form. */
@Composable
private fun MethodHeader(
    method: LoginMethod,
    onBack: () -> Unit,
) {
    Row(
        modifier =
        Modifier
            .clip(NostrordShapes.shapeSmall)
            .clickable(onClick = onBack)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back to login methods",
            tint = NostrordColors.TextMuted,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = method.icon,
            contentDescription = null,
            tint = NostrordColors.Primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = method.title,
            color = NostrordColors.TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun OrDivider() {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = NostrordColors.Divider)
        Text(
            text = "or",
            modifier = Modifier.padding(horizontal = 16.dp),
            color = NostrordColors.TextMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = NostrordColors.Divider)
    }
}
