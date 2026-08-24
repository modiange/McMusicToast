# McMusic Toast Customizer

> **🌟 Public Beta**
>
> This build is a **public beta**. It is **publicly available for testing** and **may contain bugs**.
> Feedback and suggestions are welcome via Issues. The project is **fully open source** — feel free to read, learn from, modify, and redistribute it.
>
> 📖 中文版本: [README.md](README.md)

---

![Features](images/1.png)

## Features

- Shows **Minecraft soundtrack / system media / both** in the Now Playing toast
- **Allowed music apps** settings: dynamically detects installed media apps (music / browser / player) and only lists installed ones
- "Game rules"-style settings screen: search box, toggles, Done / Cancel
- macOS **Privacy & Security → Automation** authorization guidance: prompts when unauthorized, greys out when denied, one-click jump to System Settings
- Independent truncation for title / artist (30 chars each, with ellipsis); no artist means no separator
- Toast trigger (mutually exclusive, one of three): on track change / on resume after pause / on countdown track switch
- Instant toast on resume, mimicking vanilla behavior
- Config stored at `.minecraft/config/mcmusic.json`

![Introduction](images/2.png)

## Introduction

McMusic is a **Minecraft 26.2 (Fabric) client-side mod** that customizes the vanilla **Now Playing / Music Toast** and can also display **system media** (the music currently playing on your computer).

Instead of drawing a separate HUD notification, the mod reuses Minecraft's native music-toast system, so it looks and feels exactly like vanilla.

![Intro image](images/3.png)

## Supported Platforms

- **Windows 10/11**: reads via System Media Transport Controls (SMTC) through PowerShell.
- **macOS**: reads system Now Playing through `nowplaying-cli`; a Music.app AppleScript fallback is included.
- **Linux**: reads MPRIS-compatible players through `playerctl`.

The Java / Fabric client code is platform-independent — only the system-media adapter differs per OS.

## External Dependencies (per OS)

- **Windows**: no extra software required. SMTC is available on Windows 10 version 1809 and newer.
- **macOS**: install `nowplaying-cli` for the best system-wide coverage:
  ```bash
  brew install nowplaying-cli
  ```
  If unavailable, the fallback can read the Music app.
- **Linux**: install `playerctl` to read MPRIS-compatible players and browsers.

## Settings Location

Open:

**Minecraft → Options → Music & Sounds → Music Toast**

The same screen can also be opened with **F8**.

## Build

The project targets **Java 25 / Fabric Loader 0.19.3 / Loom 1.17-SNAPSHOT / Fabric API 0.154.2+26.2**.

```bash
./gradlew build
```

Build artifacts are placed in `build/libs/`.

## License

This project is open source under the **GPL-3.0 License**. See [LICENSE](LICENSE).

## Feedback

Bug reports and suggestions are welcome via [Issues](https://github.com/modiange/McMusicToast/issues).

Thanks for joining the public beta!
