<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="WisprBoard" width="140" />

# WisprBoard

### An open-source keyboard with AI superpowers, built for people who care about privacy.

An open-source Android keyboard with **AI Voice-to-Text** and **AI Text Fix**. Bring your own [OpenRouter](https://openrouter.ai/) or [PayPerQ](https://ppq.ai/) key; OpenRouter users can keep **zero data retention on by default**.

[<img src="https://user-images.githubusercontent.com/663460/26973090-f8fdc986-4d14-11e7-995a-e7c5e79ed925.png" alt="Get APK from GitHub" height="64">](https://github.com/Turtlecute33/WisprBoard/releases/latest)

</div>

<br>

## Why I built this

I tried Wispr Flow, the trendy dictation app everyone talks about. The Android version is rough: slow, account-locked, and with a very clunky and low-quality user experience. I wanted the same idea (talk, get clean text) but with a better UX and more customization.

So I forked [HeliBoard](https://github.com/Helium314/HeliBoard) and added two AI buttons. You choose OpenRouter or PayPerQ, paste your own API key, and pick the speech or text model you want. WisprBoard asks OpenRouter for zero-data-retention endpoints by default, so nothing about you is logged or trained on when that route is available. I also polished and optimized other minor flaws and bugs of the HeliBoard codebase, offering a smoother experience and more secure and private backup and restore handling.

No subscription. No account. No telemetry. Just a keyboard.

<br>

## Voice-to-Text

**For when you'd rather talk than type, but still want to send text.**

You're walking down the street. You're somewhere a voice note would be rude or out of place. Long-press Return, tap the mic, speak. Your words land in the chat as polished text. The recipient gets a normal message. You didn't thumb-type a paragraph.

- Pick OpenRouter chat audio models or PayPerQ speech models such as Nova
- Add a custom prompt or vocabulary so it nails names and jargon
- The local recording is deleted the moment it's sent

<br>

## AI Text Fix

**For when you're writing in a language that isn't yours, or just typed too fast.**

You're chatting in English, Italian, German, whatever isn't your strongest. You hammered out a rough draft. Select it, long-press Return, hit Fix. It comes back as a clean, well-formatted message in the same language. Typos gone. Grammar tidy. Tone intact.

- Works in every language your model supports
- Good for typos, clumsy phrasing, or shifting tone (formal ↔ casual)
- Review the message and decide if you want to replace the original one

<br>

## Offline speech recognition

Prefer that your voice never leaves the phone? Set *WisprBoard Settings → Voice Input → Speech engine* to **On-device**. Dictation then runs through Android's offline recogniser: no API key, no connection, no request. Accuracy is below a cloud model, and auto-polish is skipped — sending the transcript to an LLM afterwards would defeat the point. Needs Android 12 or newer and the offline language pack for the language you dictate in.

If your device has no offline recogniser, the option cannot be selected — WisprBoard shows you how to install one instead of leaving you on an engine that fails at every dictation.

Cloud AI stays the default, and Text Fix is unaffected either way.

<br>

## Zero Data Retention

OpenRouter is the default provider, and WisprBoard asks OpenRouter for **zero-data-retention endpoints by default**. When your model offers a [ZDR route](https://openrouter.ai/docs/use-cases/zero-data-retention), your audio and text aren't logged, stored, or used for training. If a model doesn't offer one, WisprBoard tells you and falls back so things still work. You can turn the setting off if you'd rather.

PayPerQ support uses PayPerQ's own API endpoints and policies. Your provider API keys are encrypted with the Android Keystore, excluded from cloud backups, and never written to logs. AI is opt-in: both features stay off until you paste a key in.

<br>

## WisprBoard vs HeliBoard

|                                         | HeliBoard | WisprBoard |
| --------------------------------------- | :-------: | :--------: |
| Everything HeliBoard does               |     ✅     |     ✅      |
| Installs alongside HeliBoard            |     —     |     ✅      |
| Voice-to-text via OpenRouter or PayPerQ |     —     |     ✅      |
| Offline on-device voice-to-text         |     —     |     ✅      |
| Text Fix (rewrite selected text)        |     —     |     ✅      |
| Zero Data Retention requested by default where supported |     —     |     ✅      |
| API key encrypted on-device             |     —     |     ✅      |

If you don't want AI features, stay on HeliBoard. It's wonderful as-is.

<br>

## Privacy footnote

WisprBoard has no backend, no analytics, no tracking. Once your audio or selected text reaches OpenRouter, PayPerQ, or the underlying model provider, their policies apply. Read [OpenRouter's policy](https://openrouter.ai/privacy) and [PayPerQ's terms](https://ppq.ai/terms) before pointing this at anything sensitive.


<br>

## Get started

1. **Download** the APK from [Releases](https://github.com/Turtlecute33/WisprBoard/releases/latest).
2. **Enable** WisprBoard in *Settings → System → Keyboards*.
3. **Pick a speech engine** in *WisprBoard Settings → Voice Input*. On-device needs nothing else; Cloud AI needs a provider and an OpenRouter or PayPerQ API key.
4. **Long-press Return**, tap the mic, start talking.

WisprBoard installs side-by-side with HeliBoard, so you can keep both.

<br>

## Build from source

```bash
git clone https://github.com/Turtlecute33/WisprBoard.git
cd WisprBoard
./gradlew assembleDebug
```

Needs JDK 17, Android SDK 35, NDK `28.0.13004108`. APK lands in `app/build/outputs/apk/debug/`.

<br>

## Open source, top to bottom

WisprBoard stands on the shoulders of giants:

- [**HeliBoard**](https://github.com/Helium314/HeliBoard), the keyboard this fork is built on.
- [**OpenBoard**](https://github.com/openboard-team/openboard) and [**AOSP LatinIME**](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/), the foundation of both.

<br>

## License

[GPL v3](/LICENSE). AOSP-derived portions are also available under [Apache 2.0](LICENSE-Apache-2.0).

Bundled dictionaries come from [Helium314/aosp-dictionaries](https://codeberg.org/Helium314/aosp-dictionaries); the expanded English and Italian word lists are built from sources licensed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).

<div align="center">
<br>
<sub>Code changes are AI-assisted but human-reviewed and tested before release; product and architecture decisions are mine. Even with Zero Data Retention, always minimize sensitive data disclosure to 3rd parties.</sub>
</div>
