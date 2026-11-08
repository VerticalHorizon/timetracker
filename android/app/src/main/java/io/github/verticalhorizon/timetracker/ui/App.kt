package io.github.verticalhorizon.timetracker.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.verticalhorizon.timetracker.AppViewModel
import io.github.verticalhorizon.timetracker.R
import io.github.verticalhorizon.timetracker.ble.TrackerConnection

private sealed interface Sheet {
    data object Tracker : Sheet
    data class TaskEditor(val taskId: Long?) : Sheet
    data class CategoryEditor(val categoryId: Long?) : Sheet
    data class CountdownEditor(val index: Int) : Sheet
}

@SuppressLint("MissingPermission") // ACTION_REQUEST_ENABLE is only offered once permissions are granted
@Composable
fun App(vm: AppViewModel) {
    val data by vm.data.collectAsStateWithLifecycle()
    val state by vm.trackerState.collectAsStateWithLifecycle()
    val phase by vm.phase.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }

    val activity = LocalActivity.current
    var permissionsBlocked by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        // Denied without the system offering to ask again: only the settings can help now
        permissionsBlocked = activity != null && result.values.any { !it } &&
            TrackerConnection.PERMISSIONS.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
    }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    val actions = TrackerActions(
        requestPermissions = {
            if (permissionsBlocked && activity != null) {
                val uri = Uri.fromParts("package", activity.packageName, null)
                activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
            } else {
                permissionLauncher.launch(TrackerConnection.PERMISSIONS)
            }
        },
        permissionsBlocked = permissionsBlocked,
        enableBluetooth = {
            try {
                enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (e: ActivityNotFoundException) {
                // No system dialog for it on this phone: the quick settings still work
            } catch (e: SecurityException) {
                // Permission revoked meanwhile: the notice asks for it again
            }
        },
        openSheet = { sheet = Sheet.Tracker },
    )

    val titles = listOf(R.string.tab_tasks, R.string.tab_countdowns, R.string.tab_categories).map { stringResource(it) }
    val hasTracker = data.deviceAddress != null

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { Header(titles[tab], phase, hasTracker) { sheet = Sheet.Tracker } },
        bottomBar = { TabBar(titles, tab) { tab = it } },
        floatingActionButton = {
            when (tab) {
                0 -> AddButton(stringResource(R.string.new_task)) { sheet = Sheet.TaskEditor(null) }
                2 -> AddButton(stringResource(R.string.new_category)) { sheet = Sheet.CategoryEditor(null) }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab) {
            0 -> TasksScreen(data, state, phase, actions, modifier) { sheet = Sheet.TaskEditor(it.id) }
            1 -> CountdownsScreen(state, phase, hasTracker, actions, modifier) { sheet = Sheet.CountdownEditor(it) }
            else -> CategoriesScreen(data, modifier) { sheet = Sheet.CategoryEditor(it.id) }
        }
    }

    val close = { sheet = null }
    when (val s = sheet) {
        Sheet.Tracker -> TrackerSheet(vm, phase, data.deviceAddress, actions, close)
        is Sheet.TaskEditor -> TaskSheet(vm, data, state, s.taskId, close)
        is Sheet.CategoryEditor -> CategorySheet(vm, data, s.categoryId, close)
        is Sheet.CountdownEditor -> CountdownSheet(vm, s.index, state?.countdowns?.getOrNull(s.index), close)
        null -> Unit
    }
}
