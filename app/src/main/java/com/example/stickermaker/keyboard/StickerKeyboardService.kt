package com.example.stickermaker.keyboard

import android.content.ClipDescription
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.stickermaker.StickerMakerApp
import java.io.File

/**
 * The sticker keyboard: switch to it in a chat, tap a sticker, and it's inserted through
 * Android's commit-content API, the way Gboard inserts stickers. WhatsApp's message box
 * accepts `image/webp.wasticker` (checked on a real device), and content committed with that
 * type arrives as a real sticker: no pack, no 3-sticker minimum.
 *
 * Compose needs a lifecycle, saved state and a ViewModel store, which an InputMethodService
 * doesn't have, so the service provides them itself.
 */
class StickerKeyboardService : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onCreateInputView(): View {
        window.window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(this)
            decor.setViewTreeSavedStateRegistryOwner(this)
            decor.setViewTreeViewModelStoreOwner(this)
        }
        val container = (application as StickerMakerApp).container
        return ComposeView(this).apply {
            setContent {
                StickerKeyboard(
                    container = container,
                    onSticker = ::insert,
                    onBackToKeyboard = ::backToKeyboard,
                )
            }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // Also called from super.onDestroy(); only pause what is actually running.
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        }
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        // super first: it finishes the input view, which still needs a live lifecycle.
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
    }

    /** Never the landscape "extract" mode that covers the app with a text box; it's a panel. */
    override fun onEvaluateFullscreenMode(): Boolean = false

    /** Inserts a sticker file into the focused field, as the best type that field accepts. */
    private fun insert(file: File) {
        val editor = currentInputEditorInfo ?: return
        val connection = currentInputConnection ?: return
        val accepted = EditorInfoCompat.getContentMimeTypes(editor)
        val mime = PREFERRED_TYPES.firstOrNull { type -> accepted.any { ClipDescription.compareMimeTypes(type, it) } }
        if (mime == null) {
            Toast.makeText(this, "This box doesn't take stickers", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val content = InputContentInfoCompat(uri, ClipDescription("Sticker", arrayOf(mime)), null)
        // The receiving app gets read access to this one file for as long as it holds the content.
        val sent = InputConnectionCompat.commitContent(
            connection, editor, content, InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null,
        )
        if (!sent) Toast.makeText(this, "Couldn't insert the sticker", Toast.LENGTH_SHORT).show()
    }

    private fun backToKeyboard() {
        if (!switchToPreviousInputMethod()) requestHideSelf(0)
    }

    private companion object {
        /** WhatsApp's own sticker type first (arrives as a sticker); plain WebP elsewhere. */
        val PREFERRED_TYPES = listOf("image/webp.wasticker", "image/webp")
    }
}
