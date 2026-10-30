# Test matrix and evidence limits — 1.0.1

This matrix maps requested scenarios to actual test classes. It documents coverage, not execution counts. [BUILD-REPORT](BUILD-REPORT.md) identifies which exact build and tests ran, with their logs and reports. A fixture proves behavior for that fixture; it is not a physical Motorola OTA.

## Twenty requested scenarios

| # | Scenario | Automated coverage and boundary |
|---|---|---|
| 1 | No update | `FirmwareDiscoveryTest`, `CdsProtocolTest`, `CdsMonitorPolicyTest`: no/current offer, absent cache and unknown result do not become a new installable OTA. |
| 2 | New suitable candidate | `FirmwareValidatorTest`, `ReadinessTest`, `UpdateCoordinatorTest`: candidate, authenticated package, preflight and OEM-guidance authority stay distinct. |
| 3 | Wrong model | `FirmwareValidatorTest`, `DeviceCapabilitiesTest`, `UniversalWorkflowTest`, `MotorolaStagePolicyTest`: measured identity and signed contradictions cannot be replaced by a model-name assumption. |
| 4 | Wrong region | `FirmwareValidatorTest`, `CdsProtocolTest`, `MotorolaStagePolicyTest`: wrong/conflicting channel blocks; missing signed channel does not become signed proof for legacy staging. |
| 5 | Wrong source build | `FirmwareValidatorTest`, `StockEvidenceTest`, `StockRestoreTest`: wrong build/fingerprint and changed source cannot reach a valid write boundary. |
| 6 | No original backup | `ReadinessTest`, `StockRestoreTest`: current authenticated bytes are required; file existence is insufficient. |
| 7 | Corrupt backup | `BackupValidatorTest`, `BootImageStreamsTest`: truncation, wrong CRC/ISIZE, trailing output, impossible extent, oversize and wrong full hash are rejected. |
| 8 | Insufficient space | `ReadinessTest`, `ResumableTransferTest`, `VerifiedStageTransferTest`: inadequate known storage blocks transfer/preparation/publication. |
| 9 | No root | `ReadinessTest`, `DeviceCapabilitiesTest`, root guard/runtime tests: diagnostics and root-specific authority remain separate; a grant must actually be observed. |
| 10 | Active or unknown merge | `ReadinessTest`, `MetadataObservationsTest`, `ReadOnlyMetadataTest`, `UpdateCoordinatorTest`: active/conflicting/unknown state cannot complete or authorize stock writes. |
| 11 | Failed download | `ResumableTransferTest`, `DownloadRuntimeTest`: durable-prefix resume, changed entity, incomplete bytes, checkpoint loss and atomic selection are tested with injected transport. |
| 12 | Invalid ZIP | `FirmwareValidatorTest`: absent/invalid ZIP, missing metadata/properties/payload, duplicate fields and structural bounds are rejected. |
| 13 | Verification failure | `FirmwareValidatorTest`, `PlatformSafetyTest`: payload/metadata hash failure and real Android rejection of an unsigned package cannot become authenticated. Positive historical device signature evidence is labelled separately. |
| 14 | Engine error | `ObservationParsersTest`, `UpdateFailureTest`: fatal engine/merge failure is retained after a later idle status or inappropriate retry. |
| 15 | Inactive Magisk failure | `UpdateCoordinatorTest`: failed/reported/unverified patch cannot become independent image proof or a safe completed reboot sequence. File-backed patching is simulated. |
| 16 | Root after boot missing | `UpdateCoordinatorTest`: actual fresh root is required; denied app permission is distinct from proof that Magisk was removed. |
| 17 | Process death | `StockRestoreTest`, `UpdateRunStoreInstrumentedTest`, `DownloadRuntimeTest`: durable state, crash boundaries, reload and observation-only recovery; no automatic writer replay. |
| 18 | Unexpected reboot | `UpdateCoordinatorTest`, `RunAuthorizationTest`: new boot identity triggers reconciliation and invalidates old source/action scope; source build on a new boot can indicate rollback. |
| 19 | Different slot | `UpdateCoordinatorTest`, `StockRestoreTest`, `UniversalWorkflowTest`: actual target slot, bound current source and measured partition type are required. |
| 20 | External release, no Motorola offer | `FirmwareDiscoveryTest`: an external report cannot override OEM status or supply a trusted download/install decision. |

## Cross-model and release coverage

| Area | Test classes / evidence |
|---|---|
| Measured capabilities | `DeviceCapabilitiesTest`: another Motorola profile, `boot`/`init_boot`, single-slot, unknown/contradictory inventory, unavailable updater, stale association and unreviewed runtime. |
| Traditional A/B | `UniversalWorkflowTest`: a measured non-Virtual-A/B target can complete only with appropriate root/new-stock evidence; a fake merge is not required. |
| Streaming images | `BootImageStreamsTest`: bounded read requests, raw/gzip complete digests, boot-header distinction, strict footer/extent rejection and authenticated boot reference. |
| One initial intent | `RunAuthorizationTest`: full scope MAC, exact source boot/device/build/slot, package narrowing, short action nonce, wall/elapsed deadlines and strict codec shape. |
| Current Magisk association | `MagiskRuntimeConfigTest`: known runtime roots, bounded reads, one valid SHA1, malformed/duplicate syntax and rejection of authentic old images with a spoofed build. |
| Stock transaction | `StockRestoreTest`: complete prior backup, MAC, expiry, one-use markers, mode/source binding, interrupted/partial writes, locked recovery and no replay. Writes use regular files. |
| File-backed complete route | `SandboxUpdateSessionTest`, `SafeUiTest`: ordinary-file stock/OTA/patch transactions and durable phases; root/slots/reboot/merge are mocks and the live journal stays unchanged. |
| Android persistence | `UpdateRunStoreInstrumentedTest`, `DownloadRuntimeTest`, `MonitorRuntimeTest`, `VaultRuntimeTest`: actual private files, AtomicFile, reload, corruption and Android service boundaries. |
| Stock vault | `PayloadManifestTest`, `StockEvidenceTest`, `StockEvidenceStoragePolicyTest`, `VaultRuntimeTest`: signed old/new boot digests, current identity, stable immutable pins, trust anchors and durable copy fallback. |
| CDS protocol and privacy | `CdsProtocolTest`, `CdsMonitorPolicyTest`, `HttpsCdsTransportTest`, `CdsControllerRuntimeTest`: bounded strict JSON, exact endpoint/context/target/resource, metadata-only distinction, consent/revocation/cancellation and injected transport. |
| Monitoring | `MonitoringTest`, `MonitorRunnerTest`, `MonitorRuntimeTest`: opt-in, fresh identity/root/engine guards, deduplication, silent unchanged result, permissions, disable races and unique scheduling. |
| OEM import/staging | `MotorolaImportPolicyTest`, `ImportRuntimePolicyTest`, `MotorolaImporterGuardInstrumentedTest`, `MotorolaStagePolicyTest`, `VerifiedStageTransferTest`, `MotorolaStagingRuntimeTest`: bounded sources, stable identity, no-clobber JNI publication and separate staging authorization. Emulator cache tests do not prove physical OEM recognition. |
| Presentation | `SetupTourUiTest`, `PresentationUiTest`, `SafeUiTest`, `MessageRendererRuntimeTest`: tour, navigation, current locale, appearance/cached messages and clearly separated simulation. |
| Report privacy | `PrivacyTest` plus metadata/codec/secret-store tests: allowlisted export, no raw identifiers/XML/signed URL or personal filenames. |
| No bootctl side effects | `UpdateObservationsTest`: diagnostic getter probes are refused before launching a potentially mutating initializer. |
| Release artifact | Actual APK verification scripts check manifest, signing identity, native layout and dynamic entrypoints in the delivered complete-runtime APK. Shrinking is off by default; opt-in R8 builds require full qualification. Execution belongs in the build report. |

The optional host firmware tests use only the explicit `MOTO_OTA_PATH` fixture; they can skip in public CI without that private multi-gigabyte file. Windows symlink tests may be unsupported on the host; record the real skip and any corresponding Android runtime coverage rather than counting an unexecuted host test as passed.

## Physical evidence stays separate

The 0.2.0 reference-device report records read-only root, snapshot, boot metadata, engine, backup, cache and positive platform-signature observations on XT2607-1. [DEVICE-VALIDATION](DEVICE-VALIDATION.md), [historical build report](BUILD-REPORT-0.2.0.md).

The current development suite uses no phone ADB, real partition write, real OTA installation or phone reboot. The opt-in `ReadOnlyDeviceValidationTest` is skipped by default. A separately selected disposable emulator is required for instrumentation; never let an unqualified default ADB target choose a phone.

The green suite cannot establish future Motorola offers, live CDS acceptance, OEM permissions, physical flash behavior under every power/storage fault, real inactive-slot bootability, module functionality, Wallet/Play Integrity or a complete future data-preserving update. [Read-only manual plan](MANUAL-TESTS.md), [Compatibility](COMPATIBILITY.md).
