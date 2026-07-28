package com.synicch.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The most dangerous screen in the app: it deletes originals off the phone.
 *
 * Written to be slow and explicit. Every state says exactly what has been
 * checked, and the final step hands off to Android's own delete dialog, which
 * this app cannot bypass.
 */
sealed interface FreeUpState {
    data object Idle : FreeUpState
    data class Checking(val message: String) : FreeUpState
    data class Blocked(val reason: String) : FreeUpState
    data class Ready(val count: Int, val bytes: Long, val rejected: Int) : FreeUpState
    data class Done(val count: Int, val bytes: Long) : FreeUpState
}

@Composable
fun FreeUpSpaceScreen(
    state: FreeUpState,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onConfirm: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Free up space") },
                navigationIcon = { IconButton(onBack) {
                    Icon(Icons.Default.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize()
                .verticalScroll(rememberScrollState()).padding(20.dp),
        ) {
            Text(
                "This deletes photos and videos from this phone that are already " +
                "safely backed up on your server.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(20.dp))

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("What gets checked first", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(10.dp))
                    Check("The server holds byte-for-byte identical content")
                    Check("Synicch has indexed it, not just received it")
                    Check("Syncthing reports the backup fully in sync")
                    Check("It is more than 7 days old")
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "Verification compares actual file contents, not names or sizes. " +
                "Anything that does not match exactly is left alone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(28.dp))

            when (state) {
                is FreeUpState.Idle -> {
                    Button(onStart, Modifier.fillMaxWidth().height(52.dp)) {
                        Text("Check what can be freed")
                    }
                }

                is FreeUpState.Checking -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(14.dp))
                        Text(state.message)
                    }
                }

                is FreeUpState.Blocked -> {
                    Card(colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Row(Modifier.padding(16.dp)) {
                            Icon(Icons.Default.Warning, null)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Not safe to free up space right now",
                                    fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(4.dp))
                                Text(state.reason,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onStart, Modifier.fillMaxWidth()) { Text("Check again") }
                }

                is FreeUpState.Ready -> {
                    if (state.count == 0) {
                        Text("Nothing is eligible yet.",
                            style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onStart, Modifier.fillMaxWidth()) { Text("Check again") }
                    } else {
                        Text("${state.count} items  -  ${formatBytes(state.bytes)}",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text("Verified as backed up and safe to remove from this phone.",
                            style = MaterialTheme.typography.bodyMedium)
                        if (state.rejected > 0) {
                            Spacer(Modifier.height(8.dp))
                            Text("${state.rejected} were skipped because they did not " +
                                 "verify exactly. They stay on your phone.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(20.dp))
                        Button(
                            onConfirm,
                            Modifier.fillMaxWidth().height(52.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error),
                        ) { Text("Delete ${state.count} from this phone") }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Android will ask you to confirm again before anything is removed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                is FreeUpState.Done -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null,
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text("Freed ${formatBytes(state.bytes)} from ${state.count} items",
                            style = MaterialTheme.typography.bodyLarge)
                    }
                    Spacer(Modifier.height(20.dp))
                    OutlinedButton(onStart, Modifier.fillMaxWidth()) { Text("Check again") }
                }
            }
        }
    }
}

@Composable
private fun Check(text: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Icon(Icons.Default.Check, null, Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}
