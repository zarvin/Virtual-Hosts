## Virtual Hosts

Customize hosts (DNS resolution) on **non-rooted** Android devices via a local `VpnService` — with **multi-profile management** and **wildcard DNS** support.

<a href="https://play.google.com/store/apps/details?id=com.github.xfalcon.vhosts"><img src="https://play.google.com/intl/en_us/badges/images/generic/en-play-badge.png" height="48" /></a>
<a href="https://f-droid.org/packages/com.github.xfalcon.vhosts"><img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Get it on F-Droid" height="48"></a>

> Play / F-Droid badges point to the original app by xfalcon. This fork adds the multi-profile features below.

### Features

- **Multi-profile management** — keep multiple hosts profiles and toggle each on/off independently. Enabled profiles are merged with **front-priority** (the one higher in the list wins on a conflict).
- **Built-in editor** — line-number gutter, IP / `#`-comment syntax highlight, no-wrap horizontal scroll, and **format validation on save** (bad line/IP is reported and blocks saving).
- **Wildcard DNS records** — e.g. `127.0.0.1 .a.com` matches every subdomain:
  ```
  127.0.0.1 a.com     |
  127.0.0.1 m.a.com   |  =>  127.0.0.1 .a.com
  127.0.0.1 w.m.a.com |
  ```
- **Add a profile** by creating a blank one, importing a local file, or downloading from a URL. URL profiles can be **refreshed** later.
- **Active-hosts view** — while running, see the *merged effective* IP→domain mappings that are actually answering.
- **DNS hit log** — see which queries were answered locally.
- **Import / export** all profiles as a single JSON file.
- **Drag to reorder** profiles (order = merge priority).
- **Dark mode**, quick-settings tile, home-screen widget, boot auto-start, custom DNS.
- Runtime edits (toggle / edit / delete) **take effect immediately** without restarting the tunnel.

### How it works

Virtual Hosts builds a userspace TUN interface, intercepts DNS (UDP port 53), answers matching queries locally from your enabled profiles, and passes everything else through a protected socket. Only DNS is routed into the tunnel, so it's light on battery and doesn't disturb other traffic. Based on [LocalVPN](https://github.com/hexene/LocalVPN).

### Build

Two product flavors (`github` / `googleplay`); task names need the flavor:

```bash
./gradlew assembleGithubDebug            # debug APK
./gradlew testGithubDebugUnitTest        # JVM unit tests
./gradlew assembleGithubRelease          # release APK
```

Toolchain: AGP 8.12.3, Gradle 8.13, Java 8, `compileSdk 36` / `minSdk 19` / `targetSdk 34`.

### Release (CI)

Push a `v*` tag (e.g. `v2.4.0`) → GitHub Actions runs the unit tests, builds `assembleGithubRelease`, and publishes the signed APK to Releases. Configure signing via repository **Secrets**:

| Secret | |
|---|---|
| `KEYSTORE_BASE64` | base64 of your keystore (`base64 -i your.jks`) |
| `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` | keystore credentials |

If no keystore secret is set, the pipeline falls back to debug signing so it still works out of the box.

### License

App: **GPL-3.0** (Copyright 2017 xfalcon). Files under `vservice/` derived from LocalVPN are **Apache-2.0**.
