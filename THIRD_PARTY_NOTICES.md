# Third-party notices

## cloudflared

`app/src/main/jniLibs/arm64-v8a/libcloudflared.so` is the unmodified official Cloudflare `cloudflared` binary, renamed so Android will extract and execute it.

| | |
| --- | --- |
| Version | 2026.10.0 |
| Source | https://github.com/cloudflare/cloudflared |
| Download | https://github.com/cloudflare/cloudflared/releases/download/2026.10.0/cloudflared-linux-arm64 |
| SHA-256 | `e6422b9d4f72d3194bc5a38676f13667c06666523217b842a877d72a80b5ac08` |
| License | Apache License 2.0 |

cloudflared is developed by Cloudflare. The full license text ships inside the APK at `assets/licenses/cloudflared-LICENSE.txt` (source: `app/src/main/assets/licenses/cloudflared-LICENSE.txt`).

The build checks the binary against the SHA-256 above (`verifyCloudflared` task) and fails if it differs.

To update: download the new `cloudflared-linux-arm64` release, replace `libcloudflared.so`, and update the version and hash here and `CLOUDFLARED_SHA256` in `app/build.gradle.kts`.

## Libraries

The app also uses AndroidX, Jetpack Compose, Kotlin coroutines and ZXing (Apache License 2.0) and the Shizuku API (MIT). Their licenses come with their Maven artifacts.
