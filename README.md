# Moto OTA Manager

Een echte Kotlin/Jetpack Compose Android-app voor **Motorola Edge 70 Pro, XT2607-1 / merc / reteu**. Versie **0.3.0** combineert toestelonderzoek, firmwarecontrole, hervatbare downloads, optionele meldingen en een duurzame begeleide updateworkflow.

De normale route gebruikt Motorola voor de officiële OTA, Magisk 31.1 voor installatie op de inactieve slot en Motorola voor de door jou gekozen herstart. De app kan de actuele originele `init_boot` onder strikte voorwaarden voorbereiden en herstellen. Een nieuwe schrijfactie vereist altijd een afzonderlijk herstelplan en expliciete bevestiging.

**Een geslaagde APK-build bewijst geen veilige toekomstige OTA.** De uitlezende functies zijn eerder op het echte toestel onderzocht; de nieuwe herstel-, staging- en achtergrondfuncties worden in deze uitgave zonder telefoon gevalideerd. [Bewijs en grenzen](docs/BUILD-REPORT.md), [historische toestelvalidatie](docs/DEVICE-VALIDATION.md).

## Wat de app doet

| Onderdeel | Gedrag |
|---|---|
| Toestel en root | Leest het werkelijke model/SKU, kanaal, build, fingerprints, accu, opslag, root, Magisk en module-inventaris. Onbekend betekent niet veilig. |
| A/B en snapshots | Vergelijkt slotbronnen, controleert CRC-bootmetadata, snapshotmetadata en read-only UpdateEngine-callbacks. Een actieve of onbekende merge blokkeert een nieuwe voorbereiding. |
| Stock-image | Valideert exacte grootte, Android v4 `init_boot`, gzip/CRC, SHA-1, SHA-256 en onafhankelijk bewezen actuele firmwareherkomst. Vóór schrijven moet ook de runtime-SHA1 van Magisk overeenkomen. Oude Android 16-images worden niet voor Android 17 hergebruikt. |
| Firmware | Onderzoekt ZIP, A/B-metadata, bron/target, payloadheader en hashes. Whole-file ondertekening wordt afzonderlijk tegen de actuele toestelcertificaten gecontroleerd. |
| Downloads | Downloadt alleen een expliciet gekozen kandidaat van de toegestane Motorola-HTTPS-host. Ondersteunt pauze, hervatten en annuleren; onvolledige bytes worden nooit als voltooid pakket gepubliceerd. |
| Meldingen | Standaard uit. Controleert na opt-in bestaande lokale Motorola-metadata, met deduplicatie en een lokale geschiedenis. Dit is geen eigen Motorola-servercheck. |
| Updatebegeleiding | Bewaart verwachtingen en werkelijk gemeten voortgang apart. Na procesdood of herstart wordt geen schrijver opnieuw uitgevoerd. OTA, Magisk en herstart blijven afzonderlijke stappen. |
| Volgende stockreferentie | Bewaart `init_boot`-hashes uit een ondertekend payloadmanifest. Koppelt deze pas aan de werkelijk opgestarte doelbuild; de nieuwe back-up moet vervolgens volledig overeenkomen. |
| Diagnose | Exporteert uitsluitend toegestane technische velden via de bestandskiezer. Geen automatische uploads, serienummers, IMEI, module-ID's, boot-ID's of ondertekende download-URL's. |
| Simulatie | Doorloopt de hele workflow met kleine app-private testbestanden. OTA, Magisk, herstart en merge blijven uitdrukkelijk gesimuleerd. |

## Veilige route op de telefoon

1. Geef de app roottoegang in Magisk en kies **Controleer en bereid update voor**.
2. Controleer het aanbod in Motorola-systeemupdates. Open **Workflow** en bevestig de aangeboden doelbuild.
3. Laat een herstelplan maken. Alleen een aantoonbaar juiste stock-image en actuele veilige toesteltoestand laten dit toe. Lees het plan en bevestig het schrijven afzonderlijk. Een alternatief is de officiële Magisk-functie Images herstellen; daarna meet de app de partitie opnieuw.
4. **Herstart nog niet.** Laat Motorola de officiële OTA installeren en optimaliseren.
5. Na waargenomen OTA-voltooiing: gebruik Magisk → Installeren → **Install to Inactive Slot (After OTA)**. Controleer de doelslot en het resultaat in de Magisk-log.
6. Bevestig de begeleiding en kies de herstart zelf in Motorola. De app herstart niet.
7. Open Moto OTA Manager opnieuw voor de echte nieuwe-slot-, build-, root-, boot-, module-, beveiligings- en mergecontroles.

De volledige handleiding en gecontroleerde teststappen staan in [INSTALLATION](docs/INSTALLATION.md), [UPDATE-WORKFLOW](docs/UPDATE-WORKFLOW.md), [SAFETY](docs/SAFETY.md) en [MANUAL-TESTS](docs/MANUAL-TESTS.md).

## Grenzen van de automatisering

Er is geen aangetoonde publieke Motorola-firmwarecatalogus of ondersteunde servercheck-API voor dit profiel. De app gebruikt de officiële updater, herkenbare lokale metadata en optionele bestandsimport. Een metadataresource is geen volledige OTA-ZIP; een oude verwijzing naar een al geïnstalleerde build is geen nieuw aanbod. Zonder betrouwbare kandidaat wordt niets willekeurig gedownload.

Magisk biedt geen stabiele externe API voor de volledige OTA-procedure. De app start geen verzonnen Magisk-commando's en voert geen interne `restore_imgs`/`post_ota`-scripts uit. De inactieve-slotstap wordt door de gebruiker in Magisk uitgevoerd. Een handmatige melding wordt vóór de nieuwe boot niet als onafhankelijk imagebewijs weergegeven.

Motorola kan verschillende system-/vendorproductnamen in pakketmetadata gebruiken. De validator bewaart die verschillen en versoepelt de algemene installatievoorwaarden niet. De officiële begeleide route laat Motorola zijn eigen normale updateaanbod beoordelen. Lokale staging vereist daarnaast ondertekend `reteu`-kanaalbewijs; zonder dat bewijs stopt deze interne route met `STAGING_CHANNEL_UNPROVEN`. De historische ZIP zonder kanaalveld wordt dus niet automatisch gepubliceerd. Een geldige ondertekenaar, host of downloadreceipt bewijst geen regio. Klaarzetten blijft afzonderlijk bevestigd en bewijst geen herkenning of installatie.

De app ondersteunt uitsluitend het onderzochte Android 17 / Magisk 31.1-profiel voor kritieke acties. Android 18 en andere Magisk-versies vereisen herbeoordeling. Zie [COMPATIBILITY](docs/COMPATIBILITY.md), [KNOWN-LIMITATIONS](docs/KNOWN-LIMITATIONS.md), [RESEARCH](docs/RESEARCH.md) en [ROADMAP](docs/ROADMAP.md).

## Bouwen en testen

Benodigd: JDK 17, Android SDK Platform 35, Build Tools 35.0.0, NDK **27.2.12479018** en CMake **3.22.1**. Gebruik de meegeleverde Gradle Wrapper 8.11.1. Open deze map rechtstreeks in Android Studio; de lokale SDK-locatie hoort in een eigen, niet ingecheckte `local.properties`.

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --console=plain
```

Op Windows gebruik je `gradlew.bat`. De echte debug-APK verschijnt in `app/build/outputs/apk/debug/app-debug.apk`. Testresultaten staan onder `core/build/test-results` en `app/build/test-results`; HTML-rapporten onder beide `build/reports`-mappen.

Voer instrumenttests uitsluitend op een expliciet gekozen wegwerpemulator uit:

```sh
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest --no-daemon
```

De fysieke validatieklasse draait uitsluitend na het expliciete runnerargument `motoReadOnlyValidation=yes`; zonder dat argument wordt zij overgeslagen. Met opt-in moet het exacte profiel werkelijk overeenkomen. Tests met private historische firmware slaan zichzelf zonder die optionele fixture over. JVM-tests gebruiken mocks voor niet-beschikbare toesteldiensten; de volledige sandbox schrijft alleen reguliere testbestanden. [Testmatrix](docs/TEST-MATRIX.md).

De [GitHub Actions-workflow](.github/workflows/android.yml) bouwt en test opnieuw en heeft een aparte emulatorjob. De lokaal geleverde APK is debug-ondertekend. Publiceer een productie-uitgave met je eigen beheerde releasesleutel; er wordt geen sleutel meegeleverd of automatisch naar GitHub gepubliceerd.

## Projectindeling

- `app/`: Compose Material 3, expliciete gebruikersacties, toestelprobes, app-private opslag, WorkManager, beperkte roothelpers en de native no-clobber-publicatiebinding.
- `core/`: pure Kotlin-validatie, downloadengine, monitorbeleid, ondertekende stockreferenties en duurzame update-/hersteltoestanden.
- `docs/`: onderzoek, architectuur, handleidingen, compatibiliteit, veiligheid en build-/testbewijs.
- `evidence/`: concrete lokale buildlogs en afzonderlijk gelabeld historisch toestelbewijs.

MIT; zie [LICENSE](LICENSE) en [NOTICE](NOTICE) voor meegeleverde AOSP-interfacebestanden en andere bronvermeldingen. Er wordt geen Motorola-firmware of Magisk-binary herverdeeld.
