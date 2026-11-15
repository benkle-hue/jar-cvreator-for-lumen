# Lumen Client (Fabric, Minecraft 1.21.4)

A clean, animated, client-side-only mod. **No cheats**: nothing touches movement, combat, reach or the server.
Everything is visual or quality-of-life, so it is fine on servers that allow normal Fabric mods.

Open the menu with **Right Shift** (rebind in Controls > Lumen Client).

## Modules

| Category | Module | What it does |
|---|---|---|
| Visuals | Zoom | Hold **C**. Scroll while zooming to change level. Smooth, lowers mouse sensitivity. |
| Visuals | Weather | Client-side Clear / Rain / Thunder with intensity (server weather unchanged). |
| Visuals | Clouds | Cloud height, tint colour or rainbow, brightness. |
| Visuals | Custom Sky | Sky presets (Synthwave, Aurora, Sunset, Ocean, Rose, custom hue) and client-side time of day (incl. real clock). |
| Utility | Toggle Sprint | Toggle key or always-sprint. Same as vanilla's sprint toggle accessibility option. |
| Utility | Keystrokes | WASD, mouse buttons with live CPS, spacebar. Display only. |
| Integrations | Music | Now-playing HUD for Spotify AND Firefox/Chrome/Edge/any app Windows can see (YouTube, SoundCloud, ...), cover art, play/pause/next/prev. Spotify login is optional. |
| Integrations | Discord RPC | Vencord-style CustomRPC: activity type (Playing/Listening/Watching/Competing), name, two text lines with {placeholders}, images, 2 buttons, timer. |
| Client | Interface | Accent colour (or rainbow), background dim, watermark + FPS. |

## Easiest build: let GitHub do it

1. Create a new empty GitHub repository and upload everything in this folder (including the hidden `.github` folder).
2. Open the **Actions** tab, wait ~3 minutes for "Build jar" to finish.
3. Open the finished run and download the **lumen-client-jar** artifact. Unzip it: that is the mod jar.

## Build locally instead

You need **JDK 21** and **Gradle 8.12+** (or use the official Fabric template and copy `src/` into it).

```
gradle wrapper --gradle-version 8.12   # once, creates ./gradlew
./gradlew build                        # jar ends up in build/libs/lumen-client-1.0.0.jar
./gradlew runClient                    # optional: start a dev client
```

Put the jar in `.minecraft/mods` together with **Fabric Loader 0.16+** and **Fabric API for 1.21.4**.

If Gradle cannot resolve a version in `gradle.properties`, pick the current 1.21.4 values from
https://fabricmc.net/develop and paste them in.

## Spotify setup (one time, ~2 minutes)

Spotify requires every app to have its own Client ID, so one has to be created by you:

1. Go to https://developer.spotify.com/dashboard and create an app (choose **Web API**).
2. Add the redirect URI exactly: `http://127.0.0.1:8898/callback`
3. Under User management, add your own Spotify account (name + email).
4. Copy the **Client ID** (never the secret; this mod does not use it).
5. In-game: Lumen menu > Integrations > Spotify > paste Client ID > **Connect**. Log in in the browser.

Notes (Spotify's 2026 rules): the app owner needs **Spotify Premium** for the Web API, development apps allow
max. 5 users, and play/pause/skip control needs Premium on the listening account. The login is stored as a
refresh token in `config/lumen/spotify.json` (local only, delete it or press Disconnect to remove it).

## Discord RPC setup (1 minute)

1. https://discord.com/developers/applications > New Application (the name shown on your profile).
2. Copy the **Application ID** into Lumen menu > Integrations > Discord RPC.
3. Optional: upload art under Rich Presence > Art Assets and put the asset name in "Large Image Key".

The Discord desktop app must be running.

## Config

`.minecraft/config/lumen/config.json` (saved when you close the menu and when the game exits).

## Music from Firefox / Chrome / Edge

Nothing to set up: the Music module reads Windows' own media controls (the same thing your keyboard media
keys use), so whatever is playing in Firefox, Chrome, Edge, the Spotify app, VLC... shows up with cover art,
and Prev / Play / Next control it. Windows only. The optional Spotify login adds exact Spotify data.

## Discord RPC like Vencord

Set `Content` to `Custom`. Placeholders for the text lines: `{status} {server} {music} {track} {artist} {source} {fps} {time}`.
Vencord injects the activity straight into Discord's client; Lumen talks to Discord's local IPC and sends the same
fields (type, name, details, state, images, buttons, timestamps). Discord decides what it actually displays, and
buttons are not shown on your own profile (only to others).
