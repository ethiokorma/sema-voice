package com.semavoice.keyboard

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.platform.ComposeView
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
import com.semavoice.core.common.SemaLog
import com.semavoice.core.runtime.SemaGraph

/**
 * The Sema Voice IME. A Compose view hosted inside an InputMethodService needs its own
 * lifecycle / saved-state / view-model owners; this service provides them and tears them
 * down symmetrically so IME recreation (theme change, app switch) never leaks a recorder.
 */
class SemaInputMethodService : InputMethodService(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private var controller: KeyboardController? = null
    private var currentEditorInfo: EditorInfo? = null

    override fun onCreate() {
        super.onCreate()
        SemaGraph.init(this)
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        controller = KeyboardController(
            graph = SemaGraph,
            sinkProvider = { currentInputConnection?.let { InputConnectionSink(it, currentEditorInfo) } },
            onRequestPermission = { openApp("permission") },
            onOpenApp = { route -> openApp(route) },
        )
        SemaLog.i(TAG, "IME created")
    }

    override fun onCreateInputView(): View {
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@SemaInputMethodService)
            setViewTreeViewModelStoreOwner(this@SemaInputMethodService)
            setViewTreeSavedStateRegistryOwner(this@SemaInputMethodService)
            setContent {
                val c = controller ?: return@setContent
                SemaKeyboard(
                    controller = c,
                    onSwitchKeyboard = { switchKeyboard() },
                )
            }
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        currentEditorInfo = info
        controller?.onFieldChanged(info?.inputType ?: 0)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        controller?.onHidden()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    }

    override fun onFinishInput() {
        super.onFinishInput()
        currentEditorInfo = null
        controller?.onFieldChanged(0)
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onDestroy() {
        controller?.destroy()
        controller = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        super.onDestroy()
        SemaLog.i(TAG, "IME destroyed")
    }

    /** Opens the companion app on a route (permission flow, model manager, composer). */
    private fun openApp(route: String) {
        val intent = Intent().apply {
            setClassName(packageName, "com.semavoice.app.MainActivity")
            putExtra("route", route)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        runCatching { startActivity(intent) }.onFailure { SemaLog.w(TAG, "cannot open app", it) }
    }

    private fun switchKeyboard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchToNextInputMethod(false)
        } else {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
    }

    private companion object { const val TAG = "SemaIME" }
}
