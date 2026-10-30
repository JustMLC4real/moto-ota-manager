# Release 1.0.1 evidence

[Build report](../../docs/BUILD-REPORT.md) · [Machine summary](verification-summary.json)

- 389 core passes, 140 app JVM passes, 97 signed-release Android passes; six explicit host/physical assumptions skipped. Variant duplicates and reruns are counted once.
- `build-clean-source.log` records the initial 155-task clean build. `final-rebuild.log` records the test-only settings-cleanup correction; production APK bytes stayed identical.
- `clean-source-check.json` maps all final compiled inputs to tested sourcecommit `28dce4008c97e7d3f32c0a1ba3545a83176957bb` and explains the clean-copy/test-only follow-up.
- `instrumentation-final.log`, derived XML and summary retain actual terminal runner records. Dutch decimal formatting is normalized only for the measured duration.
- The CDS regressions use synthetic measured properties and injected HTTP. They reproduce the local GUID/product block without asserting live Motorola acceptance or minting live-selection proof.
- `preview-recording.json` describes an actual emulator recording of navigation/disclosures with animation enabled; no network/update action is invoked.
- `iterations/` retains earlier runs, including the corrected test cleanup failure. It is separate from the final green result.

The signing, installed-hash, ELF/ZIP-layout, lint and exact dependency-audit records are real checks with the limits stated in the build report. Private paths/hostnames are redacted; all XML/JSON is reparsed. No physical device, partition write, OTA installation or phone reboot was performed. Older ordinary-file boundary checks remain in the separately labelled 1.0.0 evidence.
