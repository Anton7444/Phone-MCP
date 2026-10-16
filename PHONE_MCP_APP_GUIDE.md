# Phone MCP — Android App Build Guide (for a coding agent)

You are building **Phone MCP**: an Android app that runs an MCP server on the phone and lets an AI client (Claude custom connector) run privileged commands and fetch files, over a temporary public HTTPS URL. It replaces a working Python/Termux prototype (`phone_mcp.py` + `start.sh`).

Read this whole file before writing code. Versions of libraries are **not** pinned here; look up the latest stable release of each when you add it.

---

## 1. Goals and non-goals

**Goals**
- One-tap setup: pick a permission mode, app verifies it, starts server + tunnel, shows the connector URL.
- Two privileged modes: **Root** and **Shizuku** (no root). Optional **Basic** mode (no privilege).
- Keep running with the screen off (wake lock, Wi-Fi lock, foreground service, battery-optimization guidance).
- Clean, customizable, safe: per-tool toggles, path rules, on-device approval for dangerous actions, audit log.
- UI in **English and Traditional Chinese (zh-TW)**.

**Non-goals**
- No Google Play release (exposing a root shell remotely will not pass policy). Distribute as a sideloaded APK / GitHub release.
- No cloud backend, no accounts, no telemetry, no analytics.
- No Simplified Chinese anywhere. Chinese strings are always Traditional (zh-TW).

---

## 2. Tech stack

| Area | Choice |
|---|---|
| Language / UI | Kotlin, Jetpack Compose, Material 3 (dark theme first) |
| SDK levels | minSdk 30 (Shizuku can start itself over wireless debugging from Android 11), compileSdk/targetSdk = latest stable |
| HTTP + MCP | Ktor server + official `io.modelcontextprotocol:kotlin-sdk-server` (Streamable HTTP, **stateless** variant) |
| Root | libsu (topjohnwu) |
| Non-root privilege | Shizuku API (`dev.rikka.shizuku:api` + `provider`), using **UserService** (not `Shizuku.newProcess`, which is deprecated) |
| Settings | Jetpack DataStore |
| Audit log | Room |
| Secrets | Android Keystore-backed storage (EncryptedSharedPreferences or equivalent) |
| Biometrics | `androidx.biometric` |
| Tunnel | `cloudflared` arm64 binary shipped as a native library (see §7) |
| DI | Keep simple: manual DI or Koin. No heavy framework. |

Notes on the MCP Kotlin SDK: it uses Ktor but does not pull in an engine; add one yourself (CIO or Netty). The Ktor helper `mcpStatelessStreamableHttp(path = ...)` serves a POST endpoint at a configurable path and returns plain JSON for simple request/response pairs. That is what we want: stateless, no SSE, works through Cloudflare quick tunnels. Check the SDK's current API before writing the server; it has had breaking changes between releases.

---

## 3. Architecture

Single module is fine at first; keep package boundaries clean so it can be split later.

```
app/
  ui/            Compose screens, navigation, theme
  service/       McpService (foreground service), notification, wake locks
  exec/          Executor interface + RootExecutor, ShizukuExecutor, BasicExecutor
  mcp/           MCP server wiring, tool definitions, tool registry
  policy/        PathPolicy, CommandClassifier, ApprovalManager
  tunnel/        CloudflaredRunner, TunnelState
  data/          SettingsRepository (DataStore), AuditLog (Room), SecretStore
  i18n/          locale helpers
```

**One process owner:** `McpService` (foreground service) owns the Ktor engine, the tunnel process, and the wake locks. UI talks to it through a bound interface or a shared `StateFlow` holder. The UI never starts the server directly.

### Executor abstraction (the core seam)

```kotlin
interface Executor {
    val mode: PrivilegeMode            // ROOT, SHIZUKU, BASIC
    suspend fun probe(): ProbeResult   // available? granted? uid? error reason
    suspend fun exec(cmd: String, timeoutMs: Long): ExecResult   // exit, stdout, stderr
    suspend fun readFile(path: String, offset: Long, length: Long): ByteArray
    suspend fun writeFile(path: String, data: ByteArray, append: Boolean)
    val capabilities: Set<Capability>  // drives which MCP tools are exposed
}
```

- **RootExecutor**: libsu shell. `probe()` runs `id` and expects `uid=0`.
- **ShizukuExecutor**: bind a Shizuku **UserService** exposing an AIDL interface (e.g. `exec(cmd, timeoutMs)`); the service runs in a shell-privileged process (uid 2000, or 0 if Shizuku runs as root). Handle: Shizuku not installed, installed but not running (`pingBinder()` false), permission not granted, binder death (reconnect), pre-v11 Shizuku (reject). `probe()` runs `id`.
- **BasicExecutor**: no privilege. Only SAF-granted folders, device info. Fewest tools.

**Capabilities differ per mode and the tool list must follow.** Shizuku (shell identity) cannot read other apps' private data under `/data/data`, so tools that imply that must not be offered or must fail clearly. Register MCP tools dynamically from `capabilities` + user toggles.

---

## 4. First-run setup wizard

State machine: `Welcome → ChooseMode → Check(mode) → ChooseTunnel → Done → Home`.

**Welcome**: purpose + explicit risk warning (AI can run commands and read/write files). Button "Start setup".

**ChooseMode**: two selectable cards, Root and Shizuku, plus a small link "Skip privileges, basic features only". Auto-detect on entry: badge "su detected" on Root if `su` exists; badge "Installed" on Shizuku if the package is present. Mark one "Recommended". Each card lists one pro and one limitation (Shizuku: cannot read other apps' private data, must be restarted after reboot).

**Check(Root)**: three steps, all must pass: (1) `su` found, (2) root permission granted (trigger the manager prompt; if denied, explain where to enable it in Magisk/KernelSU), (3) test command `id` shows `uid=0`. Show the actual output.

**Check(Shizuku)**: four steps: (1) Shizuku installed (else link to download), (2) Shizuku running (else show wireless-debugging start instructions, Android 11+ needs no computer), (3) permission granted to this app (call `requestPermission`), (4) test command `id` shows shell uid. Buttons: "Open Shizuku", "Re-check". "Continue" stays disabled until all pass.

**ChooseTunnel**: Quick Tunnel (default), Cloudflare Named Tunnel (token), Tailscale, LAN only. Button "Start and get URL".

**Done**: connector URL (secret masked until revealed), Copy, Share, QR code, one line of instruction: Claude → Settings → Connectors → Add custom connector.

Persist the chosen mode. On every app start, re-run `probe()`; if the saved mode is no longer valid (e.g. Shizuku died after reboot), show a red banner on Home with a one-tap fix, and do **not** start the server with broken privileges.

---

## 5. Foreground service and keep-alive (wake lock)

Reference: Termux's `TermuxService` (GPLv3, https://github.com/termux/termux-app). **Re-implement the behavior in your own code; do not copy its source** unless this project is GPLv3-compatible.

Behavior to reproduce:

1. **Two locks, always acquired and released together:** a CPU `PowerManager.PARTIAL_WAKE_LOCK` (tag like `phonemcp:service-wakelock`) and a Wi-Fi lock (`WifiManager.createWifiLock(WIFI_MODE_FULL_HIGH_PERF, ...)`). Termux uses HIGH_PERF, which is deprecated since API 29 but is what keeps Wi-Fi alive with the screen off; verify on the OnePlus 13T and keep it if it works.
2. **Idempotent acquire:** if the lock is already held, log and return. Release is a no-op when nothing is held. Set references to null after release.
3. **Acquire path also checks battery optimization:** if the app is not on the ignore-battery-optimizations list, prompt via `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` with the package URI.
4. **Notification reflects state and exposes the toggle:** ongoing notification text includes a "wake lock held" marker; priority is higher while held; one action toggles between "Acquire wake lock" and "Release wake lock"; a second action is "Stop". Update the notification on every state change.
5. **Release in `onDestroy()`** without trying to update the notification.
6. **Termux has no wake-lock timeout** (lint suppressed). We should: add a setting "Wake lock max duration", default equal to the Auto-stop setting, and pass it to `acquire(timeout)`.

Additional requirements beyond Termux:

- Declare a **foreground service type** (Android 14+ requires it; Termux's manifest does not declare one, do not copy that). `specialUse` with the subtype property is the likely fit; verify against current Android docs for the target SDK.
- Permissions: `WAKE_LOCK`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS` (13+), `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `RECEIVE_BOOT_COMPLETED` (optional "start on boot"), `INTERNET`, `ACCESS_NETWORK_STATE`, `USE_BIOMETRIC`.
- **Watchdogs:** restart `cloudflared` if it exits; restart the tunnel on network change (`ConnectivityManager` callback). A new quick-tunnel URL means the connector URL changed: update the UI and post a notification saying the user must update the connector in Claude.
- **OEM kill guidance** (ColorOS/OnePlus is aggressive): Settings screen has a "Vendor auto-start settings" row that deep-links best-effort to the OEM auto-start/battery screen, with a manual-instructions fallback.
- Home screen shows a health row "Wake lock: held / not held".

---

## 6. MCP server

- Transport: Streamable HTTP, **stateless**, JSON responses, bound to `127.0.0.1:<port>` (default 8000, configurable).
- **Auth = secret path.** Endpoint is `/mcp/<secret>`; `<secret>` is 32 random bytes, URL-safe base64, generated once, stored in Keystore-backed storage. Any other path returns a plain 404 with no hints. "Rotate secret" regenerates it and restarts server + tunnel (old URL dies immediately).
- DNS-rebinding/host checks must be **off**: requests arrive with the tunnel's `Host` header. The secret path is the access control.
- Never log the secret or the full URL. UI masks it until the user taps reveal.

### Tools

All tools honor: per-tool toggle, read-only mode, path policy, size caps, approval policy, audit logging.

| Tool | Args | Returns | Notes |
|---|---|---|---|
| `run_shell` | `cmd`, `timeout_s` (default 30, max 300) | exit code, stdout, stderr, truncated flag | Goes through CommandClassifier + approval. Output cap ~20k chars. |
| `list_dir` | `path` (default `/sdcard`) | listing | PathPolicy |
| `file_info` | `path` | size, perms, type | PathPolicy |
| `read_file` | `path`, `offset`, `length`, `base64` | text or base64 | Cap ~2 MB per call; page with offset/length. |
| `write_file` | `path`, `content`, `append` | bytes written | Disabled in read-only mode. |
| `screenshot` | none | base64 PNG | Root/Shizuku (`screencap -p`). Warn about size. |
| `logcat` | `filter`, `lines` | text | |
| `device_info` | none | model, Android version, battery, network | Available in all modes. |

Optional, **off by default**, each behind its own toggle and sensitive-marked in the UI: `ui_dump`, `ui_tap`, `ui_type` (screen control), `notifications_read`, `sms_contacts`.

Errors must be tool results with a clear message (`BLOCKED`, `DENIED_BY_USER`, `APPROVAL_TIMEOUT`, `PATH_NOT_ALLOWED`, `MODE_NOT_SUPPORTED`), never HTTP 500s.

---

## 7. Tunnel

- Ship `cloudflared` (arm64) as `app/src/main/jniLibs/arm64-v8a/libcloudflared.so`. Android blocks executing binaries from writable app directories, so run it from `applicationInfo.nativeLibraryDir`. Ensure native libs are extracted to disk (legacy packaging / `extractNativeLibs`), otherwise the file is not directly executable.
- Quick tunnel: run `libcloudflared.so tunnel --url http://127.0.0.1:<port> --no-autoupdate` with `ProcessBuilder`, read its output, and extract the `https://*.trycloudflare.com` URL with a regex. Time out after ~40 s with a readable error.
- **Early spike required:** a Go binary on Android may hit DNS problems (no resolv.conf). Another project that bundles cloudflared this way reports a workaround of hardcoded Cloudflare edge IPs. Prove that the tunnel comes up on the target device **before** building the rest of the UI around it. Keep the Tunnel layer behind an interface so Named Tunnel (token) and Tailscale can be added without touching the rest.
- Quick tunnels have **no SSE support**, which is why the server is stateless JSON.
- URL changes every start: always show the current URL and a "URL changed" notice.

---

## 8. Security requirements (non-negotiable)

This app hands a root/shell to a remote AI. Treat prompt injection as the main real-world threat: any file or web content the AI reads may contain instructions.

1. **Approval on the device is the real boundary.** For classified-dangerous commands and writes outside allowed paths: suspend the tool call, show a high-priority notification and an in-app sheet with the exact command, risk chips, a countdown (default 60 s), buttons **Deny** (primary) and **Allow once**. Approving requires biometric auth. Timeout = deny. Return `DENIED_BY_USER` / `APPROVAL_TIMEOUT` to the AI.
2. **CommandClassifier is a speed bump, not security.** Regexes for `rm -rf` on broad paths, `dd of=/dev/`, `mkfs`, `wipe`, `fastboot`, reboot to bootloader/recovery, writes to `/dev/block`, `setenforce 0`, uninstalling system/vendor packages, etc. Never describe it as a sandbox in the UI.
3. **PathPolicy:** user-editable allow and deny lists; defaults allow `/sdcard`, deny `/data/data` and system paths. Canonicalize paths (resolve `..`, symlinks) before checking.
4. **Read-only mode** blocks `write_file` and any `run_shell` the classifier marks as mutating.
5. **Biometric gate** to start the server (setting, on by default).
6. **Auto-stop timer** (default 2 h) stops server, tunnel and releases locks.
7. **Audit log** (Room): timestamp, tool, args (redacted), decision, result status. Viewer screen, export, auto-purge after N days. No secrets in logs.
8. Rate-limit repeated bad-path requests.
9. Per-tool toggles; sensitive tools off by default.
10. No network calls except the tunnel itself.

---

## 9. Screens and design

Nine core screens (a clickable preview exists as a Design canvas; see the app's design tokens below). Dark theme, one accent, clean cards.

1. Welcome 2. Choose mode 3a. Root check 3b. Shizuku check 4. Choose tunnel 5. Done (URL/QR) 6. Home 7. Settings 8. On-device approval sheet 9. Foreground notification (with wake-lock toggle and Stop).

**Home:** status card (Running + Stop), URL row with copy, health list (Permission, MCP server, Tunnel, Wake lock, Battery optimization with a "Fix" action), recent activity (last 3 audit entries, blocked ones marked), bottom nav (Home, Activity, Settings).

**Settings sections:** Connection (tunnel type, port, auto-stop) · Security (biometric on start, on-device approval, read-only, rotate secret) · Keep running (wake lock, Wi-Fi lock, start on boot, battery whitelist, vendor auto-start, max wake-lock duration) · Tools (per-tool toggles, sensitive ones flagged) · Path rules · Language · Permission mode (switch).

**Design tokens (dark):** background `#0D1117`, surface `#161B22`, surface-2 `#1C222B`, border `#2A313C`, divider `#222934`, text `#E6EAF0`, muted `#8A94A3`, accent `#5EEAD4` (text on accent `#06231F`), warning `#FBBF24`, danger `#F87171`. Radius: cards 20, buttons 18, chips pill. Touch targets ≥ 48 dp. Fonts: DM Sans + Noto Sans TC (UI), JetBrains Mono (commands, URLs). Use real buttons/switches with content descriptions; never rely on color alone for status.

---

## 10. Localization (English + Traditional Chinese)

- Default strings in `res/values/strings.xml` (English); Traditional Chinese in `res/values-zh-rTW/strings.xml`. **No hard-coded UI text.**
- Language setting: *Follow system* (default) / 繁體中文 / English. Implement with the platform per-app language APIs (`LocaleManager` on Android 13+, `AppCompatDelegate.setApplicationLocales` for older) and declare `android:localeConfig`.
- Commands, tool names, paths and URLs are never translated.
- Glossary (use these exact zh-TW terms):

| English | 繁體中文 |
|---|---|
| Choose permission mode | 選擇權限模式 |
| Start setup | 開始設置 |
| Continue | 繼續 |
| Running / Stop | 運行中 / 停止 |
| Connector URL | Connector 網址 |
| Tunnel | 隧道 |
| Wake lock | 喚醒鎖 |
| Wi-Fi lock | Wi-Fi 鎖 |
| Battery optimization | 電池優化 |
| Allow once / Deny | 允許一次 / 拒絕 |
| Auto-stop | 自動停止 |
| Read-only mode | 唯讀模式 |
| Rotate secret | 更換連線密鑰 |
| Recent activity | 最近活動 |
| Keep running | 保持運行 |
| Path rules | 路徑限制 |

---

## 11. Milestones and acceptance

**M0 — Spikes (do first, throw-away OK)**
- Ktor + MCP SDK stateless endpoint answers `tools/list` and one tool call locally.
- Bundled `cloudflared` produces a working trycloudflare URL on the device, and Claude can connect to it as a custom connector.
- Shizuku UserService runs `id` and returns the shell uid.

**M1 — MVP**
Wizard (Root + Shizuku), `McpService` with both locks and notification toggle, tools `run_shell/list_dir/read_file/write_file/file_info/device_info`, secret path, Quick Tunnel, Home screen.
*Accept:* from a cold install on the OnePlus 13T, a user reaches a working connector URL in under 2 minutes; server survives 30 min with the screen off.

**M2 — Safety**
CommandClassifier, PathPolicy, on-device approval with biometric, audit log + viewer, read-only mode, auto-stop, rotate secret.
*Accept:* a destructive command sent from Claude pauses until approved on the phone; denial and timeout return the right error; nothing sensitive appears in logs.

**M3 — Polish and i18n**
Settings screen, Basic mode, health checks and one-tap fixes, English + zh-TW, language switch, OEM auto-start guidance, URL-changed notification, watchdog restarts.

**M4 — Extras**
Named Tunnel, Tailscale, Quick Settings tile, home widget, screenshot/logcat/UI tools, settings profiles import/export.

**Testing**
- Unit: CommandClassifier, PathPolicy (including `..` and symlink cases), secret generation, locale switching, tool-list-by-capability.
- Instrumented/manual: probe for each mode and each failure state; wake-lock acquire/release idempotence; service kill and restart; Shizuku binder death; tunnel exit and network change; approval timeout; Android 14+ foreground-service-type crash check.
- Security review before release: grep for secret leakage in logs, confirm 404s reveal nothing, confirm no mode starts the server with failed probe.

---

## 12. Working agreements for the agent

- Build milestone by milestone; do not start M2 features inside M1.
- When an API, version, or Android behavior is uncertain, look it up in current docs rather than guessing; note the source in a code comment or `docs/decisions.md`.
- Keep a `docs/decisions.md` of non-obvious choices (e.g. wake-lock type, foreground service type, tunnel DNS workaround).
- Ask the user before: adding any network dependency other than the tunnel, adding analytics of any kind, or widening default permissions.
- Prefer small, reviewable commits. No dead code or unused toggles.
