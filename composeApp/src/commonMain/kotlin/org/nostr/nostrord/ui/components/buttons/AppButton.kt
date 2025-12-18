package org.nostr.nostrord.ui.components.buttons

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.ui.theme.NostrordColors

@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = { if (enabled) onClick() },
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = NostrordColors.Primary,
            contentColor = Color.White,
            disabledContainerColor = NostrordColors.Primary.copy(alpha = 0.4f),
            disabledContentColor = Color.White
        ),
        modifier = modifier.pointerHoverIcon(
            if (enabled) PointerIcon.Hand else PointerIcon.Default
        )
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}
