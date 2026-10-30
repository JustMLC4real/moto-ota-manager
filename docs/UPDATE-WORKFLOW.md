# Update workflow and recovery — 1.0.1

[Nederlands](nl/UPDATE-WORKFLOW.md) · [Compatibility](COMPATIBILITY.md) · [Safety](SAFETY.md)

The workflow records expected state and measured state separately. Its purpose is one understandable start action for a qualified update, including the displayed root-preservation and reboot scope. Technical checks still run at each boundary. An unsupported operation pauses with its reason and an official-app fallback; start consent never substitutes for missing evidence. [Integration decisions](INTEGRATION-DECISIONS.md) explains the selected technologies.

No physical partition, OTA or reboot is exercised by the development/emulator suite. Check [BUILD-REPORT](BUILD-REPORT.md) for the exact delivered build and executed tests. Historical read-only hardware evidence is not qualification of a new automatic update.

## Before starting

Grant this app root in Magisk when requesting protected observations. ADB root is a separate grant. Disable **Automatic system updates** in Developer options yourself; the app checks this setting and does not change it. Read the initial scope before starting. Online discovery also needs disclosure and consent for the stated identity data; this can be part of the initial scope, rather than another confirmation at every update phase.

Fresh measurements must establish the actual Motorola identity, source build/fingerprint, boot ID, A/B slots, current boot success, kernel security state, root/Magisk, module inventory, battery, free storage, idle update engine and safe snapshot state. A traditional A/B device can report snapshots as not applicable only when that layout is actually established. Unknown or contradictory Virtual A/B state is blocking.

`boot` and `init_boot` are selected from the measured layout and authenticated image evidence. The stock writer reviews API 33–37 and Magisk 31.1/code31100. It streams bounded images up to 512 MiB and requires the exact measured partition size; another model does not become safe because the reference phone uses an 8 MiB image. Single-slot or unknown layouts cannot use the inactive-slot route.

## Package and original-image proof

1. The primary detector checks Motorola CDS after online-sharing consent. An offer and complete download reference identify a candidate, not an approved installation.
2. The complete private ZIP is inspected for format, payload metadata and hashes, source/target identity, downgrade/wipe conflicts and actual OEM whole-file signature authentication. A transfer or HTTPS result cannot replace the signature.
3. Current original `boot`/`init_boot` bytes require independently authenticated manifest digest/size, the actual source identity and fresh runtime Magisk-SHA1 association. A file called `boot.img.gz` or its SHA1 directory does not supply that proof.
4. The same rules reject an old, individually authentic stock image when it does not belong to the running boot. The live reader only accepts the known Magisk runtime config roots, bounded stable no-follow reads and an unambiguous SHA1; it never sources that config as shell code.

Source, target, model and channel evidence retain their own meanings. In particular, current-stock byte proof does not establish a future OTA channel. The separate legacy OEM staging route still requires its signed channel proof before publication. [Stock provenance](STOCK-PROVENANCE.md), [CDS provider](CDS-PROVIDER.md), [staging contract](MOTOROLA_STAGING.md).

## One initial authorization, fresh action checks

A run authorization binds its UUID, measured device/channel, source build/fingerprint/boot/slot, target build/fingerprint, package SHA256, action set and both wall-clock and elapsed-time deadlines. An initially unknown package hash can only be narrowed to a verified package for that same target. It cannot be replaced with a different package later in the run.

The app issues short action permits only for the active, verified in-process run. App and root layers keep separate durable one-use consumption markers. Root rechecks the permit and the relevant fresh device facts before performing the action. Saved authorization data is observable after process recreation, but does not silently reactivate dispatch or replay an action. The full-run grant and each short action deadline are bounded independently; changing the wall clock cannot extend elapsed-time authority.

This permits a qualified route to advance without separate confirmation dialogs for stock restoration, OTA, Magisk and reboot. A future-update preference, if offered, is a separate explicit policy: an old action permit never authorizes a different update, changed device or new boot. Notifications and ordinary monitoring do not themselves start an installation.

## Actions and evidence

| Phase | Required result |
|---|---|
| Stock restore | Capture and hash the complete current patched partition, consume the scoped action, recheck the source and safety gates, write only the current measured boot partition, sync and fully read back the authenticated stock hash. |
| OTA | Retain Android's signature, payload, rollback and source-block verification. Actual update-engine state must establish application; starting a process or copying a ZIP cannot. |
| Inactive-slot root | Match the post-OTA target slot and independently authenticated target stock bytes. A supported Magisk adapter must prove the patched image and its association; a manual report stays `USER_REPORTED`. |
| Reboot | Require the matching completed preparation and target evidence under the reviewed initial scope. Persist the pending transition before dispatch; never infer a new boot from app recreation. |
| Target boot | Observe a different boot ID, actual target build and expected slot, successful boot, fresh root/Magisk, kernel security and module inventory. |
| Merge/final check | Wait for actual snapshot completion and an idle engine. Authenticate the new current stock backup separately; a missing next-update backup leaves review outstanding. |

These are evidence contracts. The current adapter and device qualification limits are listed in [Compatibility](COMPATIBILITY.md); a table row does not certify a particular phone. Official Motorola and Magisk navigation stays available when an adapter cannot establish its prerequisites.

## Recovery

| Observation | Response |
|---|---|
| App/process is recreated | Load the journal and remeasure; do not execute a saved command. |
| Another writer still holds the transaction lock | Recovery cannot claim completion. |
| Interrupted write, unknown or unexpected active hash | Keep `RECOVERY_REQUIRED`; preserve backup and diagnostics. No automatic replay, rollback or reboot. |
| Expected active stock hash after interruption | Also read and verify the full saved patched backup and exact source context before accepting recovery. |
| Source is still the exact original patched bytes | An explicit read-only recovery can recheck gates, capture a missing backup and invalidate old consent before allowing a new plan. |
| Source build/slot appears after a genuinely new boot | Report possible rollback; do not report update success. |
| Target matches but the app's root grant is denied | Allow a fresh read-only final check after the grant is fixed. Denial alone does not prove Magisk was removed. |
| Engine/snapshot fails or observations conflict | Retain the failure and diagnostic evidence; do not reset, force-merge, change slots or hide it after a later idle reading. |
| New target stock proof is absent | Use `COMPLETE_REVIEW` when the observed update otherwise succeeds; block the next unsupported stock write. |
| Existing journal is corrupt or oversized | Treat as recovery required. Only an absent journal represents an idle run. |

The complete patched source backup is retained under `files/update/transactions/<uuid>/patched-backup.img`. Export it deliberately when needed. It is never automatically flashed back. No universal recovery shell command is offered: current boot, slot, engine and snapshot state determine the correct device-specific recovery plan.

The global writer lock does not atomically lock Motorola's own updater, and watchdogs/fsync cannot guarantee success after storage failure or power loss. Repeated status readings and backup/readback improve accountability; they do not make flashing risk-free.

## File-backed rehearsal

`SandboxUpdateSession` exercises durable transitions and the stock transaction with ordinary files under a new `moto-ota-sandbox-` directory. OTA writes an inactive fixture, Magisk changes fixture bytes, and slots/root/reboot/merge are explicitly mocked. Complete backup, one-use consent, readback and failure recovery remain real file operations.

A sandbox uses a separate journal and `simulated=true` observations. It cannot authorize production actions or change the live journal. Completing it proves the file-backed software scenario, not bootability of a real image. [Test matrix](TEST-MATRIX.md).
