# Phone MCP

An Android app that turns your phone into an [MCP](https://modelcontextprotocol.io) server. Any MCP client can run shell commands, read and write files, take screenshots and more on your phone, over a private HTTPS tunnel. Dangerous actions stop and wait for your approval on the device.

> Sideload only (not for Google Play). Needs **Android 11+**, **arm64**, and either **root** or **Shizuku**.

## Features
- **Three privilege modes:** Root (Magisk / KernelSU), Shizuku, or Basic (app sandbox only).
- **Tools:** `run_shell`, `list_dir`, `read_file`, `write_file`, `file_info`, `screenshot`, `logcat`, `device_info`, plus optional UI control, notification reading and SMS/contacts (each can be switched off).
- **Connection options:** Cloudflare Quick Tunnel (default, no account), Cloudflare Named Tunnel, Tailscale, or LAN only (TLS with a pinned self-signed certificate).
- **On-device approval:** destructive commands (`rm`, `dd`, `reboot`, ...) and writes outside allowed folders need you to tap **Allow once** (biometrics). Commands that can brick the phone need two confirmations.
- **Read-only mode, path allow/deny lists, full-access mode** (for people who know the risks), and an **activity log** you can export.
- **Auto-stop timer**, biometric gate on start, secret rotation.

## Install
1. Download the latest APK from the [Releases](../../releases) page and install it (allow "install unknown apps" if asked).
2. Open the app, tap **Start setup**, choose **Root** or **Shizuku** and let it run the checks.
   - Root: allow Phone MCP in Magisk / KernelSU.
   - Shizuku: install and start Shizuku (wireless debugging works without a computer), then tap **Authorize**.
3. Choose a connection type and tap **Start and get URL**. Confirm the biometric prompt.
4. On the done screen, tap **Copy MCP config** and add it to your MCP client:

   ```json
   {
     "mcpServers": {
       "phone": { "type": "http", "url": "https://<your-tunnel>/mcp/<secret>" }
     }
   }
   ```
5. Allow the battery-optimization prompt. On OnePlus / OPPO / Xiaomi / Huawei / Vivo / Samsung use **Settings → Vendor auto-start settings** so the system does not kill the server when the screen is off.

### Verify the APK
Official releases are signed with this certificate (SHA-256):

```
57:70:48:40:7B:B5:6C:C1:10:30:F8:26:61:F9:C0:36:D3:66:D6:45:72:FB:8C:CB:DC:52:7C:87:16:AB:26:25
```

Check a download with `apksigner verify --print-certs app-release.apk`. If you installed a build from before the public release, it was signed with an older key: uninstall it first.

## Security model
The connector URL contains a 256-bit secret and is the only credential. **Treat it like a password**: anyone with it can call the tools, within the limits below.

- Dangerous or unrecognised `run_shell` commands, and anything touching protected folders, need on-device approval. Only a strict allow-list of read-only commands runs without asking.
- Protected folders (`/data/data`, `/data/system`, `/data/adb`, `/system`, ...) are blocked by default.
- With Quick and Named tunnels, TLS ends at Cloudflare's edge: Cloudflare can see the connector URL (including the secret) and all traffic. Use LAN or Tailscale mode if that matters to you.
- LAN mode uses TLS with a self-signed certificate. Pin the SHA-256 fingerprint shown after setup.
- UI control tools cannot tap inside Phone MCP itself, and every setting that gives the AI more access (Full Access, turning off read-only, enabling a tool, widening path rules) needs biometrics or the screen lock when the phone has one.
- The approval gate is a speed bump, not a sandbox. Keep Full Access off unless you really need it.

See `docs/decisions.md` for design decisions. To report a vulnerability, see [SECURITY.md](SECURITY.md).

## Build
Needs JDK 17 and the Android SDK (set `sdk.dir` in `local.properties`).

```
./gradlew :app:testDebugUnitTest      # unit tests
./gradlew :app:assembleRelease        # app/build/outputs/apk/release/app-release.apk
```

Without any signing setup, release builds are signed with the Android debug key (fine for trying it out). To sign with your own key, create `keystore.properties` in the project root (it is git-ignored):

```
storeFile=keystore/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

or set `PHONEMCP_STORE_FILE`, `PHONEMCP_STORE_PASSWORD`, `PHONEMCP_KEY_ALIAS` and `PHONEMCP_KEY_PASSWORD`. Never commit a keystore: anyone with it can publish "updates" that install over your users' copies.

`libcloudflared.so` is the unmodified official Cloudflare `cloudflared` linux-arm64 binary (Apache License 2.0). Version, download link and SHA-256 are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md); the build fails if the file does not match that hash.

### Releasing
`.github/workflows/release.yml` builds, signs and publishes a GitHub Release when a `v*` tag is pushed. It needs these repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | the keystore file, base64-encoded (`base64 -w0 release.jks`) |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

Bump `versionCode` and `versionName` in `app/build.gradle.kts` and merge. Then either open **Actions → Release → Run workflow** on `main` (it creates the tag `v<versionName>` itself), or push the tag yourself:

```
git tag v1.2.0 && git push origin v1.2.0
```

The workflow refuses to publish if the tag does not match `versionName`, if the secrets are missing, or if the APK is not signed with the certificate above.

## Project layout
`app/src/main/java/app/phonemcp/`

| Folder | Purpose |
| --- | --- |
| `ui/` | Jetpack Compose screens |
| `service/` | Foreground service, notifications |
| `exec/` | Root, Shizuku and Basic executors |
| `mcp/` | HTTP server, JSON-RPC, tool runner |
| `policy/` | Command classifier, path rules, approvals |
| `tunnel/` | cloudflared runner, LAN TLS |
| `data/` | Settings, secret store, audit log |

## Disclaimer
This app gives remote code execution on your phone by design. Use it only on devices you own, and at your own risk.

## License
See [LICENSE](LICENSE). Bundled third-party software is listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
