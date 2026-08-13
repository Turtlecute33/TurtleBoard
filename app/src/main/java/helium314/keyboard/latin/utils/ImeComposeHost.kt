// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin.utils

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Owners that Compose needs to run inside the IME window.
 *
 * Compose creates the recomposer on the root of the window, not on the [androidx.compose.ui.platform.ComposeView],
 * so the owners have to be found from that root: setting them on the Compose view alone throws
 * "ViewTreeLifecycleOwner not found". The window of an IME is a dialog that lives as long as the
 * process, and the input view can be replaced (theme or configuration change) while the window stays,
 * so a single owner for the process is used. A per-view owner would leave a stopped lifecycle behind
 * on the window, and the cached recomposer would stay paused for every later Compose view.
 */
object ImeComposeHost : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private var started = false

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    /** Allows [view] to host Compose content. Call on the main thread, with the view attached to the window. */
    fun attachTo(view: View) {
        if (!started) {
            savedStateRegistryController.performRestore(null)
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
            started = true
        }
        view.setOwners()
        view.rootView?.setOwners()
    }

    private fun View.setOwners() {
        setViewTreeLifecycleOwner(ImeComposeHost)
        setViewTreeSavedStateRegistryOwner(ImeComposeHost)
    }
}
