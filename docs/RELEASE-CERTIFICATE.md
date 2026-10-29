# Expected app release certificate — 1.0.0

This public identity is for the **Moto OTA Manager app APK**, package `nl.moto.ota`. It is separate from Motorola firmware certificates, Android Verified Boot keys and Magisk image provenance.

| Field | Expected release value |
|---|---|
| Subject | `CN=Moto OTA Manager Release` |
| Certificate SHA256 | `b8982eced488beec99ad33e0a87bf47f99e6d8b1a39f7d559ba86353bcb29bc6` |
| Public-key algorithm | RSA4096 |
| Certificate validity | 2026-10-29 through 2054-03-16 UTC |

Compare the exact delivered APK's signer independently, using Android SDK Build Tools:

```sh
apksigner verify --verbose --print-certs moto-ota-manager.apk
```

The expected identity above identifies the maintained release key. It does not claim that an arbitrary file is a release; verify the actual APK and delivery checksum. [BUILD-REPORT](BUILD-REPORT.md) records the delivered artifact, its signature and actual tests. An Android debug key is a different identity and cannot update interchangeably with this release key.

The private keystore and passwords are kept outside the repository and source archive. They are not bundled into the app, build evidence or downloadable deliverables. The maintainer must preserve them privately for future app updates. Only a public certificate/fingerprint belongs in a public release. [Signing procedure](RELEASING.md), [security policy](../SECURITY.md).

## Nederlands

Dit is de publieke ondertekeningsidentiteit van de **Moto OTA Manager-app**, niet van Motorola-firmware of AVB. Controleer de signer van de werkelijke APK met `apksigner verify --verbose --print-certs` en vergelijk de bovenstaande SHA256 en opleverchecksum. [Het buildrapport](BUILD-REPORT.md) beschrijft welk bestand werkelijk is gebouwd en gecontroleerd.

De private sleutel en wachtwoorden blijven buiten repository, bronarchief, app en bewijsbestanden. De beheerder moet ze zelf veilig bewaren voor toekomstige appupdates. Een oudere debugapp heeft een andere signer en kan niet als gewone update met deze release worden vervangen. [Nederlandse releasegids](nl/RELEASING.md).
