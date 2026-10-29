# Begeleide update en stockherstel

Dit document beschrijft de nieuwe `core.update`-workflow. Het bouwen en de offline tests bewijzen de softwarepaden; ze bewijzen niet dat de nieuwe schrijver al op een echte telefoon is uitgevoerd. Tijdens deze ontwikkelronde worden geen echte partities geschreven, OTA's geïnstalleerd, Magisk-installaties gestart of telefoons herstart.

## Wat werkelijk wordt uitgevoerd

- Diagnostiek leest toestel, Magisk, partities, bootmetadata, update-engine, snapshots en back-ups. Ontbrekende of tegenstrijdige informatie blijft onbekend en blokkeert schrijven.
- De app verifieert een stock-image tegen onafhankelijk geauthenticeerde firmwaregegevens. Een correcte header, bestandsnaam of Magisk-SHA1 alleen bewijst geen stockherkomst.
- `StockRestoreController` kan een expliciet beoordeeld stockherstel voorbereiden. De productiehelper is beperkt tot het onderzochte XT2607-1/merc/reteu-profiel, Android 17, SDK 37, Magisk 31.1/31100 en een init_boot-partitie van exact 8 MiB. Andere versies en formaten blokkeren.
- Motorola installeert de OTA via zijn officiële app. De officiële Magisk-app verzorgt de installatie op het inactieve slot. De gebruiker herstart via Motorola. Moto OTA Manager heeft geen API om een OTA toe te passen, slots te activeren of de telefoon te herstarten.
- Het app-private journaal bewaart de bronboot, bron- en doelbuild, verwachte slots en afzonderlijke bewijsniveaus. Een hervatte app leest en reconcilieert; hij start nooit zelfstandig een schrijver opnieuw.

## Volgorde op de telefoon

1. Geef de app vooraf Superuser-toegang in Magisk. Schakel automatische systeemupdates zelf uit in Ontwikkelaarsopties. De app verandert deze instelling niet; een onbekende waarde of `ota_disable_automatic_update != 1` blokkeert de productiehelper.
2. Voer een verse rootdiagnose uit. Vereist zijn onder andere een succesvolle huidige boot, ontgrendelde/oranje kernelbootstatus, snapshot `NONE`, update-engine `IDLE`, beide init_boot-partities, bekende modules, minimaal 50% accu en voldoende vrije opslag.
3. Selecteer de werkelijk aangeboden doelbuild uit Motorola, of gebruik een geauthenticeerd pakket. De officiële route vereist geen universele Android-ZIP-metadataovereenkomst: Motorola controleert zijn eigen updateketen. De gedeclareerde firmwarefingerprint en later gemeten systeemfingerprint blijven aparte velden.
4. Laat de app de huidige originele init_boot controleren. Een geïmporteerde `.img` of `.gz` wordt begrensd gelezen; gzip-envelope, CRC, volledige grootte, header en authenticiteitsreferentie moeten kloppen. Een oude Android 16-image wordt niet voor Android 17 gebruikt.

   De live helper koppelt die bytes daarnaast aan de werkelijk draaiende Magisk-boot: de exact één keer aanwezige `SHA1` uit `${magisk --path}/.magisk/config` moet gelijk zijn aan de opnieuw berekende SHA1 van de geauthenticeerde stock-image. Daarmee kan een legitieme build-/fingerprintspoof geen oudere, op zichzelf authentieke image laten doorgaan. De helper leest uitsluitend de bekende runtimepaden `/debug_ramdisk` of `/sbin`, met NOFOLLOW, rootbestand-/identiteitscontrole en een grens van 4096 bytes. Hij sourced geen config. Ontbrekend, dubbel, gewijzigd, onbekend of ongeldig formaat stopt de actie; een andere SHA1 geeft `MAGISK_BACKUP_ASSOCIATION_MISMATCH`. Deze gate wordt in beide live observe-rondes herhaald, ook na de volledige partitiebackup vlak vóór schrijven.
5. Beoordeel het herstelplan met de actieve slot, build, grootte en volledige stock-SHA256. Bevestig afzonderlijk de schrijfactie en de afspraak om niet tussentijds te herstarten. De bevestiging verloopt na 60 seconden; bereken een nieuw plan als het verlopen is.
6. De helper bewaart eerst de volledige huidige gepatchte image, controleert haar hash, verbruikt de bevestiging duurzaam en meet de veiligheidsvoorwaarden opnieuw. Hij schrijft uitsluitend de actuele `init_boot_a` of `init_boot_b`, synchroniseert en leest de volledige partitie terug. Alleen de verwachte stockhash geeft een positief resultaat.
7. Open Motorola en installeer de officiële OTA. Druk nog niet op Herstarten. De actuele UpdateEngine-status `UPDATED_NEED_REBOOT` moet de toepassing bevestigen.
8. Open de officiële Magisk-app: Installeren → Installeren naar inactieve slot na OTA. Controleer het verwachte doelslot en de voltooiingslog. Een aangevinkte gebruikersmelding blijft `USER_REPORTED`; de app stelt niet dat hiermee onafhankelijk een gepatchte image is bewezen.
9. Erken de herstart in Moto OTA Manager en voer hem zelf uit via Motorola. De app roept geen rebootfunctie aan.
10. Voer na de nieuwe boot opnieuw een rootdiagnose uit. Vereist zijn de doelbuild op de verwachte andere slot, een nieuwe boot-ID, daadwerkelijke root, Magisk 31.1, succesvolle boot, behouden module-inventaris, onveranderde kernelbootbeveiliging, snapshot `NONE` en engine `IDLE`. Wacht zolang de merge loopt.
11. De nieuwe stockback-up moet overeenkomen met de geauthenticeerde doel-image. Zonder dat bewijs kan de huidige update als waargenomen geslaagd eindigen met `COMPLETE_REVIEW`, maar blijft de volgende OTA geblokkeerd. Functionele modulecompatibiliteit en Wallet moeten apart worden gecontroleerd.

Als Motorola zelf al een volledige OTA-ZIP in zijn externe `Android/data/com.motorola.ccc.ota/files` heeft, kan de expliciete lokale importfunctie die lijst met maximaal twaalf bestanden tonen. Androids bestandskiezer hoeft die afgeschermde map niet te openen. De afzonderlijke UID0/SDK37-helper leest uitsluitend die bestaande bron; hij kopieert naar een unieke app-private stagingfile met bronidentiteit, volledige SHA256, fsync en readback. De app pauzeert downloads duurzaam en publiceert `selected-ota.zip` pas zelf onder de gedeelde selectielock. Een na appsluiting doorlopende rootkopie kan de selectie niet overschrijven. De oorspronkelijke Motorola-ZIP blijft ongewijzigd. Import is geen authenticiteitsbewijs en geen installatie: voer daarna de normale pakket- en handtekeningcontrole uit. Een oude reeds geïnstalleerde OTA wordt daardoor geen nieuwe update.

Een gedownload of geïmporteerd geauthenticeerd pakket met ondertekend bewezen reteu-kanaal kan optioneel via de afzonderlijke geavanceerde stagingroute naar Motorola's map worden gekopieerd. Die kopie is best effort; Motorola kan de ZIP meteen herkennen. De extra expliciete waarschuwing en bevestiging zijn daarom verplicht. Staging bewijst geen OEM-acceptatie of OTA-toepassing en is niet fysiek getest. De gewone officiële updaterroute blijft beschikbaar zonder staging. Zie [MOTOROLA_STAGING](MOTOROLA_STAGING.md).

## Bevestiging en transactiegrens

De app bewaart een willekeurige 32-byte HMAC-sleutel in zijn eigen private opslag. De roothelper kan deze sleutel lezen; hij probeert geen app-UID Android Keystore-sleutel als root te openen. Een bevestiging bindt de volledige immutable planning: transactienummer, workflow, uitvoeringstype, toestelprofiel, bronbuild, exacte systeemfingerprint, boot-ID, actieve slot, grootte, geauthenticeerde stockhash, volledige huidige partitiehash en zowel wallclock- als monotonic-termijnen.

Op Android komt de monotonic-tijd uit `SystemClock.elapsedRealtime()`. Een klokwijziging verlengt de toestemming niet. Er is geen geëxporteerde component die een bevestiging aanneemt. Alle paden zijn intern afgeleid; een Intent, bestandsnaam of geïmporteerd rapport kan geen partitiedoel opgeven.

De helper gebruikt uitsluitend een vaste eigen `app_process`-entrypoint. Hij controleert root-UID 0 en SDK 37; bindt en unbindt alleen de echte framework-UpdateEngine-interface; leest CRC-gecontroleerde boot-controlmetadata en Virtual A/B-metadata rechtstreeks; en controleert de bestaande snapshotstatus en lege snapshotdirectory. Hij roept geen `bootctl`-getter aan, omdat initialisatie van een boot-controlimplementatie metadata kan herstellen.

Dit zijn verse momentopnamen, geen atomair slot op Motorola's updater. Automatische updates uitschakelen vermindert de race maar maakt gelijktijdige externe systeemacties niet onmogelijk. Een I/O-stall kan ook de tien seconden reserve overschrijden; watchdog en fsync zijn geen garantie dat elke onderbroken write volledig eindigt. Daarom blijft read-only recovery vereist na een timeout en bestaat geen claim van risicoloos flashen.

Een globale exclusieve transactie-lock voorkomt gelijktijdige schrijvers. `consent.consumed` wordt met `O_EXCL` aangemaakt en zowel bestand als directory worden gesynchroniseerd vóór de eerste partitiewrite. Een lege marker betekent eveneens verbruikt. De app verwijdert de marker nooit automatisch. De helper heeft een eigen procesdeadline van 55 seconden en eist direct vóór schrijven nog minimaal tien seconden reserve. Een timeout wordt een onzekere transactie die alleen mag worden uitgelezen.

De HMAC is een grens tussen de gevalideerde app en zijn eigen helper. Dit is geen verdediging tegen een andere kwaadwillende rootgebruiker die beide processen, sleutel en opslag al kan wijzigen.

## Herstel na een onderbreking

| Waarneming | Reactie |
|---|---|
| App wordt gesloten of Android herstart de app | Laad het private journaal en lees opnieuw uit; voer geen bewaarde actie uit. |
| Writer is nog actief | Uitlezende recovery kan de exclusieve lock niet verkrijgen en bevestigt geen succes. |
| Writer werd onderbroken, actieve hash onbekend of afwijkend | `RECOVERY_REQUIRED`; niet herstarten en geen automatische replay of rollback. |
| Actieve stockhash klopt na onderbreking | Controleer ook de volledige bewaarde gepatchte image via de expliciete read-only recovery. Alleen de actieve hash is onvoldoende voor de backupgarantie. |
| Zowel actieve stockhash als bewaarde oorspronkelijke gepatchte hash kloppen, dezelfde bronboot/build/fingerprint/slot | Het herstelresultaat kan uitsluitend op grond van opnieuw gelezen bewijs worden bevestigd. De schrijver wordt niet opnieuw gestart. |
| Actieve partitie is nog exact de oorspronkelijke gepatchte image | Gebruik expliciet de controle voor nieuwe voorbereiding. Onder de writerlock worden bronidentiteit en alle gates opnieuw gelezen; een ontbrekende private backup wordt uit de ongewijzigde partitie vastgelegd. De oude toestemming wordt duurzaam ongeldig en uitsluitend daarna komt een nieuw plan met nieuwe UUID/toestemming vrij. Er wordt geen partitie geschreven. |
| Bronbuild/slot verschijnt na een nieuwe boot | Mogelijke rollback; geen succesclaim en geen automatische herstelactie. |
| Doelbuild/slot klopt, roottoestemming is geweigerd | Geef de toestemming en gebruik de expliciete nieuwe eindcontrole. Deze leest alleen; hij verbergt geen OTA- of mergefout. |
| Snapshot- of update-enginefout | Bewaar diagnostiek en onderzoek; geen reset, force-merge, automatisch terugschrijven of reboot. |
| Gebruiker meldt een mislukte Magisk-installatie vóór reboot | Herstart blijft geblokkeerd. Een expliciete nieuwe meting kan uitsluitend dezelfde bronboot/build/fingerprint/slot met root31100, engine `UPDATED_NEED_REBOOT` en snapshot `UNVERIFIED` opnieuw vrijgeven voor de handmatige officiële Magisk-stap. De app installeert zelf niets. |
| Leeg, corrupt of te groot bestaand journaal | `RECOVERY_REQUIRED`. Alleen een werkelijk ontbrekend journaal betekent `IDLE`; AtomicFile-back-ups worden hersteld. |

De volledige gepatchte bron-image staat in `files/update/transactions/<uuid>/patched-backup.img`. Deze wordt niet automatisch teruggeschreven. Het runbook schrijft geen universeel herstelcommando voor: de actuele boot-, slot- en snapshottoestand bepaalt welke handmatige herstelroute verantwoord is.

## Volledige offline simulatie

`SandboxUpdateSession` voert dezelfde duurzame faseovergangen en de echte stockhersteltransactie uit tegen gewone bestanden in een nieuwe directory met prefix `moto-ota-sandbox-`. De transacties maken een volledige gepatchte back-up, gebruiken de eenmalige HMAC-bevestiging en controleren het volledige teruggelezen bestand. De OTA schrijft een inactief fixturebestand; de Magisk-stap verandert fixturebytes; reboot/root/merge zijn expliciete mockobservaties.

`runAll()` of de afzonderlijke stappen produceren `partitions/init_boot_a`, `partitions/init_boot_b`, de transactieback-up, `new-stock-backup.img` en `update-run.properties`. Iedere toestelobservatie en ieder journaal houdt `simulated=true`. Een bestaande sandbox wordt niet stilzwijgend opnieuw gestart. De sandboxbackend weigert echte toestelobservaties en live plannen; de productiehelper weigert sandboxplannen. Deze simulatie bewijst bestandstransacties en workflowgedrag, niet dat een bootimage op echte hardware opstart.

## Magisk-onderbouwing en beperking

De onderzochte [officiële Magisk OTA-instructie](https://topjohnwu.github.io/Magisk/ota.html) beschrijft stockherstel, OTA zonder reboot en installatie naar het inactieve slot. De documentatie vermeldt dat de beschreven aanpak op Pixel is getest. Motorola-compatibiliteit mag daarom niet op basis van die tekst alleen worden aangenomen.

De onderzochte Magisk 31.1-bron is vastgezet op commit `0972f67d97f9b6302b67c5171283330c74114b4a`. [`util_functions.sh`](https://github.com/topjohnwu/Magisk/blob/0972f67d97f9b6302b67c5171283330c74114b4a/scripts/util_functions.sh), [`app_functions.sh`](https://github.com/topjohnwu/Magisk/blob/0972f67d97f9b6302b67c5171283330c74114b4a/scripts/app_functions.sh) en de [officiële installer](https://github.com/topjohnwu/Magisk/blob/0972f67d97f9b6302b67c5171283330c74114b4a/app/src/main/java/com/topjohnwu/magisk/core/tasks/MagiskInstaller.kt) bevatten meer mutaties dan alleen een init_boot-write: onder andere omgevingsherstel, migraties, slotactivering en post-OTA-scripts. De app sourced deze scripts niet en verzint geen Magisk-CLI voor installatie naar de inactieve slot.

De [officiële v31.1-release](https://github.com/topjohnwu/Magisk/releases/tag/v31.1) verwijst naar diezelfde commit. [`boot_patch.sh`](https://github.com/topjohnwu/Magisk/blob/0972f67d97f9b6302b67c5171283330c74114b4a/scripts/boot_patch.sh) schrijft de oorspronkelijke image-SHA1 als een regel `SHA1=…` in de ramdiskconfig. [`MagiskInit::setup_tmp`](https://github.com/topjohnwu/Magisk/blob/0972f67d97f9b6302b67c5171283330c74114b4a/native/src/init/mount.cpp) kopieert die naar `.magisk/config`; [`--path`](https://github.com/topjohnwu/Magisk/blob/0972f67d97f9b6302b67c5171283330c74114b4a/native/src/core/magisk.rs) rapporteert de tmpfs-root. De officiële `restore_imgs` selecteert met precies deze runtime-SHA1 de backupdirectory. De app gebruikt dezelfde associatie uitsluitend als extra bewijs bovenop de onafhankelijke stockauthenticiteit.

`TargetMagiskProof` is een getest contract voor een eventuele latere productieprobe. Zo'n probe ontbreekt momenteel. Het contract vereist vooraf onafhankelijk geauthenticeerde doel-stock-SHA1 in de workflow; een SHA1 uit het bewijsobject zelf volstaat niet. De huidige productieflow vult dit veld niet en blijft vóór boot bij `USER_REPORTED`. De gemeten root na doelboot is een apart bewijsniveau.

## Nog op echte hardware te toetsen

- De nieuwe constrained stockwriter, inclusief SELinux-toegang, directory-fsync, sector-I/O en procesonderbreking. De offline tests mogen niet als fysieke validatie worden gerapporteerd.
- Een werkelijk toekomstige Motorola-OTA met de huidige app, de officiële Magisk-handoff en de uiteindelijke snapshot-merge.
- Eventueel onafhankelijk preboot-Magisk-imagebewijs. Een gebruikersmelding of fixture is daarvoor onvoldoende.
- Functionele werking van modules en Wallet na die update.

De repository-buildlogs en testrapporten bepalen wat softwarematig is geverifieerd. Bovenstaande grenzen bepalen wat nog geen praktijktest heeft gehad.
