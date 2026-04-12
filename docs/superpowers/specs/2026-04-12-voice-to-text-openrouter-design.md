# Voice-to-Text via OpenRouter

## Summary

Add voice-to-text functionality to HeliBoard using OpenRouter's chat completions API with audio-capable models. Users configure an API key and select a model in settings. A voice button in the clipboard toolbar strip starts audio recording; tapping the screen stops recording and sends audio to OpenRouter for transcription, which is then inserted into the active text field.

## User Flow

1. **Setup:** User goes to Settings > Toolbar, enables Voice Input, enters OpenRouter API key, optionally selects a model (default: `google/gemini-3-flash-preview`).
2. **Activation:** While typing, user long-presses the return/enter key, swipes to the voice button, and releases. This triggers `KeyCode.VOICE_INPUT`.
3. **Recording:** The suggestion strip is replaced with a minimal looping dot animation. Audio recording begins via Android's `AudioRecord` API.
4. **Stop:** User taps anywhere on the keyboard. Recording stops.
5. **Transcription:** Audio is encoded as base64, sent to OpenRouter's `/api/v1/chat/completions` endpoint. A "Transcribing..." indicator replaces the dot animation.
6. **Insertion:** The transcribed text is committed to the text field via `RichInputConnection.commitText()`. The suggestion strip is restored.

## Components

### 1. Settings (modify existing Toolbar screen)

Add three preferences to the existing Toolbar settings screen (`ToolbarScreen.kt`):

- **Enable Voice Input** (`pref_voice_input_enabled`): Boolean toggle, default `false`.
- **OpenRouter API Key** (`pref_openrouter_api_key`): String, password-masked text field. Stored in SharedPreferences.
- **Voice Model** (`pref_voice_model`): Dropdown with these options:
  - `google/gemini-3-flash-preview` (default)
  - `google/gemini-2.0-flash-001`
  - `openai/whisper-large-v3`
  - Custom (free-text field that appears when selected)

Settings keys defined as constants in `Settings.java`.

### 2. Audio Recorder (`AudioRecorder.kt`)

New file: `helium314.keyboard.latin.voice.AudioRecorder`

- Uses Android `AudioRecord` API (not `MediaRecorder` — more control, works without file I/O).
- Records PCM 16-bit mono at 16kHz.
- Encodes to WAV format in-memory (WAV header + PCM data).
- Returns `ByteArray` of the WAV file on stop.
- Lifecycle: `start()` / `stop(): ByteArray`.
- Runs recording on a background thread.

### 3. OpenRouter Client (`OpenRouterClient.kt`)

New file: `helium314.keyboard.latin.voice.OpenRouterClient`

- Uses `java.net.HttpURLConnection` (no external HTTP library — keeps HeliBoard dependency-free).
- Endpoint: `https://openrouter.ai/api/v1/chat/completions`
- Request format:
  ```json
  {
    "model": "<selected_model>",
    "messages": [{
      "role": "user",
      "content": [
        {
          "type": "input_audio",
          "input_audio": {
            "data": "<base64_wav>",
            "format": "wav"
          }
        },
        {
          "type": "text",
          "text": "Transcribe this audio exactly as spoken. Output only the transcription, nothing else."
        }
      ]
    }]
  }
  ```
- Headers: `Authorization: Bearer <api_key>`, `Content-Type: application/json`
- Parses response JSON to extract `choices[0].message.content`.
- Returns `Result<String>` (transcription text or error).
- Runs on a coroutine/background thread.

### 4. Voice Input Manager (`VoiceInputManager.kt`)

New file: `helium314.keyboard.latin.voice.VoiceInputManager`

Orchestrates the full flow:

- Checks prerequisites (voice enabled, API key set, mic permission granted).
- Manages state: `IDLE` / `RECORDING` / `TRANSCRIBING`.
- On `startRecording()`: starts `AudioRecorder`, notifies UI to show recording animation.
- On `stopRecording()`: stops `AudioRecorder`, transitions to `TRANSCRIBING`, calls `OpenRouterClient`, commits result text.
- Communicates with `LatinIME` via a callback interface for UI updates and text insertion.

### 5. Recording UI

Modify `SuggestionStripView.kt` to support a recording overlay state:

- **Recording state:** Replace suggestion strip content with a minimal looping dot animation (3 dots that pulse/fade in sequence). Use theme colors from `Settings.getValues().mColors` for consistency.
- **Transcribing state:** Replace dots with "Transcribing..." text in the same style.
- **Implementation:** A simple custom `View` or animated `TextView` overlaid on the suggestion strip. Dots animated with standard Android `ObjectAnimator`.
- **Stop trigger:** Touch listener on the keyboard view during recording state — any tap calls `VoiceInputManager.stopRecording()`.

### 6. Wiring into Existing Code

**`KeyboardActionListenerImpl.kt`:**
- In `onCodeInput()` or `onLongPressKey()`: when `KeyCode.VOICE_INPUT` is received, call `VoiceInputManager.startRecording()` instead of current no-op.

**`LatinIME.java`:**
- Hold a `VoiceInputManager` instance.
- Provide callback for text insertion (`mInputLogic.mConnection.commitText()`).
- Provide callback for UI state changes (show/hide recording overlay).
- Handle `RECORD_AUDIO` permission request.

**`PointerTracker.java`:**
- During recording state, intercept touch events to trigger stop on tap.

**`ToolbarKey` enum / `ToolbarUtils.kt`:**
- `VOICE` toolbar key already exists and maps to `KeyCode.VOICE_INPUT`. No changes needed here.
- Ensure VOICE is available in clipboard toolbar key selection.

## Permissions

Add to `AndroidManifest.xml`:
- `<uses-permission android:name="android.permission.INTERNET" />`
- `<uses-permission android:name="android.permission.RECORD_AUDIO" />`

Runtime permission request for `RECORD_AUDIO` — triggered on first voice input attempt.

## Error Handling

| Condition | Behavior |
|-----------|----------|
| Voice disabled in settings | Toast: "Enable Voice Input in settings" |
| No API key | Toast: "Set OpenRouter API key in settings" |
| Mic permission denied | Toast: "Microphone permission required" |
| Network error | Toast with error message, restore suggestion strip |
| API error (4xx/5xx) | Toast with status code, restore suggestion strip |
| Empty transcription | No text inserted, restore suggestion strip silently |

## File Inventory

| File | Action |
|------|--------|
| `app/src/main/java/helium314/keyboard/latin/voice/AudioRecorder.kt` | New |
| `app/src/main/java/helium314/keyboard/latin/voice/OpenRouterClient.kt` | New |
| `app/src/main/java/helium314/keyboard/latin/voice/VoiceInputManager.kt` | New |
| `app/src/main/java/helium314/keyboard/latin/voice/RecordingOverlayView.kt` | New |
| `app/src/main/java/helium314/keyboard/latin/settings/Settings.java` | Modify — add pref key constants |
| `app/src/main/java/helium314/keyboard/settings/screens/ToolbarScreen.kt` | Modify — add voice settings UI |
| `app/src/main/java/helium314/keyboard/keyboard/KeyboardActionListenerImpl.kt` | Modify — handle VOICE_INPUT |
| `app/src/main/java/helium314/keyboard/latin/LatinIME.java` | Modify — instantiate VoiceInputManager, handle callbacks |
| `app/src/main/java/helium314/keyboard/latin/suggestions/SuggestionStripView.kt` | Modify — support recording overlay |
| `app/src/main/java/helium314/keyboard/keyboard/PointerTracker.java` | Modify — intercept taps during recording |
| `app/src/main/AndroidManifest.xml` | Modify — add INTERNET and RECORD_AUDIO permissions |

## Security Considerations

- API key stored in SharedPreferences on device-protected storage (same as all other HeliBoard prefs). Not exported or logged.
- Audio data is transient — held in memory only during recording/sending, never written to disk.
- HTTPS only for API communication.
- No telemetry or analytics added.
