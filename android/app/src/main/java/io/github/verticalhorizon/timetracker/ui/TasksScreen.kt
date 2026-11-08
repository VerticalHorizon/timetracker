package io.github.verticalhorizon.timetracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.verticalhorizon.timetracker.AppViewModel
import io.github.verticalhorizon.timetracker.R
import io.github.verticalhorizon.timetracker.ble.TrackerConnection.Phase
import io.github.verticalhorizon.timetracker.ble.TrackerState
import io.github.verticalhorizon.timetracker.data.AppData
import io.github.verticalhorizon.timetracker.data.Category
import io.github.verticalhorizon.timetracker.data.Task
import io.github.verticalhorizon.timetracker.ui.theme.categoryColor

private const val DEFAULT_TRACKERS = 5
private const val MAX_NAME = 60

private class TaskGroup(val category: Category?, val tasks: List<Task>) {
    val seconds = tasks.sumOf { it.seconds }
}

/** Tasks by category, in the order the categories were made; uncategorized last. */
private fun groupTasks(data: AppData): List<TaskGroup> {
    val known = data.categories.map { it.id }.toSet()
    val byCategory = data.tasks.groupBy { task -> task.categoryId?.takeIf { it in known } }
    return data.categories.mapNotNull { c -> byCategory[c.id]?.let { TaskGroup(c, it) } } +
        listOfNotNull(byCategory[null]?.let { TaskGroup(null, it) })
}

@Composable
fun TasksScreen(
    data: AppData,
    state: TrackerState?,
    phase: Phase,
    actions: TrackerActions,
    modifier: Modifier,
    onTask: (Task) -> Unit,
) {
    val groups = remember(data.tasks, data.categories) { groupTasks(data) }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 112.dp),
    ) {
        item(key = "notice") { TrackerNotice(phase, data.deviceAddress != null, actions) }
        if (state != null && state.running in state.trackers.indices) {
            item(key = "now") { NowCard(data, state, state.running) }
        }
        if (data.tasks.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.tasks_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
                )
            }
        }
        groups.forEach { group ->
            item(key = "group-${group.category?.id}") { GroupHeader(group) }
            items(group.tasks, key = { it.id }) { task -> TaskRow(task, state) { onTask(task) } }
        }
    }
}

@Composable
private fun NowCard(data: AppData, state: TrackerState, slot: Int) {
    val colors = MaterialTheme.colorScheme
    val task = data.tasks.find { it.link?.slot == slot }
    val category = task?.categoryId?.let { id -> data.categories.find { it.id == id } }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 12.dp)
            .clip(MaterialTheme.shapes.large)
            .background(colors.primaryContainer.copy(alpha = 0.6f))
            .padding(24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(colors.primary, pulse = true)
            Spacer(Modifier.width(10.dp))
            SectionLabel(stringResource(R.string.now, trackerLabel(slot)))
        }
        Spacer(Modifier.height(14.dp))
        Text(
            task?.name ?: stringResource(R.string.now_unlinked),
            style = MaterialTheme.typography.titleLarge,
            color = colors.onPrimaryContainer,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            clock(task?.seconds ?: state.trackers[slot].shown),
            style = MaterialTheme.typography.displayMedium,
            color = colors.onPrimaryContainer,
        )
        if (category != null) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(categoryColor(category.color))
                Spacer(Modifier.width(8.dp))
                Text(category.name, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun GroupHeader(group: TaskGroup) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 24.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        group.category?.let {
            Dot(categoryColor(it.color))
            Spacer(Modifier.width(10.dp))
        }
        SectionLabel(group.category?.name ?: stringResource(R.string.no_category), Modifier.weight(1f))
        Text(
            hoursMinutes(group.seconds),
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TaskRow(task: Task, state: TrackerState?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val slot = task.link?.slot
    val running = slot != null && state?.running == slot
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(task.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (slot != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(trackerLabel(slot), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    if (running) {
                        Spacer(Modifier.width(6.dp))
                        Dot(colors.primary, 6.dp)
                    }
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Text(
            hoursMinutes(task.seconds),
            style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
            color = if (running) colors.primary else colors.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskSheet(vm: AppViewModel, data: AppData, state: TrackerState?, taskId: Long?, onDismiss: () -> Unit) {
    // A new task gets its id on the first save; later saves (after an error) edit it
    var editingId by remember { mutableStateOf(taskId) }
    val task = data.tasks.find { it.id == editingId }
    var name by remember { mutableStateOf(task?.name ?: "") }
    var categoryId by remember { mutableStateOf(task?.categoryId) }
    var slot by remember { mutableStateOf(task?.link?.slot) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val connected = state != null
    val slots = state?.trackers?.size ?: DEFAULT_TRACKERS

    ZenSheet(onDismiss = onDismiss, busy = working) { close ->
        SheetTitle(stringResource(if (task == null) R.string.new_task else R.string.edit_task))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(MAX_NAME) },
            label = { Text(stringResource(R.string.name)) },
            singleLine = true,
            enabled = !working,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(24.dp))
        SectionLabel(stringResource(R.string.category))
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZenChip(stringResource(R.string.none), selected = categoryId == null, enabled = !working) { categoryId = null }
            data.categories.forEach { c ->
                ZenChip(c.name, selected = categoryId == c.id, enabled = !working, dot = categoryColor(c.color)) {
                    categoryId = c.id
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel(stringResource(R.string.timer))
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val canPick = connected && !working
            ZenChip(stringResource(R.string.none), selected = slot == null, enabled = canPick) { slot = null }
            repeat(slots) { i ->
                ZenChip(trackerLabel(i), selected = slot == i, enabled = canPick) { slot = i }
            }
        }
        timerHint(data, task, slot, connected)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        error?.let {
            Text(
                stringResource(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (task != null) {
                TextButton(onClick = { confirmDelete = true }, enabled = !working) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    working = true
                    error = null
                    vm.saveTask(editingId, name.trim(), categoryId, slot) { id, err ->
                        editingId = id
                        working = false
                        error = err
                        if (err == null) close()
                    }
                },
                enabled = name.isNotBlank() && !working,
            ) {
                if (working) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(stringResource(R.string.save))
                }
            }
        }

        if (confirmDelete && task != null) {
            ConfirmDialog(
                title = stringResource(R.string.delete_task_title),
                text = stringResource(R.string.delete_task_text, task.name, hoursMinutes(task.seconds)),
                confirm = stringResource(R.string.delete),
                onConfirm = {
                    confirmDelete = false
                    vm.deleteTask(task.id)
                    close()
                },
                onDismiss = { confirmDelete = false },
            )
        }
    }
}

/** Says what saving will do to the timers, before it happens. */
@Composable
private fun timerHint(data: AppData, task: Task?, slot: Int?, connected: Boolean): String? {
    val current = task?.link?.slot
    val kept = hoursMinutes(task?.seconds ?: 0)
    return when {
        !connected -> stringResource(R.string.timer_offline)
        slot == current && slot != null -> stringResource(R.string.timer_current, trackerLabel(slot))
        slot == current -> stringResource(R.string.timer_none)
        slot == null -> stringResource(R.string.timer_unlink, kept)
        else -> {
            val holder = data.tasks.find { it.link?.slot == slot && it.id != task?.id }
            listOfNotNull(
                stringResource(R.string.timer_reset, trackerLabel(slot)),
                if (task != null && task.seconds > 0) stringResource(R.string.timer_keeps, kept) else null,
                holder?.let { stringResource(R.string.timer_moves, it.name, trackerLabel(slot)) },
            ).joinToString(" ")
        }
    }
}
