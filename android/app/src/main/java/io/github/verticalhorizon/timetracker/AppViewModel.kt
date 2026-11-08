package io.github.verticalhorizon.timetracker

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.verticalhorizon.timetracker.ble.FoundTracker
import io.github.verticalhorizon.timetracker.ble.TrackerConnection
import io.github.verticalhorizon.timetracker.ble.TrackerState
import io.github.verticalhorizon.timetracker.data.AppData
import io.github.verticalhorizon.timetracker.data.Category
import io.github.verticalhorizon.timetracker.data.Ledger
import io.github.verticalhorizon.timetracker.data.Store
import io.github.verticalhorizon.timetracker.data.Task
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = Store(app)
    private val _data = MutableStateFlow(store.load())
    val data: StateFlow<AppData> = _data.asStateFlow()

    private val tracker = TrackerConnection(app)
    val phase: StateFlow<TrackerConnection.Phase> = tracker.phase
    val trackerState: StateFlow<TrackerState?> = tracker.state

    private val _found = MutableStateFlow<List<FoundTracker>>(emptyList())
    val found: StateFlow<List<FoundTracker>> = _found.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    private var scanJob: Job? = null

    // Writes outlive the ViewModel and happen one at a time, in order
    @OptIn(ExperimentalCoroutinesApi::class)
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private var lastCreditSave = 0L
    private var busy = false

    init {
        viewModelScope.launch {
            tracker.state.filterNotNull().collect { credit(it) }
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    fun onForeground() {
        _data.value.deviceAddress?.let { tracker.start(it) }
    }

    fun onBackground() {
        tracker.stop()
        stopScan()
        save()
    }

    override fun onCleared() {
        tracker.stop()
        save()
    }

    // ── Tracker ──────────────────────────────────────────────────────────────

    fun readiness(): TrackerConnection.Readiness = tracker.readiness()

    fun startScan() {
        scanJob?.cancel()
        _found.value = emptyList()
        _scanning.value = true
        scanJob = viewModelScope.launch {
            try {
                withTimeoutOrNull(SCAN_MS) {
                    tracker.scan().collect { f ->
                        _found.update { list -> (list.filter { it.address != f.address } + f).sortedByDescending { it.rssi } }
                    }
                }
            } catch (e: IOException) {
                // Scan refused or failed: the sheet offers to search again
            } finally {
                _scanning.value = false
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
    }

    fun useTracker(address: String) {
        stopScan()
        change { it.copy(deviceAddress = address) }
        tracker.start(address)
    }

    fun forgetTracker() {
        tracker.stop()
        change { it.copy(deviceAddress = null) }
    }

    // ── Tasks ────────────────────────────────────────────────────────────────

    /**
     * Saves a task's name and category, then moves it to tracker [slot] (null: no tracker)
     * if that changed. [done] gets the task's id and an error message, or null on success.
     */
    fun saveTask(id: Long?, name: String, categoryId: Long?, slot: Int?, done: (Long, Int?) -> Unit) {
        val taskId = id ?: _data.value.nextId
        change { d ->
            if (id == null) {
                d.copy(tasks = d.tasks + Task(taskId, name, categoryId), nextId = taskId + 1)
            } else {
                d.copy(tasks = d.tasks.map { if (it.id == id) it.copy(name = name, categoryId = categoryId) else it })
            }
        }
        val linked = _data.value.tasks.find { it.id == taskId }?.link?.slot
        if (slot == linked) {
            done(taskId, null)
            return
        }
        viewModelScope.launch { done(taskId, link(taskId, slot)) }
    }

    fun deleteTask(id: Long) = change { d -> d.copy(tasks = d.tasks.filter { it.id != id }) }

    /**
     * Links a task to tracker [slot], or unlinks it when [slot] is null. Returns the
     * string resource of an error message, or null on success.
     *
     * The tracker's shown time is reset so that it starts from zero for this task,
     * while the task keeps what it had. If the reset fails, the old links come back.
     */
    private suspend fun link(taskId: Long, slot: Int?): Int? {
        if (busy) return R.string.error_busy
        busy = true
        try {
            credit(tracker.refresh()) // settle what every tracker counted so far
            if (slot == null) {
                change { it.copy(tasks = Ledger.unlink(it.tasks, taskId)) }
                return null
            }
            val previous = _data.value.tasks
                .filter { it.id == taskId || it.link?.slot == slot }
                .associate { it.id to it.link }
            change { it.copy(tasks = Ledger.detach(it.tasks, taskId, slot)) }

            val acked = try {
                tracker.resetTracker(slot)
            } catch (e: IOException) {
                change { d ->
                    d.copy(tasks = d.tasks.map { t -> if (t.id in previous) t.copy(link = previous[t.id]) else t })
                }
                return R.string.error_no_reply
            }
            credit(acked)
            change { it.copy(tasks = Ledger.attach(it.tasks, taskId, slot, acked)) }
            return null
        } catch (e: IOException) {
            return R.string.error_not_connected
        } finally {
            busy = false
        }
    }

    // ── Categories ───────────────────────────────────────────────────────────

    fun saveCategory(id: Long?, name: String, color: Int) = change { d ->
        if (id == null) {
            d.copy(categories = d.categories + Category(d.nextId, name, color), nextId = d.nextId + 1)
        } else {
            d.copy(categories = d.categories.map { if (it.id == id) it.copy(name = name, color = color) else it })
        }
    }

    /** Deletes a category; its tasks stay, without a category. */
    fun deleteCategory(id: Long) = change { d ->
        d.copy(
            categories = d.categories.filter { it.id != id },
            tasks = d.tasks.map { if (it.categoryId == id) it.copy(categoryId = null) else it },
        )
    }

    // ── Countdowns ───────────────────────────────────────────────────────────

    fun setCountdown(index: Int, seconds: Long, done: (Int?) -> Unit) {
        viewModelScope.launch {
            val error = try {
                tracker.setCountdown(index, seconds)
                null
            } catch (e: IOException) {
                R.string.error_no_reply
            }
            done(error)
        }
    }

    // ── State ────────────────────────────────────────────────────────────────

    private fun credit(state: TrackerState) {
        val d = _data.value
        val tasks = Ledger.credit(d.tasks, state)
        if (tasks === d.tasks) return
        _data.value = d.copy(tasks = tasks)
        // Credits are cheap to lose: the next snapshot recomputes them from the saved
        // (seconds, lastTotal) pair. So they are saved now and then, not every second.
        val now = SystemClock.elapsedRealtime()
        if (now - lastCreditSave >= CREDIT_SAVE_MS) {
            lastCreditSave = now
            save()
        }
    }

    private fun change(transform: (AppData) -> AppData) {
        _data.update(transform)
        save()
    }

    private fun save() {
        val snapshot = _data.value
        writer.launch { store.save(snapshot) }
    }

    private companion object {
        const val SCAN_MS = 15_000L
        const val CREDIT_SAVE_MS = 15_000L
    }
}
