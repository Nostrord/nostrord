package org.nostr.nostrord.ui.screens.login.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.ui.components.buttons.AppButton
import org.nostr.nostrord.ui.components.buttons.AppButtonSize
import org.nostr.nostrord.ui.components.buttons.AppButtonVariant
import org.nostr.nostrord.ui.components.forms.AppTextField
import org.nostr.nostrord.ui.components.forms.FormError
import org.nostr.nostrord.ui.screens.login.LoginViewModel
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.utils.rememberClipboardWriter

/**
 * Private key login (prototype flow): nsec / hex / NIP-49 ncryptsec input with the
 * field layout (uppercase label, hint, floating surface), plus
 *  - pasting an ncryptsec reveals the key-password field;
 *  - a plain hex/nsec offers "Protect with password", which stores the key
 *    encrypted (ncryptsec) on this device — the unlock dialog asks the password
 *    at the next startup;
 *  - [startInWizard] opens straight on the generate wizard (backup the npub/nsec, then
 *    an optional password with the same protected-storage semantics), entered from the
 *    "Generate New Key" action on the method list.
 * Key parsing, generation and encryption live in [LoginViewModel].
 */
@Composable
fun PrivateKeyLoginTab(
    onLoginSuccess: () -> Unit,
    startInWizard: Boolean = false,
    onBack: () -> Unit = {},
) {
    val vm = viewModel { LoginViewModel(AppModule.nostrRepository) }

    // Form state
    var privateKey by remember { mutableStateOf("") }
    var keyPassword by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    // Protect-with-password (plain keys)
    var protect by remember { mutableStateOf(false) }
    var protectPwd by remember { mutableStateOf("") }
    var protectConfirm by remember { mutableStateOf("") }

    // Generate wizard: 0 = form, 1 = backup step, 2 = password step
    var wizardStep by remember { mutableStateOf(if (startInWizard) 1 else 0) }
    var wizardKey by remember { mutableStateOf(if (startInWizard) vm.generateNewKeyHex() else "") }
    var wizardPwd by remember { mutableStateOf("") }
    var wizardConfirm by remember { mutableStateOf("") }

    val isEncrypted = vm.isEncryptedKeyInput(privateKey)
    val isPlain = vm.isPlainKeyInput(privateKey)
    // ncryptsec-at-rest only makes sense on the web; native key storage is already
    // Keystore / keychain-backed, so the protect option and wizard password step hide.
    val protectApplicable = vm.isProtectApplicable
    val protectActive = protect && isPlain && protectApplicable
    val canLogin =
        vm.isValidKeyInput(privateKey) &&
            (!isEncrypted || keyPassword.isNotEmpty()) &&
            (!protectActive || protectPwd.isNotEmpty()) &&
            !isLoading

    fun handleResult(result: Result<Unit>) {
        isLoading = false
        if (result.isSuccess) {
            onLoginSuccess()
        } else {
            errorMessage = result.exceptionOrNull()?.message ?: "Invalid private key or login failed"
        }
    }

    fun doLogin(
        input: String,
        password: String?,
        isNewIdentity: Boolean,
    ) {
        isLoading = true
        errorMessage = null
        vm.loginWithPrivateKeyInput(input, password, isNewIdentity) { handleResult(it) }
    }

    /** Password-protected login: persists the ncryptsec, unlock asked at next startup. */
    fun doProtectedLogin(
        input: String,
        password: String,
        isNewIdentity: Boolean,
    ) {
        isLoading = true
        errorMessage = null
        vm.loginProtected(input, password, isNewIdentity) { handleResult(it) }
    }

    fun login() {
        if (!canLogin) return
        val input = privateKey.trim()
        when {
            isEncrypted -> doLogin(input, keyPassword, isNewIdentity = false)
            protectActive -> {
                if (protectPwd != protectConfirm) {
                    errorMessage = "Passwords don't match"
                    return
                }
                doProtectedLogin(input, protectPwd, isNewIdentity = false)
            }
            else -> doLogin(input, null, isNewIdentity = false)
        }
    }

    when {
        // ── Generate wizard ─────────────────────────────────────────────────
        wizardStep > 0 -> {
            Column {
                // Errors render at the top, mirroring the web's .login-error
                // placement under the tab strip (visible above the keyboard).
                FormError(errorMessage)
                if (protectApplicable) {
                    StepDots(current = wizardStep, total = 2)
                    Spacer(modifier = Modifier.height(16.dp))
                }

                if (wizardStep == 1) {
                    WizardTitle(
                        "Your new key",
                        "Save the nsec somewhere safe. Whoever has it controls your account.",
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    val bech = vm.deriveBech32Keys(wizardKey)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = NostrordShapes.shapeMedium,
                        color = NostrordColors.BackgroundFloating,
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            KeyLine("npub", bech?.first ?: "", danger = false)
                            KeyLine("nsec", bech?.second ?: "", danger = true)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier =
                        Modifier
                            .clip(NostrordShapes.shapeSmall)
                            .clickable { wizardKey = vm.generateNewKeyHex() }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = NostrordColors.TextLink,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Generate another key", color = NostrordColors.TextLink, fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppButton(
                            text = "Back",
                            // Entered from the method list: there is no key form behind
                            // this step, so back leaves the private-key flow entirely.
                            onClick = { if (startInWizard) onBack() else wizardStep = 0 },
                            variant = AppButtonVariant.Ghost,
                        )
                        AppButton(
                            // Without the web's password step the wizard is single-step:
                            // finishing logs straight in with the new key.
                            text =
                            when {
                                protectApplicable -> "Continue"
                                isLoading -> "Logging in..."
                                else -> "Finish"
                            },
                            onClick = {
                                if (protectApplicable) wizardStep = 2 else doLogin(wizardKey, null, isNewIdentity = true)
                            },
                            enabled = !isLoading,
                            loading = !protectApplicable && isLoading,
                            modifier = Modifier.weight(1f),
                            fullWidth = true,
                        )
                    }
                } else {
                    WizardTitle(
                        "Protect your account",
                        "Add an extra layer of protection with a password",
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    // Info card (brand-tinted) mirroring the prototype's recommendation box
                    Row(
                        modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(NostrordShapes.shapeMedium)
                            .background(NostrordColors.PrimarySubtle)
                            .border(
                                1.dp,
                                NostrordColors.Primary.copy(alpha = 0.4f),
                                NostrordShapes.shapeMedium,
                            )
                            .padding(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = NostrordColors.Primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                "Password protection (recommended)",
                                color = NostrordColors.TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                "Encrypts your new key on this device (ncryptsec); the password " +
                                    "is asked to unlock the app. Optional, but strongly recommended.",
                                color = NostrordColors.TextSecondary,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    AppTextField(
                        value = wizardPwd,
                        onValueChange = {
                            wizardPwd = it
                            errorMessage = null
                        },
                        placeholder = "Create a password (or skip)",
                        label = "Password (optional)",
                        leadingIcon = Icons.Default.Lock,
                        masked = true,
                        keyboardType = KeyboardType.Password,
                        enabled = !isLoading,
                    )
                    if (wizardPwd.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        AppTextField(
                            value = wizardConfirm,
                            onValueChange = {
                                wizardConfirm = it
                                errorMessage = null
                            },
                            placeholder = "Repeat the password",
                            label = "Confirm password",
                            leadingIcon = Icons.Default.Lock,
                            masked = true,
                            keyboardType = KeyboardType.Password,
                            enabled = !isLoading,
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppButton(
                            text = "Back",
                            onClick = { wizardStep = 1 },
                            enabled = !isLoading,
                            variant = AppButtonVariant.Ghost,
                        )
                        AppButton(
                            text = if (wizardPwd.isEmpty()) "Finish without password" else "Finish with password",
                            onClick = {
                                if (wizardPwd.isEmpty()) {
                                    doLogin(wizardKey, null, isNewIdentity = true)
                                } else if (wizardPwd != wizardConfirm) {
                                    errorMessage = "Passwords don't match"
                                } else {
                                    doProtectedLogin(wizardKey, wizardPwd, isNewIdentity = true)
                                }
                            },
                            enabled = !isLoading && (wizardPwd.isEmpty() || wizardConfirm.isNotEmpty()),
                            modifier = Modifier.weight(1f),
                            fullWidth = true,
                            loading = isLoading,
                        )
                    }
                }
            }
        }

        // ── Login form ──────────────────────────────────────────────────────
        else -> {
            Column {
                FormError(errorMessage)
                AppTextField(
                    value = privateKey,
                    onValueChange = {
                        privateKey = it
                        errorMessage = null
                    },
                    placeholder = "hex, nsec1, ncryptsec1",
                    label = "Private key (hex, nsec or ncryptsec)",
                    hint = "Your key never leaves this device.",
                    leadingIcon = Icons.Default.VpnKey,
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                imageVector = if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showKey) "Hide key" else "Show key",
                                tint = NostrordColors.TextMuted,
                            )
                        }
                    },
                    masked = !showKey,
                    keyboardType = KeyboardType.Password,
                    enabled = !isLoading,
                    onDone = { login() },
                )

                if (isEncrypted) {
                    Spacer(modifier = Modifier.height(16.dp))
                    AppTextField(
                        value = keyPassword,
                        onValueChange = {
                            keyPassword = it
                            errorMessage = null
                        },
                        placeholder = "Password",
                        label = "Key password",
                        hint =
                        if (protectApplicable) {
                            "This key is encrypted (NIP-49); enter its password to unlock it."
                        } else {
                            // Native: the password is needed once for the import; the key then
                            // lives in the platform's secure storage (Keystore / keychain).
                            "This key is encrypted (NIP-49); enter its password once to import it. " +
                                "It is then stored in your device's secure storage, with no password at startup."
                        },
                        leadingIcon = Icons.Default.Lock,
                        masked = true,
                        keyboardType = KeyboardType.Password,
                        enabled = !isLoading,
                        onDone = { login() },
                    )
                }

                // Plain key: offer protected (ncryptsec-at-rest) storage — web only
                if (isPlain && protectApplicable) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(NostrordShapes.shapeMedium)
                            .background(NostrordColors.BackgroundFloating)
                            .border(1.dp, NostrordColors.Divider, NostrordShapes.shapeMedium)
                            .clickable { protect = !protect }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = protect,
                            onCheckedChange = {
                                protect = it
                                errorMessage = null
                            },
                            colors =
                            CheckboxDefaults.colors(
                                checkedColor = NostrordColors.Primary,
                                uncheckedColor = NostrordColors.TextMuted,
                            ),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Shield,
                                    contentDescription = null,
                                    tint = NostrordColors.Primary,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "Protect with password (recommended)",
                                    color = NostrordColors.TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                "Encrypts your key on this device (ncryptsec); the password is asked to unlock the app.",
                                color = NostrordColors.TextSecondary,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    if (protect) {
                        Spacer(modifier = Modifier.height(12.dp))
                        AppTextField(
                            value = protectPwd,
                            onValueChange = {
                                protectPwd = it
                                errorMessage = null
                            },
                            placeholder = "Create a password",
                            label = "Password",
                            leadingIcon = Icons.Default.Lock,
                            masked = true,
                            keyboardType = KeyboardType.Password,
                            enabled = !isLoading,
                        )
                        if (protectPwd.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            AppTextField(
                                value = protectConfirm,
                                onValueChange = {
                                    protectConfirm = it
                                    errorMessage = null
                                },
                                placeholder = "Repeat the password",
                                label = "Confirm password",
                                leadingIcon = Icons.Default.Lock,
                                masked = true,
                                keyboardType = KeyboardType.Password,
                                enabled = !isLoading,
                                onDone = { login() },
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                AppButton(
                    text = if (isLoading) "Logging in..." else "Login",
                    onClick = { login() },
                    enabled = canLogin,
                    size = AppButtonSize.Large,
                    fullWidth = true,
                    loading = isLoading,
                    icon = Icons.AutoMirrored.Filled.Login,
                )
            }
        }
    }
}

/** Wizard progress dots: the active step is a wide brand pill. */
@Composable
private fun StepDots(
    current: Int,
    total: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (step in 1..total) {
            if (step > 1) Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier =
                Modifier
                    .height(6.dp)
                    .width(if (step == current) 24.dp else 6.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(if (step == current) NostrordColors.Primary else NostrordColors.InputBackground),
            )
        }
    }
}

@Composable
private fun WizardTitle(
    title: String,
    subtitle: String,
) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            title,
            color = NostrordColors.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            subtitle,
            color = NostrordColors.TextMuted,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** One npub/nsec row in the wizard backup box: label, truncated mono value, copy. */
@Composable
private fun KeyLine(
    label: String,
    value: String,
    danger: Boolean,
) {
    val copyToClipboard = rememberClipboardWriter()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label.uppercase(),
            color = if (danger) NostrordColors.Error else NostrordColors.TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(40.dp),
        )
        Text(
            value,
            color = NostrordColors.TextContent,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier =
            Modifier
                .size(28.dp)
                .clip(NostrordShapes.shapeSmall)
                .clickable {
                    copyToClipboard(value)
                    copied = true
                }
                .pointerHoverIcon(PointerIcon.Hand),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                contentDescription = if (copied) "Copied" else "Copy $label",
                tint = if (copied) NostrordColors.Success else NostrordColors.TextMuted,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
