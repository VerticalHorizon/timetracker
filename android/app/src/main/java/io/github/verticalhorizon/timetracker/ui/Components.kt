package io.github.verticalhorizon.timetracker.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.verticalhorizon.timetracker.R
import io.github.verticalhorizon.timetracker.ble.TrackerConnection.Phase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Thin line icons, drawn here instead of pulling in an icon library. */
object ZenIcons {
    val Plus: ImageVector by lazy {
        stroke("plus") { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) }
    }
    val Minus: ImageVector by lazy { stroke("minus") { moveTo(5f, 12f); lineTo(19f, 12f) } }

    private fun stroke(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, pathBuilder = block)
        }.build()
}

/** What the tracker controls in the UI need from the activity. */
class TrackerActions(
    val requestPermissions: () -> Unit,
    val permissionsBlocked: Boolean,
    val enableBluetooth: () -> Unit,
    val openSheet: () -> Unit,
)

@Composable
fun Dot(color: Color, size: Dp = 8.dp, pulse: Boolean = false) {
    val alpha = if (pulse) {
        val transition = rememberInfiniteTransition(label = "pulse")
        transition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
            label = "alpha",
        ).value
    } else {
        1f
    }
    Box(Modifier.size(size).alpha(alpha).clip(CircleShape).background(color))
}

@Composable
fun Header(title: String, phase: Phase, hasTracker: Boolean, onStatus: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 28.dp, end = 16.dp, top = 24.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        StatusPill(phase, hasTracker, onStatus)
    }
}

@Composable
private fun StatusPill(phase: Phase, hasTracker: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val (label, color) = when {
        !hasTracker -> stringResource(R.string.status_connect) to colors.outline
        phase == Phase.Connected -> stringResource(R.string.status_connected) to colors.primary
        phase == Phase.NoPermission -> stringResource(R.string.status_permission) to colors.error
        phase == Phase.BluetoothOff -> stringResource(R.string.status_bluetooth_off) to colors.outline
        phase == Phase.Incompatible -> stringResource(R.string.status_incompatible) to colors.error
        else -> stringResource(R.string.status_searching) to colors.secondary
    }
    Row(
        Modifier
            .clip(CircleShape)
            .border(1.dp, colors.outlineVariant, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(color, pulse = hasTracker && (phase == Phase.Connecting || phase == Phase.Idle))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
    }
}

@Composable
fun TabBar(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.background(colors.background)) {
        HorizontalDivider(thickness = 0.5.dp, color = colors.outlineVariant)
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(64.dp),
        ) {
            labels.forEachIndexed { i, label ->
                val isSelected = i == selected
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(i) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) colors.onBackground else colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) colors.primary else Color.Transparent),
                    )
                }
            }
        }
    }
}

@Composable
fun AddButton(description: String, onClick: () -> Unit) {
    FloatingActionButton(
        onClick = onClick,
        shape = CircleShape,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
    ) {
        Icon(ZenIcons.Plus, contentDescription = description)
    }
}

/** A quiet card with a sentence and, maybe, one action. */
@Composable
fun Notice(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(20.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}

/** The notice that fits a missing connection, if the user can do something about it. */
@Composable
fun TrackerNotice(phase: Phase, hasTracker: Boolean, actions: TrackerActions, otherwise: String? = null) {
    when {
        !hasTracker ->
            Notice(stringResource(R.string.notice_no_tracker), stringResource(R.string.find_tracker), actions.openSheet)
        phase == Phase.NoPermission ->
            Notice(
                stringResource(R.string.notice_permission),
                stringResource(if (actions.permissionsBlocked) R.string.open_settings else R.string.allow),
                actions.requestPermissions,
            )
        phase == Phase.BluetoothOff ->
            Notice(stringResource(R.string.notice_bluetooth_off), stringResource(R.string.turn_on), actions.enableBluetooth)
        phase == Phase.Incompatible -> Notice(stringResource(R.string.notice_incompatible))
        otherwise != null -> Notice(otherwise)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
fun SheetTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 20.dp))
}

@Composable
fun ZenChip(label: String, selected: Boolean, enabled: Boolean = true, dot: Color? = null, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        leadingIcon = if (dot != null) { { Dot(dot) } } else null,
        shape = CircleShape,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}

/**
 * A bottom sheet that can't be swiped away while [busy]. The content gets a
 * `close` function that slides the sheet out before [onDismiss].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZenSheet(
    onDismiss: () -> Unit,
    busy: Boolean = false,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    val busyNow by rememberUpdatedState(busy)
    val state = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !busyNow },
    )
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { state.hide() }.invokeOnCompletion { onDismiss() } }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            content(close)
        }
    }
}

/** A round −/+ button; holding it keeps stepping. */
@Composable
fun StepButton(icon: ImageVector, description: String, enabled: Boolean, onStep: () -> Unit) {
    val step by rememberUpdatedState(onStep)
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(colors.surfaceContainerHighest)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
                if (!enabled) disabled()
                onClick {
                    if (enabled) step()
                    enabled
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    step()
                    val repeat = scope.launch {
                        delay(450)
                        while (true) {
                            step()
                            delay(70)
                        }
                    }
                    tryAwaitRelease()
                    repeat.cancel()
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) colors.onSurface else colors.outline)
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirm, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}
