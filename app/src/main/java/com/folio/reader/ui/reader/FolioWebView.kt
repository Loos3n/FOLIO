package com.folio.reader.ui.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebView

/**
 * The book's WebView, with its own menu for selected text (Highlight, Note, Define, Copy)
 * in place of the system's. Picking an action reads the selection from the page first,
 * then hands it to [onSelectionAction] as the JSON returned by `folio.selection()`.
 */
@SuppressLint("ViewConstructor")
class FolioWebView(context: Context) : WebView(context) {

    /** The chapter request last loaded, so the same one isn't loaded twice. */
    var loadedToken: Long = -1

    var selectionActions: List<String> = emptyList()
    var onSelectionAction: (action: Int, json: String) -> Unit = { _, _ -> }

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? =
        super.startActionMode(wrap(callback), type)

    override fun startActionMode(callback: ActionMode.Callback): ActionMode? =
        super.startActionMode(wrap(callback))

    private fun wrap(original: ActionMode.Callback): ActionMode.Callback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            val show = original.onCreateActionMode(mode, menu)
            fill(menu)
            return show
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            original.onPrepareActionMode(mode, menu)
            fill(menu)
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val action = item.itemId - FIRST_ID
            if (action !in selectionActions.indices) return original.onActionItemClicked(mode, item)
            evaluateJavascript("window.folio ? folio.selection() : null") { json ->
                Log.d("Folio", "selection action $action: $json")
                mode.finish()
                evaluateJavascript("window.folio && folio.clearSelection()", null)
                if (json != null && json != "null") onSelectionAction(action, json)
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) = original.onDestroyActionMode(mode)

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            if (original is ActionMode.Callback2) original.onGetContentRect(mode, view, outRect)
            else super.onGetContentRect(mode, view, outRect)
        }
    }

    private fun fill(menu: Menu) {
        menu.clear()
        selectionActions.forEachIndexed { i, label ->
            menu.add(Menu.NONE, FIRST_ID + i, i, label).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
    }

    private companion object {
        const val FIRST_ID = 0x4F10
    }
}
