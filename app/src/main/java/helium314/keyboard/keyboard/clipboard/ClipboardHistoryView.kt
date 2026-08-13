// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.clipboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.text.InputType
import android.util.AttributeSet
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.withStyledAttributes
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.KeyboardId
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.MainKeyboardView
import helium314.keyboard.keyboard.PointerTracker
import helium314.keyboard.keyboard.internal.KeyVisualAttributes
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.ClipboardHistoryManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.createToolbarKey
import helium314.keyboard.latin.utils.getCodeForToolbarKey
import helium314.keyboard.latin.utils.getCodeForToolbarKeyLongClick
import helium314.keyboard.latin.utils.getEnabledClipboardToolbarKeys
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.setToolbarButtonsActivatedStateOnPrefChange

/**
 * The clipboard panel: a Compose (Material 3) list of clips on top of a keyboard row.
 * The bottom row is the usual clipboard row, and turns into a full alphabet keyboard while
 * searching or editing a clip, as the IME cannot type into a text field of its own window.
 */
@SuppressLint("CustomViewStyleable")
class ClipboardHistoryView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet?,
        defStyle: Int = R.attr.clipboardHistoryViewStyle
) : LinearLayout(context, attrs, defStyle), View.OnClickListener, View.OnLongClickListener,
    ClipboardDao.Listener, ClipboardPanelActions, LifecycleOwner, SavedStateRegistryOwner,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val toolbarKeys = mutableListOf<ImageButton>()
    private val panelState = ClipboardPanelState()
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    private lateinit var composeView: ComposeView
    private lateinit var bottomRowKeyboardView: MainKeyboardView

    lateinit var keyboardActionListener: KeyboardActionListener
    private var clipboardHistoryManager: ClipboardHistoryManager? = null
    private var editorInfo: EditorInfo? = null
    private var typingElementId = KeyboardId.ELEMENT_ALPHABET
    private var oneShotShift = false

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    init {
        context.withStyledAttributes(attrs, R.styleable.ClipboardHistoryView, defStyle, R.style.ClipboardHistoryView) {
            panelState.pinIconRes = getResourceId(
                R.styleable.ClipboardHistoryView_iconPinnedClip, R.drawable.ic_clipboard_pin_lxx)
        }
        if (Settings.getValues().mSecondaryStripVisible) {
            getEnabledClipboardToolbarKeys(context.prefs())
                .forEach { toolbarKeys.add(createToolbarKey(context, it)) }
        }
        fitsSystemWindows = true
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = ResourceUtils.getKeyboardWidth(context, Settings.getValues()) + paddingLeft + paddingRight
        val height = ResourceUtils.getSecondaryKeyboardHeight(context.resources, Settings.getValues()) + paddingTop + paddingBottom
        // measure with the final size, so the panel gets exactly the space that the bottom keyboard leaves
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
        setMeasuredDimension(width, height)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun onDetachedFromWindow() {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        super.onDetachedFromWindow()
    }

    private fun initialize() { // needs to be delayed for access to ClipboardStrip, which is not a child of this view
        if (this::composeView.isInitialized) return
        setViewTreeLifecycleOwner(this)
        setViewTreeSavedStateRegistryOwner(this)
        bottomRowKeyboardView = findViewById(R.id.bottom_row_keyboard)
        composeView = findViewById<ComposeView>(R.id.clipboard_panel).apply {
            setContent { ClipboardPanel(panelState, this@ClipboardHistoryView) }
        }
        val colors = Settings.getValues().mColors
        val clipboardStrip = KeyboardSwitcher.getInstance().clipboardStrip
        toolbarKeys.forEach {
            clipboardStrip.addView(it)
            it.setOnClickListener(this@ClipboardHistoryView)
            it.setOnLongClickListener(this@ClipboardHistoryView)
            colors.setColor(it, ColorType.TOOL_BAR_KEY)
            colors.setBackground(it, ColorType.STRIP_BACKGROUND)
        }
    }

    private fun setupToolbarKeys() {
        val toolbarKeyLayoutParams = LayoutParams(
            resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_edge_key_width), LayoutParams.MATCH_PARENT)
        toolbarKeys.forEach { it.layoutParams = toolbarKeyLayoutParams }
    }

    private fun setupBottomRowKeyboard(editorInfo: EditorInfo, listener: KeyboardActionListener) {
        bottomRowKeyboardView.setKeyboardActionListener(listener)
        PointerTracker.switchTo(bottomRowKeyboardView)
        val kls = KeyboardLayoutSet.Builder.buildEmojiClipBottomRow(context, editorInfo)
        bottomRowKeyboardView.setKeyboard(kls.getKeyboard(KeyboardId.ELEMENT_CLIPBOARD_BOTTOM_ROW))
    }

    fun setHardwareAcceleratedDrawingEnabled(enabled: Boolean) {
        if (!enabled) return
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun startClipboardHistory(
            historyManager: ClipboardHistoryManager,
            keyVisualAttr: KeyVisualAttributes?,
            editorInfo: EditorInfo,
            keyboardActionListener: KeyboardActionListener
    ) {
        clipboardHistoryManager = historyManager
        this.editorInfo = editorInfo
        initialize()
        setupToolbarKeys()
        historyManager.prepareClipboardHistory()
        historyManager.setHistoryChangeListener(this)

        panelState.typingMode = null
        panelState.menuFor = null
        panelState.filter = ClipFilter.ALL
        panelState.buffer.clear()
        refreshClips()

        setupBottomRowKeyboard(editorInfo, keyboardActionListener)

        // absurd workaround so Android sets the correct color from stateList (depending on "activated")
        toolbarKeys.forEach { it.isEnabled = false; it.isEnabled = true }
    }

    fun stopClipboardHistory() {
        if (!this::composeView.isInitialized) return
        // the keyboard view is set up again when the panel is shown, so only the state is reset here
        panelState.typingMode = null
        panelState.buffer.clear()
        panelState.menuFor = null
        clipboardHistoryManager?.setHistoryChangeListener(null)
        clipboardHistoryManager = null
    }

    private fun refreshClips() {
        panelState.setClips(clipboardHistoryManager?.getHistoryEntries().orEmpty())
    }

    override fun onClipboardHistoryChanged() {
        refreshClips()
    }

    // region toolbar strip

    override fun onClick(view: View) {
        val tag = view.tag
        if (tag is ToolbarKey) {
            AudioAndHapticFeedbackManager.getInstance()
                .performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, this, HapticEvent.KEY_PRESS)
            val code = getCodeForToolbarKey(tag)
            if (code != KeyCode.UNSPECIFIED) {
                keyboardActionListener.onCodeInput(code, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
            }
        }
    }

    override fun onLongClick(view: View): Boolean {
        val tag = view.tag
        if (tag is ToolbarKey) {
            val longClickCode = getCodeForToolbarKeyLongClick(tag)
            if (longClickCode != KeyCode.UNSPECIFIED) {
                keyboardActionListener.onCodeInput(
                    longClickCode, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
            }
            return true
        }
        return false
    }

    // endregion

    // region panel actions

    override fun onPaste(item: ClipItem) {
        val wasSearching = panelState.typingMode is TypingMode.Search
        if (wasSearching) finishTyping(commit = false)
        keyboardActionListener.onPressKey(KeyCode.NOT_SPECIFIED, 0, true, HapticEvent.KEY_PRESS)
        keyboardActionListener.onTextInput(item.text)
        keyboardActionListener.onReleaseKey(KeyCode.NOT_SPECIFIED, false)
        if (Settings.getValues().mAlphaAfterClipHistoryEntry)
            keyboardActionListener.onCodeInput(KeyCode.ALPHA, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
    }

    override fun onTogglePin(id: Long) {
        performKeyFeedback()
        clipboardHistoryManager?.toggleClipPinned(id)
    }

    override fun onDelete(id: Long) {
        performKeyFeedback()
        clipboardHistoryManager?.removeEntry(id)
    }

    override fun onCopy(item: ClipItem) {
        performKeyFeedback()
        clipboardHistoryManager?.copyToSystemClipboard(item.text)
        KeyboardSwitcher.getInstance().showToast(context.getString(R.string.toast_msg_clipboard_copy), true)
    }

    override fun onShare(item: ClipItem) {
        performKeyFeedback()
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, item.text)
        val chooser = Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(chooser) }
            .onFailure { Log.e(TAG, "can't share clip", it) }
    }

    override fun onStartEdit(item: ClipItem) {
        startTyping(TypingMode.Edit(item.id), item.text)
    }

    override fun onStartSearch() {
        startTyping(TypingMode.Search, "")
    }

    override fun onFinishTyping(commit: Boolean) {
        finishTyping(commit)
    }

    override fun onClearHistory() {
        performKeyFeedback()
        clipboardHistoryManager?.clearHistory()
        refreshClips()
    }

    override fun onCloseHistory() {
        keyboardActionListener.onCodeInput(KeyCode.ALPHA, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
    }

    private fun performKeyFeedback() {
        AudioAndHapticFeedbackManager.getInstance()
            .performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, this, HapticEvent.KEY_PRESS)
    }

    // endregion

    // region typing (search / edit)

    private fun startTyping(mode: TypingMode, initialText: String) {
        performKeyFeedback()
        panelState.menuFor = null
        panelState.buffer.set(initialText)
        panelState.typingMode = mode
        typingElementId = KeyboardId.ELEMENT_ALPHABET
        oneShotShift = false
        if (!showTypingKeyboard()) {
            // no keyboard to type on: stay in the browsing view instead of showing a dead panel
            panelState.typingMode = null
        }
    }

    private fun finishTyping(commit: Boolean) {
        val mode = panelState.typingMode ?: return
        if (commit && mode is TypingMode.Edit)
            clipboardHistoryManager?.updateEntryText(mode.id, panelState.buffer.text.trim())
        panelState.typingMode = null
        panelState.buffer.clear()
        refreshClips()
        editorInfo?.let { setupBottomRowKeyboard(it, keyboardActionListener) }
    }

    /** Replaces the clipboard bottom row with a full keyboard, typing into the panel instead of the app */
    private fun showTypingKeyboard(): Boolean {
        val keyboard = buildTypingKeyboard(typingElementId) ?: return false
        bottomRowKeyboardView.setKeyboardActionListener(typingActionListener)
        PointerTracker.switchTo(bottomRowKeyboardView)
        bottomRowKeyboardView.setKeyboard(keyboard)
        return true
    }

    private fun buildTypingKeyboard(elementId: Int): Keyboard? {
        val sv = Settings.getValues()
        val res = context.resources
        val width = ResourceUtils.getKeyboardWidth(context, sv)
        val panelHeight = ResourceUtils.getSecondaryKeyboardHeight(res, sv)
        val panelReserved = res.getDimensionPixelSize(R.dimen.config_clipboard_typing_area_height)
        val height = (panelHeight - panelReserved).coerceAtLeast((panelHeight * 0.55f).toInt())
        // always a text keyboard, even in number or phone fields, as we type into the panel
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            packageName = context.packageName
        }
        return runCatching {
            KeyboardLayoutSet.Builder(context, info)
                .setKeyboardGeometry(width, height)
                .setSubtype(RichInputMethodManager.getInstance().currentSubtype)
                .setVoiceInputKeyEnabled(false)
                .setNumberRowEnabled(sv.mShowsNumberRow)
                .setNumberRowInSymbolsEnabled(sv.mShowsNumberRowInSymbols)
                .setLanguageSwitchKeyEnabled(false)
                .setEmojiKeyEnabled(false)
                .setSplitLayoutEnabled(sv.mIsSplitKeyboardEnabled)
                .setOneHandedModeEnabled(sv.mOneHandedModeEnabled)
                .build()
                .getKeyboard(elementId)
        }.onFailure { Log.e(TAG, "can't build keyboard for the clipboard panel", it) }.getOrNull()
    }

    private fun switchTypingElement(elementId: Int) {
        typingElementId = elementId
        buildTypingKeyboard(elementId)?.let { bottomRowKeyboardView.setKeyboard(it) }
    }

    private fun handleTypingCode(code: Int) {
        val buffer = panelState.buffer
        when (code) {
            KeyCode.DELETE -> buffer.backspace()
            KeyCode.SHIFT -> {
                oneShotShift = typingElementId == KeyboardId.ELEMENT_ALPHABET
                switchTypingElement(
                    when (typingElementId) {
                        KeyboardId.ELEMENT_ALPHABET -> KeyboardId.ELEMENT_ALPHABET_MANUAL_SHIFTED
                        KeyboardId.ELEMENT_ALPHABET_MANUAL_SHIFTED -> KeyboardId.ELEMENT_ALPHABET
                        KeyboardId.ELEMENT_SYMBOLS -> KeyboardId.ELEMENT_SYMBOLS_SHIFTED
                        KeyboardId.ELEMENT_SYMBOLS_SHIFTED -> KeyboardId.ELEMENT_SYMBOLS
                        else -> KeyboardId.ELEMENT_ALPHABET
                    }
                )
            }
            KeyCode.CAPS_LOCK -> switchTypingElement(KeyboardId.ELEMENT_ALPHABET_SHIFT_LOCKED)
            KeyCode.SYMBOL -> switchTypingElement(KeyboardId.ELEMENT_SYMBOLS)
            KeyCode.ALPHA -> switchTypingElement(KeyboardId.ELEMENT_ALPHABET)
            KeyCode.ARROW_LEFT -> buffer.moveCursorBy(-1)
            KeyCode.ARROW_RIGHT -> buffer.moveCursorBy(1)
            KeyCode.MOVE_START_OF_LINE, KeyCode.MOVE_START_OF_PAGE -> buffer.moveCursor(0)
            KeyCode.MOVE_END_OF_LINE, KeyCode.MOVE_END_OF_PAGE -> buffer.moveCursor(buffer.text.length)
            KeyCode.CLIPBOARD, KeyCode.IME_HIDE_UI -> finishTyping(commit = false)
            Constants.CODE_ENTER -> {
                if (panelState.typingMode is TypingMode.Edit) buffer.insert("\n") else finishTyping(commit = false)
            }
            else -> {
                if (code < Constants.CODE_SPACE) return // any other function key: nothing sensible to do here
                buffer.insert(String(Character.toChars(code)))
                if (oneShotShift) {
                    oneShotShift = false
                    switchTypingElement(KeyboardId.ELEMENT_ALPHABET)
                }
            }
        }
    }

    private val typingActionListener = object : KeyboardActionListener.Adapter() {
        override fun onPressKey(primaryCode: Int, repeatCount: Int, isSinglePointer: Boolean, hapticEvent: HapticEvent) {
            AudioAndHapticFeedbackManager.getInstance()
                .performHapticAndAudioFeedback(primaryCode, this@ClipboardHistoryView, hapticEvent)
        }

        override fun onCodeInput(primaryCode: Int, x: Int, y: Int, isKeyRepeat: Boolean) {
            handleTypingCode(primaryCode)
        }

        override fun onTextInput(text: String?) {
            text?.let { panelState.buffer.insert(it) }
        }
    }

    // endregion

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        setToolbarButtonsActivatedStateOnPrefChange(KeyboardSwitcher.getInstance().clipboardStrip, key)

        // The setting can only be changed from a settings screen, but adding it to this listener seems necessary:
        // https://github.com/HeliBorg/HeliBoard/pull/1903#issuecomment-3478424606
        if (clipboardHistoryManager != null && key == Settings.PREF_CLIPBOARD_HISTORY_PINNED_FIRST) {
            Settings.getInstance().onSharedPreferenceChanged(prefs, key) // ensure settings are reloaded first
            clipboardHistoryManager?.sortHistoryEntries()
            refreshClips()
        }
    }

    companion object {
        private const val TAG = "ClipboardHistoryView"
    }
}
