# Compatibility and evidence — 1.0.1

[Nederlands](nl/COMPATIBILITY.md)

Implementation, emulator coverage and physical-device qualification are different evidence levels. [BUILD-REPORT](BUILD-REPORT.md) records actual executions; [DEVICE-VALIDATION](DEVICE-VALIDATION.md) records historical read-only hardware observations.

| Capability | Boundary |
|---|---|
| App | Android 13/API 33+, compile/target SDK 35, JVM 17 |
| Native libraries | arm64-v8a and x86_64; NDK 27.2.12479018/CMake 3.22.1 |
| Presentation | Complete default English and Dutch resources; native per-app language; system/light/dark appearance |
| Basic diagnostics | Observed properties only; restricted or missing fields stay unknown |
| Motorola guidance | Measured Motorola identity, fresh build/device evidence and actual official-updater resolution |
| Root-preservation guidance | Known A/B layout and slots, fresh root/Magisk and backup association evidence |
| Stock write adapter | Motorola A/B `boot` or `init_boot`, reviewed API 33–37, Magisk code31100, authenticated exact current bytes, fresh runtime SHA1 association and every live write gate |
| Direct OTA adapter | Reviewed framework UpdateEngine `applyPayloadFd`, OEM-authenticated unmodified payload, bound current source/target, scoped action and actual completion/need-reboot evidence; OEM permission/SELinux availability still needs runtime qualification |
| Inactive Magisk adapter | Exact official installed package `com.topjohnwu.magisk`, code31100/known whole-APK SHA256, supported 64-bit ABI, signed target-stock digest, private patch proof, next-slot evidence and consumed write/readback action; hidden/repacked managers use fallback |
| Single-slot/unknown layout | Diagnostics and applicable official guidance; no guessed A/B write target |
| CDS online detector | Explicit disclosed data-sharing consent, measured serial/GUID and supported global production request schema; no guessed identity or China endpoint |
| Downloads | Consistent measured Motorola identity, exact allowed HTTPS host and complete resource; transfer is separate from installation approval |
| OEM private import/staging | Version/profile/path-specific adapters; narrower than general Motorola guidance or downloads |
| Background monitor | Explicit opt-in; CDS additionally requires online consent; local-cache fallback retains its reviewed exact-profile parser |
| Future Android/unknown runtime | Diagnostics where available; no silent extension of critical write adapters beyond reviewed SDKs |

`DeviceCapabilities` derives support from actual evidence, not a guessed model catalog. `QUALIFIED_MERC` identifies the reference profile; `PROOF_QUALIFIED` identifies another configuration that satisfies the code's current write gates. Neither label claims a successful physical OTA on every model. The historical reference is XT2607-1/merc/reteu; another SKU, channel, firmware or trust store needs its own qualification.

Current-partition stock proof can authenticate the exact measured bytes through an OEM-signed manifest and independent live backup association. It does not authenticate a new OTA channel. Missing signed model/channel fields can be recorded as observed context for that narrow byte-proof purpose; explicit signed contradictions remain blocking. [Stock provenance](STOCK-PROVENANCE.md).

Local Motorola staging retains separate signed `motorola-channel`/`pre-channel` proof exactly `reteu` and `report.regionVerified`. Unknown channel blocks publication with `STAGING_CHANNEL_UNPROVEN`. Signature, host, cache, CDS tracking or download receipt cannot substitute for it. Motorola's ordinary official offer remains the fallback. [Staging contract](MOTOROLA_STAGING.md).

The reviewed AOSP API 33–37 Binder definitions retain the same `bind`/`unbind` order and one-way status/completion callback signatures; Android 16 adds a trailing method. This supports the adapter's wire comparison, not universal OEM access. SELinux constrains even root processes. [AOSP interface](https://android.googlesource.com/platform/system/update_engine/+/android-13.0.0_r1/binder_bindings/android/os/IUpdateEngine.aidl), [Android SELinux](https://source.android.com/docs/security/features/selinux).

Requalify after firmware, Magisk, certificate, boot-layout, snapshot, activity, namespace or OEM-path changes. For native staging also review filesystem no-clobber support, directory durability and ABI loading. [Known limitations](KNOWN-LIMITATIONS.md).
