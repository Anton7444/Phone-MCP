# Decisions

Non-obvious choices, and the reason for each. Anything that differs from `PHONE_MCP_APP_GUIDE.md` is marked **Differs**.

## HTTP + MCP
- **Differs: hand-written HTTP server and JSON-RPC layer instead of Ktor + the MCP Kotlin SDK.**
  The guide itself warns the SDK has had breaking changes between releases, and a stateless "POST one endpoint,
  return JSON" server needs about 250 lines. That removes two fast-moving dependencies and a large amount of
  reflection/serialization code that I could not run on a device in this environment. The wire behaviour follows
  the MCP Streamable HTTP spec (stateless, JSON responses, `202` for notifications, `405` for `GET`/`DELETE`,
  protocol version negotiation across `2025-06-18`, `2025-03-26`, `2024-11-05`).
  Covered by `HttpServerTest` (real sockets) and `McpRpcTest`. Chunked request bodies are decoded because Cloudflare may send them.
- Host-header / DNS-rebinding checks are off by design: requests arrive with the tunnel's `Host`. The secret path is the access control.
- Wrong path -> bare `404 Not Found`. More than 30 bad requests per minute -> `429` for bad paths only; the real endpoint keeps working.

## Storage
- **Differs: `SharedPreferences` (settings) and plain `SQLiteOpenHelper` (audit log) instead of DataStore and Room.**
  Room needs an annotation processor (KSP) and DataStore adds coroutine plumbing for a few dozen values. Same behaviour, less to break.
- **Differs: AES-GCM with an Android Keystore key written directly** instead of `EncryptedSharedPreferences` (deprecated).
  If the Keystore is unusable on a device the value falls back to app-private storage rather than crashing.
- **Differs: no libsu.** Root uses `su -c` through `ProcessBuilder`. One process per call, wrapped in `timeout -k 2 N sh -c ...` when toybox `timeout` exists so child processes die with the call.

## Privilege backends
- Root: `su -c`. Shizuku: a `UserService` (AIDL, `IShellService`) that runs `sh -c`. The Shizuku binder limit is about 1 MB per transaction, so file reads/writes are chunked (256 KB) and use plain `java.io` inside the shell-uid process; `exec` output is capped at 400 KB per stream.
- Every service method returns bytes and never throws (a leading status byte for file calls), because checked exceptions crossing Binder are unreliable.
- Shizuku cannot read other apps' private data. The default deny list covers `/data/data` etc., and tool calls that fail return `IO_ERROR` with the OS message.
- **Differs: Basic mode uses "All files access" (`MANAGE_EXTERNAL_STORAGE`) and `java.io.File`** instead of SAF tree grants. SAF would need a document-tree layer for every tool. This app is sideload-only, so the Play policy on that permission does not apply. Without the permission Basic mode only offers `device_info`.

## Foreground service and wake lock
- `foregroundServiceType="specialUse"` with the subtype property; on API 34+ the type is passed to `startForeground`, below that the plain call is used.
- `PARTIAL_WAKE_LOCK` plus `WIFI_MODE_FULL_HIGH_PERF` (deprecated since API 29 but still what keeps Wi-Fi up with the screen off). Both are acquired and released together; acquire is idempotent; the Wi-Fi lock additionally follows its own setting.
- The wake lock gets a timeout (default = auto-stop, or the "max duration" setting); Termux has none.
- `START_NOT_STICKY`: if the system kills the service it does not come back by itself, because the biometric gate cannot run without the user.
- The battery-optimisation prompt is shown from the UI (a service cannot reliably start an activity), once per app run, after Start.

## Tunnel (cloudflared)
- `cloudflared` linux-arm64 (official release, SHA-256 checked against Cloudflare's published digest) ships as `lib/arm64-v8a/libcloudflared.so`. `extractNativeLibs=true` and `useLegacyPackaging=true` make it a real file in `nativeLibraryDir`, which is the one app location Android still lets us execute from.
- **DNS workaround.** Android has no `/etc/resolv.conf`, so Go's resolver cannot look anything up. For Quick Tunnels the app:
  1. calls `https://api.trycloudflare.com/tunnel` from Java (Android's resolver) to get tunnel credentials,
  2. writes a credentials file and a one-rule config,
  3. runs `cloudflared tunnel --config ... --protocol http2 --edge <ip>:7844 ... run <id>` with edge IPs resolved by Java (hardcoded fallback list).
  If that does not register within 25 s it falls back to plain `cloudflared tunnel --url ...` and parses the `*.trycloudflare.com` URL. `SSL_CERT_DIR=/system/etc/security/cacerts` supplies trusted roots.
- Named tunnels pass the token through the `TUNNEL_TOKEN` environment variable so it does not appear in the process list.
- Watchdog: the supervisor restarts the process with back-off (2 s doubling to 30 s); a default-network change restarts it after a 3 s debounce. A new Quick Tunnel URL raises a "URL changed" notification and a banner.
- **Not verified on a device in this build environment.** The API response shape was confirmed against the live endpoint and the edge IPs against live DNS, but running the binary needs an arm64 Android phone. See "First run checklist" in the README.

## Safety
- The command classifier is a speed bump and the UI never calls it a sandbox. The path policy is checked on both the lexical path and the `readlink -f` result, and an allowed path needs *every* form to be inside an allowed root.
- With on-device approval switched off, dangerous actions are **blocked**, not allowed. That is safer than silently running them.
- Approval requires biometrics or the screen lock when the phone has one. If the phone has no lock screen at all, tapping Allow is enough (otherwise nobody could ever allow anything).
- The audit log stores redacted args (file bodies and typed text reduce to their length) and never the secret.

## UI
- Dark theme only. Fonts are the system sans/mono: bundling DM Sans, Noto Sans TC and JetBrains Mono would add several MB, and the app must not need network at runtime.
- Icons are the design's own SVG path data drawn on a Compose canvas, so there is no icon-library dependency.
- Strings: English default, Traditional Chinese in `values-zh-rTW` (glossary from the guide). Language switching uses `AppCompatDelegate.setApplicationLocales` (which uses `LocaleManager` on Android 13+) and `android:localeConfig`; notifications use a locale-wrapped context so they follow the chosen language on Android 11-12 as well.

## Not included
Quick Settings tile, home widget and settings import/export (guide milestone M4 extras). Everything in M0-M3, plus Named Tunnel, Tailscale, LAN, and the screenshot / logcat / UI / notification / SMS tools, is in.

## Security review follow-up (1.1.1)
Each finding was reproduced first (the classifier bypasses, the `env`/`ip netns` read-only escapes and the critical-list gaps were confirmed with tests on the old code), then fixed; the cases live in `SecurityRegressionTest`.
- **run_shell is default-ask outside Full Access.** A blacklist can never be complete, so a command now runs without a prompt only if it is provably read-only (allow-list) and does not name a protected folder. Everything else asks on the phone. This also closes the "use run_shell instead of the disabled sms/ui/screenshot tools" route. Full Access is the switch for people who do not want the prompts. The classifier now also matches commands after any non-word character (`/system/bin/rm`, quotes, backslash).
- **Protected folders apply to shell commands**: any command that names a deny-list folder (including `/storage/emulated/0/...` aliases of `/sdcard/...`) asks first.
- **Read-only list**: `env` removed; `dumpsys` only for a short list of harmless services; `ip` only for show-style subcommands. Read-only mode now also blocks `ui_tap` and `ui_type`.
- **Approval sheet shows the whole command** (scrollable). Invisible/bidi characters are shown as `⟦U+202E⟧`, long whitespace runs collapse to `⋯`, and a "hidden characters" chip is added. The notification uses the same text.
- **No screen lock = approvals are refused** (fail closed). While an approval is on screen, `ui_tap`, `ui_type` and shell commands that inject input (`input`, `uiautomator`, `sendevent`, `monkey`) are refused, so the AI cannot press Allow itself.
- **Critical list widened**: any rm flag spelling on root/system paths including `/*`, any raw write to `/dev/block`, `mmcblk`, `by-name`, `blkdiscard`, `flash_erase`, `wipefs`, `mke2fs`, fastboot with `-s SERIAL`.
- **Tailscale mode listens only on the Tailscale address** (restarts if that address changes). LAN mode still listens on all interfaces and says the traffic is unencrypted.
- **HTTP**: the secret path is checked before any request body is read; at most 64 simultaneous connections; screenshot / UI-dump temp files get random names.
- Not changed: the signing key is in the source zip on purpose (sideload build); the biometric prompt is not bound to a Keystore `CryptoObject` (defence in depth against a rooted attacker hooking the app).
