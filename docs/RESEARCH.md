# Research and evidence map — 1.0.0

Research distinguishes upstream contracts, observed implementation details, app policy, synthetic execution and physical-device qualification. A successful manual update shows feasibility for that recorded device state; it does not establish a stable third-party API for every Motorola model.

## Current decisions

- [Integration decisions](INTEGRATION-DECISIONS.md): Motorola CDS is the primary source-derived detector; Magisk automation is versioned and evidence-bound; avbroot is a separate custom-trust deployment rather than the default OEM chain.
- [CDS provider](CDS-PROVIDER.md): pinned source paths, minimized request, consent, strict JSON/resource binding, actual HTTPS and runtime acceptance limits.
- [Stock provenance](STOCK-PROVENANCE.md): independent OEM-signed old/new boot manifests, actual current source/target identity, runtime Magisk association and no inferred fingerprint aliases.
- [Compatibility](COMPATIBILITY.md): measured cross-model capabilities, supported APIs/ABIs and the narrower private OEM path adapters.
- [Update workflow](UPDATE-WORKFLOW.md): one initial scoped intent, fresh short action permits, durable evidence and observation-only interruption recovery.
- [Motorola staging](MOTOROLA_STAGING.md): separate legacy copy-only route, signed channel proof and native no-clobber publication.
- [Release engineering](RELEASE-ENGINEERING.md): maintained release identity, dynamic-interface verification, optional R8 qualification, source hygiene, dependency inventory and exact artifact validation.

## Evidence levels

| Evidence | What it establishes |
|---|---|
| Upstream source/documentation | The reviewed source version's behavior or declared contract; not OEM access on every firmware |
| App policy and parser tests | Which data this implementation accepts or rejects |
| Actual host/emulator build and execution | Compilability and the scenarios recorded in that run's reports |
| File-backed simulation | Real ordinary-file transactions with explicitly mocked device/OTA/Magisk/boot state |
| Historical read-only hardware report | Only the recorded physical observations on that version/device |
| Future physical update qualification | Actual OTA, inactive-slot root, target boot and merge on the exact qualified device; cannot be inferred from the earlier rows |

[BUILD-REPORT](BUILD-REPORT.md) identifies current executed evidence. [DEVICE-VALIDATION](DEVICE-VALIDATION.md) and [BUILD-REPORT-0.2.0](BUILD-REPORT-0.2.0.md) retain historical reference-device reads. [Historical research through 0.3.0](RESEARCH-0.3.0.md) preserves earlier conclusions, including limits that changed in the cross-model release; use the current capability and integration guides for current behavior.

No physical phone is used for the current development round. The app does not erase updater data, reset arbitrary counters, spoof attestation, disable SELinux or change AVB trust as a shortcut. An unavailable OEM/Magisk contract needs a safe explicit fallback, not an invented successful operation.
