// A back stack of screens kept in Compose state; no Navigation library.
package io.github.lopezec007.tracker.ui.nav

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList

sealed class Screen {
    object Setup : Screen()
    object Main : Screen()
    object Settings : Screen()
}

class Nav(start: Screen) {
    val stack: SnapshotStateList<Screen> = mutableStateListOf(start)
    val current: Screen get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    fun push(s: Screen) {
        stack.add(s)
    }

    /** Returns false when already at the root (the activity should finish). */
    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun reset(s: Screen) {
        stack.clear()
        stack.add(s)
    }
}
