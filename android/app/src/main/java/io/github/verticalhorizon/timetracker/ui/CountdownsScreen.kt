package io.github.verticalhorizon.timetracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.verticalhorizon.timetracker.AppViewModel
import io.github.verticalhorizon.timetracker.R
import io.github.verticalhorizon.timetracker.ble.Countdown
import io.github.verticalhorizon.timetracker.ble.TrackerConnection.Phase
import io.github.verticalhorizon.timetracker.ble.TrackerProtocol
import io.github.verticalhorizon.timetracker.ble.TrackerState

@Composable
fun CountdownsScreen(
    state: TrackerState?,
    phase: Phase,
    hasTracker: Boolean,
    actions: TrackerActions,
    modifier: Modifier,
    onCountdown: (Int) -> Unit,
) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state == null) {
            item { TrackerNotice(phase, hasTracker, actions, otherwise = stringResource(R.string.countdowns_offline)) }
            return@LazyColumn
        }
        itemsIndexed(state.countdowns) { i, countdown -> CountdownCard(i, countdown) { onCountdown(i) } }
        item {
            Text(
                stringResource(R.string.countdowns_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun CountdownCard(index: Int, countdown: Countdown, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    // Same as the tracker's display: the time left, or the full length when not started
    val shown = if (countdown.remaining > 0) countdown.remaining else countdown.duration
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(colors.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(countdownLabel(index), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.every, duration(countdown.duration)),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
        if (countdown.running) {
            Dot(colors.primary, 6.dp, pulse = true)
            Spacer(Modifier.width(10.dp))
        }
        Text(
            compactClock(shown),
            style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
            color = if (countdown.running) colors.onSurface else colors.onSurfaceVariant,
        )
    }
}

@Composable
fun CountdownSheet(vm: AppViewModel, index: Int, countdown: Countdown?, onDismiss: () -> Unit) {
    val min = TrackerProtocol.MIN_COUNTDOWN / 60
    val max = TrackerProtocol.MAX_COUNTDOWN / 60
    var minutes by remember { mutableLongStateOf(((countdown?.duration ?: 300L) / 60).coerceIn(min, max)) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    val connected = countdown != null
    val editable = connected && !working

    ZenSheet(onDismiss = onDismiss, busy = working) { close ->
        SheetTitle(countdownLabel(index))
        Text(
            stringResource(R.string.rings_every),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            duration(minutes * 60),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 20.dp),
        )
        Stepper(
            label = stringResource(R.string.hours),
            value = minutes / 60,
            enabled = editable,
            onMinus = { minutes = (minutes - 60).coerceAtLeast(min) },
            onPlus = { minutes = (minutes + 60).coerceAtMost(max) },
        )
        Spacer(Modifier.height(12.dp))
        Stepper(
            label = stringResource(R.string.minutes),
            value = minutes % 60,
            enabled = editable,
            onMinus = { minutes = (minutes - 1).coerceAtLeast(min) },
            onPlus = { minutes = (minutes + 1).coerceAtMost(max) },
        )

        val note = when {
            !connected -> stringResource(R.string.countdowns_offline)
            countdown?.running == true -> stringResource(R.string.countdown_running, countdownLabel(index))
            else -> null
        }
        note?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 20.dp),
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
            Spacer(Modifier.weight(1f))
            TextButton(onClick = close, enabled = !working) { Text(stringResource(R.string.cancel)) }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    if (minutes * 60 == countdown?.duration) {
                        close()
                    } else {
                        working = true
                        error = null
                        vm.setCountdown(index, minutes * 60) { err ->
                            working = false
                            error = err
                            if (err == null) close()
                        }
                    }
                },
                enabled = editable,
            ) {
                if (working) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(stringResource(R.string.set))
                }
            }
        }
    }
}

@Composable
private fun Stepper(label: String, value: Long, enabled: Boolean, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        StepButton(ZenIcons.Minus, "${stringResource(R.string.less)} · $label", enabled, onMinus)
        Text(
            value.toString(),
            style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
            textAlign = TextAlign.Center,
            modifier = Modifier.width(64.dp),
        )
        StepButton(ZenIcons.Plus, "${stringResource(R.string.more)} · $label", enabled, onPlus)
    }
}
