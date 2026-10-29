# Build- en toesteltestrapport — 0.2.0-safe

**28 oktober 2026 · nl.moto.ota · versionCode 2**

De definitieve APK is daadwerkelijk gebouwd, geïnstalleerd en uitlezend getest op de aangesloten **Motorola Edge 70 Pro, XT2607-1 / merc / reteu, Android 17, build A171WMH.44-25**. **266 tests slagen: 161 core-JVM-tests, 98 Android-JVM-tests en 7 fysieke toesteltests. Geen fouten of overgeslagen tests.**

De zeven fysieke tests omvatten vier echte diagnose-/firmwarecontroles en drie Compose-UI-tests. De op de telefoon geïnstalleerde APK en de opgeleverde APK hebben aantoonbaar dezelfde SHA-256. Er is geen OTA geïnstalleerd, image teruggeschreven, Magisk gepatcht, slot gewijzigd of telefoon herstart.

## Definitieve bestanden

- `moto-ota-manager-debug.apk`: **26.658.417 bytes**.
- SHA-256: **50ebb65e43a1ad7b3f720902b72f7df86d97c3ef965d2dc6486fd8656ad585bc**.
- APK Signature Scheme v2: `apksigner verify` geslaagd.
- ARM64 inbegrepen; minSdk 33, compile/targetSdk 35. De echte runtime is SDK 37.
- `moto-ota-manager-source.zip`: actuele broncode, echte Gradle Wrapper, tests, documentatie, CI en geschoond bewijs.
- `SHA256SUMS.txt`: checksums van de definitieve APK en het bronarchief.

[Identieke geïnstalleerde/opgeleverde APK](../evidence/device-validation/installed-apk-verification.json) · [APK-handtekening](../evidence/device-validation/apk-signature.txt) · [Manifest](../evidence/device-validation/apk-badging.txt).

De actuele 0.2.0-bewijsbestanden staan onder **evidence/device-validation/**. De oudere bestanden rechtstreeks onder `evidence/` en [het historische 0.1.0-verslag](BUILD-REPORT-0.1.0.md) documenteren de eerdere ontwikkelfase; zij zijn niet het finale toestelbewijs voor 0.2.0.

## Build en geautomatiseerde tests

Toolchain: Temurin JDK 17.0.20.1+1, Gradle Wrapper 8.11.1, AGP 8.9.2, Kotlin/Compose compilerplugin 2.1.20, SDK Platform 35 en Build Tools 35.0.0. Wrapper en distributie zijn eerder met de officiële SHA-256 gecontroleerd. JDK/SDK/cache zijn taaklokaal; geen globale Java- of Android-instellingen zijn vervangen.

```text
gradlew.bat :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain --no-daemon
BUILD SUCCESSFUL in 55s
85 actionable tasks: 26 executed, 59 up-to-date
```

Voor de optionele echte firmwaretest is `MOTO_OTA_PATH` expliciet naar de al aanwezige historische OTA gezet. Die test was daadwerkelijk uitgevoerd, niet overgeslagen: de volledige ZIP-/payloadhashes en de OEM-fingerprintverschillen zijn met de echte 3,42 GB-ZIP gecontroleerd. Dezelfde test wordt in CI zonder eigen firmwarebestand overgeslagen; de overige synthetische gevallen blijven beschikbaar.

[Definitieve buildlog](../evidence/device-validation/build-final.log) · [Core JUnit XML](../evidence/device-validation/junit-core) · [Android-JVM JUnit XML](../evidence/device-validation/junit-appJvm).

| JVM-suite | Tests | Fouten |
|---|---:|---:|
| BackupValidatorTest | 15 | 0 |
| FirmwareDiscoveryTest | 12 | 0 |
| FirmwareValidatorTest, inclusief echte historische ZIP | 62 | 0 |
| PackageTransferTest | 8 | 0 |
| PrivacyTest | 4 | 0 |
| ReadinessTest | 14 | 0 |
| ReadOnlyMetadataTest | 13 | 0 |
| VerifiedStockCatalogTest | 5 | 0 |
| WorkflowTest | 28 | 0 |
| EngineProbeSafetyTest | 3 | 0 |
| FirmwareSecurityReviewTest | 5 | 0 |
| FirmwareSignatureProbeSafetyTest | 3 | 0 |
| InspectionMapperTest | 28 | 0 |
| MetadataObservationsTest | 13 | 0 |
| MotorolaMetadataParserTest | 14 | 0 |
| ObservationParsersTest | 25 | 0 |
| OfficialAppsTest | 7 | 0 |
| **JVM-totaal** | **259** | **0** |

### Fysieke Android-tests

De toestelselectie was steeds expliciet; het serienummer staat alleen in de private werkmap. Er is geen algemene installopdracht op een willekeurig ADB-target gebruikt. De app-APK en test-APK zijn met behoud van appdata geïnstalleerd/bijgewerkt. De gebruiker heeft de diagnose-app zelf Superuser-toegang verleend; de testcode heeft geen Magisk-policy aangepast.

```text
adb -s <expliciet-toestel> shell am instrument -w -r -e class nl.moto.ota.ReadOnlyDeviceValidationTest -e motoReadOnlyValidation yes nl.moto.ota.test/androidx.test.runner.AndroidJUnitRunner
Time: 17,692
OK (4 tests)

adb -s <expliciet-toestel> shell am instrument -w -r -e class nl.moto.ota.SafeUiTest nl.moto.ota.test/androidx.test.runner.AndroidJUnitRunner
Time: 8,496
OK (3 tests)
```

De vier echte uitleestests bewijzen samen:

1. De huidige SKU/codenaam/regio/build, root 31100, slots b/a, succesvolle boot, kernelbeveiligingsbron, Virtual A/B, snapshot `NONE`, live UpdateEngine `IDLE`, module-inventaris en geslaagde gereedheidscontroles.
2. De echte Magisk-gzip is een volledige 8 MiB-stock-`init_boot` van deze build, geauthenticeerd door OTA-manifest en toesteltrustanchor; de officiële updater resolveert expliciet naar Motorola.
3. De bestaande Motorola-downloadjob is alleen metadata voor de reeds geïnstalleerde build: `ALREADY_INSTALLED`, geen aangeboden OTA-download.
4. Androids daadwerkelijke `RecoverySystem.verifyPackage` accepteert de al aanwezige historische telefoon-ZIP tegen de toesteltruststore. Omvang en SHA-256 matchen exact het onafhankelijk geverifieerde pakket. De helper heeft geen bestand gestaged of gewijzigd en geen installatie-API aangeroepen.

De drie UI-tests bevestigen het uitlezende beginscherm, expliciete rootbevestiging met annuleren en de afzonderlijk gelabelde simulatie. De telefoon is na de demonstratie teruggebracht naar een echte, opnieuw uitgelezen diagnose.

[Vier uitleestests](../evidence/device-validation/physical-readonly-tests.log) · [Drie UI-tests](../evidence/device-validation/physical-ui-tests.log) · [Gecombineerde fysieke JUnit XML](../evidence/device-validation/junit-physical.xml) · [Echte Android-signature-uitkomst](../evidence/device-validation/platform-signature-verification.json).

## Gevonden en herstelde bugs

- Snapshottekst gebruikte echte lowercase-/hyphennamen en een andere HAL-samenvatting dan de oorspronkelijke parser verwachtte. Beide zijn gecorrigeerd met AOSP-bronnen en regressietests.
- `bootctl`, `snapshotctl` en `update_engine_client` ontbreken werkelijk op dit toestel. De app leest nu bestaande boot-/Virtual A/B-/snapshotmetadata, controleert formaat en CRC en vraagt de actuele enginecallback via uitsluitend `bind`/`unbind` in een begrensd rootproces op SDK 37. De geladen interfacebindings komen uit het draaiende framework; er worden geen Binder-nummers gegokt.
- Een gewone app kreeg een lege `ro.virtual_ab.enabled`; dezelfde allowlisted property is na rootverlening wel leesbaar. Lege waarden worden gecontroleerd opnieuw gelezen.
- Androids app-isolatie verbergt de private Motorola-prefs voor een rootproces dat de appnamespace erft. De twee vaste voorkeur-uitlezingen gebruiken nu het gedocumenteerde `su --target 0`, zonder permanente namespace-, permissie- of beleidswijziging.
- De werkelijke JSON-envelop en technische bestandsnaam `payload_metadata.bin` werden aanvankelijk gemist. Ze zijn nu getypeerd herkend; oude metadata wordt geen nieuwe OTA.
- `locked/green` in Android-properties werd los van `unlocked/orange` in de kernelbootconfig behandeld. De app toont het verschil en gebruikt de kernelbron voor de gemelde boottoestand.
- De huidige stockback-up krijgt nu alleen bij de exact geauditeerde build, fingerprint, inhoudshash en echte OTA-trustanchor een geverifieerde herkomst. Andere builds en aliases blijven geweigerd.
- De officiële updateactivity wordt system-only en met een expliciet Motorola-component geselecteerd. Werkelijke vendor-15/system-17-pakketmetadata wordt afzonderlijk getoond en blijft een compatibiliteitsreview vereisen.

De eerste fysieke testruns legden de property-/namespace-/bestandsnaamfouten bloot. [Eerste foutreproductie](../evidence/device-validation/debug-physical-01.log) · [Tweede foutreproductie](../evidence/device-validation/debug-physical-02.log). De finale zeven fysieke tests zijn groen.

## Controle op behoud van toestelstatus

De begin- en eindmeting bevestigen:

- SHA-256 onveranderd voor `boot_a`, `boot_b`, `init_boot_a`, `init_boot_b`, `vendor_boot_a`, `vendor_boot_b`.
- De gecomprimeerde stockback-up is ongewijzigd.
- De twaalf modules en hun aan/uit-/verwijdermarkeringen zijn identiek.
- Android-build, actieve slot, Magisk-versie, kernelbootbeveiliging en opstartsessie zijn gelijk gebleven: geen telefoonherstart.
- `/metadata/ota/state`, `ota_prefs.xml` en `dl_prefs.xml` hebben dezelfde hashes als bij aanvang.

[Volledige vergelijking en exacte meetomvang](../evidence/device-validation/non-mutation-audit.json). De controle claimt niet dat alle Android-achtergrondlogs of alle userdata van een draaiende telefoon bit voor bit stilstaan. Eigen app-/testinstallatie en app-private diagnosebestanden zijn de geautoriseerde testartefacten; er is niets van bestaande gebruikersgegevens gewist.

## Lint en resterende grenzen

Lint: **nul fouten, negen waarschuwingen**. Zes melden nieuwere vastgezette dependencies. Eén betreft conservatieve vrije-ruimtecontrole. Twee betreffen de bewust beperkte, SDK-37/root-only `ServiceManager`-reflectie en de vast begrensde bestaande Motorola-opslaglocatie. De actuele uitleesroute is fysiek getest; dit bewijst geen blijvende ondersteuning op toekomstige firmware. [Lint HTML](../evidence/device-validation/lint-results-debug.html) · [Lint XML](../evidence/device-validation/lint-results-debug.xml).

Deze validatie bewijst de bestaande uitlees- en verificatiefuncties op dit profiel. Een toekomstige OTA-installatie, Magisk-restore/-patch, herstart, Android 18, functionele modulecompatibiliteit, Wallet en Play Integrity zijn niet door een schrijfflow getest en worden niet automatisch uitgevoerd. De historische oorzaak van `ALREADY_UP_TO_DATE` blijft onbekend. De oorspronkelijke **bronhashmismatch zelf**, de latere succesvolle OTA/merge en de huidige stock17-back-up zijn wel onafhankelijk bewezen; zie [het forensische rapport](DEVICE-VALIDATION.md).

[Echte overzichtscreenshot](../evidence/device-validation/device-overview.png) · [Echte gereedheidsscreenshot](../evidence/device-validation/device-readiness.png) · [Geanonimiseerd app-rapport](../evidence/device-validation/device-diagnostic-report.txt) · [Machineleesbare resultaten](../evidence/device-validation/verification-summary.json).
