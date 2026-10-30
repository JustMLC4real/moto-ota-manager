# Build and test report — 1.0.0

**29 October 2026 · `nl.moto.ota` · versionCode4 · first public release**

Both APKs were built from a new extraction of committed source. **609 unique Kotlin/Android tests passed, 6 were deliberately skipped, and none failed.** Seven Python evidence-tool tests and four additional regular-file integration cases passed. The exact signed release APK was installed and tested on an explicitly verified disposable API35 emulator. No physical phone was used, flashed, updated or restarted during this release verification.

## Delivered release

| Field | Measured result |
|---|---|
| APK | `moto-ota-manager-1.0.0.apk` |
| Version | 1.0.0 / code4 |
| Bytes | 21,923,224 |
| APK SHA256 | `e64664cf1ee4a1d218787e64474151298809f5e9961e08505a8f9c341e4a03e8` |
| Certificate SHA256 | `b8982eced488beec99ad33e0a87bf47f99e6d8b1a39f7d559ba86353bcb29bc6` |
| Signing | Maintained private release key; APK Signature Scheme v2 verified |
| Debuggable / shrinking | false / disabled; complete runtime retained |
| Minimum / target / compile SDK | 33 / 35 / 35 |
| Native ABIs | arm64-v8a, x86_64 |

[APK verification](../evidence/release-1.0.0/release-apk-verification.json) · [Signature](../evidence/release-1.0.0/apk-signature.txt) · [Metadata](../evidence/release-1.0.0/apk-badging.txt) · [Installed APK hash match](../evidence/release-1.0.0/installed-apk-match.json).

The debug APK uses the same build inputs and remains debug-signed. Exact APK/source-ZIP checksums and the final repository commit are in the adjacent delivery `ARTIFACTS.json` and `SHA256SUMS.txt`. The new release signer cannot directly update an older debug-signed installation. Preserve recovery data before deliberate migration; no automatic uninstall occurs. [Signing identity](RELEASE-CERTIFICATE.md).

## Executed verification

| Check | Actual result | Evidence |
|---|---|---|
| Core JVM | 388 discovered: 386 passed, 2 Windows symlink skips | [30 JUnit suites](../evidence/release-1.0.0/jvm/core/) |
| App JVM | 131 passed; debug/release both ran, counted once | [13 release JUnit suites](../evidence/release-1.0.0/jvm/app/), build log below |
| Signed release Android runtime | 96 discovered: 92 passed, 4 physical-fixture assumptions skipped, zero failures/errors | [Raw runner](../evidence/release-1.0.0/instrumentation-final.log), [derived XML](../evidence/release-1.0.0/junit-emulator.xml), [summary](../evidence/release-1.0.0/instrumentation-summary.json) |
| Evidence parser | 7 Python tests passed; truncated/crashed/contradictory runners rejected | [Execution](../evidence/release-1.0.0/python-tool-tests.log), [tests](../tools/tests/test_instrumentation_to_junit.py) |
| Official Magisk31.1 file tools | 2 actual native cases passed: synthetic 8MiB init_boot v4 and 32MiB boot v3; embedded stock SHA1, original full hash, KEEP flags and fstab verified | [Results](../evidence/release-1.0.0/magisk-file-tools-final.json), [tool output](../evidence/release-1.0.0/magisk-file-tools-final.log), [reproduction](../tools/test_magisk_file_patch.py) |
| Normal Android OTA file boundary | 2 root-emulator file cases passed: exact bytes/hash, owner/mode/normal SELinux label, duplicate/wrong-hash rejection, unchanged source and cleanup | [Results](../evidence/release-1.0.0/ota-file-proof.json), [execution](../evidence/release-1.0.0/ota-file-proof.log), [reproduction](../tools/test_ota_payload_file.py) |
| Actual APK contract | Root entrypoints, JNI/AIDL/Worker interfaces, manifest, expected certificate and both native ABI layouts passed | [Verifier result](../evidence/release-1.0.0/release-apk-verification.json) |
| ZIP alignment | `zipalign -c -P 16 4` passed | [Output](../evidence/release-1.0.0/apk-zipalign.txt) |
| Lint | Debug/release each: zero errors, 60 retained warnings | [Debug XML](../evidence/release-1.0.0/lint-results-debug.xml), [release XML](../evidence/release-1.0.0/lint-results-release.xml) |
| Dependencies | 104 exact release modules queried against OSV; no matching advisories returned | [Inventory/hashes](../evidence/release-1.0.0/release-runtime.json), [dated query](../evidence/release-1.0.0/osv-audit.json) |
| Presentation | EN/NL, native language switching, light/dark, tour, tab restoration, disclosure cancellation and complete file simulation passed | Runtime suite above; [actual screenshots](images/) |

The two host skips concern Windows symlink privileges; corresponding Android file/symlink boundaries ran without those host skips. The four Android skips are explicitly opt-in `ReadOnlyDeviceValidationTest` cases. A non-Motorola emulator cannot supply their actual stock/OTA/device fixture. Skips are not passes. Repeated runs and variant duplicates do not increase the unique count.

Two optional host cases read an explicitly supplied existing historical OTA and passed. This is package-byte evidence, not a new device observation; firmware bytes are excluded from delivery. Ordinary CI without that private fixture skips those cases. Warnings retain version-dependent private APIs, restricted system paths and dependency recommendations. No lint error was suppressed to obtain green. OSV coverage is incomplete; no returned match is not a vulnerability guarantee.

## Clean source build

Tested source commit: `198f66c41f49b2b6273d27c8e77485fdb768a42e`. Source was extracted with `git archive` into a new directory without `.git`, local SDK configuration or generated module/cache folders. Existing SDK tools and downloaded dependencies were reused. With `--no-build-cache`: **BUILD SUCCESSFUL in 4m 12s; 155 of 155 tasks executed**.

Before the first public GitHub push, historical local user-directory paths were anonymized throughout the unpublished history. The [publication provenance](PUBLICATION-PROVENANCE.md) maps original executed commit IDs to their public equivalents. Every one of the 203 app/core/Gradle build inputs and both APKs remains unchanged; old build records retain the IDs actually executed.

```sh
./gradlew :core:test :app:testDebugUnitTest :app:testReleaseUnitTest \
  :app:assembleDebug :app:assembleSignedRelease :app:assembleReleaseAndroidTest \
  :app:lintDebug :app:lintRelease :app:writeReleaseDependencyInventory \
  -Pmoto.testBuildType=release -Pkotlin.compiler.execution.strategy=in-process \
  --no-daemon --no-build-cache --console=plain
```

Windows used `gradlew.bat`. Private signing variables were supplied outside the checkout and never logged. Toolchain: JDK17.0.20.1, Gradle8.11.1, AGP8.9.2, Kotlin/Compose plugin2.1.20, SDK35, NDK27.2.12479018, CMake3.22.1. [Full build log](../evidence/release-1.0.0/build-clean-source.log).

The canonical build-input digest is `dd4896c700aae12517b16a6f8a0ce69bb78ade095dc39f37d5f377aaca2eba02`. The [input manifest](../evidence/release-1.0.0/clean-source-check.json) lists every app/core/Gradle input with actual extracted bytes and SHA256; `.bat` line endings follow `.gitattributes`. Later changes add documentation, CI refinements and evidence. Final archive verification compares every listed input with the tested bytes. Byte-identical builds across arbitrary machines are not claimed.

Only explicit `adb -s emulator-5556` commands were used after `ro.kernel.qemu=1` verification. The [emulator](../evidence/release-1.0.0/emulator-environment.json) is API35/x86_64/ranchu with 4096-byte kernel pages. ELF LOAD segments have 16384-byte alignment and ZIP alignment passes; physical 16KiB-kernel runtime remains untested.

Workspace paths use XML-safe `[WORKSPACE]` redaction and JUnit hostnames are removed. All retained XML/JSON is reparsed. The instrumentation converter requires complete consistent terminal records and the runner footer; ADB exit status alone cannot establish success. [Machine-readable complete result](../evidence/release-1.0.0/verification-summary.json).

## Iteration findings

[Earlier logs](../evidence/release-1.0.0/iterations/) are separate from the final green run. Optimized release instrumentation exposed missing shared Kotlin/Compose/WorkManager APIs; version1.0 ships the complete runtime, with optional R8 requiring separate qualification. A missing authorization-store directory now returns a typed fail-closed error. A localization assertion now compares the exact unknown-code fallback instead of rejecting legitimate prose. The last lint correction makes merge-progress number formatting recompose after an app-language change.

## Working, simulated and physically unqualified boundaries

| Boundary | Established result and limit |
|---|---|
| Real app behavior | Compile/install, Compose/locales/tour, actual private files/AtomicFile/Keystore, import/transfer policies, scheduling/notification boundaries, persistence and dynamic entrypoints have recorded test coverage. |
| Real ordinary-file integration | Genuine official Magisk tools patched synthetic Android images. Normal OTA-directory publication was checked with emulator root. Neither proof invokes partition, OTA-install or power APIs. |
| Simulated device transition | The one-start demonstration uses small regular files and mocks root/slots/OTA/reboot/merge. The live journal remains unchanged and the UI labels simulation. |
| Production code implemented | CDS HTTPS selection, complete OEM validation, active stock write/readback, UpdateEngine dispatch, inactive-slot Magisk write/readback and scoped reboot have real restricted adapters. Runtime gates stop unsupported or missing evidence. Code implementation is not physical qualification. |
| Needs actual hardware qualification | Live CDS acceptance, OEM root/SELinux/Binder permissions, physical writes, complete OTA, target boot, retained root/modules/data and the next update's stock association. None was exercised for this release. |
| Historical hardware evidence | Earlier XT2607-1 read-only observations remain in [DEVICE-VALIDATION](DEVICE-VALIDATION.md) and the [0.2 report](BUILD-REPORT-0.2.0.md). They are not current1.0 write/OTA proof. The [0.3 report](BUILD-REPORT-0.3.0.md) is historical too. |

Other Motorola models are evaluated from measured layout/identity/evidence; they are not all hardware-certified. Unknown layouts, unsupported/hidden/repacked Magisk, absent original bytes and unsafe snapshot/engine states retain safe fallbacks. avbroot custom-key/OTA signing is researched, not inserted into the original OEM trust chain. Wallet/Play Integrity, arbitrary module compatibility and guaranteed data preservation are not established by an emulator run.

## Nederlands

De volledige release **1.0.0** is werkelijk vanuit schone broncode gebouwd, getekend en op de wegwerpemulator getest. **609 unieke Kotlin/Android-tests slagen, 6 zijn bewust overgeslagen; 7 Python-tests en 4 aanvullende bestandsintegratiegevallen slagen ook.** EN/NL, tour, thema's en de volledige bestandssimulatie zijn gecontroleerd. De geleverde APK is exact de geteste APK. Je telefoon is voor deze releasecontrole niet gebruikt. Echte partitieschrijfacties, een volledige Motorola-OTA en rootbehoud na de echte herstart vragen nog fysieke kwalificatie en worden hier niet als bewezen voorgesteld.

[Requirements audit](REQUIREMENTS-AUDIT.md) · [Test matrix](TEST-MATRIX.md) · [Compatibility](COMPATIBILITY.md) · [Direct OTA boundaries](DIRECT-OTA.md) · [Release procedure](RELEASING.md).
