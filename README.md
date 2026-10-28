# Moto OTA Manager

Een echte Kotlin/Jetpack Compose-app voor het uitlezen en veilig begeleiden van officiële OTA-updates op **Motorola XT2607-1 / merc / reteu**. Material 3, lokale verwerking, beperkte root-allowlist, firmwarevalidatie en een duurzaam workflowjournaal.

**Deze release schrijft geen partities, installeert geen OTA, patcht geen Magisk en herstart niet.** Daarvoor zijn op dit toestel geen bewezen stabiele externe Motorola-/Magisk-interfaces vastgesteld. De app biedt de best onderbouwde veilige fallback: diagnostiek, pakketonderzoek en begeleiding naar de officiële apps. Een geslaagde build bewijst geen veilige OTA-installatie.

## Direct gebruiken

1. Installeer de geleverde `moto-ota-manager-debug.apk` handmatig via Android. De ontwikkelsessie installeert niets op je telefoon.
2. Open de app. De eerste controle leest alleen niet-geprivilegieerde eigenschappen.
3. Verleen voor uitgebreide diagnostiek vooraf toegang aan Moto OTA Manager in **Magisk → Superuser**. Magisk 31.1 heeft reactieve rootprompts verwijderd.
4. Kies **Controleer en bereid update voor → Start uitlezen**. Controleer vooral de actuele snapshotstatus; de historische merge uit de specificatie wordt nooit als afgerond aangenomen.
5. Open **Firmware** voor de officiële updater, een gevonden lokale Motorola-downloadverwijzing of een handmatig gekozen OTA-ZIP. Een gevonden verwijzing kan oud of verlopen zijn.
6. Onderzoek het pakket en kies afzonderlijk **Verifieer officiële ondertekening**. Die controle gebruikt `RecoverySystem.verifyPackage` en de echte `/system/etc/security/otacerts.zip` van het toestel.
7. Exporteer via **Diagnose** desgewenst een lokaal rapport. De veilige simulatie werkt volledig los van het echte workflowjournaal.

Lees vóór gebruik de [veiligheidsgrenzen](docs/SAFETY.md) en [toesteltestchecklist](docs/MANUAL-TESTS.md). Na terugkeer uit Magisk of Motorola kies je opnieuw uitlezen. De app bedient hun knoppen niet.

## Werkende implementaties en hun bewijsgrenzen

| Onderdeel | Implementatie | Nog te valideren op XT2607-1 |
|---|---|---|
| Toestelprofiel | Werkelijke getprops; exact model/SKU, codenaam, kanaal, build, fingerprints | Werkelijke propertynamen en onderlinge consistentie |
| Root/Magisk | Alleen op verzoek `su -c id`, `magisk -v/-V`; timeout en aparte stdout/stderr | Magisk 31.1-policy, SELinux en binarypaden |
| A/B en Virtual A/B | Slotproperties, uitlezende bootctl-probes, snapshotctl-dump en HAL-status | Beschikbaarheid/OEM-uitvoer; onbekend of conflict blokkeert |
| UpdateEngine | Korte, uitsluitend uitlezende `--follow`-statusobservatie | Callbacktoegang; er wordt geen verzonnen Android `--status` gebruikt |
| Magisk-back-ups | Begrensde gzip-uitlezing, grootte, header, SHA-256/SHA-1, directorycontrole | Werkelijk partitietype en actuele stock-herkomst blijven onbewezen |
| Lokale imagekluis | SAF-import in app-private opslag; strikte gzip/imagevalidatie | Geen automatische geauthenticeerde stockreferentie beschikbaar |
| Firmwaredetectie | Voorkeur voor officiële updater; read-only `downloadRequest.contentResource.wifiUrl` | Fragiele private metadata; geen publieke discovery-API vastgesteld |
| Download | Expliciet verzoek, HTTPS-hostallowlist, geen redirects, lengte/ruimte, atomair opslaan | Actuele geldige Motorola-link en servergedrag |
| OTA-validatie | ZIP, A/B-metadata, exact bronfingerprint/incremental, model/kanaal, CrAU-v2, payload- en metadatahash, datum/SPL | Whole-file signaturecheck werkt tegen toestelcertificaten; payload/installer beslist definitief |
| Workflow | Atomair journaal, procesherstel, onafhankelijke waarnemingen, rollback/root/mergecontrole | Echte updateketen is niet uitgevoerd |
| Simulatie | Synthetische demo en mocktests, permanent gelabeld | Levert nooit toestemming voor een echte update |

Een Magisk-back-up, een SHA-256 of de naam `boot.img.gz` bewijst **niet** dat dit de originele Android 17-`init_boot` is. Ook `cid50`, een URL-hostnaam of `release-keys` in een bestandsnaam bewijst geen geschikte officiële firmware. De historische Android 16-hash wordt nooit als huidige stockreferentie ingebouwd.

Zonder onafhankelijke actuele stockherkomst en kanaalbewijs blijft het echte voorbereidingsjournaal geblokkeerd. Deze release bevat bewust geen knop die een onbewezen image toch herstelbaar verklaart. De handmatige handleiding blijft beschikbaar.

## Reproduceerbaar bouwen

Benodigd: JDK 17, Android SDK Platform 35 en Build Tools 35.0.0. De repository bevat de echte Gradle Wrapper 8.11.1, AGP 8.9.2, Kotlin/Compose-plugin 2.1.20 en vastgezette dependencies. `minSdk=33`, `targetSdk=35`. Android 17 kan een app met dit target uitvoeren; dat is geen claim van platformbrede OTA-compatibiliteit.

Maak lokaal een genegeerde `local.properties` met bijvoorbeeld `sdk.dir=C:/Android/Sdk`, of stel `ANDROID_HOME` in.

```powershell
$env:JAVA_HOME = 'C:\pad\naar\jdk-17'
.\gradlew.bat :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

De app-APK verschijnt in `app/build/outputs/apk/debug/app-debug.apk`. JUnit-XML en HTML staan onder `core/build/test-results`, `core/build/reports/tests`, `app/build/test-results` en `app/build/reports`. De GitHub Actions-workflow voert deze controles opnieuw uit en bevat een aparte emulatorjob voor de veilige UI-tests.

De ontwikkelsessie gebruikt een geïsoleerde toolchain/cache onder de werkmap, zonder bestaande Android-projecten of globale Java-instellingen te wijzigen. De meegeleverde APK is debug-ondertekend; een openbare productie-uitgave vereist een eigen beheerde releasesleutel.

## Architectuur

```text
app/  Kotlin + Compose Material 3
  MotoViewModel           expliciete acties, waarnemingen en lokale toestand
  platform/DeviceInspector    getprop, accu, ruimte, beperkte rootprobes
  platform/ReadOnlyShell      vaste allowlist; geen vrije shellinvoer
  platform/ObservationParsers snapshot/engine/XML met fail-closed parsing
  platform/FirmwareFiles     SAF, HTTPS en toestelcertificaten
  platform/WorkflowStore     AtomicFile-journaal, geen simulatiepersistentie
core/ pure Kotlin JVM
  BackupValidator    gzip, v4 init_boot-header, exacte omvang en stockreferentie
  FirmwareValidator  ZIP/metadata, fingerprint, antirollback en hashes
  PackageTransfer    begrensde overdracht, fsync, atomische commit en cleanup
  FirmwareDiscovery  lokaal cachebewijs versus officieel aanbod/externe claim
  Readiness          actuele, consistente observaties en conservatieve blokkades
  Workflow           bewijsafhankelijke toestanden en herstel na proces/herstart
  Privacy            export op basis van een kleine veldallowlist
```

De app vraagt uitsluitend `INTERNET`; bestanden gaan via de Android-bestandskiezer. Geen `MANAGE_EXTERNAL_STORAGE`, verborgen UpdateEngine-reflectie, accessibility-automatisering, platformhandtekening of algemene shellconsole. Er is geen automatische telemetrie, firmware-scraping, notificatiepolling of upload.

## Documentatie

- [Concrete buildresultaten en testbewijs](docs/BUILD-REPORT.md)
- [Onderzoek en haalbaarheidsmatrix met primaire bronnen](docs/RESEARCH.md)
- [Alle twintig gevraagde testscenario's](docs/TEST-MATRIX.md)
- [Veiligheid](docs/SAFETY.md), [bekende beperkingen](docs/KNOWN-LIMITATIONS.md)
- [Compatibiliteit](docs/COMPATIBILITY.md), [Android 18-roadmap](docs/ROADMAP.md)
- [Handmatige toesteltestchecklist](docs/MANUAL-TESTS.md)

Licentievoorstel en oorspronkelijke projectcode: MIT. De Gradle Wrapper behoudt zijn eigen Apache-2.0-voorwaarden; Magisk-broncode wordt niet meegeleverd of uitgevoerd. Dit onafhankelijke project is niet uitgegeven of goedgekeurd door Motorola of Magisk.
