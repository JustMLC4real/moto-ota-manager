# Read-only manual qualification plan — 1.0.0

This plan validates presentation and observations on a selected Motorola. It does not authorize a stock write, OTA, inactive-slot patch, slot change or phone reboot. The current development round uses mocks/emulators; do not connect a phone just to complete these checks. Historical reference-device reads are in [DEVICE-VALIDATION](DEVICE-VALIDATION.md).

## Setup and presentation

- Verify the exact APK checksum and signing identity from the delivery. Install it yourself only when intended. Preserve local recovery data before any debug/release signing migration.
- Check the first-run tour, English/Dutch language choice, skip/reopen behavior and system/light/dark appearance. Changing language should update cached messages and notification channel names too.
- Check narrow/large displays, enlarged text, accessible button descriptions and scrolling. A critical persisted run must remain visible instead of being hidden by onboarding.
- Before granting root, verify that unavailable root data is shown honestly and the app stays usable for basic diagnostics. ADB root must not imply app Superuser access.
- If choosing protected diagnostics, grant this app access in Magisk. Do not change unrelated Magisk settings.

## Read-only device observations

Use only **Diagnostics** refresh/root-diagnostics controls. Do not tap **Start update**, stock execution, OEM staging or any reboot action during this plan.

Compare measured manufacturer/model/device/channel, Android/build and security patch against available local sources. Another model need not equal XT2607-1/merc/reteu. System/vendor fingerprints may legitimately occupy different namespaces; no automatic alias substitution should conceal the difference. If Android properties are spoofed, compare independent kernel security and current-image/runtime association rather than treating the spoofed string as hardware proof.

Inspect active/inactive slot, measured partition inventory and byte size, successful-boot evidence, root and Magisk version independently. A Magisk app icon alone does not prove live root. Inspect engine and snapshot sources; missing tools, old log percentages, permission errors and contradictory state must stay unknown. Traditional A/B may be positively not applicable for snapshots only with consistent layout evidence.

Wait and refresh if a real background merge is already underway. Do not start another action to make its state green. Do not enable services, alter snapshots, reset counters or run bootctl getters for this read-only check.

## Backups and package inspection

Inspect the Magisk backup inventory: compressed bytes, expanded size, actual boot-image type and hashes are separate facts. An unassociated backup remains unproven even if its gzip is valid. Old authenticated stock bytes cannot become current merely because a displayed build is spoofed. Module inventory means presence/state, not functional compatibility.

Use only an independent harmless copy for corruption cases. Truncated gzip, bad CRC, impossible image extents, wrong size and trailing shell text must be rejected. Keep originals unchanged. Do not run Magisk Restore Images, Install or Uninstall.

A local ZIP can be selected and inspected in private app storage. It must not be copied to Motorola's watched directory or applied in this plan. Verify malformed `.zip` text rejection, genuine signature requirements and separate model/source/channel results. A filename containing `reteu`/`cid50` is not signed channel proof.

## Optional online check

Only if you deliberately allow the stated sharing, test CDS after reviewing its serial/GUID/device/build disclosure. Record result category and whether a full resource exists; do not publish identifiers, signed URLs or raw response bodies. Revoke consent and verify no further identity read/request starts. `NO_OFFER`, network failure and unknown schema are separate states and do not prove a permanent block.

Official-updater navigation can be tested without starting its offered installation. Local zero failure counters remain local observations. This plan does not require a production download or the real update start button.

## Sandbox and process recovery

Use the clearly labelled complete file-backed simulation. Check separate sandbox journal, regular-file partitions, patched source backup and new stock backup. Root/slots/reboot/merge are mock observations; no privileged device call should be required and the live journal must stay unchanged.

Close/reopen the app or force-stop the app only. Saved progress should survive; current measurements must be fresh. Simulated process death, changed boot/slot/root, partial write, stale consent and delayed callbacks must not replay a saved live command or report fake success. A corrupt existing journal must require recovery instead of starting idle.

Check the initial route scope in the UI without starting it. It should describe included writes/root/reboot. File-backed policy tests establish the absence of per-phase confirmation requirements for that scoped route; this read-only manual plan does not click a real write to demonstrate it.

## Report and results

Export the allowlisted report to a chosen local destination and inspect it locally. It should preserve true/simulated and known/unknown categories while omitting raw identifiers, signed URLs/tokens, raw XML, accounts, personal filenames and boot/module IDs. No report is uploaded automatically.

Record app version, APK hash, Android build, Magisk version, date and `pass`, `deviation` or `unavailable` per check. Share only reviewed sanitized output. A successful read-only check is not qualification of a real data-preserving update.

A future physical update plan must specify the exact device/firmware/adapter, independent firmware proof, power/storage/recovery preparation and authorized destructive actions. It is separate from this checklist and the normal CI suite.
