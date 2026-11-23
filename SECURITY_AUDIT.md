# Security hardening audit — DuiAI fork (`standalone`)

Scope: source audit of the Android app (`app/src/main`), the Go IDE bridge (`agy_ide_bridge`), build/release
workflows, docs and user docs. This is a static audit plus CI build verification; it is **not** a penetration test.
No claim of “100% secure” is made.

## 1. What was removed

- Original developer telemetry/auto-upload paths. No Firebase, Crashlytics, Sentry or analytics SDK is present.
  `DuiAIApplication` actively deletes legacy TelemetryDeck state; crash handling is local only.
- Automatic update/plugin metadata checks remain, but only as **opt-in** settings. Both periodic workers no-op unless
  the user enabled them. Default plugin repositories and the default mini-app catalog are empty.
- Original developer release/download/plugin links replaced with this fork's URLs in `README.md`, `docs/index.html`,
  `user_docs/`, `mini_app_docs/`, `scripts/` and Go sources. Third-party/upstream-project links are retained but
  explicitly labelled as separate projects, not rewritten to false fork URLs.
- Unnecessary permissions/components removed: `REQUEST_DELETE_PACKAGES`, `QUERY_ALL_PACKAGES` (safe at `targetSdk 28`),
  `SYSTEM_ALERT_WINDOW`, and the unneeded `exported="true"` on `MiniAppsActivity` and `MiniAppActivity`.

## 2. Credentials at rest

| Store | Control |
|---|---|
| Auth/access/refresh/SSH/security tokens, PC pairing tokens | Android Keystore AES-256-GCM (`SecretCipher`, alias `dui_secret_v1`), fail-closed, legacy plaintext rewritten/removed |
| Jarvis TTS key, mini-app signing key | Keystore-backed AES-GCM |
| Saved server profiles (`profiles_json`) | Whole store encrypted with `SecretCipher` on write; legacy plaintext migrated on first read; fail-closed on Keystore failure |
| Backups/device extraction | Disabled in manifest and backup/extraction rules |

Fixes in this pass: bridge security token redacted from launch-command logcat; crash reports redacted before being
written (so copy/share/GitHub issue cannot export a token); bridge error logger redacts `token`, `code`, `state`,
`api_key`, `authorization`, `password`, `passphrase`, `secret`, `headers`, `env` values; Google TTS key moved from a
`?key=` query parameter to the `X-Goog-Api-Key` header; MCP config files written `0600` (existing files chmod'ed);
hub CSRF tokens logged as byte counts only.

**Residual risk (documented, not “fixed”):** upstream CLIs require plaintext credential files — `~/.claude/.credentials.json`,
`~/.claude/settings.json`, `~/.claude/agy_providers*.json`, `~/.dui_bridge/*`, `~/.gemini/*`, Codex `config.toml`,
`gh` token store. These stay plaintext for CLI compatibility and are owner-only where the CLI permits. App-private
mini-app cookies are plaintext SQLite (browser-like); `.agkey` backups are encrypted but offline-brute-forceable if a
weak password is chosen.

## 3. Network destinations (automatic vs user action)

**Automatic (app/bridge initiated, mostly opt-in settings):**
- Update metadata: `https://github.com/tadak5422-spec/Dui-Ai-King/releases/latest/download/version.json`
  (opt-in update check). APK download only from this fork's release assets.
- Plugin update check (opt-in, empty default repos): `raw.githubusercontent.com`, `api.github.com`, GitHub
  archive/codeload URLs — only for repositories the user added.
- Go hub probe/monitor: `http://127.0.0.1:<hubPort>` only (loopback).
- Go tickers (session reap, voice keepalive, Codex ping) have no external destinations by themselves.

**Explicit user action:**
- AI providers: Antigravity/Google sign-in, Claude presets (OpenRouter, Moonshot, DeepSeek, Z.ai), OpenCode/Codex
  providers, Anthropic voice WebSocket, Google/OpenAI TTS, custom provider base URLs.
- GitHub sign-in (`gh` device flow), git clone, plugin/skill installs, Android-dev/Rea script downloads.
- Rootfs, Termux bootstrap, render bundle, X11 APK/`.deb` installs — all user-confirmed; rootfs/render/X11 are
  fork-pinned with SHA-256 and/or signer pinning.
- WebView/browser/CDN: user-entered URLs, DuckDuckGo/Google search, jsDelivr (Mermaid/Eruda), YouTube embeds.

**Local only:** bridge HTTP/WebSocket, Android local MCP/REST server, browser extension host, cloud-preview
forwarder, Rea DevTools user-configured `http://127.0.0.1:<port>`, local AI examples (Ollama, LM Studio, vLLM).

## 4. Cleartext / URL policy — important limitation

`usesCleartextTraffic="true"` was removed. `network_security_config.xml` denies cleartext at OS level for fixed public
endpoints (github.com, google.com/apis, anthropic.com, openai.com, huggingface.co and their subdomains).

**It is not a global OS-level cleartext ban.** Android NSC matches hostnames, not arbitrary private IP ranges, so a
global ban would break PC bridge / Termux / local AI on addresses such as `192.168.x.x` or `10.x.x.x`. For automatic
connections, public cleartext is blocked in code instead: `AuthPreferences.normalizeServerUrl()` (now enforced at
save **and** load), the Go `netutil.CheckEndpoint` used by both provider backends and model-list pagination, the
updater URL allowlists with final-redirect verification, and WebView resource rules. A user can still type a public
`http://` URL into the address bar, as in any browser. This must not be described as “HTTP fully blocked globally”.

## 5. Updater integrity

- Metadata URL allowlist restricted to `version.json` under this fork's GitHub releases (branch copies rejected).
- APK URL allowlist restricted to this fork's release download path; OkHttp final response host verified after redirects.
- Mandatory 64-hex `sha256` in `version.json`; missing/invalid hash rejects the update before any installer is opened.
- Downloaded APK verified byte-for-byte against the hash, then package name, version code and installed signing
  certificate are checked. `404` on metadata means “no update”.
- CI publishes `version.json` with the SHA-256 of the APK built in the same job. The current v2.0.2 release also has
  a manually uploaded `version.json` for its published APK.

## 6. Residual risks / limits of this audit

- CLI-native plaintext credential files (above) cannot be encrypted without breaking upstream tools.
- Shell-piped installers (`curl … | bash`) for Claude Code and AGY CLI execute remote scripts without pinned hashes;
  they are explicit user actions, but supply-chain risk remains.
- `.deb` companion and Termux bootstrap downloads are host/scheme allowlisted; the `.deb` is not hash/signer verified.
- `DuiAIFileProvider` grants whole-filesystem URIs, but grants are only made by a signature-protected receiver.
- Plugin repo parser still accepts legacy `antigem-plugins.json` filenames as compatibility aliases for user-added
  repositories; fetches are rebuilt as HTTPS.
- Runtime penetration testing, device-level network capture and Ruby/native dependency review were out of scope.
- Rootfs/render/X11 release assets are not published in this fork; those features fail until assets are uploaded.
