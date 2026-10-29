# Installatie en gebruik — 0.3.0

## APK installeren

Gebruik de debug-APK uit de oplevering en vergelijk de SHA-256 met [BUILD-REPORT](BUILD-REPORT.md). Open de APK op de telefoon met Androids pakketinstaller. De APK is de beheerapp, geen firmwarebestand. Bij een bestaande installatie van dezelfde debug-ondertekening kan Android de app bijwerken; verwijder de app niet om een ondertekeningsconflict te omzeilen zonder eerst lokale herstelback-ups te exporteren.

De app vraagt geen brede bestandsrechten en uploadt niets. Roottoegang beheer je zelf in Magisk → Superuser. Meldingen zijn optioneel en worden pas gevraagd wanneer je monitoring inschakelt of een download start. Weigeren van meldingen verleent nooit andere rechten.

## Eerste controle

1. Open Moto OTA Manager. De eerste controle vraagt geen root en beweert daardoor ook geen veilige updategereedheid.
2. Verleen de app toegang in Magisk als je de rootfuncties wilt gebruiken.
3. Kies **Controleer en bereid update voor** en bevestig de uitlezende diagnose.
4. Controleer het model/SKU, `merc`, `reteu`, actuele build, slot, Magisk, root, bootstatus, snapshots en back-upherkomst. Onbekend of tegenstrijdig blokkeert.
5. Bekijk **Diagnose** voor de afzonderlijke bronnen. `UNSAFE_INITIALIZER_DISABLED` bij de oude bootctl-probenamen is opzettelijk: de app start die tool niet, omdat diens initialisatie metadata kan wijzigen. De werkelijke slot-/bootcontroles gebruiken kernelgegevens en CRC-gecontroleerde metadata.

Na eenmaal expliciet toegestane rootdiagnostiek leest de app bij terugkeer naar het scherm opnieuw uit. Dit voert geen eerdere schrijfactie opnieuw uit. De monitoringkeuze staat hiervan los en is standaard uit.

## Normale officiële update

Open onder Firmware **Open systeemupdates** en laat Motorola controleren op aanbod. De app kan geen nieuwe serveraanbieding afdwingen. Een lokaal gevonden oude build is geen actuele aanbieding en een `payload_metadata.bin` is geen volledige OTA-ZIP.

Neem de werkelijk aangeboden doelbuild over onder **Workflow**. Een herkenbare lokale kandidaat kan de doelweergave vooraf invullen, maar bewijst geen beschikbaarheid. **Start begeleide updatecontrole** leest eerst opnieuw het toestel uit en bewaart pas daarna de verwachtingen.

Voor stockherstel moet **Automatische systeemupdates** in Ontwikkelaarsopties uit staan, zoals de officiële Magisk-procedure vereist. De app leest deze instelling en wijzigt haar niet. Voor kritieke acties gelden onder andere het exacte toestelprofiel, Android 17 / SDK 37, Magisk 31.1, minimaal 50% accu, voldoende ruimte, succesvolle huidige boot, actuele idle-engine en snapshot NONE.

Een bewezen huidige stock-image kan uit de gevalideerde Magisk-back-up of een zelf geïmporteerde image/gzip komen. Bestaan, bestandsnaam, gzip, header en SHA-1-directory zijn op zichzelf onvoldoende. Het volledige image moet overeenkomen met een onafhankelijk geauthenticeerde referentie voor de huidige build.

Kies **Maak stockherstelplan ter beoordeling**. Alleen voorbereiden schrijft nog geen partitie. Het plan toont de exacte huidige build, actieve init_boot, omvang en stock-SHA-256. Lees het plan en bevestig het schrijven apart, inclusief het niet-herstarten. Toestemming vervalt na 60 seconden en kan maar één keer worden gebruikt. De gepatchte huidige image wordt eerst volledig bewaard en gecontroleerd. De nieuwe live schrijffunctie is in deze ontwikkelfase niet op je telefoon uitgevoerd; zie de [veiligheidsgrenzen](SAFETY.md).

Als alternatief open je Magisk voor de officiële functie Images herstellen. Kies daarna **Controleer handmatig stockherstel**. De app meet de werkelijke actieve partitie; een handmatige melding alleen laat de workflow niet doorgaan.

**Herstart na stockherstel nog niet.** Open Motorola vanuit de workflow en laat de officiële OTA volledig installeren en optimaliseren. Keer terug naar Moto OTA Manager voor de actuele UpdateEngine-status. Pas bij waargenomen `UPDATED_NEED_REBOOT` biedt de workflow de volgende Magisk-stap aan.

Open Magisk en voer **Install to Inactive Slot (After OTA)** uit. Controleer doelslot, `init_boot` en het afgeronde resultaat in de Magisk-log. Meld dat in de workflow. Dit blijft vóór de nieuwe boot als handmatig gemeld weergegeven; de app heeft geen productieadapter die de target-image onafhankelijk als Magisk-gepatcht certificeert.

Beoordeel de herstart en open Motorola. De app herstart niet: je kiest zelf Motorola's herstartknop. Open na de herstart Moto OTA Manager opnieuw. Laat root, doelbuild, doelslot, succesvolle boot, module-inventaris, bootbeveiliging en het afronden van snapshot-merge daadwerkelijk controleren.

**Update bevestigd; nieuwe stockherkomst ontbreekt** betekent dat de update-/root-/mergecontroles zijn geslaagd, maar de volgende originele image nog niet onafhankelijk is geauthenticeerd. Start dan geen volgende OTA. Verifieer het officiële pakket voor die build en laat de nieuwe back-up toetsen tegen de ondertekende manifesthash. Wallet en de functionele werking van modules blijven afzonderlijke handmatige controles.

## Download, bestaand bestand en lokale fallback

Onder Firmware kun je een herkenbare volledige ZIP-verwijzing op de toegestane Motorola-host expliciet downloaden. Standaard wacht de download op een niet-gemeten netwerk. Gebruik de voortgangskaart voor pauze, hervatten of annuleren. Android bepaalt wanneer achtergrondwerk kan draaien. Een verlopen URL of gewijzigd serverbestand kan een nieuwe aanbieding vereisen; de app raadt geen tokens of endpoints.

Je kunt ook een officieel OTA-pakket via de bestandskiezer selecteren. De app kopieert het atomisch naar eigen opslag en onderzoekt de volledige bytes. **Verifieer officiële ondertekening** controleert de whole-file OTA-signature tegen de werkelijke toestelcertificaten. De verifieerde selectie wordt vastgezet zodat een nieuwe download haar niet stilzwijgend kan vervangen.

Na bevestigd stockherstel kan een gekoppeld geverifieerd pakket uitsluitend mét ondertekend `reteu`-kanaalbewijs en een eigen bevestiging worden klaargezet voor Motorola-beoordeling. Zonder bewijs stopt deze route met `STAGING_CHANNEL_UNPROVEN`, ook als de ondertekening en hashes geldig zijn. De historische ZIP heeft geen ondertekend kanaalveld en wordt via deze automatisering niet gepubliceerd. Gebruik dan Motorola's normale updateaanbod. Een hostnaam, cache of downloadreceipt vormt geen uitzondering.

Lees het bevestigingsscherm: de interne map is geen ondersteunde publieke installatie-API en Motorola kan een gepubliceerd bestand onmiddellijk herkennen. Er wordt geen installer rechtstreeks aangeroepen. De helper weigert ook onveilige bron-/doeltoestanden, ondertekeningsfouten, downgrade/wipe, kanaalconflicten en onbewezen atomaire publicatie. Een bestaande afwijkende bestemming wordt niet overschreven. Bij een niet ondersteunde kernel/FUSE-operatie stopt deze route. [Details en grenzen](MOTOROLA_STAGING.md).

Een kopieerreceipt bewijst alleen dat het pakket volledig en hashgelijk is klaargezet. Herkenning, installatie, Magisk en boot blijven afzonderlijk te controleren. Verschillende OEM-fingerprintnamespaces worden niet gelijkgesteld tot algemene installatiecompatibiliteit; de normale Motorola-route blijft de voorkeursroute.

## Onderbreking en herstel

Een opnieuw geopende app leest het journaal en de actuele toestelstatus. Zij herhaalt geen restore of bevestiging. Bij een onderbroken restore kies je **Lees onderbroken herstel uitsluitend terug**. Zowel de huidige partitie als de gepatchte herstelback-up moeten volledig overeenkomen voordat stockherstel bevestigd wordt.

Wanneer aantoonbaar nog helemaal niet naar de partitie is geschreven, kan de afzonderlijke actie **Controleer ongewijzigde bron voor een nieuw plan** een nieuwe voorbereiding vrijgeven. De oude bevestiging wordt ongeldig; een nieuwe poging krijgt een nieuw plan en nieuwe toestemming. Gedeeltelijk gewijzigde bytes worden nooit automatisch teruggeschreven. Bij twijfel niet herstarten en diagnostiek exporteren.

Onder Diagnose kun je de gepatchte herstelback-up exporteren. De app controleert eerst de volledige omvang en verwachte SHA-256 voordat de bestandskiezerbestemming wordt beschreven. Deze gepatchte image is geen nieuwe stock-image.

## Monitoring en simulatie

Monitoring staat standaard uit. Na opt-in wordt ongeveer elke zes uur bestaande lokale Motorola-metadata onderzocht, met batterijvoorwaarden en Android-planning. Het vraagt geen nieuwe OTA bij Motorola aan en downloadt of installeert niets. Meldingen verschijnen alleen bij nieuwe bruikbare informatie; herhaalde of reeds geïnstalleerde builds blijven stil. [Werking en privacy](MONITORING-AND-DOWNLOADS.md).

Voor veilig oefenen: Diagnose → **Bekijk veilige simulatie** → Workflow → **Start volledige updatesimulatie**. Doorloop de vijf vervolgstappen. Stockherstel gebruikt uitsluitend kleine reguliere appcachebestanden; OTA, Magisk, herstart en merge zijn synthetische observaties. Het echte updatejournaal wordt niet aangepast.

## Android Studio en commandline

Benodigd: JDK 17, SDK Platform 35, Build Tools 35.0.0, NDK 27.2.12479018 en CMake 3.22.1. De app ondersteunt arm64-v8a en x86_64. Gebruik de Gradle Wrapper; zet je lokale SDK-pad in een eigen `local.properties` of `ANDROID_HOME`.

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --console=plain
```

Windows: vervang `./gradlew` door `.\gradlew.bat`. De debug-APK staat onder `app/build/outputs/apk/debug/`. Gebruik voor instrumenttests een expliciet geselecteerde wegwerpemulator en raadpleeg de [testmatrix](TEST-MATRIX.md). Er wordt geen toestemming verondersteld om tests op een aangesloten echte telefoon te starten.

## Lokale gegevens

Images, pakketselectie, versleutelde downloadreferenties, receipts en het workflowjournaal staan in eigen opslag. Diagnostiek exporteer je alleen zelf via de bestandskiezer; bekijk het rapport vóór delen. Android-cloudback-up staat uit. App verwijderen of appdata wissen verwijdert ook de eigen back-ups en bewijskluis; exporteer belangrijke herstelbestanden eerst. Een pakket dat afzonderlijk naar Motorola is klaargezet valt buiten de eigen appopslag en wordt niet stilzwijgend door de app verwijderd.
