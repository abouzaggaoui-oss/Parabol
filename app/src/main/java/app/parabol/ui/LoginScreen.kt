package app.parabol.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.parabol.MainViewModel
import app.parabol.R
import app.parabol.SignalAmber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(vm: MainViewModel, onLoggedIn: () -> Unit, onBack: (() -> Unit)? = null) {
    var name by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showFillWarning by remember { mutableStateOf(false) }

    // Leave the screen once THIS login succeeded (not because another account is already open).
    val startTick = remember { vm.loginSuccessTick }
    LaunchedEffect(vm.loginSuccessTick) {
        if (vm.loginSuccessTick != startTick) onLoggedIn()
    }
    if (onBack != null) androidx.activity.compose.BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Parabol",
            fontSize = 44.sp,
            fontWeight = FontWeight.Bold,
            color = SignalAmber
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.login_title),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.account_name_label)) },
            placeholder = { Text(stringResource(R.string.account_name_placeholder)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp)
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = server,
            onValueChange = { server = it },
            label = { Text(stringResource(R.string.login_server)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp)
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(stringResource(R.string.login_username)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp)
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.login_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp)
        )
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                if (name.isBlank() || server.isBlank() || username.isBlank() || password.isBlank()) {
                    showFillWarning = true
                } else {
                    showFillWarning = false
                    var normalized = server.trim()
                    if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
                        normalized = "http://$normalized"
                    }
                    vm.connect(normalized, username.trim(), password.trim(), name.trim())
                }
            },
            enabled = !vm.loading,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp)
                .height(52.dp)
        ) {
            if (vm.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text(stringResource(R.string.login_button), fontSize = 16.sp)
            }
        }

        if (showFillWarning) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.login_fill_all),
                color = MaterialTheme.colorScheme.error
            )
        }
        if (vm.loginError) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.login_error),
                color = MaterialTheme.colorScheme.error
            )
        }
        if (onBack != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onBack) { Text(stringResource(R.string.recording_cancel_confirm)) }
        }
    }
}
