package io.github.verticalhorizon.timetracker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.verticalhorizon.timetracker.AppViewModel
import io.github.verticalhorizon.timetracker.R
import io.github.verticalhorizon.timetracker.ble.TrackerConnection.Phase
import io.github.verticalhorizon.timetracker.ble.TrackerConnection.Readiness
import kotlinx.coroutines.delay

@Composable
fun TrackerSheet(vm: AppViewModel, phase: Phase, address: String?, actions: TrackerActions, onDismiss: () -> Unit) {
    val found by vm.found.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()

    // Bluetooth can be switched on or off from the quick settings while the sheet is open
    var readiness by remember { mutableStateOf(vm.readiness()) }
    LaunchedEffect(Unit) {
        while (true) {
            readiness = vm.readiness()
            delay(700)
        }
    }
    LaunchedEffect(readiness, address) {
        if (address == null && readiness == Readiness.Ready) vm.startScan()
    }
    DisposableEffect(Unit) { onDispose { vm.stopScan() } }

    ZenSheet(onDismiss = onDismiss) { close ->
        SheetTitle(stringResource(R.string.tracker))
        when {
            readiness == Readiness.NoPermission -> {
                Body(stringResource(R.string.sheet_permission))
                Button(onClick = actions.requestPermissions) {
                    Text(stringResource(if (actions.permissionsBlocked) R.string.open_settings else R.string.allow))
                }
            }

            readiness == Readiness.BluetoothOff -> {
                Body(stringResource(R.string.sheet_bluetooth_off))
                Button(onClick = actions.enableBluetooth) { Text(stringResource(R.string.turn_on)) }
            }

            address == null -> {
                Body(
                    stringResource(
                        when {
                            found.isNotEmpty() -> R.string.sheet_pick
                            scanning -> R.string.sheet_scanning
                            else -> R.string.sheet_nothing_found
                        },
                    ),
                )
                found.forEach { tracker ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable {
                                vm.useTracker(tracker.address)
                                close()
                            }
                            .padding(horizontal = 4.dp, vertical = 12.dp),
                    ) {
                        Text(tracker.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            tracker.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (scanning) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                } else {
                    TextButton(onClick = vm::startScan) { Text(stringResource(R.string.search_again)) }
                }
            }

            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val connected = phase == Phase.Connected
                    Dot(
                        if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                        pulse = !connected,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(
                            when (phase) {
                                Phase.Connected -> R.string.status_connected
                                Phase.Incompatible -> R.string.status_incompatible
                                else -> R.string.sheet_scanning
                            },
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Text(
                    address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 18.dp, top = 2.dp),
                )
                Spacer(Modifier.height(16.dp))
                Body(stringResource(R.string.sheet_about))
                TextButton(onClick = vm::forgetTracker) {
                    Text(stringResource(R.string.forget_tracker), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun Body(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 16.dp),
    )
}
