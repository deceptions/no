# Nekomo repository

`index.json` is the official Nekomo repository manifest. In the app: Settings -> Extensions -> Extensions manager ->
enter the repository code (or this file's raw URL) -> confirm. Nothing is installed before that.

Format (`nekomoRepository` must be present):

- `repositories[]`: `{ "type": "cloudstream" | "zangetsu", "name", "url", "description" }`. Each is registered and all
  of its (non-18+) plugins/sources are installed. Only script/plugin sources are supported.
- `apis[]`: metadata APIs shown in the manager (information only).

APK extensions (Aniyomi, Mihon) are never listed or installed by repositories: users install those themselves.
The list only contains free/legal sources. Anyone can host their own manifest and add it by URL.
