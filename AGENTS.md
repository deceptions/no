# Notes for coding agents / contributors

## Build & verify

This is a Gradle Android project (Kotlin). Build and test locally (JDK 17 + Android SDK; min Android 7.0 / API 24):

```
./gradlew assembleDebug        # or assemblePhoneDebug / assembleTvDebug depending on flavor
./gradlew lint
```

Requires a JDK (17 recommended) and the Android SDK (compileSdk/targetSdk per `app/build.gradle`).

Windows/OneDrive note: the repo lives in OneDrive and a first build of a new variant can fail with
`AAPT: failed writing to ... R.txt: The data is invalid (13)`. It is transient (OneDrive touching
`app/build`); just re-run the same Gradle command, ideally one variant at a time.

## Variants / release builds

- Build types: `debug` (`.debug`), `beta` (`.beta`, minified, signed with the release key), `release` (stable).
  Flavors: `phone`, `tv`. `./gradlew assemble<Flavor><Type>`; copy outputs to `apks/Nekomo-<flavor>-<type>.apk`.
- `coreLibraryDesugaringEnabled true` stays on for newer java.time/collection APIs (minSdk is 24, see "Browsing / installing sources").
- Release/beta builds OOM'd in lint-vital with a 2 GB heap; heap is now 4 GB. If it recurs, add
  `-x lintVitalAnalyze<Variant> -x lintVitalReport<Variant> -x lintVital<Variant>`.
- TV manifest declares leanback `required=false` so the TV APK can be installed on phones for testing
  (`TvActivity` has both LAUNCHER and LEANBACK_LAUNCHER categories).
- Use `NavController.safeNavigate` (`utils/Synthetics.kt`) instead of `navigate` to avoid double-tap crashes;
  use `ContextCompat.registerReceiver` with an explicit export flag (Android 14 target).

## Error logging

`com.lagradost.shiro.utils.ErrorLogger` persists a rolling crash/error log to app-private
storage (`filesDir/logs/error_log.txt`), unlike `logError()`'s old debug-only Logcat output.
It's wired up via:
- `Thread.setDefaultUncaughtExceptionHandler` in `AcraApplication.attachBaseContext`
- every call to `logError()` (in `utils/mvvm/ArchComponentExt.kt`)
- the `CoroutineExceptionHandler` on `Coroutines.main`

Users can view/copy/save/share/clear it from Settings -> History -> "Error log"
(`ui/settings/ErrorLogActivity`), and file it as a pre-filled GitHub issue against
`Nekomo-App/neko-source`.

## Networking / DNS over HTTPS

The app's own network calls (`NekomoApi`, `LiveApi`, `MALApi`, `AniListApi`, extractors, ...)
all go through the `khttp.get/post/put/delete/head/patch` facade in
`app/src/main/java/khttp/KHttp.kt`. That facade is now backed by OkHttp (previously raw
`HttpURLConnection`) specifically so a single `okhttp3.Dns` can be swapped in for DNS-over-HTTPS.

- `com.lagradost.shiro.utils.DohProvider` holds the provider list (Cloudflare, Google,
  AdGuard, AdGuard Family, Quad9) and builds an `okhttp3.dnsoverhttps.DnsOverHttps` resolver
  for the selection, or `Dns.SYSTEM` when disabled.
- Settings -> General -> "DNS over HTTPS" (`dns_provider` key, `settings_general.xml`) lets
  the user pick a provider; `khttp`'s cached `OkHttpClient` is rebuilt automatically the next
  request after the preference changes (see `baseClient()` in `KHttp.kt`), no app restart
  needed.
- This does **not** affect ExoPlayer's own `DefaultHttpDataSource` used for video/stream
  playback sockets - matches CloudStream's scope, which only applies DoH to its own API/HTML
  scraping requests, not to the video CDN connections themselves.
- Added Gradle deps: `com.squareup.okhttp3:okhttp:4.12.0` and
  `com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0`.

## Settings audit

Cross-referenced every preference key defined in `res/xml/settings_*.xml` against where it's
actually read in code. Re-enabled three that had a finished feature behind them but were
left commented out in both the XML and the Kotlin read site (`PlayerFragment.kt`): "Skip OP
button", "Playback speed button", "Player resize button" (all default `true` to preserve
today's always-on behavior, and blacklisted from TV settings since `PlayerFragmentTv` doesn't
use them - it has its own always-available D-pad actions instead).

**Left disabled on purpose:** "Use alternative Vidstream" (`alternative_vidstream`,
`utils/extractors/Vidstream.kt`). Its `mainUrl`/`getExtractorUrl()` toggle only ever pointed at
the old fastani/gogo-stream/streamani.net mirrors, which the class's own `getUrl()` already
treats as dead ("Legacy fastani/vidstream ids are dead with the old backend") - the live code
path goes through `LiveApi.resolveStreams` instead and never consults `mainUrl`. Re-enabling
this toggle would just add a switch that does nothing.

## NEX app build (separate repo: ../nex-app)

`Nekomo-App/NEX` is a separate Android repo (Kotlin, `net.nekomo.nex`, AGP 8.7.3 / Kotlin
2.0.21 / KSP-Room). `v0.3.2-alpha` prerelease has a real APK (`NEX-0.3.2-alpha-debug.apk`).
Local builds on this machine fail `compileDebugJavaWithJavac` with a Windows
"volume label" error in AGP's `SdkLocator.validateSdkPath` — unsolved env bug; CI
(`.github/workflows/build.yml`, ubuntu + temurin-17) builds clean and is the
canonical way to produce NEX APKs. Real code bug that surfaced in CI: the
classic-icon alias referenced `color/ic_launcher_background` which didn't exist in
NEX's `colors.xml` — now defined (`#FF101114`). Version via `-PnexVersionName`/
`-PnexVersionCode`; N3KO icon + classic alias live in `nex-app/app/src/main/`.

## Watch Together + nekomo-play links

- `utils/WatchTogether.kt`: same-WiFi sync, no server. Host broadcasts `slug|ep|posMs` on TCP
  port 47100 every 3s; guests type the host IP and `seekTo` when drift > 4s. Player "Together"
  button (`watch_together_btt` in `player_custom_layout.xml`) opens the Host/Join/Stop dialog.
- `nekomo-play://play?slug=<slug>&ep=<n>&pos=<ms>` deep links open a title at a position
  (handled in `AppUtils.handleIntent`, mirroring the shiro.is branch); the details Share button
  includes a play-link. This is the protocol WT/NEX sync upgrades to later.

## Known follow-up work

- **`Fragment.fv()` (`utils/Synthetics.kt`) — mitigated:** it is now `inline reified` and returns a
  detached throwaway view of the requested type when the fragment view is destroyed, instead of
  throwing. Call sites are unchanged. Remaining risk: code that casts `layoutParams` or reads
  state from that dummy view can still fail. Original note: it throws `IllegalStateException` via
  `requireView()` whenever a fragment's view is already destroyed (e.g. callbacks firing
  after back-navigation). This is the single biggest source of crashes in the app. Fixing
  it properly means changing `fv()` to return `T?` (`view?.findViewById(id) as? T`) and
  auditing every call site that currently assumes a non-null result (`fv(id).foo()` instead
  of `fv(id)?.foo()`) — this could not be done blind without a compiler in this environment,
  so it was intentionally left out of this pass. Do this with a working Gradle build so
  compile errors surface immediately.
- **Aniyomi/Mihon/Zangetsu**: implemented (see below). LN Reader plugins are not implemented.

## CloudStream extensions (.cs3)

- `app/libs/cs3api.jar` is a trimmed `com/lagradost/**` copy of CloudStream's `pre-release`
  `classes.jar` (what real plugins compile against). It needs Kotlin >= 2.4 (root `build.gradle`)
  and the runtime deps listed under "CloudStream extension" in `app/build.gradle`.
  It is GPL-derived; review licensing before redistributing.
- `utils/cs3/CsPluginManager.kt`: repos, install (sha256 verify, atomic replace), `PathClassLoader`
  loading from `filesDir/Extensions`, enable/disable, update. Initialised in `AcraApplication`.
- `utils/cs3/CsBridge.kt`: maps providers into app models. Slugs/tokens are `cs3|<api>|<hex url>`;
  hooks are in `NekomoApi.searchNew`, `LiveApi.getAnimePage` and `LiveApi.resolveStreams`.
- `ExtractorLink` gained `isDash` and `headers`; `PlayerFragment` applies them.
- UI: Settings -> Extensions (`ui/settings/ExtensionsActivity`).
- Slugs/tokens are now `cs3-<hex api>-<hex url>` (hex + `-` only: safe in file names, prefs keys and
  `|`-split tokens). Search/load/loadLinks calls have timeouts in `CsBridge`.
- Extensions screen shows a one-time "only install extensions you trust" warning.

## Sign-in gate / welcome screen

- `ui/welcome/WelcomeGate` is a full-screen non-cancelable Dialog shown over `MainActivity` and
  `TvActivity` (called right after `setContentView`) until `AuthManager.isSignedIn` (token AND fetched
  profile for AniList, MAL or Kitsu) and the community rules have been accepted.
  OAuth redirects still return through `handleIntent`; the gate polls and advances by itself.
  On TV the in-app WebView sits under the dialog window, so the dialog hides while it is open.
- Kitsu (`utils/auth/KitsuApi`): OAuth password grant with Kitsu's public client credentials.
- Official URLs (Discord, Telegram, website, support, ToS, Privacy, DMCA) live in `res/values/links.xml` and `settings_about.xml`.
- Settings -> Community Rules (`CommunityRulesActivity`) reuses the rules text and `SocialLinks`.
- `PlayerActivity` (exported for `content://` video intents) is gated: it bounces to the launcher unless signed in
  and rules accepted.

- Verified: phone + TV debug builds and phone release (R8) compile; `lintPhoneDebug` has 28 errors, none
  in the new code (rest pre-existing: NewApi, Range, receiver flags).
- Earlier note: phone + TV debug builds compile. NOT verified: on-device plugin loading, playback,
  and the release (R8) build - no emulator/device was available.

## Device testing notes (verified on a Galaxy S22 Ultra, Android 16)

- Extensions: `CsBridge.search` tries `search(query, page)` first, then the legacy `search(query)`
  (each provider implements only one; the other throws NotImplementedError). Verified end to end with
  CloudStream's official repo (`recloudstream/extensions`): install InternetArchiveProvider -> search ->
  details -> play; the player logs `ExtractorLink(name=Internet Archive ...)` from archive.org.
- `CsPluginManager.init` and `ErrorLogger.init` must run with a real context: `applicationContext` is null in
  `attachBaseContext`. `ErrorLogger` silently dropped every entry before this was fixed.
- R8: Jackson + kotlin-reflect failed on `kotlin.collections.EmptyMap` (home feed never loaded in release/beta,
  with no logged error). `LiveApi.gql` now uses plain JDK maps and proguard keeps the empty collections,
  view constructors (`Synthetics.fv` placeholders) and `com.lagradost.shiro.**`.
- `desugar_jdk_libs` must be >= 2.1.5 (1.2.2 rewrote `List.addFirst` to a method that does not exist -> crash on
  Android 16 at launch).
- To read logs from a minified build, temporarily set `debuggable true` on the beta type and use
  `adb exec-out run-as app.nekomo.beta cat files/logs/error_log.txt`.

## Sources / playback (verified on device, release build)

- Anime page has a cloud-icon "Choose source" button (`ResultFragment.showSourceChooser`): lists the built-in
  catalogue plus every loaded extension provider, searches the title there and opens the match.
- In-player "Source" menu picks a stream link; it now uses `setValue` (was `postValue`, which raced with the
  reload) and `onPlayerError` automatically falls through to the next link.
- The old built-in streaming backend (AllAnime/mkissa/fastani, WebView scraper, host extractors) was **removed**; AniList
  is metadata only and streams come exclusively from user-installed sources (`LiveApi.resolveStreams` routes
  `cs3-` / `zg-` / `ay-` tokens). `Vidstream.kt` only dispatches those tokens.
- Not implemented: LN Reader plugins (need a cheerio-style JS DOM plus a novel reader).

## Zangetsu sources (JS providers)

- `utils/zg/ZangetsuEngine` runs Zangetsu provider scripts on Rhino (interpreted, ES6, one big-stack thread;
  host API = promise `fetch`, timers, `htmlText`, `absUrl`, base64/hex helpers, sha256/AES-CTR). `ZangetsuManager`
  stores repos (manifest `index.json` URLs) and installed scripts in `filesDir/Zangetsu`; `ZangetsuBridge` maps
  `search` / `getDetail` / `getVideoSources` into app models. Slugs are `zg-<hex id>-<hex url>`.
- Nothing is bundled: the user adds a repo and installs sources in Settings -> Extensions -> Zangetsu sources.
- Verified on device (debug + minified release): add `Spyou/zangetsu-providers` -> install HiAnime -> results show
  in search, anime page loads, and an episode streamed (HLS) in debug. HiAnime itself can answer
  "no playable server" for some episodes; the player now says so instead of spinning forever.
- Aniyomi/Mihon were ported from Zangetsu's reference bridges (GPLv3); LN Reader plugins are still not implemented.

## No bundled sources (policy) + Aniyomi / Mihon / filters

- A fresh install ships NO streaming source, repo, extractor or extension. AniList (graphql.anilist.co) is used for
  metadata only (trending/search/details); an AniList title has no episodes until the user picks one of their own
  sources via the cloud "Choose source" button (the page opens the chooser automatically). The old AllAnime/mkissa
  backend, its WebView scraper and all host extractors were removed; there is no "browse public repositories" list.
- Sources the user adds themselves: CloudStream (`cs3-`), Zangetsu (`zg-`), Aniyomi anime extensions (`ay-`), and
  Mihon manga extensions (Settings -> Manga). Settings -> Extensions has one section per kind; every add/install asks
  for confirmation, and each installed item has a "Report" button (opens the repo's issue tracker).
- `eu/kanade/tachiyomi/**`, `tachiyomi/**` are the vendored Aniyomi/Mihon extension API (Apache-2.0, copied from
  Zangetsu's repo, GPLv3 host). `utils/aniyomi/AniyomiRuntime` stands up Injekt; `AniyomiManager`/`MihonManager`
  download APKs from an `index.min.json` repo, validate and load them with `DexClassLoader`.
- Settings -> General: "Show movies and TV shows" (`non_anime_enabled`, default off) and "Show 18+ sources and
  extensions" (`nsfw_enabled`, default off); see `utils/ContentPrefs`.
- Verified on device: Aniyomi (yuzono/anime-repo, Anikoto) -> install -> search -> details -> episode list; stream playback
  could not be confirmed because the test phone's DNS-filter VPN blocks that extension's backends. Mihon
  (`keiyoushi/extensions` `index.pb`, MANGA Plus) -> install -> popular -> chapters -> reader works on debug and on the
  minified release build. Keiyoushi's/Yuzono's `index.min.json` now return only an "update your app" stub; use `index.pb`.
- Not device-tested: the "Show movies and TV shows" toggle revealing CloudStream plugins, the TV builds, Zangetsu after the
  OkHttp 5 upgrade. LN Reader plugins are not implemented (need a cheerio-compatible JS DOM).

## Update channel (in-app updater)

- `utils/InAppUpdater` reads GitHub releases from every repo in the `update_repos` setting (default
  `Nekomo-App/Nekomo,Nekomo-App/neko-source`), compares versions numerically, and offers the newest one.
- Assets are matched to the installed build: `Nekomo-<phone|tv>-<release|beta|debug>.apk` (legacy `x.y.z.apk` /
  `x.y.z-TV.apk` still match for stable). Beta/Debug apps (different package + signature) only ever see their own
  APK and always follow pre-releases; the stable app follows pre-releases only with "beta_mode".
- Settings -> Updates: startup check (`auto_update`, throttled to 1/h), pre-release switch, repositories, manual
  check, version/channel line. Downloads are restricted to https github.com / *.githubusercontent.com.
- Publish with `scripts/publish-release.sh <version> [stable|beta] [owner/repo]` (needs `gh`). No releases exist
  yet in either Nekomo-App repo, so the updater currently reports "up to date".
- `ShiroApi` was renamed `NekomoApi`; the old Shiro token/donor/shiro-db/fastani endpoints were deleted.

## Licensing

- `LICENSE` = original Shiro MIT notice (kept). `COPYING` = GPL-3.0 text: the shipped APK includes GPL-3.0 parts
  (cs3api.jar, NiceHttp, NewPipeExtractor, Zangetsu-derived), so the combined app is GPL-3.0-or-later.
- `THIRD_PARTY_LICENSES.md` + `app/src/main/assets/licenses.txt` are generated: run
  `./gradlew :app:dependencies --configuration phoneReleaseRuntimeClasspath > deps.txt` then
  `python scripts/gen_licenses.py deps.txt` after changing dependencies (script fails loudly on unknown licenses).
- fuzzywuzzy (GPL-2.0-only, incompatible with GPL-3) was removed; `utils/FuzzyMatch` replaces it.
- play-services-cast/gms use Google's proprietary SDK license; keep that in mind for F-Droid style distribution.

## Theme

- Default Cyanea theme (`assets/themes/cyanea_themes.json`[0] "Nekomo") and `values/colors.xml` use the website/icon
  palette (accent #e5395f, deep #8d2249, bright #ff5c7a, background #0c0508). It is re-applied once per install
  (`nekomo_theme_v1`). The old red is still available as "Classic red". AMOLED (x2), Midnight, Nekomo Light and
  Minimal Slate variants were added at the end of the same JSON; Cyanea picks them up automatically.

## Interface modes and card styling

- `utils/UiStyle` is the single read point for the appearance tweaks in Settings -> Appearance
  (`custom_pref_cyanea.xml`): `ui_mode` (classic/modern), `poster_size` (%), `card_corner_dp`, `card_spacing_dp`.
  Modern defaults to 14 dp radius / 8 dp spacing; switching modes seeds those values in `CyaneaSettingsFragment`.
- `UiStyle.restyle(view)` is called once per inflated card in `CardAdapter`, `CardContinueAdapter`, `ResAdapter` and
  `LibraryCardAdapter`: it walks the view tree, sets the radius on every CardView, scales fixed-size
  `imageView`/`imageText` pairs by `poster_size` and rewrites `home_card_root` side margins. `UiStyle.entrance`
  plays the modern fade/rise animation (skipped under `reduce_motion`); keep the per-adapter `lastAnimated`
  high-water mark so scroll-backs don't replay it. Focus handlers that reset `.radius` must use
  `UiStyle.cardRadiusPx`, not a hardcoded dp.

## Settings search, startup page, splash, episode alerts

- Settings search: `utils/SettingsSearch` indexes every titled Preference inside the settings XMLs
  (plus standalone manager screens). The box lives at the top of `fragment_settings.xml`
  (`settings_search`/`settings_search_results`, wired in `SettingsFragmentNew`). Results open
  `SettingsActivity` with `XML_KEY` + `HIGHLIGHT_KEY`; `SubSettingsFragment` then
  `scrollToPreference(key)` after ~350 ms.
- "Start on page" (`startup_page`, settings_general) is read in `MainActivity.onCreate` right after
  `setupWithNavController` and navigates to the chosen tab.
- Splash: `SplashTheme` (values + values-v31) on both launchers — layer-list `drawable/splash_screen`
  (bg #0d0714 + radial glow + nekomo_logo) as windowBackground pre-31, `windowSplashScreen*` attrs on
  31+, `postSplashScreenTheme` handoff via `androidx.core:core-splashscreen:1.0.1` +
  `installSplashScreen()` at the top of `MainActivity.onCreate` and inside `TvActivity.init()`
  (both before super.onCreate). Both activities swap the window background for the Cyanea color once
  content is up.
- New-episode alerts: `utils/NewEpisodeWorker` (12 h periodic, network) diffs `LiveApi.getRecents()`
  against per-slug seen episode floats in `episode_notify_seen`; first run only baselines. Gated by
  `notify_new_episodes` (settings_general); toggling it requests POST_NOTIFICATIONS (33+) and calls
  `schedule(ctx, override)`. Scheduled from `AcraApplication.onCreate`. Notification channel
  "new_episodes", id 7401.
- Onboarding is 5 steps: Sign in -> DMCA -> Rules -> Third-party repo warning -> Quick setup
  (`Step.SETUP` in `ui/welcome/WelcomeGate`, gated on `AuthManager.hasCompletedSetup`
  `setup_completed_v1` in DataStore; `canEnter` includes it so existing users see it once).
  The setup step writes `ui_mode` (+card corner/spacing seeds), `non_anime_enabled` and
  `dns_provider` directly to default prefs and ends with a DMCA/rules reminder line.
- App lock: `utils/AppLock` stores a salted SHA-256 of the PIN (`app_lock_hash_v1`/`app_lock_salt_v1`);
  `AppLock.unlocked` is a per-process flag so the PIN is asked once per cold start.
  `ui/welcome/AppLockGate.attachIfNeeded` runs on both launchers right after `WelcomeGate`.
  Set/change/remove in Settings -> History and privacy -> "Lock the app with a PIN".
- Search page: "Random" sits in the history panel (always visible on an empty query) and opens a
  `getRandom()` title; subtitle size/color/background prefs (`subtitle_size_pct`, `subtitle_color`,
  `subtitle_bg`) are applied to `playerView.subtitleView` in `PlayerFragment.applySubtitleStyle`.

## Manga chapter alerts

- `utils/manga/MangaFollows.kt`: per-device follows (`sourceId:Long|url` -> title in
  `manga_follows` prefs) + last-seen chapter counts in `episode_notify_seen`.
- `MangaDetailActivity` header gets a "Follow — notify on new chapters" checkbox.
- `NewEpisodeWorker` (same 12h job + quiet hours) loops follows after the anime pass:
  `MihonBridge.details(...).chapters.size` vs stored count; first run sets baseline.

## Mode-aware corners (UiStyle)

`utils/UiStyle.kt` is the central place for Classic vs Modern (`ui_mode` pref), card radius
(`card_corner_dp`, default 6 classic / 14 modern), card spacing, entrance anims and
`buttonRadiusDp` (10 classic / 20 modern). `PageBuilder` (`ui/settings/InfoPages.kt`) reads
these for its cards and primaryButton — add new chrome radii there, not hardcoded dp values.
Dialog window bg: `drawable/dialog_rounded_bg.xml` (12dp).

## Launcher disguise (activity-alias)

- `app/src/phone/AndroidManifest.xml`: the LAUNCHER intent-filter moved OFF `.ui.MainActivity`
  onto `.ui.AliasDefault` (enabled) + themed aliases (disabled): Neko, NekoHub, AnimeNeko,
  Neko Stream, Neko Club, Neko-chan + seasonal Sakura/Winter/Spooky/Midnight icons
  (recolored Nekomo mark on `ic_bg_*` tiles). MainActivity keeps VIEW filters and stays
  exported, so deep links and direct-component launches (NEX) still work.
- `AppIdentity.aliases` maps alias suffix -> launcher label; `applyAlias` flips component
  enabled state (`DONT_KILL_APP`, never leaves zero enabled). Picker lives in
  AppIdentityActivity ("Launcher name & icon" row, phone only — hidden on TV).
- Cat artwork (N3KO box-cat, katakana marks) belongs to the NEX app — do NOT re-add it here.
  The identity screen is presets-only: no free-text name, no custom image import.
- Verified on device: all aliases register LAUNCHER filters, `.ui.AliasDefault` launches the app.

## Kuro-style translucent banner + UI polish pass

- `utils/BlurTransform.kt`: lightweight Glide BitmapTransformation (1/8 downscale +
  desaturate + darken) applied to `results_banner` — no RenderScript, API 24 safe.
- `site_data_card_view` is set to ~85% of `Cyanea.instance.backgroundColor` in code so
  the blurred banner shows through (theme-aware).
- Edge fades: `res/drawable/gradient_top.xml` (bg -> transparent) pairs with the
  existing `gradient` (transparent -> bg); used on `results_banner_holder` and
  `main_poster_holder` (home hero). `gradient_gloss` adds a faint top sheen.
- Dialog polish: `AlertDialogCustom` now has `windowBackground=@drawable/dialog_rounded_bg`
  (18dp corners, theme bg) + `DialogAnimationCustom` (pop_enter/pop_exit scale+fade).
  Everything is additive — Classic layout/positions untouched.

## Advanced & diagnostics (Settings -> General)

- `experimental_features` switch gates genuinely experimental prefs (currently it hides
  the `subtitle_delay_ms` slider unless on or already non-zero). Read in
  `SubSettingsFragment` settings_player block.
- `adv_error_log` (reuses ErrorLogActivity), `adv_diagnostics` (copies build/device info),
  `adv_cache_clear` (deletes the exoplayer + image cache dirs, shows freed size).

## Subtitle delay

- `ui/player/DelayedTextRenderer.kt`: TextRenderer is final in ExoPlayer 2.19, so a
  `Renderer` wrapper delegates every method and calls `render(positionUs - offsetUs)` —
  positive `subtitle_delay_ms` (Settings -> Player, -10s..+10s) delays subtitles.
  `DelayedRenderersFactory` plugs it into `SimpleExoPlayer.Builder` and keeps the live
  instance in `current`; a pref listener in `PlayerFragment` applies changes mid-playback.

## Player stats overlay

- `player_stats` switch (Settings -> Player, "Playback statistics overlay") toggles a
  monospace readout (`player_stats_overlay` in `player_custom_layout.xml`): codec,
  resolution, bitrate, buffered seconds, state and speed, refreshed every second via
  `PlayerFragment.applyStatsOverlay`/`statsHandler` (stopped in `onDestroy`).

## Per-show alert mutes + card presets

- Episode alerts can be muted per title: long-press the subscribe bell on a title page
  toggles `episode_notify_muted` (StringSet); `NewEpisodeWorker` skips those slugs.
- Settings -> Appearance gets "Card layout presets" (Compact/Classic/Modern, sets
  ui_mode + corner + spacing + poster size) and "Reset card layout".

## "Coming up" home section

- `LiveApi.getUpcoming()` queries `airingSchedules(sort: TIME_ASC, notYetAired: true)`, dedupes
  per slug (earliest episode wins), and stuffs `"Ep N · EEE d MMM"` into the card's `english`
  field (shows as the subtitle in adapters that render it, e.g. ResAdapter).
- Registered as `upcoming` on `AniListHomeSource` (defaultDisabled, enable in Home settings).
  No Jikan/Kitsu fallback — the row is empty if AniList is down.

## Manga chapter downloads

- Reader settings (gear in `MangaReaderActivity`) adds "Auto-download next chapter"
  (`manga_auto_download`, default off): after a chapter loads, `maybeAutoDownloadNext`
  quietly downloads the next chapter in reading order via `ChapterDownloads`.
- Chapter long-press menu also has "Mark previous read" (bulk-read below the chapter).

- `utils/manga/ChapterDownloads` stores each chapter under
  `filesDir/manga_dl/<sourceId>/<md5(chapterUrl)>/` as `p<i>` page files + `meta.json`
  (sourceId/url/name/manga/pages). Writes go to a `<md5>.part` dir renamed into place,
  so a killed download never leaves a half-valid chapter.
- Long-press a chapter in `MangaDetailActivity`: Download chapter / Download all unread /
  Delete download / Mark read|unread. Downloaded chapters get a "⬇" badge in the list.
- `MangaReaderActivity.loadChapter` checks `ChapterDownloads.pages` before any network —
  downloaded chapters read fully offline (source resolution skipped, `localFiles` in
  `PageAdapter` decodes file bytes through the same `MangaSession.decode` path).
- `MangaBrowseActivity` "Downloaded" button lists every download (newest first): tap
  reads (single-chapter `MangaSession`), long-press deletes.

## Manga reader

- `MangaReaderActivity` reads `manga_reader_mode` (webtoon / ltr / rtl — paged modes use a horizontal
  LinearLayoutManager + PagerSnapHelper, rtl = reverseLayout), `manga_reader_bg` (black/gray/white),
  `manga_volume_keys`, `manga_keep_screen_on`, `manga_immersive` and `manga_remember_position` (per-chapter page
  index in the `manga_read` prefs as `pos_<chapterUrl>`). The same options are in Settings -> General ->
  "Manga reader" and live-editable via the reader's gear dialog. All six keys are TV-blacklisted.

## Browsing / installing sources

- No in-app directory/search/one-click install of sources: users paste a repository URL themselves (CloudStream
  shortcodes via cutt.ly/py.md and the "Install all" button were removed). A repo's own list is shown only after the
  user adds that repo. Mihon repos accept `index.min.json` or the new gzip'd protobuf `index.pb`.
- minSdk is 24: current Mihon extensions (extension-lib 1.6) need real interface default methods, which D8 strips at
  minSdk < 24 (AbstractMethodError). OkHttp is 5.3.2 (extensions use okhttp3.brotli.Brotli / okhttp3.zstd.Zstd).
  proguard-rules.pro keeps kotlin/okhttp/okio/jsoup/rx/injekt/eu.kanade for the dynamically loaded extension APKs.

## Offline (local) library

- Not signed in? `utils/LocalLibrary` keeps a per-device library (Watching / Plan to watch / Completed / On hold / Dropped).
  Add titles from the title page's right-hand sync panel ("Save to offline library"); view/edit/export/import in
  `ui/library/LocalLibraryActivity` (Library tab -> "Open offline library"). Export is a JSON share; import merges by
  newest update; "Sync to AniList" pushes entries whose slug is `al<id>` once signed in.
- Favorites (hearts), continue-watching, downloads and the existing Back up / Restore in Settings -> History also work
  without an account.

## Home page, source kinds and adding new APIs

- The phone home (`ui/home/HomeFragment.renderSections`) and TV home (`MasterCardAdapter`) are built from
  `utils/home/HomeLayout` (user order, per-section and per-source switches; Settings -> Home page,
  `ui/settings/HomeSettingsActivity`). `HomeFeed` caches section contents for 20 minutes.
- Every provider implements `HomeSource` (`utils/home/HomeSources.kt`) and is listed in `HomeRegistry`. Each declares a
  `SourceKind` so the UI always labels it: `OFFICIAL` (link-outs only, e.g. Crunchyroll/HIDIVE - no public streaming
  API, so Nekomo only opens their site/app), `METADATA` (AniList, Jikan/MyAnimeList: titles/art/recommendations only),
  `THIRD_PARTY` (rows published by the user's own CloudStream providers) and `LOCAL` (continue watching, subscribed,
  favorites).
- To add an API: implement `HomeSource` (sections + blocking `fetch`), register it in `HomeRegistry`, and set
  `attribution` / `termsUrl`. Respect the provider's terms: AniList and Jikan are rate limited (Jikan calls are spaced
  450 ms apart in `JikanHomeSource`), cache results via `HomeFeed`, keep attribution visible in home settings, and never
  bundle a streaming source. Slugs: `al<id>` AniList, `mal<id>` MAL (opens through AniList), `http...` link-out.
- `HomeFilters` is the home blocklist (Sazae-san and similar endless weeklies); AniList results go through it.
- Defaults: AniList on (trending, recently updated, popular, recommended, Action/Romance/Comedy/Fantasy), Official
  links on, everything else off.

## Privacy, offline library and onboarding

- Incognito (`utils/Privacy`, Settings -> History and privacy): no watch progress (`setViewPosDur`), no AniList/MAL
  progress sync (`PlayerFragment.updateProgress`), no search history (`utils/SearchHistory`). Optional 18+ device lock
  (`adult_lock`) uses the system PIN/pattern prompt before the 18+ switch can be turned on.
- Search history lives in `SearchHistory` (15 items, chips above results in both search layouts).
- Offline library: see `utils/LocalLibrary` (works without an account; export/import/sync to AniList).
- Welcome gate order: sign in or skip -> DMCA notice (`AuthManager.hasSeenDmca`) -> community rules -> home.
- Settings pages: `About`, `DMCA notice`, `Rules` are `ui/settings/InfoPages.kt` (short overview + link to the full
  text on the website). Keep the website paths `/dmca`, `/rules`, `/terms`, `/privacy`, `/support`, `/sources` stable.
- `SourceHealth` marks sources that fail twice in a row (shown in the source chooser).
- Unit tests: `./gradlew testPhoneDebugUnitTest` (pure logic only). CI: `.github/workflows/build.yml`.

## Metadata hub, multi-media and tracker mode

- `utils/meta/MetaHub` is the metadata layer next to `LiveApi` (AniList, primary). `ApiHealth` pauses a provider on HTTP 429
  (Retry-After) or after 3 failures; callers then use the next one: AniList -> Kitsu -> Jikan (MAL). `MetaCache` keeps the
  last good page of every title so it opens even when every API is down. Test it from Settings -> Home page -> "Test backups".
- Slugs: `al<id>` AniList, `mal<id>` MyAnimeList (AniList first, then Jikan, then Kitsu via mappings), `kt<id>` Kitsu,
  `tvm<id>` TVMaze (TV, no key), `tmm<id>` / `tmt<id>` TMDB movie / TV (needs the user's own key, Settings -> General).
  Non-anime search/rows only appear with "Show movies and TV shows".
- Episode lists for metadata-only pages come from Jikan/Kitsu/TVMaze/TMDB (episodes with `ext == "meta"`; numbered
  placeholders when only the count is known). They are tracker episodes: tapping one opens a menu (play through a source,
  mark watched, mark all up to here) handled in `ResultFragment.showMetaEpisodeMenu` -> `utils/Tracker` (same watched flags the
  player uses; mirrors into `LocalLibrary`, pushes to AniList when signed in, disabled by incognito). `loadPlayer` refuses
  meta episodes and sends the user to the title page instead.
- Title page: Sources dropdown (`source_row`/`source_spinner`, preferred source = `PreferredSource`, also in Settings ->
  General), globe button = language switch + "open on AniList/MAL/Kitsu/TVMaze/TMDB", cast button shows when a Cast device is found.
- Player: Pop-out (picture-in-picture) and External player buttons; Settings -> Player -> "Open videos in an external player".
- Attribution to keep visible: TMDB ("This product uses the TMDB API but is not endorsed or certified by TMDB"), TVMaze (CC BY-SA).

## Builds live in /apks; metadata vs streaming

- `apks/` is the main build directory (see `apks/README.md`). `./gradlew assembleAllApks` builds the six variants and runs
  `collectApks`, which copies them to `apks/Nekomo-<phone|tv>-<release|beta|debug>.apk` and writes `apks/latest.json`
  (version, size, SHA-256, build time). Do not rename the files: the website `_redirects`, `beta.html` and
  `scripts/publish-release.sh` depend on them. Older/legacy builds go in `apks/archive/` (never served by the website).
  `.kotlin/`, `app/build/` and stray APKs under `app/` are ignored. Note each commit of APKs grows `.git` (~100 MB); prefer
  GitHub Releases for history.
- Metadata and streaming are separate. The built-in catalogue (AniList, backups Kitsu/Jikan, TVMaze/TMDB) supplies titles, art,
  descriptions and episode lists. Installed extensions only stream: `utils/SourcePlayback` finds the title in a source,
  matches the episode number, caches the match (`srcmatch_*`), opens the player and mirrors "watched" back to the tracker
  (`metalink_*`). A tap on a metadata episode streams through the default source (preferred, or the only installed one); long
  press opens the full menu. Search shows built-in results only unless "Show source results in search" is on.
- Failures are explained to the user (not installed / disabled, incompatible or unreachable extension, title not found,
  no matching episode). Zangetsu logs the provider's answer when it returns no streams.

## Extension metadata, trailers and TV labels

- Extension pages (Aniyomi / CloudStream / Zangetsu) are enriched by `utils/meta/MetaEnricher` from the built-in catalogue
  (AniList, backups Kitsu/Jikan): title/cover/banner/synopsis/genres/score/studio/season/trailer are filled in; episodes and
  streams are never touched. The matched built-in slug is cached (`enrich_<extSlug>`). `AniyomiBridge` remembers titles and
  covers from search (`aycache_*`) because extensions often return an empty title from getAnimeDetails.
- Trailers: tokens `youtube:<id>` / `dailymotion:<id>` in `AnimePageNew.trailer` (AniList, Kitsu, Jikan, TMDB). `utils/Trailers`
  resolves YouTube with NewPipe (OkHttp downloader) and plays it in a dialog with the app's ExoPlayer; failures show a message
  and an "Open in browser" button. R8 keep rules for NewPipe/Rhino are in `proguard-rules.pro`.
- TV labelling: app titles Nekomo TV / Nekomo TV Beta / Nekomo TV Alpha (`src/tv*/res/values/strings.xml`), phone Nekomo /
  Nekomo Beta / Nekomo Alpha. APKs: `Nekomo-<phone|TV>-<release|beta|alpha>.apk` ("alpha" = debug build type). The updater accepts
  both `alpha` and `debug`; the website keeps redirects for the old lowercase names.
- Settings -> General -> "Reduce motion" (also follows the system animation setting) via `utils/Motion`.

## Nekomo repository, extensions manager and updater

- Policy (user decision): an OPTIONAL official repository. Nothing is installed until the user adds it in Settings ->
  Extensions -> Extensions manager (a private code or a manifest URL; the code is stored only as a SHA-256 hash and must never be shown in the UI or docs) and confirms. It only lists free/legal sources and
  never APK extensions. Manifest: `repo/index.json` (marker `nekomoRepository`), format in `repo/README.md`. No catalogue of
  piracy-oriented sources is hosted or listed; users can still add any repository URL manually (ExtensionsActivity).
- `utils/repo/NekomoRepo` (resolve/fetch/plan/apply/remove, history) and `ExtensionUpdater` (check/apply, WorkManager
  `ExtensionUpdateWorker` + start-up check, settings `ext_auto_update`, `ext_update_hours`). Updates re-download via
  `CsPluginManager.updatePlugin` / `ZangetsuManager.install`, which only replace the old version after a verified download.
- `ui/settings/ExtensionManagerActivity`: Nekomo repo, updater + history, repositories, sources (details/enable/providers/update/
  uninstall), built-in metadata APIs (enable/disable via `api_enabled_<id>`, TMDB key), APK extensions (listed, enable/disable/
  remove only; installs stay manual in `ExtensionsActivity`).

## Speed and fallbacks

- `ApiHealth` tracks smoothed latency and failures per metadata API; `ApiHealth.fastest(ids)` orders backups (paused last).
  `Race.first(hedgeMs, tasks, ok)` runs alternatives in priority order and starts the next one in parallel when the current
  attempt is slower than `hedgeMs`; the first acceptable result wins. Used for search (AniList then Kitsu/Jikan), MAL details,
  episode lists and movie/TV search. AniList calls fail fast (10 s) and record latency.
- `SourcePlayback.resolveAuto` tries the chosen source (20 s limit) and then the other installed sources ranked by
  `SourceHealth.rank` (healthy and fast first), accepting only confident title matches; the UI says which source played.

## NEX companion app and the add-repo link

- NEX (github.com/Nekomo-App/NEX, package `net.nekomo.nex`) manages repositories/extensions for Nekomo, Mihon, Aniyomi, CloudStream and
  Zangetsu. It reads `repo/index.json` and `apks/latest.json` (flavor, channel stable/beta/alpha, version, versionCode, sha256): keep
  those schemas stable and keep the package ids `app.nekomo[.tv][.beta|.debug]`.
- `ExtensionManagerActivity` handles `nekomo-repo://add?url=<url>` (exported, login-gated, always a confirmation dialog). It accepts Nekomo
  manifests and CloudStream/Zangetsu/Aniyomi/Mihon repository addresses (address only; APK extensions stay manual). The manager also has
  an Open/Get NEX button (manifest `<queries>` lists the NEX packages). Issues for the app live on Nekomo-App/Nekomo.

## Onboarding, search keyboard, custom app identity

- Onboarding (`ui/welcome/WelcomeGate`): Sign in -> DMCA notice -> Rules -> Third-party repository warning (step 4, `AuthManager.hasAcceptedRepoWarning`). People who finished the older three steps see only the new step once. Auto-backup is disabled (`allowBackup=false` in main/phone/tv manifests) so a reinstall always starts onboarding and never restores account tokens.
- Search (`ui/search/SearchFragment`): the bar never grabs focus on entry (focus is cleared after `onActionViewExpanded()`); the keyboard opens only when the bar is tapped. Recent searches stay under/above the bar, tap to re-run.
- Custom name and icon (`utils/AppIdentity`, `ui/settings/AppIdentityActivity`, NEX has the same in `util/AppIdentity` + a Settings dialog): pinned home-screen shortcut, long-press shortcut and recent-apps card. Always give the shortcut an icon (launchers crash without). Android TV launchers cannot pin shortcuts; the UI says so.
- Policy: no catalogue of unofficial third-party source repositories is bundled or given shortcodes; users add those by URL.
- Aniyomi + Mihon are one integration in the Extensions manager: adding a repository URL (or `nekomo-repo://add` link) detects the shared extension format, classifies entries by package name (animeextension = Aniyomi, otherwise Mihon) and adds the repo to whichever manager(s) need it. A repo used by both shows as one "Aniyomi + Mihon" row. APKs are never installed by Nekomo.
- Releases are published to BOTH Nekomo-App/Nekomo and Nekomo-App/Neko-Source (same tags and assets); the in-app updater reads both and picks the newest per build.
- Screen-capture protection is on by default (`FLAG_SECURE` via `utils/CapturePolicy`, applied in the activity lifecycle callbacks). Turning it off (Settings > Screen capture) and changing the app name/icon each sit behind their own multi-step puzzle (`utils/Puzzle`, `ui/settings/PuzzleUi`). A hidden bypass exists in the puzzle title; do not document it publicly. NEX has the same in `util/Puzzle` + `ui/screens/PuzzleDialog`.
- Orientation: every screen follows the device's auto-rotate setting (`SCREEN_ORIENTATION_USER`); "Force landscape" still forces landscape. The player keeps `userLandscape`.
- Default theme is dark purple (`assets/themes/cyanea_themes.json`[0], `values/colors.xml`, applied once per install via `nekomo_theme_v2`). NEX uses a dark purple palette in `ui/Theme.kt`.
- Updater: when the GitHub releases API fails or is rate-limited it falls back to each repo's `apks/latest.json` (raw.githubusercontent.com) and logs the failure.
- Genre filter: a genre-only search sends `q = null` (an empty string made AniList return nothing); the adult genre ("Hentai") is hidden from the genre list unless 18+ is on. The title page re-measures its height after a rotation (it used to stay at the old height and look cut in half).
- Manual Extensions page: Aniyomi and Mihon are ONE section (single add-repository field that detects anime/manga by package name, one repo list, one installed list). Mihon's loader falls back to the sibling `index.pb` when `index.min.json` is an "update your app" stub (keiyoushi). Settings landscape hides the logo header (follow the view size, not `resources.configuration`, which is stale during rotation).

