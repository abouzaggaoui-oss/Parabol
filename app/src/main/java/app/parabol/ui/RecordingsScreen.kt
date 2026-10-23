package app.parabol.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.parabol.MainViewModel
import app.parabol.R
import app.parabol.playSingle
import java.text.DateFormat
import java.util.Date

@Composable
fun RecordingsScreen(vm: MainViewModel) {
    val context = LocalContext.current

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { SectionHeader(stringResource(R.string.recordings_scheduled)) }
        if (vm.scheduledRecordings.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.recordings_none_scheduled),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(vm.scheduledRecordings, key = { it.id }) { rec ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(rec.programTitle, style = MaterialTheme.typography.titleSmall)
                            val startDate = DateFormat
                                .getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                .format(Date(rec.startEpochSeconds * 1000))
                            Text(
                                "${rec.channelName} · $startDate",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { vm.cancelScheduledRecording(rec) }) {
                            Text(stringResource(R.string.recording_cancel_confirm))
                        }
                    }
                }
            }
        }

        item { SectionHeader(stringResource(R.string.recordings_completed)) }
        if (vm.completedRecordings.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.recordings_none_completed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(vm.completedRecordings, key = { it.id }) { rec ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { playSingle(context, rec.contentUri, rec.programTitle) }
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(rec.programTitle, style = MaterialTheme.typography.titleSmall)
                            val date = DateFormat
                                .getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                .format(Date(rec.recordedAtEpochSeconds * 1000))
                            Text(
                                "${rec.channelName} · $date",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { vm.deleteCompletedRecording(rec) }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.recording_delete))
                        }
                    }
                }
            }
        }
    }
}
