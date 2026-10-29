# Release1.0.0 evidence

The [build report](../../docs/BUILD-REPORT.md) explains results and limits. `verification-summary.json` is the current machine-readable result, not a plan.

- `build-clean-source.log`: actual clean archive build, 155 tasks executed, success.
- `jvm/core`, `jvm/app`: 386 core and 131 app passes; two Windows symlink assumptions. `jvm/app-debug` preserves the second app variant without double-counting it.
- `instrumentation-final.log`, `instrumentation-summary.json`, `junit-emulator.xml`: 92 passes and four intentional physical-fixture skips on the exact signed release. The summary derives from complete runner records and hashes the retained log.
- `release-apk-verification.json`, `installed-apk-match.json`, signature/metadata/alignment records: actual version, signer, native layout, entrypoints and installed/built APK equality.
- `magisk-file-tools-final.*`, `ota-file-proof.*`: actual native/tool and root file-boundary integration, limited to temporary regular files. No block device, OTA-install or reboot API is exercised.
- `clean-source-check.json`: tested source commit and complete app/core/Gradle input hashes. Later documentation/evidence commits preserve these inputs.
- `release-runtime.json`, `osv-audit.json`: actual resolved coordinates/hashes and dated public advisory response, with explicit limits.
- `iterations/`: historical failed attempts that led to corrections; never substitute these for the final result.

Workspace/host redaction is intentional and XML-safe. All XML and JSON is reparsed after redaction. Screenshots in [docs/images](../../docs/images/) show the release on a disposable emulator; simulation is visibly labelled. Historical physical observations remain in earlier evidence directories. No phone update, physical partition write or phone reboot was performed for this release.
