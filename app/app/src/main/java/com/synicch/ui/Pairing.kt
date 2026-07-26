package com.synicch.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * First run.
 *
 * The QR path is the intended one -- typing a 43-character token on a phone is
 * miserable. Manual entry exists as a fallback for when the camera is
 * unavailable or the code will not scan.
 */
@Composable
fun PairingScreen(
    onScan: () -> Unit,
    onManual: (url: String, token: String) -> Unit,
    error: String?,
    busy: Boolean,
) {
    var manual by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("https://photos.ngserver") }
    var token by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Synicch", style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(8.dp))
        Text(
            "Connect to your photo server",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(36.dp))

        if (!manual) {
            Text(
                "On the server, run:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
            ) {
                Text("synicch pair", Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(24.dp))

            Button(onClick = onScan, enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Default.QrCodeScanner, null)
                Spacer(Modifier.width(10.dp))
                Text("Scan the QR code")
            }
            Spacer(Modifier.height(10.dp))
            TextButton({ manual = true }) { Text("Enter details manually") }
        } else {
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text("Server URL") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = token, onValueChange = { token = it },
                label = { Text("Token") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { onManual(url.trim(), token.trim()) },
                enabled = !busy && url.isNotBlank() && token.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Connect") }
            Spacer(Modifier.height(10.dp))
            TextButton({ manual = false }) { Text("Scan a code instead") }
        }

        if (busy) {
            Spacer(Modifier.height(20.dp))
            CircularProgressIndicator()
        }

        error?.let {
            Spacer(Modifier.height(20.dp))
            Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}
