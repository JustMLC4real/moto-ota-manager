# Release guide — 1.0.0

[Nederlands](nl/RELEASING.md)

The first public release is `1.0.0`, Android version code 4, package `nl.moto.ota`. The delivered release is non-debuggable and retains the complete runtime, with code and resource shrinking off by default. R8 is an opt-in research configuration requiring full qualification. A completed local build is separate from publishing a GitHub Release.

The expected public app signing identity is recorded in [RELEASE-CERTIFICATE](RELEASE-CERTIFICATE.md). It is not a firmware or AVB key.

1. Review the commit, English/Dutch user guides, screenshots and capability limits. Run the public-repository scanner, core/app tests, lint and disposable-emulator tests. Record actual results in [BUILD-REPORT](BUILD-REPORT.md); retain historical hardware evidence as historical.
2. Build once from a clean source archive. Compare compiled inputs, verify actual APK version/ABI/dynamic interfaces and preserve build logs. Generate checksums for the exact deliverables.
3. Keep a private release keystore outside the checkout. Set all four signing environment variables below, then build and independently verify the signed APK. The build never falls back to the debug key.
4. Create a reviewed version tag only after checks pass. The manually dispatched [signing workflow](../.github/workflows/signed-release.yml) accepts an existing tag reachable from the default branch and uploads artifacts; it does not publish a release or tag automatically.
5. Publish the verified signed APK, SHA256 checksums, source archive and concise release notes. Distinguish production implementation, synthetic/emulator evidence and hardware qualification per feature. Keep the key for future updates.

| Local environment variable | Value |
|---|---|
| `MOTO_RELEASE_STORE_FILE` | Absolute keystore path outside the checkout |
| `MOTO_RELEASE_STORE_PASSWORD` | Keystore password |
| `MOTO_RELEASE_KEY_ALIAS` | Release-key alias |
| `MOTO_RELEASE_KEY_PASSWORD` | Key password |

```sh
./gradlew :app:assembleSignedRelease :app:lintRelease :app:writeReleaseDependencyInventory --no-daemon --no-configuration-cache
```

Use `gradlew.bat` on Windows. Output: `app/build/outputs/apk/release/app-release.apk`. Set the independently known public certificate fingerprint in `MOTO_RELEASE_CERT_SHA256`, then use `tools/verify_release_apk.py` as described in [RELEASE-ENGINEERING](RELEASE-ENGINEERING.md).

For GitHub, configure a protected `release-signing` environment. Its secrets are `MOTO_RELEASE_KEYSTORE_BASE64`, `MOTO_RELEASE_STORE_PASSWORD`, `MOTO_RELEASE_KEY_ALIAS` and `MOTO_RELEASE_KEY_PASSWORD`; its public variable is `MOTO_RELEASE_CERT_SHA256`. Keep all private signing material out of source, logs, issues, artifacts and build caches. Ordinary pull-request CI has no signing secrets.

Without signing credentials, plain `:app:assembleRelease` produces unsigned CI output. It is not an installable signed release. A different signing identity cannot update an existing installation with the same package ID; export recovery data before any deliberate migration and preserve the existing app until that migration is planned. Android documents [signing identity and update requirements](https://developer.android.com/studio/publish/app-signing).

Publishing does not convert a build result into physical-device qualification. A release note must not present a simulated OTA/Magisk/reboot or a historical read-only observation as a successful current live update.
