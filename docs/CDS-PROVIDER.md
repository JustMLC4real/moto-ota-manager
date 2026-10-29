# Motorola CDS provider — 1.0.0

[English](CDS-PROVIDER.md) · [Nederlands](nl/CDS-PROVIDER.md)

The primary online detector is an independent Kotlin client for the production CDS protocol observed in [FixeQD/motorola-update, commit 272f1c9](https://github.com/FixeQD/motorola-update/tree/272f1c9bbe930a00ed76fe65d481f5cb108dacdf). It uses real HTTPS by default. Tests inject synthetic identities and responses. No live request with a real device identifier was made during implementation.

This is an internal integration derived from source observations, not a documented public Motorola SDK. Server acceptance, rollout decisions and future schema stability are not guaranteed. The official updater, recognizable local cache and explicit package import remain fallbacks.

## Consent and data

Before an identity read or HTTP request, the user must explicitly allow online sharing. Only the consent boolean is stored. A dedicated fixed read then obtains the serial number and build-context GUID. They never enter ordinary probes, `Inspection`, reports, WorkManager input or histories. No IMEI, phone permission, OEM credential or account credential is used.

Requests send the serial number, GUID, measured manufacturer/model/product/device/channel, build ID/fingerprint/incremental, Android version, app language and available brand/hardware/locale information. The destination sees normal connection metadata, including the network IP. Identifiers and raw responses remain in memory. An authorized download URL moves into the downloader's encrypted secret store. Revocation stops future requests and discards the controller result; an already transmitted request cannot be recalled.

## Protocol boundaries

| Step | Boundary |
|---|---|
| Origin | Exact `https://moto-cds.appspot.com`; no alternate cloud, guessed China/staging server or redirect |
| Check | POST `/cds/upgrade/1/check/ctx/ota/key/<measured GUID>` |
| Request | App-shaped `id`, `contentTimestamp`, `deviceInfo`, `extraInfo`, `identityInfo`, `triggeredBy`, `idType`; serial-only identity |
| Response | Strict bounded JSON, no duplicate keys, exact `ota` context/GUID, explicit target fingerprint, source GUID checked when provided |
| Resources | Only after an offer; returned timestamp/tracking/reporting fields bind the second POST; response tracking must match |
| Download | Known Wi-Fi resource, exact allowed download host and complete `.zip` reference; metadata-only stays distinct |
| Headers | Nonempty arbitrary resource headers are not forwarded |
| Limits | Request 16 KiB, response 256 KiB, JSON depth 12, 4096 values, strings 16 KiB, 16 resources and bounded network waits |

The [CDS constants](https://github.com/FixeQD/motorola-update/blob/272f1c9bbe930a00ed76fe65d481f5cb108dacdf/app-src/src/java/com/motorola/otalib/cdsservice/utils/CDSUtils.java) establish the production paths. The [app check route](https://github.com/FixeQD/motorola-update/blob/272f1c9bbe930a00ed76fe65d481f5cb108dacdf/app-src/src/java/com/motorola/ccc/ota/sources/bota/BotaUpgradeSource.java) uses [BuildPropReader](https://github.com/FixeQD/motorola-update/blob/272f1c9bbe930a00ed76fe65d481f5cb108dacdf/app-src/src/java/com/motorola/ccc/ota/utils/BuildPropReader.java). The client minimizes optional telemetry; acceptance of that subset requires a separately consented runtime test.

The library's `CheckUpdateHandler` instead accepts a caller-supplied numeric source version. The provider does not guess that value from a timestamp or relabel the Android build ID as a number. It uses the observed app variant's actual string build ID. The external README's IMEI-heavy example is not treated as the protocol specification.

## What a result proves

`NO_OFFER` means no offer was returned for this request. `OFFER` identifies a different target build. A resource can still be missing, unsupported, expired or metadata-only. The client does not execute server instructions, upload requested logs or change updater counters.

The observed schema contains package size/MD5, target fingerprint and source/target GUIDs. A full-package SHA256 or independently signed channel assertion has not been established. HTTPS response binding is separate from whole-ZIP authentication, stock provenance, compatibility and installation authority. [Metadata builder](https://github.com/FixeQD/motorola-update/blob/272f1c9bbe930a00ed76fe65d481f5cb108dacdf/app-src/src/java/com/motorola/otalib/common/metaData/builder/MetaDataBuilder.java), [resource schema](https://github.com/FixeQD/motorola-update/blob/272f1c9bbe930a00ed76fe65d481f5cb108dacdf/app-src/src/java/com/motorola/otalib/cdsservice/responsedataobjects/builders/ContentResourcesBuilder.java).

Background checks require monitor opt-in plus online consent. Only a fresh different target can notify; deduplication excludes identifiers and URLs. Failures stay quiet and can use the local cache fallback. A notice starts no download or installation.

## Source and test evidence

The research repository is labelled [AGPL 3.0](https://github.com/FixeQD/motorola-update/blob/272f1c9bbe930a00ed76fe65d481f5cb108dacdf/LICENSE) and contains decompiled OEM sources. Those files remain outside this distribution. No OEM implementation, credentials, firmware or binary is copied into this independently written client.

`CdsProtocolTest`, `CdsMonitorPolicyTest`, `HttpsCdsTransportTest` and emulator-only `CdsControllerRuntimeTest` describe synthetic coverage for binding, bounds, reference kinds, endpoint refusal, consent, revocation and cancellation. Actual executions and results belong in [BUILD-REPORT](BUILD-REPORT.md).
