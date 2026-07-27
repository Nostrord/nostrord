package org.nostr.nostrord.ui.screens.login

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nostrord.composeapp.generated.resources.Res
import nostrord.composeapp.generated.resources.nostrord_logo
import org.jetbrains.compose.resources.painterResource
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes

@Composable
fun NostrLoginScreen(
    modifier: Modifier = Modifier,
    onLoginSuccess: () -> Unit,
) {
    BoxWithConstraints(
        modifier =
        modifier
            .fillMaxSize()
            .background(
                // Theme-aware page gradient: corners around the main background
                // (mirrors the web's .login-page / prototype page-gradient).
                Brush.linearGradient(
                    colors =
                    listOf(
                        NostrordColors.PageGradientFrom,
                        NostrordColors.Background,
                        NostrordColors.PageGradientTo,
                    ),
                ),
            ),
    ) {
        // A phone never reaches the card's 448dp, so the web's 32px gutters would eat a
        // third of the row width there; trim them to keep the card's proportions.
        val compact = maxWidth < 600.dp
        Column(
            modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(if (compact) 12.dp else 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            // Centers the card when it fits; the column still scrolls when taller.
            verticalArrangement = Arrangement.Center,
        ) {
            Card(
                modifier =
                Modifier
                    .widthIn(max = 448.dp)
                    .fillMaxWidth(),
                shape = NostrordShapes.shapeXLarge,
                colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            ) {
                Column(
                    modifier = Modifier.padding(if (compact) 20.dp else 32.dp),
                ) {
                    // Card header: logo on the brand tile, app name, tagline
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Image(
                            painter = painterResource(Res.drawable.nostrord_logo),
                            contentDescription = "Nostrord",
                            modifier =
                            Modifier
                                .size(56.dp)
                                .clip(NostrordShapes.shapeXLarge)
                                .background(NostrordColors.Primary),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Nostrord",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = NostrordColors.TextPrimary,
                        )
                        Text(
                            text = "decentralized groups on nostr",
                            style = MaterialTheme.typography.bodyMedium,
                            color = NostrordColors.TextMuted,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    // Method list + chosen method's form (shared with the add-account modal)
                    LoginMethods(onLoginSuccess = onLoginSuccess)
                }
            }
        }
    }
}
