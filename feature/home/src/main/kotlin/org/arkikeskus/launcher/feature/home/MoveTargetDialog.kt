package org.arkikeskus.launcher.feature.home

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.arkikeskus.launcher.R

/**
 * What the move dialog is moving: the name it goes by, where it stands now, and the call that lands it
 * somewhere. Apps and folders move through different repository calls, so the dialog keeps the call,
 * not the item.
 */
internal data class MoveRequest(
    val label: String,
    val page: Int,
    val cellX: Int,
    val cellY: Int,
    /** Lands the item on the picked cell; false when the grid refuses it (a widget, a locked page). */
    val move: suspend (page: Int, cellX: Int, cellY: Int) -> Boolean,
)

/** One cell of the move dialog's grid: what sits there now, and whether it can take the moved icon. */
internal data class MoveCell(
    val x: Int,
    val y: Int,
    /** The occupant's name, or null for an empty cell. */
    val occupant: String?,
    /** Not a target: the icon's own cell, or a cell inside a widget (a widget is not swappable). */
    val blocked: Boolean,
    /** This is where the icon stands now — named aloud as such rather than by what it would land on. */
    val current: Boolean,
)

/**
 * Every cell of the grid it might be, one row per cell, for whoever moves icons with a screen reader:
 * dragging needs a finger on a spot TalkBack does not describe, so a swipe-down action lists the cells
 * instead. Widget cells are listed but not offered — a widget is not swappable (the repository refuses
 * it), and the icon's own cell is its current position.
 */
@Composable
internal fun MoveTargetDialog(
    appLabel: String,
    entries: List<HomeEntry>,
    pageCount: Int,
    columns: Int,
    rows: Int,
    currentPage: Int,
    currentCellX: Int,
    currentCellY: Int,
    onPick: (page: Int, cellX: Int, cellY: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val widgetLabel = stringResource(R.string.tile_move_widget)
    var page by remember { mutableIntStateOf(currentPage.coerceIn(0, pageCount - 1)) }
    val cells = remember(entries, page, columns, rows, widgetLabel, currentPage, currentCellX, currentCellY) {
        moveCells(
            entries = entries,
            page = page,
            columns = columns,
            rows = rows,
            widgetLabel = widgetLabel,
            currentPage = currentPage,
            currentCellX = currentCellX,
            currentCellY = currentCellY,
        )
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)) {
                Text(
                    text = stringResource(R.string.tile_move_title, appLabel),
                    style = MaterialTheme.typography.titleLarge,
                )
                if (pageCount > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        repeat(pageCount) { index ->
                            FilterChip(
                                selected = index == page,
                                onClick = { page = index },
                                label = { Text(stringResource(R.string.tile_move_page, index + 1)) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(cells, key = { it.y * columns + it.x }) { cell ->
                        val position = stringResource(R.string.tile_move_cell, cell.y + 1, cell.x + 1)
                        val what = when {
                            cell.current -> stringResource(R.string.tile_move_here)
                            else -> cell.occupant ?: stringResource(R.string.tile_move_empty)
                        }
                        TextButton(
                            onClick = { onPick(page, cell.x, cell.y) },
                            enabled = !cell.blocked,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "$position — $what",
                                modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }
}

/**
 * The cells of [page], row by row, each naming what stands there. A widget's rectangle marks all of
 * its cells: none of them can take an icon, and the icon would vanish under it if it could.
 */
internal fun moveCells(
    entries: List<HomeEntry>,
    page: Int,
    columns: Int,
    rows: Int,
    widgetLabel: String,
    currentPage: Int,
    currentCellX: Int,
    currentCellY: Int,
): List<MoveCell> {
    val occupants = arrayOfNulls<String>(columns * rows)
    val inWidget = BooleanArray(columns * rows)
    for (entry in entries) {
        if (entry.page != page) continue
        val (spanX, spanY, label) = footprintOf(entry, widgetLabel)
        for (dy in 0 until spanY) {
            for (dx in 0 until spanX) {
                val x = entry.cellX + dx
                val y = entry.cellY + dy
                if (x in 0 until columns && y in 0 until rows) {
                    occupants[y * columns + x] = label
                    if (entry is PlacedWidget || entry is PendingWidget || entry is PlacedBuiltin) {
                        inWidget[y * columns + x] = true
                    }
                }
            }
        }
    }
    return buildList(rows * columns) {
        for (y in 0 until rows) {
            for (x in 0 until columns) {
                val current = page == currentPage && x == currentCellX && y == currentCellY
                add(
                    MoveCell(
                        x = x,
                        y = y,
                        occupant = occupants[y * columns + x],
                        blocked = current || inWidget[y * columns + x],
                        current = current,
                    ),
                )
            }
        }
    }
}

/** How many cells an entry covers and what to call it there: spans belong to widgets alone. */
private fun footprintOf(entry: HomeEntry, widgetLabel: String): Triple<Int, Int, String> =
    when (entry) {
        is PlacedApp -> Triple(1, 1, entry.app.label)
        is PlacedFolder -> Triple(1, 1, entry.name)
        is PlacedShortcut -> Triple(1, 1, entry.label)
        is PlacedWidget -> Triple(entry.spanX, entry.spanY, widgetLabel)
        is PendingWidget -> Triple(entry.spanX, entry.spanY, widgetLabel)
        is PlacedBuiltin -> Triple(entry.spanX, entry.spanY, widgetLabel)
    }
