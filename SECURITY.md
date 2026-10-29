# Security policy

Moto OTA Manager handles firmware and privileged actions. A successful APK build, passing fixture, user report or readable hash is not a completed physical update. Current behavior and limits are described in [Safety](docs/SAFETY.md), [Compatibility](docs/COMPATIBILITY.md) and [Update workflow](docs/UPDATE-WORKFLOW.md). Historical hardware observations keep their original version labels.

## Report a problem

For a private report, use GitHub's **Report a vulnerability** feature when the repository owner has enabled it. If it is unavailable, open a minimal issue asking for a private reporting channel. Do not publish exploit details or real device data while arranging that channel. No unverified email address or external reporting endpoint is required by this project.

Include the app version/commit, Android and Magisk versions, affected policy or parser, expected behavior and a minimal synthetic reproducer. Do not include IMEI, serial numbers, build-context GUIDs, accounts, signed OTA URLs, recovery images, keystores, secrets or raw Motorola preferences. The app's allowlisted report is preferable to a raw log dump; review any attachment before sharing it.

## Boundaries that need review

- Firmware integrity, OEM signature authentication, source/target compatibility, channel evidence and current-stock provenance are separate checks.
- Measured capabilities determine critical adapters. A model name, spoofed property, backup filename or an existing root grant cannot authorize a write.
- Root helpers accept fixed operations and internally derived paths. A scoped initial run authorization is narrowed to the authenticated package; short action permits bind the exact run, action, source boot and deadline. Durable consumption markers prevent silent replay.
- Full-image backup, a write boundary check and complete readback are required for stock restoration. Recovery observes interrupted state; it does not automatically replay a write or roll it back.
- A user report, app launch or process exit cannot independently prove an OTA, inactive-slot Magisk installation, successful target boot or completed merge.
- A writer lock does not lock the independent OEM updater. Private files and MACs cannot defend against another attacker who already has complete root control.

Do not weaken a blocker to make an unknown state appear successful. Requalify changes to Android, Magisk, Motorola services, trust anchors, filesystem publication, boot layout and privileged transport. Tests must use ordinary files and disposable emulators unless a separate device test plan explicitly authorizes the exact physical actions. No production OTA, flash or phone reboot belongs in ordinary CI.

## Local data and signing

Imported firmware, image backups, trust associations and the workflow journal live in private app storage. Android backup and device transfer are disabled. Removing app data or uninstalling can remove recovery information; preserve it deliberately before migration. Export is user initiated and there is no automatic diagnostic upload.

Release keys remain outside the repository. A production signature differs from the development debug signature, so Android cannot update one over the other. See [Release signing](docs/RELEASING.md). Security findings should identify the affected delivered version; this policy does not promise support for every Android/Magisk version or Motorola model.

[Nederlandse veiligheidsgids](docs/nl/SAFETY.md).
