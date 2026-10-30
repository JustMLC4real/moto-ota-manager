# Changelog

## 1.0.1 — update-check fix and cleaner interface

[Release notes](docs/RELEASE-NOTES-1.0.1.md)

- Fix incorrect rejection of measured 15-character Motorola build GUIDs; keep full 40-character GUID support without padding or guessing values.
- Use the observed product name when the legacy vendor display property is empty, while rejecting conflicting source identities.
- Make overview, firmware and update screens concise, with secondary tools behind accessible disclosures and compact language/theme choices.
- Add finite tab, status, disclosure and measured-progress animations that follow the system animation duration scale.
- Add reader/protocol/runtime regressions for the reported failure, disclosure and reduced-motion tests, and support Dutch decimal formatting in retained JUnit runner evidence.

## 1.0.0 — first public release

[Publication-ready release notes](docs/RELEASE-NOTES-1.0.0.md)

- English/Dutch native app languages, a first-run tour, system/light/dark appearance and adaptive Compose navigation.
- Measured Motorola capabilities across models, distinguishing `boot`/`init_boot`, A/B, traditional A/B and unsupported/unknown layouts.
- Primary source-derived Motorola-CDS HTTPS detection with explicit identifier-sharing consent, strict context/resource validation and official-updater fallback.
- Resumable controlled downloads, private complete-file publication, existing OEM package import and optional deduplicated monitoring.
- Streaming raw/gzip boot-image validation with full hashes, strict CRC/trailer/header bounds and actual measured partition sizes.
- Current stock provenance from OEM-authenticated old/new payload manifests, stable private package pins and independent live Magisk association.
- One initial scoped run intent, short one-use action permits, durable journals and observation-only recovery after interruption.
- Versioned direct official-payload and exact-official-Magisk31.1 adapters with independent phase/image evidence and safe fallback on unknown prerequisites.
- Maintained production signing configuration, non-debuggable release with the complete runtime, dynamic-entrypoint/native APK checks, pinned CI actions, public-source hygiene and resolved dependency inventory. Code/resource shrinking is off by default; opt-in R8 builds require full qualification.

Executed builds, tests, screenshots and exact delivered hashes are recorded in [the build report](docs/BUILD-REPORT.md). File-backed simulations, emulator execution and historical read-only device observations are separate evidence. This release does not claim a physically qualified OTA on every Motorola model or guarantee module, Wallet or attestation behavior.

The package remains `nl.moto.ota`, version code4, Android13/API33+. The production release uses its own [app signing identity](docs/RELEASE-CERTIFICATE.md); Android cannot update it interchangeably with an older debug-signed installation. Preserve private recovery data before a deliberate migration, and never remove a critical workflow journal just to install a different signer.

Earlier 0.1–0.3 development reports remain in the repository as historical evidence, not as test proof for the new APK. No Motorola firmware, Magisk binary or private release key is redistributed.

## Nederlands

De eerste publieke release 1.0.0 voegt een Engelse/Nederlandse introductie en interface, thema's, gemeten ondersteuning voor andere Motorola-modellen, primaire CDS-detectie, streaming stockcontrole, begrensde runautorisatie en versiegebonden OTA/Magisk-adapters toe. Releaseondertekening, artifactcontrole, CI en publieke broncontrole horen bij de oplevering.

[Het buildrapport](docs/BUILD-REPORT.md) bevat echte uitvoeringen en hashes. Simulatie/emulatorbewijs is geen fysieke OTA-certificering. De nieuwe releasesigner kan een debugapp niet gewoon bijwerken; behoud herstelgegevens en plan migratie bewust. [Nederlandse handleiding](README.nl.md).
