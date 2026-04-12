# TurtleBoard

TurtleBoard is a privacy-conscious and customizable open-source keyboard, forked from [HeliBoard](https://github.com/HeliBorg/HeliBoard) (which is based on AOSP / OpenBoard).

[<img src="https://user-images.githubusercontent.com/663460/26973090-f8fdc986-4d14-11e7-995a-e7c5e79ed925.png" alt="Get APK from GitHub" height="80">](https://github.com/Turtlecute33/TurtleBoard/releases/latest)

## What's different from HeliBoard?

TurtleBoard adds the following features on top of HeliBoard:

- **AI Voice-to-Text** — On-device audio recording with transcription via [OpenRouter](https://openrouter.ai/) API. Bring your own API key, choose your preferred model, and customize the transcription prompt. Your audio never touches a server you don't control.
- **Settings performance improvements** — Optimized settings screens for smoother navigation and reduced recomposition overhead.
- **Separate application ID** — Can be installed side-by-side with HeliBoard without conflicts.

## Setup

1. Install from [Releases](https://github.com/Turtlecute33/TurtleBoard/releases/latest)
2. Enable TurtleBoard in **Settings > System > Keyboard**
3. For voice input: go to **TurtleBoard Settings > Voice Input** and enter your OpenRouter API key

## Building from source

```bash
git clone https://github.com/Turtlecute33/TurtleBoard.git
cd TurtleBoard
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/`.

### Requirements

- JDK 17
- Android SDK with API level 35
- Android NDK 28.0.13004108

## Features (inherited from HeliBoard)

- Add dictionaries for suggestions and spell check
- Customize keyboard themes (style, colors, background image)
- Emoji search (inline and separate)
- Customize keyboard [layouts](layouts.md)
- Multilingual typing
- Glide typing (*only with closed source library*)
- Clipboard history
- One-handed mode
- Split keyboard
- Number pad
- Backup and restore settings

For more details, see the [HeliBoard wiki](https://github.com/HeliBorg/HeliBoard/wiki).

## Contributing

Contributions are welcome! Please open an issue or pull request.

## License

TurtleBoard (as a fork of HeliBoard/OpenBoard) is licensed under [GNU General Public License v3.0](/LICENSE).

Since the app is based on Apache 2.0 licensed AOSP Keyboard, an [Apache 2.0](LICENSE-Apache-2.0) license file is also provided.

## Credits

- [HeliBoard](https://github.com/HeliBorg/HeliBoard) and its contributors
- [OpenBoard](https://github.com/openboard-team/openboard)
- [AOSP Keyboard](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/)
- Icon by [Fabian OvrWrt](https://github.com/FabianOvrWrt) with contributions from [The Eclectic Dyslexic](https://github.com/the-eclectic-dyslexic)
