# Nekomo builds

This folder is the home of every Nekomo build. The website and the in-app updater read it, so keep the names stable.
Android TV builds always have **TV** in the file name and in the app title.

| File | App title | Package |
| --- | --- | --- |
| `Nekomo-phone-release.apk` | Nekomo | `app.nekomo` |
| `Nekomo-phone-beta.apk` | Nekomo Beta | `app.nekomo.beta` |
| `Nekomo-phone-alpha.apk` | Nekomo Alpha | `app.nekomo.debug` |
| `Nekomo-TV-release.apk` | Nekomo TV | `app.nekomo.tv` |
| `Nekomo-TV-beta.apk` | Nekomo TV Beta | `app.nekomo.tv.beta` |
| `Nekomo-TV-alpha.apk` | Nekomo TV Alpha | `app.nekomo.tv.debug` |
| `latest.json` | | Version, size, SHA-256 and build time of each file (written by Gradle) |
| `archive/` | | Older or legacy builds kept for reference. Not served by the website. |

"Alpha" is the debug build type. The old names (`Nekomo-phone-debug.apk`, `Nekomo-tv-*.apk`) still redirect on the website.

Build and collect everything with `./gradlew assembleAllApks` (or run `./gradlew collectApks` after building single
variants). The website redirects (`/Nekomo-TV-release.apk` ...) and `scripts/publish-release.sh` depend on these names.
