# Safety and evidence — 1.0.0

[Nederlands](nl/SAFETY.md) · [Workflow](UPDATE-WORKFLOW.md)

The app distinguishes observation, user intent, byte integrity, authenticated provenance and an actual completed action. An unknown or contradictory result cannot authorize a write. A start acknowledgement applies only to the displayed run scope, device, target and root-preservation choice. It cannot override missing technical proof.

## Stock writes

A production stock restore is a real partition write. It requires a fresh supported Motorola A/B configuration, known `boot`/`init_boot` partition and exact measured size, reviewed SDK/Magisk, matching current identity, authenticated stock bytes and independent runtime Magisk-SHA1 association. Battery, space, successful unlocked boot, kernel security evidence, idle engine, safe snapshots, module inventory and automatic-update setting are checked again at the write boundary. Current image bytes are durably backed up before writing. Readback and journal evidence determine success; process exit alone does not.

Stock proof authenticates exact current partition bytes, not a new package's region or installability. A gzip name, SHA1 directory, header, manual build label or copied live patched partition is insufficient. Full ZIP authentication, image digest/size and runtime association have separate roles. [Stock provenance](STOCK-PROVENANCE.md).

A journal interrupted during writing needs inspection and transaction recovery. It is not replayed or automatically rolled back. The app's writer lock cannot atomically lock Motorola's separate updater. Snapshot checks and disabling automatic updates reduce races but do not create an OEM transaction guarantee. Hardware failure and power loss remain possible.

## Packages and OTA

HTTPS, CDS tracking, an allowed host or a transfer receipt do not authenticate firmware. Whole-ZIP signatures do not establish every source-block hash, rollback rule, channel or OEM condition. Server-reported availability and locally approved compatibility remain separate. Android/Motorola must retain their own signature, payload and rollback checks.

Local Motorola staging can cause immediate OEM processing. It requires the separately implemented signed `reteu` channel proof before publication; unknown channel is blocking. No cache or CDS receipt exception applies. Native atomic no-clobber publication avoids exposing partial files but cannot prove the OEM accepted or installed the package. [MOTOROLA_STAGING](MOTOROLA_STAGING.md).

Magisk/runtime integration must use only the reviewed adapter and evidence contracts. An app opening, shell exit or user report is not independent proof of inactive-slot patching. Reboot and post-boot observations are separate from preparation. Unsupported integration must pause with a truthful fallback instead of fabricating success.

## Privacy and simulation

Online detection requires explicit identifier-sharing consent. Serial/GUID stay outside ordinary inspection and export; no IMEI or OEM credentials are used. Downloads make real connections to the allowed source. Reports contain allowlisted technical facts only. Root-level compromise is outside the protection offered by private storage and Keystore. [CDS provider](CDS-PROVIDER.md), [monitoring/download privacy](MONITORING-AND-DOWNLOADS.md).

Sandbox tests write ordinary private files and simulate OTA, Magisk, slots, reboot and merge. Historical hardware records concern read-only observations. Neither a sandbox completion nor an APK build proves a successful current physical update. [BUILD-REPORT](BUILD-REPORT.md), [historical validation](DEVICE-VALIDATION.md).
