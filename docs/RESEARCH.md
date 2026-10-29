# Technisch onderzoek en haalbaarheid

Onderzocht op 28–29 oktober 2026. Dit onderzoek gebruikt officiële Magisk-, AOSP-, Android Developers- en Motorola-bronnen. De eerste onderzoeksfase gebruikte de aangeleverde projectspecificatie. In de 0.2.0-fase zijn de telefoon, bewaarde logs en firmware zelfstandig uitlezend gevalideerd. De 0.3.0-fase ontwikkelt verder zonder telefooninteracties.

**Aanvulling 0.2.0:** de oorspronkelijke tekst hieronder documenteert het onderzoek vóór hardwarevalidatie. De telefoon is inmiddels daadwerkelijk uitlezend onderzocht en de APK is fysiek getest. De ontbrekende binaries, feitelijke statusformats en app-isolatie leidden tot aanvullende veilige adapters. De historische bronhashfout is in bewaarde logs teruggevonden; de actuele stock17-back-up is onafhankelijk geauthenticeerd. Ook Androids echte whole-file-verificatie is positief op de bestaande telefoon-ZIP uitgevoerd. De actuele conclusies en grenzen staan in [DEVICE-VALIDATION.md](DEVICE-VALIDATION.md) en [BUILD-REPORT.md](BUILD-REPORT.md); zij vervangen de eerdere onzekerheden waar nieuw bewijs beschikbaar is.

## Conclusie voor deze versie

De app leest toestelgegevens, root, bootslots, Magisk-back-ups, Motorola-metadata en snapshotstatus werkelijk uit en onderzoekt lokale OTA-bytes en de whole-file-ondertekening. Versie 0.3.0 voegt hervatbare downloads, optionele lokale metadatameldingen, ondertekende toekomstige stockreferenties en een volledige duurzame begeleiding toe. Er is een beperkte, expliciet bevestigde schrijver voor alleen de huidige geauthenticeerde 8 MiB-init_boot, met volledige gepatchte back-up, one-use-toestemming, nieuwe veiligheidsmetingen en readback. De nieuwe live schrijver is zonder echte partities getest en niet op de telefoon uitgevoerd; dat blijft een materiële validatiegrens.

Een betrouwbare publieke externe Motorola-interface voor de gehele keten is niet aangetoond. Daarom blijven OTA-installatie, Magisk op de doelslot en herstart in de officiële apps. Lokale pakketstaging vereist ondertekend bewezen reteu-kanaal en gebruikt een afzonderlijke bevestiging en atomaire no-replace-publicatie, en blijft een fragiele copy-only-integratie. [Updateworkflow en herstelgrenzen](UPDATE-WORKFLOW.md), [stagingonderzoek](MOTOROLA_STAGING.md), [monitoring en downloads](MONITORING-AND-DOWNLOADS.md). Een volledige simulatie gebruikt uitsluitend reguliere testbestanden en verleent nooit toestemming voor echte toestelacties.

## Haalbaarheidsmatrix

| Onderdeel | Technisch bewijs | Verantwoorde integratie | Resterend bewijs |
|---|---|---|---|
| Model, build, kanaal en beveiligingspatch | Android Build-informatie en afzonderlijke properties | Werkelijk uitlezen; onbrekende of afwijkende waarden blokkeren het doelprofiel | Properties op XT2607-1 vergelijken met een lokaal toesteldiagnoserapport |
| Root | Magisk `su` en `id` | Alleen vaste uitleesopdrachten met timeout, gescheiden stdout/stderr en uitvoerlimiet | Magisk 31.1 Superuser-toekenning en SELinux op dit toestel |
| A/B-slots | Kernelproperties en CRC-gecontroleerde bootmetadata | Huidige slot en relevante partities uitsluitend lezen; bootctl-uitvoering uitgeschakeld | Huidige slot is iets anders dan de volgende bootslot; geen metadatareparatie door een toolinitializer |
| Virtual A/B | AOSP SnapshotManager, begrensde snapshotstate en misc Virtual A/B | Actuele metadata vergelijken; onbekend blokkeert | snapshotctl is slechts aanvullende dumpinformatie; bootctl-HAL-probe wordt niet uitgevoerd |
| Magisk-back-up | Magisk v31.1 scripts | Gzip uitlezen, integriteit, hash en imageheader onderzoeken; herkomst blijft apart bewijs | Actuele stock-image van exact de huidige build en partitie |
| Officiële updatecontrole | Motorola beschrijft de eigen systeemupdater | Open alleen een geschikte systeemactiviteit; gebruiker bedient officiële controle | Geen publiek Motorola discovery-SDK of extern aanroepcontract aangetoond |
| Bestaande Motorola-downloadmetadata | Bestandslocaties en sleutel uit de gebruikersspecificatie | Beperkt uitlezen als fragiele bron van kandidaten | Nieuwe updater-versies kunnen sleutels, schema en toegang wijzigen |
| Firmwaredownload | Normale HTTPS-bestandsoverdracht is technisch mogelijk | Alleen expliciet gekozen, beperkt toegestane bron, redirectcontrole en lokale verificatie | Een downloadlink bewijst geen model, kanaal, actualiteit of signature |
| Lokaal OTA-pakket | AOSP A/B ZIP-structuur en publieke verifyPackage-API | Bestandskiezer, beperkte parser, hashes, pakketondertekening | Engine-, payload-, rollback- en OEM-precondities zijn extra controles |
| Originele image herstellen | De huidige stock17-image is onafhankelijk geauthenticeerd; de handmatige systeemroute is eerder gelukt | Afzonderlijk bevestigd exact-init_boot-herstel met back-up/readback; officiële Magisk-handoff als alternatief | Nieuwe writer is file-backed getest, niet fysiek uitgevoerd; geen stabiele externe Magisk-restore-API |
| OTA installeren | AOSP UpdateEngine SystemApi en Motorola-updater | Officiële updater gebruiken buiten deze app | Root is geen aantoonbare externe OEM-installatie-API |
| Magisk naar inactieve slot | Magisk-app en interne installer | Officiële Magisk-app; resultaat onafhankelijk opnieuw onderzoeken | Interne shellfuncties zijn geen stabiel publiek contract |
| Update gereed verklaren | Nieuwe build, juiste slot, bootstatus, root en afgeronde merge | Pas na nieuwe uitlezing; tussentoestanden apart tonen | Echt toestel en toekomstige OTA moeten dit traject nog valideren |

## Magisk 31.1: relevante versieverschillen

De officiële release v31.1 verwijdert de reactieve rootpop-up. Toegang moet vooraf in Magisk via de Superuser-tab aan de app worden verleend. De release verwijdert ook het verbergen/herverpakken van de Magisk-app; het bestaan van een oud verborgen pakket mag dus geen vereiste voor rootdiagnose worden. Lees de daemonversie onafhankelijk van de startbaarheid van de Magisk-app. [Officiële release v31.1](https://github.com/topjohnwu/Magisk/releases/tag/v31.1).

De gedocumenteerde OTA-volgorde gebruikt Magisk om originele images te herstellen, laat de systeemupdater de OTA afronden, installeert Magisk op de andere slot en laat vervolgens de systeemupdater herstarten. Tussen herstellen en het opnieuw voorbereiden van root mag niet tussentijds worden herstart. De gids zegt dat de beschreven procedure op Pixel-apparaten is getest; zij biedt geen Motorola-garantie. [Magisk OTA-gids](https://topjohnwu.github.io/Magisk/ota.html).

De Magisk CLI documenteert versie-uitlezing en hulpfuncties, maar geen ondersteunde externe opdracht `magisk --restore-images` of `magisk --install-inactive`. Deze app verzint zulke opdrachten niet. [Magisk Tools](https://topjohnwu.github.io/Magisk/tools.html).

### Wat boot.img.gz wel en niet bewijst

In v31.1 maakt `boot_patch.sh` van een gedetecteerde stock-input een kopie `stock_boot.img` en bewaart diens SHA-1 in de ramdiskconfiguratie. De generieke naam betekent niet automatisch dat de input uit de partitie `boot` kwam. [boot_patch.sh v31.1](https://github.com/topjohnwu/Magisk/blob/0972f67d97f9b6302b67c5171283330c74114b4a/scripts/boot_patch.sh).

`run_migrations` plaatst een gecomprimeerde stock-image onder `/data/magisk_backup_<SHA1>/boot.img.gz`. `find_boot_image` kiest onder voorwaarden `init_boot`; alleen het bestaan van die partitie volstaat niet in alle kernelconfiguraties. De hashdirectory identificeert bytes, geen Android-build, kanaal of slot. [util_functions.sh v31.1](https://github.com/topjohnwu/Magisk/blob/0972f67d97f9b6302b67c5171283330c74114b4a/scripts/util_functions.sh).

`restore_imgs` selecteert de back-up via de in Magisk opgeslagen SHA-1 en schrijft die naar de gekozen partitie. `post_ota` bevat bovendien bootslotmutatie en een bootscript. Dit zijn interne implementaties van de Magisk-app en worden niet vanuit Moto OTA Manager gesourced of uitgevoerd. [app_functions.sh v31.1](https://github.com/topjohnwu/Magisk/blob/0972f67d97f9b6302b67c5171283330c74114b4a/scripts/app_functions.sh).

Een betrouwbare restore vereist vergelijking met onafhankelijk geauthenticeerde huidige firmware, het imageformaat, de volledige uitgepakte omvang, partitiegrootte en actuele installatie-identiteit. Voor de gemeten huidige build is deze stock17-herkomst inmiddels onafhankelijk bewezen; [DEVICE-VALIDATION](DEVICE-VALIDATION.md) bevat de concrete hashes, signature en manifestvergelijking. Een geldige gzip, SHA-1-directory of recent tijdstip alleen blijft onvoldoende. De bekende Android 16-image is geen restorebron voor Android 17. Voor volgende builds bewaart de app geverifieerde targetmanifesthashes en bindt die pas aan de werkelijke doelboot; een onbekende nieuwe image blijft geblokkeerd.

## A/B- en snapshotdiagnostiek

Android 17 heeft fs_mgr verplaatst naar `platform/system/fs/fs_mgr`. Onderzoek uitsluitend tegen een actuele `android17-release`-referentie voorkomt dat een bevroren `main`-branch abusievelijk als Android 17-bewijs wordt gebruikt. De hieronder genoemde AOSP-output blijft een referentie; Motorola kan wijzigingen bevatten.

### Onderzochte bootctl-getters; uitvoering uitgeschakeld

| Opdracht | Betekenis | Beperking |
|---|---|---|
| `bootctl get-current-slot` | Slot van het draaiende systeem, numeriek 0/1 | Exitcode en parsing controleren |
| `bootctl get-active-boot-slot` | Slot dat bij de volgende boot wordt gekozen | Niet verwarren met draaiende slot |
| `bootctl get-number-slots` | Aantal bootslots | Twee verwachten voor dit profiel |
| `bootctl is-slot-marked-successful 0` of `1` | Bootsuccesvlag; resultaat én exitcode verwerken | Niet zelf `mark-boot-successful` uitvoeren |
| `bootctl get-snapshot-merge-status` | Boot HAL merge-status | `none`, `unknown`, `snapshotted`, `merging`, `cancelled`; geen engine-IDLE-bewijs |

Deze namen en de scheiding tussen huidige en volgende bootslot staan in [bootctl voor Android 17](https://android.googlesource.com/platform/system/extras/+/1289e9b3f282909eec6e80414e14dcc207bda439/bootctl/bootctl.cpp). Dit is een onderzoeksinventaris, geen uitvoerallowlist. In 0.3.0 worden alle zes bootctl-probes vóór processtart geweigerd: de AOSP-initialisatie kan ongeldige bootmetadata opnieuw opslaan en ontbrekende Virtual A/B-metadata initialiseren, ook wanneer daarna alleen een getter wordt gevraagd. De app leest de benodigde bytes zelf in read-only modus en controleert de CRC. [AOSP BootControl::Init](https://android.googlesource.com/platform/bootable/recovery/+/cbb0b54b1253bcf5d29fb0b29441f8f821a061b6/boot_control/libboot_control.cpp).

`snapshotctl dump` roept SnapshotManager::Dump aan. Het is diagnostische, niet atomair vergrendelde uitvoer. De dump kan globale status, huidige slot, bronfingerprint en tijdens een merge een percentage tonen. Een ontbrekend percentage wordt onbekend; oude logpercentages zijn geen actuele meting. [snapshotctl Android 17](https://android.googlesource.com/platform/system/fs/fs_mgr/+/157112fcbaa00b6e84c63146cfde249c56cd652c/libsnapshot/snapshotctl.cpp), [SnapshotManager::Dump](https://android.googlesource.com/platform/system/fs/fs_mgr/+/157112fcbaa00b6e84c63146cfde249c56cd652c/libsnapshot/snapshot.cpp).

| Globale `Update state` | Interpretatie |
|---|---|
| 0 / None | Geen snapshotupdate of merge in voortgang |
| 1 / Initiated | Update gestart; snapshots kunnen bestaan |
| 2 / Unverified | Update wacht op een succesvolle nieuwe boot |
| 3 / Merging | Merge actief |
| 4 / MergeNeedsReboot | Afronding vereist verdere systeemactie |
| 5 / MergeCompleted | Merge voltooid maar nog bevestiging/cleanup nodig |
| 6 / MergeFailed | Mergefout |
| 7 / Cancelled | Update geannuleerd; oorzaak apart vaststellen |

De per-partitie `SnapshotState` gebruikt een andere nummering. Parse daarom uitsluitend de globale regel als globale status. Nieuwe of onleesbare waarden blokkeren. Ook een globale 5 is geen toestemming voor een volgende OTA. [Android 17 snapshot-protobuf](https://android.googlesource.com/platform/system/fs/fs_mgr/+/157112fcbaa00b6e84c63146cfde249c56cd652c/libsnapshot/android/snapshot/snapshot.proto).

Virtual A/B schrijft wijzigingen van dynamische partities in snapshots en voegt ze na de nieuwe boot samen. Een geslaagde OTA-toepassing en een afgeronde merge zijn verschillende gebeurtenissen. [Virtual A/B-overzicht](https://source.android.com/docs/core/ota/virtual_ab).

### UpdateEngine is geen gewone SDK-API

`android.os.UpdateEngine` is een verborgen SystemApi voor systeemupdateclients; een gewone geïnstalleerde app krijgt die toegang niet door een manifestpermission te noemen. AOSP gebruikt Binder en SELinux-domeinen. De OEM-policy en rootcontext blijven relevant; de app wijzigt SELinux niet. [UpdateEngine.java](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/core/java/android/os/UpdateEngine.java), [update_engine SELinux-policy](https://android.googlesource.com/platform/system/sepolicy/+/e066568e98d86db31a9346d30977f3632fa7073c/private/update_engine.te).

Het Android-programma `update_engine_client` kent in de gecontroleerde Android 17-bron geen `--status`. Die aanname verwart Android met andere update-engine-clients. `--follow` bindt een statuscallback en kan met een harde timeout als uitleesprobe dienen. `--merge` roept juist cleanup aan en is geen passieve statusopdracht. Deze app gebruikt geen update-, cancel-, reset-, allocate- of merge-opdrachten. [Android 17 clientbron](https://android.googlesource.com/platform/system/update_engine/+/4591637f51644f5429f9e2fe589ab22b92f61842/aosp/update_engine_client_android.cc).

## Firmwareverificatie en ontdekking

A/B-OTA's hebben metadata, payload-properties en een payload. `FILE_HASH` dekt de volledige payload; `METADATA_HASH` alleen de payloadheader en het manifest. Voor payload major-version 2 is de metadataomvang 24 bytes plus de manifestlengte; de aparte metadatahandtekening valt erbuiten. Beide properties gebruiken Base64 SHA-256. Deze zelf opgegeven hashes detecteren beschadiging, maar zonder vertrouwde ondertekening geen vervalste firmware. [Android 17 payload-propertiesgenerator](https://android.googlesource.com/platform/system/update_engine/+/4591637f51644f5429f9e2fe589ab22b92f61842/payload_generator/payload_properties.cc), [payloadmetadata-parser](https://android.googlesource.com/platform/system/update_engine/+/4591637f51644f5429f9e2fe589ab22b92f61842/payload_consumer/payload_metadata.cc).

De publieke `RecoverySystem.verifyPackage` verifieert het volledige OTA-ZIP-bestand tegen de op het toestel vertrouwde certificaten; met `null` gebruikt zij de systeemtruststore. Dit start geen installatie. De functie bewijst op zichzelf geen toepasbaarheid van de payload, juiste regio, rollbackveiligheid of actuele bronpartitie. De systeemupdater moet zijn eigen controles blijven uitvoeren. [Android RecoverySystem API](https://developer.android.com/reference/android/os/RecoverySystem#verifyPackage(java.io.File,%20android.os.RecoverySystem.ProgressListener,%20java.io.File)).

AOSP metadata kan meerdere toegestane fingerprints/devices bevatten. Fingerprints uit system, product en vendor worden apart gemeten; verschillende strings worden nooit door een verzonnen aliasregel gelijkgesteld. Een bestandsnaam, `release-keys`, `cid50` of een reeds vertrouwd lijkende host bepaalt het kanaal niet. Als de regio niet onafhankelijk kan worden vastgesteld, blijft installatie geblokkeerd. ZIP-offsets worden voor streaming door officiële releasetools op de uiteindelijke pakketlayout berekend; een entry-index of ZIP-bestandsgrootte is geen payload-offset. [AOSP OTA-releasetools](https://android.googlesource.com/platform/build/+/5ce6f787337d0223710bf7d4a16dbe6d2a35f777/tools/releasetools/ota_utils.py).

Motorola ondersteunt officieel de controle via Instellingen → Systeemupdates. Motorola beschrijft bovendien variatie tussen modellen, regio's en distributiekanalen. Het ontbreken van een aanbod na een fout is daarmee nog geen bewijs van een blokkade; lokale status, verstreken aanbiedingen, uitrolbeleid en huidige build zijn afzonderlijke hypotheses. De nulwaarden van lokale fouttellers bewijzen niet welke serverbeslissing is genomen. [Motorola updatecontrole](https://en-us.support.motorola.com/app/answers/detail/a_id/172729/~/check-for-software-update).

Er is in dit onderzoek geen publiek, ondersteund Motorola-API-contract aangetoond dat voor `XT2607-1 / merc / reteu` geschikte OTA's onafhankelijk teruglevert. De private `dl_prefs.xml` uit de gebruikersspecificatie blijft een fragiele diagnostische kandidaatbron. Zij mag geen externe firmware-index tot officiële bevestiging promoveren. Software Fix Rescue is geen gegevensbehoudende OTA-fallback: Motorola beschrijft dat Rescue gebruikersgegevens wist. Deze app biedt die handeling niet aan. [Motorola Rescue-documentatie](https://en-gb.support.motorola.com/app/answers/detail/a_id/167770/~/how-to-run-a-rescue-software-update).

De eerder geobserveerde lokale OTA-herkenning in de Motorola-appmap is waardevol toestelbewijs uit één geval, geen publiek importcontract. Gewone apps kunnen door scoped storage niet zomaar in de `Android/data`-map van een andere app schrijven. Root verandert sommige toegangsgrenzen, maar bewijst geen betrouwbaar updateprotocol. In 0.3.0 kan een beperkte roothelper onder expliciete bevestiging een volledig geverifieerd pakket met ondertekend bewezen reteu-kanaal copy-only klaarzetten. Hij roept geen installer aan; de herkenning blijft door Motorola te beoordelen. Atomaire no-replace-publicatie voorkomt het overschrijven van bestaande afwijkende bestanden. Ondersteunt de werkelijke kernel/FUSE-stack dit niet, dan wordt geen onveilige publicatiefallback gebruikt. [Android opslagbeperkingen](https://developer.android.com/about/versions/11/privacy/storage), [concrete stagingimplementatie en grenzen](MOTOROLA_STAGING.md).

## Onderzoeksgrenzen

Er is geen private Motorola-APK gedecompileerd, geen serverauthenticatie omzeild en tijdens deze ontwikkelsessie geen echte OTA geïnstalleerd, partitie geflasht of telefoon herstart. De oorspronkelijke handmatige update is gebruikersbewijs; de afzonderlijke historische 0.2.0-metingen en cryptografische firmware-/back-upvalidatie zijn concrete aanvullende bewijzen. De nieuwe 0.3.0-functies worden zonder telefoon onderzocht en getest. Een toekomstige echte OTA vereist nieuwe toestelmetingen en afzonderlijke praktijkvalidatie; een geslaagde appbuild vervangt dat niet.
