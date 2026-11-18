package org.arkikeskus.launcher.ui.component

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription

/**
 * One extra action an app tile offers to a screen reader, on the swipe that opens the actions
 * menu ("Переместить", "Убрать с экрана"). [label] is what the menu says; [action] runs it.
 */
class AppTileAction(val label: String, val action: () -> Unit)

/**
 * A whole app tile as ONE stop for a screen reader: a button that says its name once.
 *
 * Without this an app tile is a picture with a described name and a text under it, so TalkBack
 * called it an image and offered nothing to do with it. Cleared and set here, the tile says
 * "[label], кнопка", activates with a double tap ([onClick]) and holds the actions a swipe opens
 * ([actions]). Everything drawn inside — the picture, the label, the badge — is dropped from the
 * tree: the tile is the only thing there is to stop at.
 *
 * A null [onClick] leaves the tile a plain spoken stop (a settings preview, a folder preview cell).
 * [onLongClick] and [actions] need an [onClick] to be announced on a button: a screen reader only
 * reads the actions of a clickable node.
 */
fun Modifier.appTileSemantics(
    label: String,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    stateDescription: String? = null,
    actions: List<AppTileAction> = emptyList(),
): Modifier = clearAndSetSemantics {
    contentDescription = label
    role = Role.Button
    if (stateDescription != null) this.stateDescription = stateDescription
    if (onClick != null) {
        this.onClick(label = onClickLabel) {
            onClick()
            true
        }
        if (onLongClick != null) {
            this.onLongClick(label = onLongClickLabel) {
                onLongClick()
                true
            }
        }
        if (actions.isNotEmpty()) {
            customActions = actions.map { tile ->
                CustomAccessibilityAction(tile.label) {
                    tile.action()
                    true
                }
            }
        }
    }
}
