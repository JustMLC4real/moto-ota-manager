# Known limitations — 1.0.0

[Nederlands](nl/KNOWN-LIMITATIONS.md)

1. **Physical qualification is specific.** Historical hardware records cover read-only reference-device observations. The current release's new writes, cross-model adapters and complete future OTA/root-preservation chain are not thereby proven on a phone. Current builds and executed tests are recorded in [BUILD-REPORT](BUILD-REPORT.md).

2. **CDS is an internal integration.** The primary detector has an actual HTTPS transport and strict source-derived protocol, but no public Motorola SDK contract or uptime/schema guarantee. Server acceptance of minimized optional telemetry requires a separately consented runtime check. A no-offer response does not prove absence of a rollout. [Provider evidence](CDS-PROVIDER.md).

3. **Identifiers require consent.** Online checking needs a measured serial number and build-context GUID; denial, unreadable properties, regional mismatch or inconsistent identity stops the request. No IMEI or guessed substitute is used. Ordinary diagnostics and the official updater remain available where supported.

4. **Background checks are not exact alarms.** Android controls scheduling and permissions. Monitoring starts disabled, and online checks also need sharing consent. A cache may be stale; metadata-only and repeated/current targets do not become new complete packages. [Monitoring](MONITORING-AND-DOWNLOADS.md).

5. **Transfer support depends on the source.** Downloads use one exact allowed HTTPS host. Arbitrary resource headers, redirects, expired references, unsuitable lengths/validators or unavailable foreground/Keystore/storage services can stop a job. Resume needs a strong matching validator. Copying an OEM ZIP proves byte transfer, not authenticity.

6. **Stock proof has a narrow meaning.** OEM ZIP authentication plus exact current image digest/size and fresh runtime association can establish current partition bytes. It does not establish a new OTA's channel or suitability. Explicit signed identity conflicts remain blocking; changing trust anchors or device/build identity invalidates stale associations. [STOCK-PROVENANCE](STOCK-PROVENANCE.md).

7. **Local staging remains narrower.** Its internal OEM path can trigger processing immediately and requires signed `reteu` channel proof before publication. Missing proof blocks with `STAGING_CHANNEL_UNPROVEN`. A CDS reference, host, signature or receipt is not a channel exception. Publication/readback is not installation proof. [Staging contract](MOTOROLA_STAGING.md).

8. **Writes cannot be risk-free.** The reviewed API 33–37/Magisk 31100 adapter still needs all fresh capability, image, backup and live gates. Writer locks cannot lock the independent Motorola updater. Interrupted writes require inspection/recovery, not replay. Power/storage hardware failure remains possible. Future runtimes are not silently approved.

9. **Automation cannot invent evidence.** Start consent scopes the reviewed run; it cannot turn unsupported OEM/Magisk operations or user reports into verified image evidence. Missing proof must pause with a clear fallback. Post-boot root, build, slot, successful boot, snapshots and new backup are separate checks.

10. **Modules and payment apps need their own check.** Module inventory does not establish functionality, Play Integrity or Wallet behavior. The app does not spoof attestation or security state. A missing newly authenticated stock reference can leave the final run in review and block a subsequent stock write.

11. **Private storage has practical limits.** Hardlink denial causes a full pin copy; large download/pin/temp files can coexist. Atomic selection preserves the old complete file on a failed replacement. Keystore and private storage cannot protect against total control of a rooted device. Explicit online checks/downloads disclose their stated network data.

12. **App signing identities matter.** An unsigned release build is CI output. A signed production APK needs its own private maintained key; it cannot replace an older debug-signed app as an ordinary update. Migration must preserve recovery data deliberately. [Release guide](RELEASING.md).


13. **Automatic root preservation requires the exact reviewed tools.** The adapter admits only the official installed Magisk31.1 APK and supported ABI. Hidden/repacked managers, other versions, recovery/vendor-boot layouts or unproven preinit state use a safe stop/fallback. A private-file patch, real inactive-partition write and new-boot root are separate evidence. Actual OEM Binder/SELinux access and bootability are not proven by file-backed tests. [Integration decisions](INTEGRATION-DECISIONS.md).

Implemented code, synthetic tests and physical results stay distinct. [Compatibility](COMPATIBILITY.md) · [Safety](SAFETY.md).
