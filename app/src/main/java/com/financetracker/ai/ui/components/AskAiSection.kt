package com.financetracker.ai.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.financetracker.ai.viewmodel.ModelState

/**
 * The "Ask AI" entry point, shown on screens with data worth asking about.
 *
 * Suggestions are pre-written and passed in per screen so they're specific to what the user is
 * looking at ("Am I over budget on Food?") rather than generic. Tapping one seeds the chat input
 * rather than firing immediately, so the user can edit before spending inference time — which
 * matters a lot when a single answer can take a while on device.
 *
 * Renders nothing when the model isn't ready, since the chat screen would just tell them to
 * finish setup — better to keep the screen clean and let the More-tab badge point at setup.
 */
@Composable
fun AskAiSection(
    suggestions: List<String>,
    isModelReady: Boolean,
    onAsk: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenChat: (() -> Unit)? = null
) {
    if (!isModelReady) return

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Ask AI",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                if (onOpenChat != null) {
                    TextButton(onClick = onOpenChat) { Text("Chat") }
                }
            }

            Text(
                "Ask about anything on this screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(suggestions) { suggestion ->
                    AssistChip(
                        onClick = { onAsk(suggestion) },
                        label = { Text(suggestion) }
                    )
                }
            }
        }
    }
}

/** Standard padding for a section placed at the end of a screen's content list. */
val AskAiSectionPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)
