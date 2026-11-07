package io.github.verticalhorizon.timetracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.verticalhorizon.timetracker.AppViewModel
import io.github.verticalhorizon.timetracker.R
import io.github.verticalhorizon.timetracker.data.AppData
import io.github.verticalhorizon.timetracker.data.Category
import io.github.verticalhorizon.timetracker.ui.theme.CategoryColors
import io.github.verticalhorizon.timetracker.ui.theme.categoryColor

private const val MAX_NAME = 30

@Composable
fun CategoriesScreen(data: AppData, modifier: Modifier, onCategory: (Category) -> Unit) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 112.dp),
    ) {
        if (data.categories.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.categories_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
                )
            }
        }
        items(data.categories, key = { it.id }) { category ->
            val seconds = data.tasks.filter { it.categoryId == category.id }.sumOf { it.seconds }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable { onCategory(category) }
                    .padding(horizontal = 4.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Dot(categoryColor(category.color), 12.dp)
                Spacer(Modifier.width(16.dp))
                Text(
                    category.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    hoursMinutes(seconds),
                    style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategorySheet(vm: AppViewModel, data: AppData, categoryId: Long?, onDismiss: () -> Unit) {
    val category = data.categories.find { it.id == categoryId }
    var name by remember { mutableStateOf(category?.name ?: "") }
    var color by remember { mutableIntStateOf(category?.color ?: (data.categories.size % CategoryColors.size)) }
    var confirmDelete by remember { mutableStateOf(false) }

    ZenSheet(onDismiss = onDismiss) { close ->
        SheetTitle(stringResource(if (category == null) R.string.new_category else R.string.edit_category))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(MAX_NAME) },
            label = { Text(stringResource(R.string.name)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(24.dp))
        SectionLabel(stringResource(R.string.color))
        Spacer(Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CategoryColors.forEachIndexed { i, swatch ->
                val selected = i == color
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .border(2.dp, if (selected) MaterialTheme.colorScheme.onSurfaceVariant else Color.Transparent, CircleShape)
                        .selectable(selected = selected, role = Role.RadioButton) { color = i },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(swatch),
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (category != null) {
                TextButton(onClick = { confirmDelete = true }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    vm.saveCategory(category?.id, name.trim(), color)
                    close()
                },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.save))
            }
        }

        if (confirmDelete && category != null) {
            ConfirmDialog(
                title = stringResource(R.string.delete_category_title),
                text = stringResource(R.string.delete_category_text, category.name),
                confirm = stringResource(R.string.delete),
                onConfirm = {
                    confirmDelete = false
                    vm.deleteCategory(category.id)
                    close()
                },
                onDismiss = { confirmDelete = false },
            )
        }
    }
}
