# Web Apps for Android TV (pwa-weblink)

Turn any website into an app tile on Android TV or Google TV. Paste a link, and the app reads the site the way Chrome does when it offers to install a PWA (name, icon, theme colour) and saves it as a full-screen web app that opens from a tile, with a pointer you steer with the remote.

Android TV has no Chrome and no "Install app", so this is one small app that keeps a list of web apps. Each tile opens a full-screen WebView, so service workers, cookies, local storage and fullscreen video work. The app has a launcher entry, so it also shows up in Google TV and in [Aurora](https://github.com/android-tv-pack/aurora-launcher).

## Install

1. Download **WebApps.apk** from the [latest release](https://github.com/android-tv-pack/pwa-weblink/releases/latest).
   Direct link, for the *Downloader* app on the TV: `https://github.com/android-tv-pack/pwa-weblink/releases/latest/download/WebApps.apk`
2. Put it on the TV and open it: with a file manager app, with the *Downloader* app (enter the release address), or from a USB stick. Android asks once to allow installs from that app.
3. Open **Web Apps** from the app list. It needs no special permissions and no computer.

To update, install the new APK over the old one: your web apps are kept, because every release is signed with the same key. (If you build and sign it yourself, remove the old copy first, as the signature differs.)

## Using it

- **Add by link:** choose the **+** tile, type or paste an address, and check the preview (name, icon, whether a manifest was found).
- **Add from your phone or computer:** the app shows an address like `http://<tv-ip>:8765` at the bottom; open it, paste the link, and the app appears on the TV. This avoids typing with a remote.
- **Open:** OK on a tile. **Options:** hold OK on a tile (open, rename, pointer on/off, desktop/mobile layout, refresh name and icon, remove).
- **Video in fullscreen:** OK plays or pauses, Left and Right seek 10 seconds, and a small badge shows what happened. Hold OK to switch to the pointer (for the site's own buttons), hold OK again to switch back. The play, pause, fast-forward and rewind keys of the remote work too. Players embedded from another site can only be played or paused, not seeked.
- **In a web app:** the arrow keys move a mouse pointer and OK clicks (hold OK to drag). Pushing against a screen edge scrolls. Hold **Back** or press **INFO** for the menu (reload, start page, open another address, pointer, layout, zoom, close). Back goes back in history, then leaves the app. Turn the pointer off for sites made for TV, such as YouTube TV.

## How a link is read

1. Normal HTTP request; parse `<link rel="manifest">`, icons, `theme-color`, title.
2. Read the web app manifest (short name, scope, theme and background colour, icons: SVG skipped, 128-512 px preferred, maskable-only icons ranked lower).
3. If the site blocks plain requests (Cloudflare and similar challenge pages), the page is loaded in a hidden WebView, which can pass the browser check, and its HTML and cookies are used instead.
4. If nothing can be read, the link can still be added with the host name and a letter icon.

The app also exposes a read-only provider (`content://app.hermes.webapps/...`) that lists web apps and icons, so a launcher can show each one as its own tile.

## Build

JDK 17 and the Android SDK (platform 36). No other dependencies.

```sh
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk (debug-signed, ~0.7 MB)
```

## Limits

No push notifications, camera, microphone, file downloads or file pickers. Only one web app is open at a time. Sites that test for "standalone" display mode behave as in a browser tab. Sites that insist on an interactive human check time out while reading and can be added anyway, then opened and solved with the pointer.

Tested on a Xiaomi TV Box S (Android 14). Not tested: dragging, typing inside a page, fullscreen video, zoom, and holding Back on a physical remote.

## License

MIT, see [LICENSE](LICENSE).

## Support

If this project is useful to you, you can support its development:

[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/G0U5290K4T)
