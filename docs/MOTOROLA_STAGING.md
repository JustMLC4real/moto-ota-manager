# Officiële OTA aanbieden via Motorola's interne bestandenmap

Deze optionele route kopieert na een afzonderlijke bevestiging uitsluitend een geverifieerde ZIP met bewezen ondertekend `reteu`-kanaal naar exact:

`/storage/emulated/0/Android/data/com.motorola.ccc.ota/files/moto-ota-<volledige-sha256>.zip`

Dit is een **fragiele interne folderintegratie**, gebaseerd op de eerder handmatig aangetoonde toestelprocedure. Er is geen gedocumenteerde Motorola-import-API of garantie dat een toekomstige updater deze bestandsnaam herkent. Een ontvangstbewijs bewijst uitsluitend de teruggelezen bestandsbytes. De app roept geen installatie-API aan en schrijft via deze route geen partities, instellingen of slots; de app herstart het toestel niet.

Motorola kan een compleet gepubliceerd bestand onmiddellijk opmerken. Daarom moet de gebruiker zowel de concrete kopieeractie als deze mogelijke automatische herkenning afzonderlijk erkennen. `ota_disable_automatic_update=1` is een verplichte gemeten voorzorg; deze Android-instelling is geen bewijs dat alle interne Motorola-reacties uitgeschakeld zijn. Een installatiebeslissing blijft bij de officiële updater en de gebruiker.

## Invoer en actuele voorwaarden

Alleen een app-private pin `verified-packages/<UUID>.zip` wordt geaccepteerd. URL's, SAF-documenten, shellcommando's, uitvoerpaden en willekeurige partities kunnen niet als staging-invoer worden opgegeven. Een eerdere UI-claim `signatureVerified=true` volstaat niet: de geïsoleerde roothelper inspecteert de ZIP opnieuw, controleert de payloadhash en verifieert de volledige OTA-ondertekening met `RecoverySystem.verifyPackage` tegen de actuele `/system/etc/security/otacerts.zip`. De toestelcertificaten en volledige pakket-SHA-256 moeten tijdens de handeling stabiel blijven.

De helper controleert onafhankelijk de huidige root-/Magisk- en toestelgegevens, kernel-bootconfig, succesvolle A/B-bronboot, Virtual A/B-metadata, lege snapshotdirectory en een verse read-only update_engine-IDLE-callback. Toegestaan is uitsluitend XT2607-1 / merc / reteu, Android 17 / SDK 37, Magisk-code 31100, een ontgrendelde bootloader en minstens 50% accu. Onbekende status blokkeert de kopie. Het toestel moet in dezelfde boot-ID, slot, build en volledige waargenomen fingerprint van de lopende workflow blijven.

De actieve init_boot wordt uitsluitend gelezen en volledig gehasht. Die hash moet overeenkomen met de reeds geauthenticeerde huidige stock-image van de workflow. Alleen `OBSERVED` of `CRYPTOGRAPHIC_IMAGE` geldt als voorafgaand stockbewijs; een handmatige melding geldt niet. De workflow moet `STOCK_RESTORED` of `AWAITING_OFFICIAL_OTA` zijn. Het duurzame workflowjournaal wordt direct vóór publicatie opnieuw gelezen en aan bronidentiteit, stage, route, stockbewijs, pakket en doel gekoppeld.

Het ondertekende `pre-device` moet `merc` bevatten. Pakket-SHA, doelbuild en gedeclareerde doelfingerprint moeten exact bij de workflow horen. Wipe-, downgrade-, kanaalconflict-, teruggaande Android-, tijdstempel- of patchclaims blokkeren. Ontbrekende tijdstempel of patchinformatie blokkeert eveneens.

## Ondertekend kanaalbewijs is verplicht

Vóór een ZIP in Motorola's directory verschijnt, moeten de opnieuw geïnspecteerde, volledig ondertekende metadata expliciet `motorola-channel=reteu` of `pre-channel=reteu` bevatten. Als beide velden aanwezig zijn, moeten ze exact overeenkomen. Daarnaast moet de verse pakketinspectie `regionVerified=true` opleveren; een los booleanlabel of een niet-ondertekende toestel-/cachewaarde is geen vervanging voor deze velden. Deze eis geldt ook voor `OFFICIAL_MOTOROLA`.

- Ontbrekend, leeg of onbewezen signed kanaal: `STAGING_CHANNEL_UNPROVEN`; er wordt geen ZIP gepubliceerd.
- Expliciet ander of tegenstrijdig signed kanaal: `STAGING_METADATA_MISMATCH`; er is geen override.
- Een gebruikerswaarschuwing, correcte actuele `reteu`-property, vertrouwde HTTPS-host, downloadreceipt, bestandsnaam of eerdere handmatige succesvolle installatie heft deze blokkade niet op.

Deze grens volgt uit het onderscheid tussen authenticiteit en toewijzing. De [AOSP-implementatie van RecoverySystem.verifyPackage](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/RecoverySystem.java) vergelijkt de pakketsleutel met de toegestane OTA-sleutels en verifieert de ondertekende bestandsbytes. Zij controleert geen Motorola-firmwarekanaal. Android ondersteunt bovendien [één OTA voor meerdere SKU's](https://source.android.com/docs/core/ota/tools#build-ota-packages-for-multiple-skus); een passende codenaam of fingerprint op zichzelf bewijst daarom geen specifieke regiotoewijzing.

Voor deze private lokale folderroute is geen aantoonbaar Motorola-contract vastgesteld dat vóór mogelijke verwerking opnieuw het kanaal afdwingt. Ook de huidige lokale metadata en app-downloadreceipts bevatten geen onafhankelijk geauthenticeerde binding tussen precies dit pakket, het kanaal en een officiële serveraanbieding aan dit toestel. Omdat publicatie de updater kan activeren, mag deze ontbrekende controle niet aan een onbewezen latere OEM-afwijzing worden gedelegeerd. Er is in deze versie geen provenance-uitzondering.

De historische officiële ZIP kan volledig authentiek zijn en toch geen voor de app aantoonbaar ondertekend kanaal bevatten. Zo'n ZIP blijft leesbaar, importeerbaar en inspecteerbaar, maar wordt niet door deze functie klaargezet. De veilige fallback is Motorola's normale updatecontrole openen, zodat zijn eigen aanbodroute de selectie verzorgt. De eerdere handmatige successituatie is geen algemene veiligheidsautorisatie voor andere pakketten.

## Gescheiden Motorola-namespaces

Motorola kan verschillende productnamen/fingerprints gebruiken in standaard Android-metadata, payloadproperties en actuele systeemproperties. De app herschrijft deze namen niet en zet `compatible` of `regionVerified` nooit alsnog op true.

Voor de kopie naar **officiële Motorola-beoordeling** geldt:

1. Signed payload `SOURCE_FP` moet Android-versie, build-id en incremental exact aan actuele, afzonderlijk gelezen systeemwaarden binden.
2. Als die volledige fingerprint afwijkt van de actuele `ro.build.fingerprint`, moet de signed standaard Android `pre-build` bovendien exact voorkomen in de actuele `ro.vendor.build.fingerprint` of `ro.system.build.fingerprint`.
3. Signed payload `TARGET_FP` blijft een afzonderlijke, exacte doelclaim van het pakket en de workflow. Het wordt niet gelijkgesteld aan een standaard Android-fingerprint in een andere namespace.
4. Zonder onafhankelijke volledige propertymatch volgt `STAGING_METADATA_MISMATCH`. Er wordt geen productalias geraden.
5. Ondertekend `reteu`-kanaalbewijs blijft verplicht; de naamruimtebeoordeling geeft geen kanaaluitzondering. Een nieuw succesvol ontvangstbewijs vermeldt `signedChannelProven=true` en altijd `oemReviewRequired=true`.

Deze voorwaarden binden uitsluitend de aangeboden kopie aan waargenomen brongegevens, bewezen kanaal en de expliciet geselecteerde OTA. Ze bewijzen geen algemene Android-installatiecompatibiliteit. De generieke `AUTHENTICATED_PACKAGE`-route blijft afhankelijk van alle oorspronkelijke compatibiliteits- en kanaalcontroles. Motorola moet de resterende installatievoorwaarden zelf beoordelen; onbekende regio blijft vóór folderpublicatie een blokkade.

## Atomische publicatie zonder overschrijven

De helper schrijft eerst buiten Motorola's bewaakte directory naar een unieke `.part` onder de eigen externe appmap. Er moet ruimte zijn voor het volledige pakket plus 256 MiB reserve. Daarna volgen fsync, volledige SHA-256 van de tijdelijke kopie, opnieuw de volledige bronhash en verse toestel-/workflowcontroles.

Een kleine JNI-binding opent uitsluitend vaste directories component voor component met `O_DIRECTORY|O_NOFOLLOW`. De bronnaam moet een UUID-partnaam zijn, de bestemming uitsluitend de vaste SHA-256-bestandsnaam. De binding controleert een regulier bronbestand, grootte en hetzelfde bestandssysteem, fsynct bestand en directories en publiceert met `renameat2(..., RENAME_NOREPLACE)`. Zij accepteert geen algemene syscall, willekeurig pad, partitie of vervangingsvlag.

Een bestaande bestemming wordt nooit vervangen. Een reeds aanwezige, volledig hashgelijke ZIP kan na teruglezing een `alreadyPresent`-bewijs opleveren. Een afwijkend bestand of een concurrent aangemaakte bestemming geeft `STAGING_DESTINATION_EXISTS`. Onbeschikbare kernel-/FUSE-ondersteuning geeft `STAGING_ATOMIC_PUBLISH_UNSUPPORTED`; er is geen copy-, hardlink- of gewone rename-fallback. De veilige fallback is de normale officiële Motorola-updatecontrole.

Bij een fout ná atomische publicatie maar vóór bewezen directorydurability volgt `STAGING_DURABILITY_UNPROVEN`. Het mogelijk reeds door Motorola gelezen bestand wordt niet automatisch verwijderd of vervangen. Er wordt geen succesbewijs verzonnen. Een nieuw, expliciet bevestigd verzoek kan hetzelfde bestand veilig opnieuw lezen en bewijzen.

## Onderbreking en herstel

Een verzoek is app-private, maximaal een uur geldig en wordt met een exclusieve consumed-marker eenmalig gemaakt. De app schrijft tijdens het wachten een heartbeat. De roothelper controleert daarnaast het oorspronkelijke app-PID, proces-starttijd, duurzame cancelmarker en een eigen monotone deadline. Een afzonderlijke watchdog beëindigt uitsluitend zijn eigen app_process wanneer de client verdwijnt, de heartbeat oud wordt of annulering/deadline optreedt. Direct vóór publicatie worden deze voorwaarden nogmaals gecontroleerd. De app start geen staging automatisch bij openen, reboot of journaalherstel.

Een succesvolle volledige teruglezing levert een app-private `<job>.receipt.json` op. Crash na publicatie maar vóór dit bewijs blijft onzekere aflevering: voer geen installatie of automatische herhaling uit op basis van het ontbreken van een fout. Een nieuwe expliciete kopieerbevestiging kan de al bestaande hashgelijke ZIP teruglezen. Achtergebleven tijdelijke `.part`-bestanden staan uitsluitend in de eigen appmap, buiten Motorola's scanlocatie.

## Testgrenzen

JVM-tests oefenen het beleid en echte bestandsoverdrachten met een atomische no-clobber-publicatie uit. Kanaalregressies bewijzen afzonderlijk dat ontbrekend bewijs ook bij de officiële route en expliciete waarschuwingserkenning blokkeert, dat een los `regionVerified`-label of kanaaltekst onvoldoende is, dat conflicten niet worden overschreven en dat alleen consistente ondertekende `reteu`-declaraties met verificatie doorgaan. Emulatorinstrumentatie oefent afwijzing van gewone app-UID/onjuiste SDK/ontbrekende bevestiging en de echte native `renameat2` plus no-clobber uit in een exclusieve app-cache-sandbox. De sandbox-entry werkt alleen voor een niet-root qemu-app en een vaste `staging-native-test-<UUID>`-cachemap; de productie-entry blijft root-only en gebruikt de vaste externe directories.

De werkelijke SDK37-roothelper, Motorola-certificaatketen, FUSE-directorytoegang, herkenning door de updater en OTA-installatie zijn daarmee **niet op het echte toestel gevalideerd**. Geen ontwikkelingstest mag daarvoor stilzwijgend een echte telefoon gebruiken.

## Bronnen

- [Android RecoverySystem.verifyPackage](https://developer.android.com/reference/android/os/RecoverySystem#verifyPackage(java.io.File,android.os.RecoverySystem.ProgressListener,java.io.File)) — volledige ZIP-signatuurcontrole tegen opgegeven OTA-truststore.
- [AOSP RecoverySystem-bron](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/RecoverySystem.java) — vertrouwde sleutel en ondertekende bytes zijn geen Motorola-kanaalcontrole.
- [AOSP OTA voor meerdere SKU's](https://source.android.com/docs/core/ota/tools#build-ota-packages-for-multiple-skus) — device-/fingerprintvoorwaarden zijn geen zelfstandig regiobewijs.
- [AOSP MediaProvider FuseDaemon.cpp](https://android.googlesource.com/platform/packages/providers/MediaProvider/+/refs/heads/main/jni/FuseDaemon.cpp) — FUSE rename accepteert RENAME_NOREPLACE; de hardlink-operatie is niet geïmplementeerd.
- [AOSP bionic renameat2-header](https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/include/stdio.h) — Android native renameat2-declaratie.
- [Java Files.move](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/file/Files.html#move(java.nio.file.Path,java.nio.file.Path,java.nio.file.CopyOption...)) — ATOMIC_MOVE alleen geeft geen algemene no-replace-garantie.
