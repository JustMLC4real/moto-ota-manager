# Build and test report — 1.0.1

**30 October 2026 · `nl.moto.ota` · versionCode 5**

This update fixes the local GUID/product validation reported on a Motorola and simplifies the interface. **626 unique Kotlin/Android tests passed, 6 were deliberately skipped, zero failures/errors.** Eight Python evidence-tool tests passed. The exact signed release APK was installed and tested on the verified disposable API35 emulator. No physical phone was connected or used for this verification.

## Reported failure and correction

Version 1.0.0 incorrectly required the measured Motorola build GUID to contain40 hex characters. Actual Motorola devices also use15 hex characters, so valid input could be reported as missing before HTTP. The reader also depended on the legacy vendor display property without the documented product-name fallback.

Version1.0.1 accepts the measured15 or 40 hex value unchanged and uses the measured `ro.product.name` when `ro.vendor.product.display` is empty. New tests reproduce that input combination, exercise the reader-to-controller HTTP path with injected transport and reject missing/conflicting/PIF evidence. They cannot mint genuine live-selection proof. [Exact field policy and primary sources](CDS-PROVIDER.md).

The main screens now emphasize status and primary action. Secondary tools use accessible disclosures; language/theme choices wrap compactly. Finite tab/result/stage/color/progress animations follow Android's animator duration scale. [Actual firmware screen](images/firmware-nl-dark.png) · [Compact settings](images/settings-nl-dark.png) · [English overview](images/overview-en.png).

## Delivered APK

| Field | Measured result |
|---|---|
| Release APK | `moto-ota-manager-1.0.1.apk` |
| Version | 1.0.1 / code5 |
| Bytes | 21,963,928 |
| SHA256 | `d7e7ea20974982cea9085d8d1301d4aed2e370049b4f1c982d8d4dc0adbafa1d` |
| Certificate SHA256 | `b8982eced488beec99ad33e0a87bf47f99e6d8b1a39f7d559ba86353bcb29bc6` |
| Debuggable / shrinking | false / disabled |
| Minimum / target / compile SDK | 33 / 35 / 35 |
| Native ABIs | arm64-v8a and x86_64 |

[APK/signing/layout verification](../evidence/release-1.0.1/release-apk-verification.json) · [Signature](../evidence/release-1.0.1/apk-signature.txt) · [Installed hash equality](../evidence/release-1.0.1/installed-apk-match.json) · [ZIP alignment](../evidence/release-1.0.1/apk-zipalign.txt).

The release uses the same maintained key as 1.0.0. Update the same signing variant to preserve app data; release/debug remain different signing identities. Final source/preview/debug checksums are in the delivery `ARTIFACTS.json` and `SHA256SUMS.txt`.

## Actual checks

| Check | Result | Evidence |
|---|---|---|
| Core JVM | 391 discovered: 389 passed, 2 Windows symlink skips | [30 XML suites](../evidence/release-1.0.1/jvm/core/) |
| Android app JVM | 140 passed; both debug/release variants ran and are counted once | [Release XML](../evidence/release-1.0.1/jvm/app/), [debug XML](../evidence/release-1.0.1/jvm/app-debug/) |
| Signed release instrumentation | 101 discovered: 97 passed, 4 physical-fixture assumptions skipped,0 failures/errors | [Raw runner](../evidence/release-1.0.1/instrumentation-final.log), [XML](../evidence/release-1.0.1/junit-emulator.xml), [summary](../evidence/release-1.0.1/instrumentation-summary.json) |
| Reported CDS regression | 9 reader cases, additional protocol cases and Android resolver→injectedHTTP coverage | `CdsIdentityReaderTest`, `CdsProtocolTest`, `CdsControllerRuntimeTest` in the records above |
| Clean UI and motion | Navigation, saved disclosures, language/theme persistence, disabled simulation tools, unchanged consent/journal and reduced-motion operation/restoration passed | `PresentationUiTest`, `SetupTourUiTest`, `SafeUiTest`; [recording facts](../evidence/release-1.0.1/preview-recording.json) |
| Runner evidence parser | 8 passed, including Dutch decimal-comma duration; crashes/truncation/false green remain rejected | [Execution](../evidence/release-1.0.1/python-tool-tests.log) |
| Lint | Debug/release each:0 errors, 62 retained warnings | [Debug](../evidence/release-1.0.1/lint-results-debug.xml), [release](../evidence/release-1.0.1/lint-results-release.xml) |
| Dependencies | Exact resolved release inventory queried against OSV; no matching advisories returned | [Inventory](../evidence/release-1.0.1/release-runtime.json), [dated result](../evidence/release-1.0.1/osv-audit.json) |

The two Windows skips concern symlink privileges; the four Android skips are explicitly opt-in physical-device fixtures. No skip is counted as a pass. Repeated runs and variant duplicates do not increase the unique total. The optional historical-package host checks read the existing private fixture and passed; ordinary CI without it skips them. The firmware itself is excluded.

Warnings remain visible: the two additions are Compose modifier-parameter style and a plural-resource suggestion. No lint error or safety check is suppressed to obtain green. OSV coverage is incomplete; no returned match is not a vulnerability guarantee.

## Source and build trace

The initial clean archive was source commit `8fb38d5`; its directory had no generated module folders, Git metadata or local SDK configuration. JDK17/Gradle8.11.1/AGP8.9.2/Kotlin2.1.20/SDK35/NDK27.2/CMake3.22.1 produced **BUILD SUCCESSFUL in 6m 33s; 155 of 155 tasks executed**, with `--no-build-cache`. SDK/dependency downloads were reused. [Initial clean log](../evidence/release-1.0.1/build-clean-source.log).

A newly added reduced-motion test passed its UI checks but failed its cleanup assertion: `UiAutomation.executeShellCommand` preserved quote characters in the setting value. The test now restores the exact previous value through the Android Settings API under the emulator-only test identity. **Only the test file changed; production APK SHA256 remained identical.** The corrected source copy rebuilt successfully in 50s, 17 tasks executed/138 up-to-date. The complete 101-case runtime suite then passed. [Final rebuild](../evidence/release-1.0.1/final-rebuild.log), [earlier failed cleanup](../evidence/release-1.0.1/iterations/instrumentation-before-test-helper-fix.log).

Final tested source commit: `28dce4008c97e7d3f32c0a1ba3545a83176957bb`. The [204-input manifest](../evidence/release-1.0.1/clean-source-check.json) records actual extracted/applicable bytes and SHA256, including the test correction. Its canonical digest is `ea020dad87cceb05fe7c96be993ac6c236672a92ee760ac2ea94a38564169807`. Final documentation/evidence commits preserve those inputs; the final ZIP is compared against every entry. Arbitrary-machine byte reproducibility is not claimed.

Windows used `gradlew.bat`; signing variables stayed private outside the checkout. Only explicit `adb -s emulator-5556` calls ran after the emulator guard. The [API35/x86_64/ranchu emulator](../evidence/release-1.0.1/emulator-environment.json) has 4KiB kernel pages; native 16KiB alignment is a layout check, not physical 16KiB runtime proof. XML-safe path/hostname redaction is applied and every retained XML/JSON report is reparsed. [Complete summary](../evidence/release-1.0.1/verification-summary.json).

## Scope of the evidence

The screenshot's local validation failure is reproduced and corrected. Controller tests verify that supported measured input reaches injected HTTP. **No live Motorola request with this phone's identifiers was executed**, so actual device/server acceptance is not claimed. Unknown schemas, absent data and explicit identity conflicts retain visible errors and the official-updater fallback.

This release does not qualify physical partition writes, a complete OTA, real reboot, retained root/modules/data, Wallet or attestation. The ordinary-file Magisk/OTA-boundary proofs in [1.0.0 evidence](../evidence/release-1.0.0/) remain historical; they were not rerun or reclassified as physical 1.0.1 proof. [Previous 1.0.0 report](BUILD-REPORT-1.0.0.md) · [Compatibility](COMPATIBILITY.md).

## Nederlands

De fout waarbij een geldige 15-teken-GUID of ontbrekende vendor-displaynaam de controle blokkeerde is gereproduceerd en hersteld. De schermen zijn compacter en bevatten korte animaties. **626 unieke Kotlin/Android-tests slagen, 6 zijn bewust overgeslagen; 8 Python-tests slagen ook.** De geteste release-APK is exact de geleverde APK. Je telefoon is niet gebruikt. Live acceptatie door Motorola op jouw toestel en een echte volledige OTA/rootbehoudketen zijn hiermee niet bewezen.
