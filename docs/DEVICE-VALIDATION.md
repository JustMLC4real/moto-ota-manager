# Forensische validatie van de echte Motorola Edge 70 Pro

Onderzoeksdatum: **28 oktober 2026**. Toestel: **Motorola XT2607-1 / merc / reteu**. Dit rapport beschrijft daadwerkelijk uitgelezen toestelgegevens, behouden OTA-logs en onafhankelijke verificatie van de historische firmware. De definitieve 0.2.0-APK slaagt bovendien voor vier fysieke uitleestests en drie UI-tests; het volledige build- en testbewijs staat in [BUILD-REPORT.md](BUILD-REPORT.md).

De eerdere installatie mislukte aantoonbaar doordat de bronblokken van `init_boot_a` niet overeenkwamen met de incrementele OTA. Diezelfde OTA is later succesvol toegepast, de snapshot-merge is afgerond en de actuele engine meldt `IDLE`. De huidige Magisk-back-up is bytegelijk aan de stock-`init_boot` voor de geïnstalleerde Android 17-build, zoals vastgelegd in het cryptografisch geverifieerde OTA-pakket.

Dat **Magisk de unieke veroorzaker van de oude bronwijziging** was, is sterk consistent met de gebruikersgeschiedenis. Een oude gepatchte image ontbreekt echter voor een volledige onafhankelijke vergelijking van die foutblokken. Waarom Motorola daarna tijdelijk `ALREADY_UP_TO_DATE` meldde, is met het behouden bewijs niet vastgesteld.

## Omvang en bewijsgrenzen

De toestelmetingen gebruikten uitsluitend uitlezende diagnostiek: specifieke properties, kernelbootconfig, begrensde metadata, hashes, bestaande back-ups en behouden logs. Er is geen OTA toegepast, image hersteld, partitie beschreven, bootslot gewijzigd, snapshotcleanup gestart of toestelherstart uitgevoerd. De historische firmware is op de host onderzocht; de al aanwezige telefoon-ZIP is daarnaast met Androids echte `RecoverySystem.verifyPackage` positief geverifieerd. De diagnostische opdrachten wijzigden geen rootbeleid, modules, OTA-appdata of fouttellers. De gebruiker verleende de nieuwe diagnose-app zelf Superuser-toestemming.

De normale Android-achtergrondprocessen blijven actief en kunnen logs schrijven. Daarom betekent "uitlezend" hier dat de diagnostische opdrachten geen firmware- of configuratiemutaties uitvoeren; het betekent geen stilgelegd, bit voor bit onveranderd toestel. Appinstallatie, appstart en app-instrumentatie hebben hun eigen testverslag.

Ruwe private preferences en volledige logbestanden worden niet met deze broncode verspreid. De gepubliceerde bewijsbestanden bevatten alleen toegestane technische velden. Zij bevatten geen ondertekende download-URL, token, account, serienummer, IMEI, boot-ID of persoonlijke hostpaden.

## Actuele identiteit en werkende root

| Waarneming | Werkelijk gemeten waarde |
|---|---|
| Fabrikant / hardware-SKU / codenaam | `motorola` / `XT2607-1` / `merc` |
| Kanaal | `reteu`, uit `ro.carrier` |
| Android / SDK | `17` / `37` |
| Build | `A171WMH.44-25` |
| Incremental | `0347f0-d290bf` |
| Beveiligingspatch | `2026-09-01` |
| Draaiende slot | `_b`, zowel property als kernelbootconfig |
| Root | `su` leverde UID `0` |
| Magisk-daemon | `31.1`, versiecode `31100` |

De actuele systeemfingerprint is:

```text
motorola/merc_g_syseq/merc:17/A171WMH.44-25/0347f0-d290bf:user/release-keys
```

De SHA-256 van de werkelijk geïnstalleerde Magisk-APK is:

```text
89cefac0a318afa39fb3880c45c87b036c05bc1ebf02a7a715b7f8d7c26ac6e7
```

Deze waarde matcht exact de gepubliceerde digest van het officiële releaseasset `Magisk-v31.1.apk` (10.679.283 bytes). Dit bevestigt de appbytes; de daemonversie is daarnaast afzonderlijk uitgelezen. Het is geen complete herkomstaudit van iedere rootbinary of module. De officiële v31.1-release vereist vooraf ingestelde Superuser-toegang in plaats van een reactieve rootpop-up. [Magisk v31.1-release](https://github.com/topjohnwu/Magisk/releases/tag/v31.1), [officiële releasegegevens](https://api.github.com/repos/topjohnwu/Magisk/releases/tags/v31.1).

Voor twee specifieke preference-uitlezingen en de expliciete verificatie van de bestaande Motorola-ZIP is `su --target 0` gebruikt. In de geverifieerde v31.1-bron selecteert dit de globale Magisk-mountnamespace voor de nieuwe subprocess; `0` is hier geen Android-gebruikersnummer. Dit heeft geen permanente namespace-instelling, Superuser-policy, SELinux-regel of bestandstoestemming aangepast. [MagiskSU-bron, v31.1](https://github.com/topjohnwu/Magisk/blob/0972f67d97f9b6302b67c5171283330c74114b4a/native/src/core/su/su.cpp).

### Tegenstrijdige beveiligingsproperties

| Bron | Bootloader-/verified-bootmelding |
|---|---|
| Android-properties | `vbmeta.device_state=locked`, `verifiedbootstate=green`, `flash.locked=1` |
| Kernelbootconfig | `vbmeta.device_state=unlocked`, `verifiedbootstate=orange` |

Dit is een werkelijk gemeten bronconflict. De kernelbootconfig meldt de ontgrendelde/oranje toestand, passend bij de gebruikersgeschiedenis. De userspaceproperties mogen daarom niet zelfstandig als bewijs van een vergrendelde bootloader worden gebruikt. Het onderzoek wijst zonder aanvullend bewijs geen individuele module als veroorzaker aan en heeft niets aangepast om de meldingen gelijk te maken. Deze uitlezingen zijn geen hardwareattestatie.

## Actuele slots, snapshots en engine

De verwachte programma's `bootctl`, `snapshotctl` en `update_engine_client` waren via de bestaande toestelomgeving niet beschikbaar: de probes eindigden met exitcode `127`. Er is geen vervangend programma geïnstalleerd om een status te verkrijgen. Lege succesvolle `dumpsys`-uitvoer werd evenmin tot `IDLE` of merge-afronding gepromoveerd.

De uitlezende fallback combineerde de bestaande AOSP-metadata met een echte enginecallback:

| Bron | Uitkomst |
|---|---|
| `/metadata/ota/state` | Bestond daadwerkelijk, volledig en consistent gelezen, **0 bytes** |
| `/metadata/ota/snapshots` | Bestaande directory, geen snapshotbestanden |
| Virtual A/B-record uit `misc` | Ondersteund formaat/magic; ruwe merge-status **`NONE`** |
| Boot-controlrecord uit `misc` | Ondersteunde magic/versie; **CRC-32 geldig**; twee slots |
| Slot b in boot-controlrecord | Priority `15`, tries remaining `1`, successful boot `true`, verity corrupted `false` |
| Live `UpdateEngine.bind(callback)` | **`IDLE`**, statuscode `0`, percentage `0.0` |
| Callbackprobeproces | Exitcode `0`, geen timeout, geen truncatie, geen foutreden |

De raw-metadata-uitlezing was begrensd tot het 32-byte boot-controlrecord op offset 2048 en het 64-byte Virtual A/B-record op offset 32768. Er is geen volledige `misc`-dump geëxporteerd en geen initialisatie- of reparatiefunctie van BootControl aangeroepen. De AOSP-layout en CRC-/slotsemantiek zijn de referentie; ongeldige of onbekende OEM-layouts moeten onbekend blijven. [Bootmetadata-layout](https://android.googlesource.com/platform/bootable/recovery/+/cbb0b54b1253bcf5d29fb0b29441f8f821a061b6/bootloader_message/include/bootloader_message/bootloader_message.h), [AOSP boot-controlimplementatie](https://android.googlesource.com/platform/bootable/recovery/+/cbb0b54b1253bcf5d29fb0b29441f8f821a061b6/boot_control/libboot_control.cpp).

Een werkelijk bestaand, volledig gelezen leeg `state`-bestand is een geldige lege proto3-`SnapshotUpdateStatus`, waarvan de toestand standaard `None` is. Dat is iets anders dan ontbrekende toegang of een ontbrekend bestand. De lege snapshotdirectory en de afzonderlijke Virtual A/B-status ondersteunen hier dezelfde conclusie. Alleen een lege uitvoer had daarvoor niet volstaan. [Snapshot-protobufschema](https://android.googlesource.com/platform/system/fs/fs_mgr/+/157112fcbaa00b6e84c63146cfde249c56cd652c/libsnapshot/android/snapshot/snapshot.proto), [SnapshotManager::ReadSnapshotUpdateStatus](https://android.googlesource.com/platform/system/fs/fs_mgr/+/157112fcbaa00b6e84c63146cfde249c56cd652c/libsnapshot/snapshot.cpp).

De versiegebonden diagnostische roothelper registreerde op het echte SDK 37-toestel een callback via Androids `UpdateEngine` en gaf bovenstaande actuele status terug. Het uitleespad gebruikt uitsluitend `bind`/`unbind`, geen payloadtoepassing of cleanupaanroep. `completionCode=null` betekent dat de probe geen voltooiingsevent rapporteerde; de live statusmelding zelf is `IDLE`. Dit is geen bewijs dat een nieuwe OTA beschikbaar of toepasbaar is. [Android UpdateEngine-frameworkbron](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/core/java/android/os/UpdateEngine.java).

De bronnen zijn onderling consistent: **er is bij deze uitlezing geen actieve snapshot-merge, slot b is als succesvol gemarkeerd en de engine is idle**. De afzonderlijke firmware- en back-upvoorwaarden voor een volgende update blijven gelden.

## Historische fout: bronhashafwijking zelfstandig bewezen

De behouden log met bestandsnaam `update_engine.20261026-212129` bevat ook gebeurtenissen van 28 oktober. Daarom zijn de tijdstempels binnen de log gebruikt, niet alleen de bestandsnaam. De logtijdzone is niet onafhankelijk vastgesteld; onderstaande tijden zijn letterlijk de door het toestel gelogde tijden.

Op **28 oktober om 11:29:05**, regels 89–95, meldt de engine een bronhashafwijking bij `init_boot_a`:

| Gegeven | Waarde |
|---|---|
| Verwachte SHA-256 | `056ece9d539c096dd62121a1685a4c3d09b4a49a1e879ca264e4a71a7f36a00a` |
| Berekende SHA-256 | `55a3c92f5ad61aba84346d13989d3a0fddd2a26737882504b202a6e1f80d8f83` |
| Bronextents, startblok:aantal | `0:1,756:1,2047:1` |
| Partitie | `init_boot_a` |
| Blocksize uit dezelfde OTA | `4096` bytes |

Dezelfde operatie en verwachte hash zijn onafhankelijk teruggevonden in het manifest van de historische OTA. Het hashen van precies deze stock-Android 16-blokken, in de opgegeven volgorde, levert exact de verwachte hash. Het betreft dus de hash van **operatiebronblokken**, niet de SHA-256 van de volledige 8 MiB-partitie.

AOSP controleert deze bronextents tegen `src_sha256_hash`. De fout kan technisch zowel door een gewijzigde partitie als door een onjuiste bronversie ontstaan. De fouttekst alleen identificeert geen unieke veroorzaker. Hier verbinden de exacte extents, de historische OTA en de passende stock16-image de fout wel aan aantoonbaar afwijkende `init_boot_a`-brondata. [AOSP PartitionWriter::ValidateSourceHash](https://android.googlesource.com/platform/system/update_engine/+/refs/heads/android17-release/payload_consumer/partition_writer.cc).

De daaropvolgende ECC/FEC-poging kon geen ECC-bron voor `init_boot_a` openen. Dat is een fallback ná de hashafwijking en bewijst niet zelfstandig dat de fysieke init_boot-partitie ontbrak.

De gebruikersgeschiedenis vermeldt dat Magisk deze partitie had gepatcht en dat de passende stock16-image daarna zonder tussentijdse herstart werd teruggezet. De oude gepatchte image is in de toegestane lokale zoeklocaties niet aangetroffen. Daardoor luidt de conclusie zorgvuldig:

- **Bewezen:** de toenmalige OTA-bronblokken op `init_boot_a` weken af van de juiste stock16-bron.
- **Sterk ondersteund:** stockherstel maakte deze bron weer passend; de daaropvolgende OTA met dezelfde manifestverwachtingen slaagde.
- **Consistent, niet uniek aan oude bytes toegeschreven:** Magisk veroorzaakte die wijziging.

De beschreven volgorde sluit aan bij de officiële Magisk A/B-gids: stock herstellen, officiële OTA afronden, Magisk op de bijgewerkte andere slot installeren en vanuit de systeemupdater herstarten. Die gids noemt Pixel als getest platform; hij biedt geen algemene Motorola-garantie. Deze validatie herhaalde de schrijfstappen niet. [Officiële Magisk OTA-gids](https://topjohnwu.github.io/Magisk/ota.html).

## De latere succesvolle OTA en merge

Dezelfde behouden log bevestigt de succesvolle poging:

| Tijd op 28 oktober | Bewijs |
|---|---|
| 20:14:09 | Oude en nieuwe `init_boot`-omvang/hash uit het manifest gelezen |
| 20:22:14 | Vijf updateoperaties gericht op `init_boot_b` |
| 20:24:31 | Doel-`init_boot_b` gehasht: `9972073a…8c7c5ea` |
| 20:24:37–38 | Postinstall geslaagd, `kSuccess`, update toegepast en wachtend op herstart |

De latere log `update_engine.20261028-210411`, regels 553–569, meldt om **21:12:33** globale `MergeCompleted`, het verwijderen van update state, succesvolle `CleanupPreviousUpdateAction`, het wissen van de update-complete-marker en `CleanupSuccessfulUpdate` met `kSuccess`. De gemelde mergetijd is **168.432 ms**, met **0 hervattingen**.

De daaropvolgende log `update_engine.20261028-211634`, regels 16–18, meldt om **21:16:46** globale `None` en geen snapshots om te mergen. De merge is dus aantoonbaar afgerond; de eerdere 32%-waarneming was een tussenstand. Deze historische afronding wordt ondersteund door de actuele metadata en live enginecallback hierboven.

De gepubliceerde [geselecteerde engine-regels](../evidence/device-validation/forensic-update-engine-excerpts.txt) bewaren de bronbestandsnamen en oorspronkelijke regelnummers. Volledige ruwe logs met mogelijke privégegevens zijn niet opgenomen.

## Historische OTA cryptografisch gecontroleerd

De reeds aanwezige historische OTA is op de host gelezen, zonder nieuwe firmwaredownload. Zij heeft **3.417.373.748 bytes** en SHA-256:

```text
7b548666bfb8ca2ef8a65026f449af35b87354a8b098424fda52c434d0502c90
```

De volledige payload heeft **3.417.360.736 bytes**, SHA-256 `94b294284ee22524e25dff332ea5b70a95bb1ee39ca5c2d7703e67eb5be09b96`. `FILE_HASH`, `FILE_SIZE`, `METADATA_HASH` en `METADATA_SIZE` zijn opnieuw berekend en kloppen. Deze interne hashes bewijzen integriteit; de aanvullende handtekeningcontrole levert de authenticiteitskoppeling.

Voor de whole-file-handtekening zijn de ZIPfooter, unieke EOCD en het ondertekende bereik overeenkomstig `RecoverySystem.verifyPackage` verwerkt. De handtekening dekt de eerste **3.417.371.721 bytes**. SHA-256 van dat bereik: `03f622ae5020f2368466ab58b6e6c94fd6aaaacb160bc27f1dc592e20e58a539`. De CMS/PKCS7-structuur bevat één signer, SHA-256, RSA PKCS#1 v1.5 en een handtekening van 512 bytes, zonder signed attributes. Python `cryptography` en een onafhankelijke OpenSSL-RSA-controle verifieerden de handtekening allebei succesvol. [RecoverySystem-bron en vertrouwenscontrole](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/core/java/android/os/RecoverySystem.java).

Het signer-publickey matcht exact het enige publickey in de werkelijk uitgelezen toestel-OTA-truststore. Hun gedeelde SPKI-SHA-256 is:

```text
19acf5edb7a55816a55163183171097a785bdb5d815230b46d446d6e6004f8cc
```

Dit bevestigt de whole-file-handtekening tegen de actuele toesteltruststore en authenticeert daardoor ook het payloadmanifest in dit pakket. Het bewijst geen toestemming voor herinstallatie, toekomstige compatibiliteit, regio of rollback. De [handtekeningfeiten](../evidence/device-validation/forensic-signature.json) bevatten uitsluitend publieke sleutelhashes en technische controle-uitkomsten.

Daarna is de al aanwezige telefoon-ZIP positief gecontroleerd met Androids daadwerkelijke `RecoverySystem.verifyPackage(file, null, null)`. De beperkte roothelper las het bestand uitsluitend, bevestigde dezelfde omvang en SHA-256 en rapporteerde `signatureVerified=true` met de toesteltruststore. De fysieke test `existingHistoricalOtaPassesTheActualPlatformSignatureVerifier` slaagt. Er is niets naar de telefoon gestaged of geïnstalleerd. [Toesteltestresultaten](../evidence/device-validation/physical-readonly-tests.log).

### Werkelijke OEM-metadata gebruikt verschillende namespaces

| Bron | Bronversie | Doelversie |
|---|---|---|
| `META-INF/com/android/metadata` | `merc_g_vext`, Android `15`, build `W3WMS36.40-75-5-2` | `merc_g_vext`, Android `15`, build `A171WMH.44-25`, SDK `35` |
| Motorola `payload_properties.txt` | `SOURCE_FP`: `merc_g_sys`, Android `16`, build `W3WMS36.40-75-5-2` | `TARGET_FP`: `merc_g_sys`, Android `17`, build `A171WMH.44-25` |
| Actueel draaiend systeem | — | `merc_g_syseq`, Android `17`, SDK `37`, build `A171WMH.44-25` |

Dit zijn werkelijk aangetroffen verschillen in hetzelfde ondertekende pakket en de huidige toestelmeting. Ze worden bewaard, niet met een verzonnen aliasregel gelijkgesteld. Een compleet geldig payload-fingerprintpaar kan de informatieve doelweergave verbeteren naar Android 17. De afwijking blijft **`OEM_METADATA_REVIEW_REQUIRED`**, met incompatibele status voor automatische voortgang. Ook de exacte bronfingerprint-/incrementalcontroles blijven gelden.

De historische doelbuild staat al op het toestel: **`ALREADY_INSTALLED`**. De vendorachtige Android 15-string mag daarom niet als zelfstandig bewijs worden gepresenteerd dat het pakket een Android 15-downgrade beoogde. Authenticiteit en toepasbaarheid zijn afzonderlijke beoordelingen. De [firmwarefeiten](../evidence/device-validation/forensic-firmware.json) bevatten de beperkte technische metadata en bronextents, zonder hostpad of download-URL.

## De huidige stockback-up is aan Android 17 gekoppeld

| Image | Omvang uitgepakt | SHA-256 |
|---|---:|---|
| Historische stock16-`init_boot` | 8.388.608 bytes | `2813c93926eae599bd3bd14c4e9e39b10bc3b2dd30134bff8fe594a3f8a67d29` |
| Stock17-`init_boot` uit ondertekend OTA-doelmanifest | 8.388.608 bytes | `9972073a2fdc3bd63ea3b33b3e26ec39f78ddea82ed3be274acc086a88c7c5ea` |
| Werkelijk uitgepakte huidige Magisk-back-up | 8.388.608 bytes | `9972073a2fdc3bd63ea3b33b3e26ec39f78ddea82ed3be274acc086a88c7c5ea` |

De gecomprimeerde back-up heeft **2.412.155 bytes**. Zij is op de host gedecomprimeerd en op integriteit, volledige omvang en hash gecontroleerd. Haar SHA-1 is `da444102f5a0e669ca3db7ba1de6a6c839b65f41`; die matcht zowel de back-updirectorysuffix als de werkelijk gelezen `SHA1` in de huidige Magisk-runtimeconfig. De header heeft magic `ANDROID!`, versie `4`, kernelsize `0`, ramdisksize `2.739.212` en headersize `1584`.

De bewijsverbinding bestaat daardoor uit **actuele Magisk-selectie → volledige back-upbytes → authentiek OTA-doelmanifest → geïnstalleerde build**. Naam, tijdstip en compressiegrootte waren daarvoor niet voldoende. In Magisk heet de generieke back-up ook `boot.img.gz` wanneer de input uit `init_boot` kwam; de gepinde patcher en migratiefunctie verklaren die naamgeving. [Magisk boot_patch.sh v31.1](https://github.com/topjohnwu/Magisk/blob/0972f67d97f9b6302b67c5171283330c74114b4a/scripts/boot_patch.sh), [Magisk util_functions.sh v31.1](https://github.com/topjohnwu/Magisk/blob/0972f67d97f9b6302b67c5171283330c74114b4a/scripts/util_functions.sh).

De huidige `init_boot_a` matcht nog de stock16-hash. Dat maakt de inactieve oude slot **geen stockbron voor de actuele Android 17-build**. De actieve `init_boot_b` heeft nu een andere hash, `ec99ff0bc4607b2d7a936f4654850e748963b3f25e625695d77c91fc1d4f89c1`; dit past bij de werkende rootinstallatie. De stockback-up en de huidige gepatchte partitie zijn verschillende objecten.

De geauditeerde stockreferentie is uitsluitend bestemd voor het exact gemeten profiel, build, fingerprint en vertrouwde toestel-OTA-sleutel. Een toekomstige build of afwijkende sleutel krijgt deze referentie niet automatisch. De image is niet teruggeschreven en deze verificatie garandeert geen geslaagde toekomstige restore. Magisk `restore_imgs` schrijft daadwerkelijk naar de gekozen partitie; `post_ota` wijzigt bovendien bootgedrag. Die interne routines zijn tijdens deze validatie niet uitgevoerd. [Magisk interne appfuncties v31.1](https://github.com/topjohnwu/Magisk/blob/0972f67d97f9b6302b67c5171283330c74114b4a/scripts/app_functions.sh).

## Motorola-aanbod: huidige metadata is geen nieuwe update

De beperkte, actuele preference-uitlezing toont:

| Veld | Waarde |
|---|---|
| `maxUpgradeAttemptCount` | `1` |
| `failure_count` | `0` |
| `updateFailCount` | `0` |
| Geschiedenisversie | `A171WMH.44-25` |
| Interne geschiedenisstatus | `201`, betekenis niet onafhankelijk gedocumenteerd |
| `IS_UPDATE_PENDING_ON_REBOOT` | `false` |
| `VAB_MERGE_DEVICE_CORRUPTED` | `false` |

Deze lokale waarden zijn niet aangepast. Zij bewijzen niet waarom Motorola eerder geen aanbod gaf of wanneer een volgende update verschijnt. `201` is niet zonder documentatie als succescode geïnterpreteerd.

De huidige `downloadRequest` beschrijft een `.bin`-metadatabestand: omvang **1.853.495 bytes**, starting offset **4744**. Beide waarden matchen exact het bereik van de payloadheader, het manifest en de 523-byte metadatahandtekening binnen de historische OTA-ZIP. SHA-256 van die metadatabytes: `b57a21a1817f1aa02cfc828b2b3b32ead0dcfb33f08a6def770438393cfe257f`.

Dit is dus sterk consistent met **behouden metadata van de reeds geïnstalleerde OTA**, niet met een zelfstandig installeerbare nieuwe firmware. De volledige link en identifiers zijn niet gepubliceerd en er is geen nieuwe download aangevraagd. Een downloadRequest met `proceed=true` verandert deze historische kandidaat niet in een actuele firmwareaanbieding.

Voor het eerdere `ALREADY_UP_TO_DATE` ontbreekt de toenmalige Motorola-besliscontext. De zes onderzochte retained engine-logs bevatten geen bewijs voor een permanente toestelblokkade of de exacte serverbeslissing. Lokale caching, aanvraag-/fingerprintcontext en uitrolbeleid blijven mogelijke verklaringen, geen vastgestelde oorzaak. Motorola beschrijft verschillen tussen modellen, regio's en uitrolmomenten; deze algemene documentatie verklaart deze individuele gebeurtenis niet. [Motorola software-updatecontrole](https://en-us.support.motorola.com/app/answers/detail/a_id/172729/~/check-for-software-update).

## Wat hiermee wel en niet is vrijgegeven

Bevestigd zijn de huidige identiteit/root, succesvolle historische installatie, afgeronde merge, actuele engine-IDLE en de inhoudelijke herkomst van de huidige stock17-back-up. De [actuele technische meetgegevens](../evidence/device-validation/forensic-current-state.json) leggen bronverschillen en meetuitkomsten apart vast.

Dit geeft geen toestemming voor een nieuwe schrijfactie. Een volgende OTA vereist een nieuw werkelijk aanbod of passend officieel pakket, onafhankelijke authenticiteit, actuele bron-/doelvoorwaarden, regio- en rollbackbeoordeling en opnieuw gemeten toestand. De oude OTA wordt niet opnieuw aangeboden als nieuwe update. Een oude stock16-image, een generieke back-upnaam of een groen userspaceproperty mag die voorwaarden niet vervangen. De oorzaak van Motorola's tijdelijke aanbiedingsprobleem blijft expliciet onbekend.
