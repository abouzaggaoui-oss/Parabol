package app.parabol.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.parabol.MainViewModel
import app.parabol.PlayerQueue
import app.parabol.R
import app.parabol.data.Category
import app.parabol.playQueue
import app.parabol.playSingle

// ---------------- Settings ----------------

@Composable
fun SettingsScreen(vm: MainViewModel, onLogout: () -> Unit, onManageGroups: () -> Unit) {
    var useHls by remember { mutableStateOf(vm.store.useHls) }
    val context = LocalContext.current
    val isTv = remember { app.parabol.isTelevision(context) }
    var profilePendingRemoval by remember { mutableStateOf<app.parabol.data.Profile?>(null) }

    // Removing an account also deletes its favorites, hidden groups and scheduled
    // recordings, so ask first instead of doing it on a single (mis)tap.
    profilePendingRemoval?.let { profile ->
        AlertDialog(
            onDismissRequest = { profilePendingRemoval = null },
            title = { Text(stringResource(R.string.account_remove_title, profile.name)) },
            text = { Text(stringResource(R.string.account_remove_message)) },
            confirmButton = {
                TextButton(onClick = {
                    profilePendingRemoval = null
                    vm.removeProfile(profile.id)
                    if (vm.activeProfile == null) onLogout()
                }) { Text(stringResource(R.string.account_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { profilePendingRemoval = null }) {
                    Text(stringResource(R.string.recording_cancel_confirm))
                }
            }
        )
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Account
        item { SectionHeader(stringResource(R.string.settings_account)) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.settings_logged_in_as, vm.activeProfile?.username ?: ""),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    val exp = vm.userInfo?.expDate?.toLongOrNull()
                    if (exp != null && exp > 0) {
                        val date = java.text.DateFormat.getDateInstance()
                            .format(java.util.Date(exp * 1000))
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.settings_expires, date),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.loadAll() }) {
                            Text(stringResource(R.string.settings_refresh))
                        }
                        OutlinedButton(onClick = { profilePendingRemoval = vm.activeProfile }) {
                            Text(stringResource(R.string.settings_logout))
                        }
                    }
                }
            }
        }

        // Accounts (multiple Xtream logins)
        item { SectionHeader(stringResource(R.string.settings_accounts)) }
        items(vm.profiles, key = { it.id }) { profile ->
            val isActive = profile.id == vm.activeProfile?.id
            Card(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            profile.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                        )
                        Text(
                            "${profile.username} · ${profile.baseUrl}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isActive) {
                        Text(
                            stringResource(R.string.account_active),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    } else {
                        TextButton(onClick = { vm.switchProfile(profile.id) }) {
                            Text(stringResource(R.string.account_switch))
                        }
                    }
                    IconButton(onClick = { profilePendingRemoval = profile }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.account_remove))
                    }
                }
            }
        }
        item {
            var showAddAccount by rememberSaveable { mutableStateOf(false) }
            if (!showAddAccount) {
                OutlinedButton(onClick = { showAddAccount = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.account_add))
                }
            } else {
                AddAccountForm(vm = vm, onDone = { showAddAccount = false })
            }
        }

        // Menu size (TV only)
        if (isTv) {
            item { SectionHeader(stringResource(R.string.settings_tv_size)) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.settings_tv_size_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                "small" to R.string.tv_size_small,
                                "normal" to R.string.tv_size_normal,
                                "large" to R.string.tv_size_large
                            ).forEach { (key, label) ->
                                val selected = (app.parabol.TvUiSizeState.value ?: vm.store.tvUiSize) == key
                                Row(
                                    Modifier
                                        .tvClickable(cornerRadius = 20) {
                                            vm.store.tvUiSize = key
                                            app.parabol.TvUiSizeState.value = key
                                        }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(selected = selected, onClick = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(label))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Stream format
        item { SectionHeader(stringResource(R.string.settings_stream_format)) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.settings_stream_format_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = useHls, onClick = {
                            useHls = true; vm.store.useHls = true
                        })
                        Text("HLS (.m3u8)", Modifier.clickable {
                            useHls = true; vm.store.useHls = true
                        })
                        Spacer(Modifier.width(24.dp))
                        RadioButton(selected = !useHls, onClick = {
                            useHls = false; vm.store.useHls = false
                        })
                        Text("MPEG-TS (.ts)", Modifier.clickable {
                            useHls = false; vm.store.useHls = false
                        })
                    }
                }
            }
        }

        // Which groups appear: its own screen (countries + single groups)
        item { SectionHeader(stringResource(R.string.manage_groups)) }
        item {
            val total = vm.liveCategories.size + vm.vodCategories.size + vm.seriesCategories.size
            val hidden = vm.hiddenGroups.size
            Card(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvClickable(cornerRadius = 12, onClick = onManageGroups)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.manage_groups), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.manage_groups_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (total > 0) {
                            Text(
                                stringResource(R.string.groups_visible_summary, (total - hidden).coerceAtLeast(0), total),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null)
                }
            }
        }

        // About: version + screen info. Focusable, so the remote can scroll all the way down.
        item { SectionHeader(stringResource(R.string.settings_about)) }
        item {
            val dm = context.resources.displayMetrics
            val cfg = androidx.compose.ui.platform.LocalConfiguration.current
            val d = androidx.compose.ui.platform.LocalDensity.current
            val version = remember {
                runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
                    .getOrNull() ?: ""
            }
            Card(Modifier.fillMaxWidth().tvClickable(cornerRadius = 12) {}) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.settings_version, version), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(
                            R.string.screen_info,
                            dm.widthPixels, dm.heightPixels, cfg.screenWidthDp, cfg.screenHeightDp,
                            dm.density, cfg.fontScale, d.density
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun AddAccountForm(vm: MainViewModel, onDone: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var server by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    // Close the form automatically once a new account has successfully been added.
    val initialProfileCount = remember { vm.profiles.size }
    LaunchedEffect(vm.profiles.size, vm.loading, vm.loginError) {
        if (!vm.loading && !vm.loginError && vm.profiles.size > initialProfileCount) {
            onDone()
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.account_add), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.account_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                label = { Text(stringResource(R.string.login_server)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(stringResource(R.string.login_username)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.login_password)) },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            if (vm.loginError) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.login_error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        var normalized = server.trim()
                        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
                            normalized = "http://$normalized"
                        }
                        vm.connect(normalized, username.trim(), password.trim(), name.trim())
                    },
                    enabled = !vm.loading && name.isNotBlank() && server.isNotBlank() && username.isNotBlank() && password.isNotBlank()
                ) {
                    if (vm.loading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.login_button))
                    }
                }
                TextButton(onClick = onDone) {
                    Text(stringResource(R.string.back))
                }
            }
        }
    }
}
