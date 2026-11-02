package com.remotecontroller.web.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.remotecontroller.shared.network.LinkCrypto
import com.remotecontroller.shared.network.RelayProtocol

@Composable
private fun GateColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        content()
    }
}

@Composable
internal fun DeviceGate(onContinue: (String, String) -> Unit) {
    var id by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val digits = RelayProtocol.normalizeId(id)
    GateColumn {
        Text(
            text = "Connect to a PC",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Type the ID shown on the PC. Add its access code if the PC asks for one; otherwise you will wait for Allow.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = id,
            onValueChange = { id = it.take(16) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("PC ID") },
            placeholder = { Text("123 456 789") },
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Access code (optional)") },
            placeholder = { Text("XXXX-XXXX-XXXX") },
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = { onContinue(digits, code) },
            enabled = digits.length == RelayProtocol.ID_DIGITS && (code.isBlank() || LinkCrypto.isCode(code)),
        ) {
            Text("Connect")
        }
    }
}

@Composable
internal fun AccessGate(
    message: String?,
    onContinue: (String) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    GateColumn {
        Text(
            text = "Access code",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message ?: "This PC asks for the code from its window. It is not saved in this page.",
            style = MaterialTheme.typography.bodyMedium,
            color = if (message != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("XXXX-XXXX-XXXX") },
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = { onContinue(code) },
            enabled = LinkCrypto.isCode(code),
        ) {
            Text("Continue")
        }
    }
}

@Composable
internal fun WaitingGate(checkCode: String) {
    GateColumn {
        Text(
            text = "Waiting for the PC",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Press Allow on the PC. It shows this number; if it shows another one, deny there.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = checkCode,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
internal fun StoppedGate(message: String, onRetry: () -> Unit) {
    GateColumn {
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onRetry) {
            Text("Ask again")
        }
    }
}
