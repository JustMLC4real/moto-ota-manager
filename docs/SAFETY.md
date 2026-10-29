# Veiligheidsmodel

## Grenzen van de geleverde app

Moto OTA Manager bevat uitlezende diagnostiek, validatie, duurzame begeleiding en een afzonderlijke, nauw begrensde stockhersteladapter. Alleen die adapter kan na expliciet beoordeelde toestemming de huidige init_boot-partitie herstellen. OTA, Magisk naar de inactieve slot en herstart blijven bij de officiële apps. Er is geen algemene rootterminal, directe UpdateEngine-installer, slotwissel of rebootfunctie. De nieuwe live schrijver is nog niet fysiek getest; de volledige uitgevoerde schrijfbewijzen komen uit de regular-file sandbox. Zie [de workflow en transactiegrenzen](UPDATE-WORKFLOW.md).

Er bestaan daarnaast afzonderlijke kopieerhelpers: bestaande Motorola-ZIP naar app-private opslag en optionele geauthenticeerde ZIP met ondertekend bewezen reteu-kanaal naar de Motorola-map. De tweede route vereist een aparte expliciete stagingbevestiging omdat Motorola het bestand mogelijk direct herkent; zij is best effort en nog niet fysiek getest. Geen van deze helpers schrijft een partitie of roept een installer aan. Zie [het stagingcontract](MOTOROLA_STAGING.md).

Op de echte `XT2607-1 / merc / reteu`, Android 17-build `A171WMH.44-25`, zijn uitlezende root-, slot-, snapshot-, engine- en back-upmetingen uitgevoerd. De live engine meldde `IDLE`; consistente metadata bevestigde geen actieve merge en succesvolle slot b. De [toestelvalidatie](DEVICE-VALIDATION.md) beschrijft het bewijs. Deze ontwikkelvalidatie voerde geen OTA, restore, Magisk-patch, slotwissel of reboot uit. Definitieve APK- en instrumentatieresultaten staan afzonderlijk in [het buildrapport](BUILD-REPORT.md).

Gesimuleerde root, engine en updater leveren uitsluitend testdata. Een mockresultaat is zichtbaar als simulatie en mag nooit echte gereedheid of een geslaagde toestelupdate bewijzen.

## Toestelprofiel en harde blokkades

Het doelprofiel is Motorola `XT2607-1`, codename `merc`, kanaal `reteu`. Een ontbrekende property is niet gelijk aan de gewenste waarde. De app moet stoppen bij een afwijkend model, onduidelijk kanaal, onbekende slot, onleesbare snapshotstatus, actieve merge, enginefout, onvoldoende accu/opslag, ontbrekende root of onbewezen stock-imageherkomst. Een resultaat van een oudere sessie is geen actuele veiligheidsmeting.

`MERGING`, `INITIATED`, `UNVERIFIED`, `MERGE_NEEDS_REBOOT`, `FAILED`, `CANCELLED` en `UNKNOWN` zijn geen toestemming voor een volgende OTA. Ook een gemelde merge-afronding moet in samenhang met engine-IDLE en de nieuwe toestand worden beoordeeld. Bij tegenstrijdige bronnen wint de onveilige of onbekende interpretatie.

De daadwerkelijk aangetroffen userspaceproperties `locked/green` spreken de kernelbootconfig `unlocked/orange` tegen. De app bewaart deze mismatch en gebruikt de ondersteunde kernelbron voor de gemelde beveiligingstoestand. Een property of rootmeting is geen hardwareattestatie; de app repareert of verbergt het conflict niet. Android 18 en andere builds worden niet stilzwijgend met de huidige kwalificatie goedgekeurd.

## Back-ups en images

Een volledige partitieback-up moet exact de werkelijk gemeten partitiegrootte bevatten. Binaire bytes lopen niet door tekstdecodering; stderr en rootmeldingen worden nooit aan een image toegevoegd. De bekende fout met 83 extra bytes moet door omvangcontrole worden afgewezen.

Voor een stock-image zijn afzonderlijk nodig:

1. Geldig, ondersteund imageformaat en het verwachte partitietype.
2. Correcte volledige omvang; een gzip-compressiegrootte is geen partitiegrootte.
3. Hash van de uitgepakte bytes en intacte gzip-trailer indien gecomprimeerd.
4. Aantoonbare koppeling aan de actuele firmwarebuild en het toestelprofiel.
5. Onafhankelijke herkomst uit betrouwbare stock-firmware.

Een kopie van de huidige gepatchte partitie is een nuttige herstelkopie maar is geen originele stock-image. Een `boot.img.gz` in een Magisk-directory kan `init_boot` bevatten; de naam alleen identificeert het type niet. Een geslaagde hashcontrole bewijst byte-integriteit, niet de firmwareversie. Stockherstel vereist aanvullend het volledige gecontroleerde plan, eenmalige HMAC-toestemming en alle verse live gates.

Voor de exact gemeten huidige build is nu wel onafhankelijke stockherkomst vastgesteld: de volledige uitgepakte 8 MiB-back-up matcht omvang en SHA-256 van de nieuwe `init_boot` in het geauthenticeerde historische OTA-manifest. `VerifiedStockCatalog` bevat uitsluitend die geauditeerde referentie, zonder imagebytes. Zij wordt alleen actief bij exact passende Android 17-identiteit, build, fingerprint en de juiste werkelijk gelezen toestel-OTA-SPKI. Een andere build, regio, fingerprintalias, trustanchor of simulatie krijgt deze referentie niet. Een geïmporteerd JSON-record of handmatige claim mag geen referentie authentiek maken.

`FirmwareVault` kan daarnaast een toekomstige stockreferentie vastleggen uit een werkelijk geauthenticeerde OTA met geparseerd new-init_boot-manifest. Die doelclaim wordt pas na een verse werkelijke doelboot aan de gemeten systeemfingerprint gebonden. De volledige nieuwe back-up moet daarna exact overeenkomen met de authentieke imagehash en grootte. Er is geen door de gebruiker importeerbaar trustmanifest of aliasnormalisatie.

## Firmware en authenticiteit

Statussen worden gescheiden: bestand ontvangen, pakketstructuur gelezen, inhoudshashes correct, ZIP-ondertekening door toesteltruststore gecontroleerd, compatibiliteit vastgesteld, OTA daadwerkelijk toegepast, Magisk voorbereid, nieuwe boot bevestigd en merge afgerond. Een gematchte SHA-256 is geen ondertekening. Een ondertekende ZIP is geen bewijs dat een incrementele payload past op de gewijzigde bronpartities.

De Android-updateketen blijft verantwoordelijk voor payloadsignatures, bronblokhashes, rollbackregels en OEM-controles. Moto OTA Manager schakelt geen van die controles uit. Het uitlezen van een interne Motorola-URL maakt die kandidaat niet automatisch geschikt. Onbekende regio, onduidelijke bronfingerprint, beta- of downgradeflags en tegenstrijdige metadata houden generieke pakketgoedkeuring geblokkeerd. De afzonderlijke officiële begeleidingsroute mag Motorola openen zonder zo'n generieke goedkeuring; zij delegeert authenticiteit/compatibiliteit expliciet aan Motorola en claimt geen lokaal goedgekeurde directe installatie.

De lokale parser onderscheidt metadata-only, OTA-ZIP en onbekend. De werkelijk gevonden `payload_metadata.bin`-job hoort bij de al geïnstalleerde doelbuild en kan nooit als nieuwe volledige OTA worden gedownload. Een allowlisted host alleen bewijst geen resourcesoort of actueel serveraanbod. Onbekende JSON-/XML-schema's, duplicaten en overschreden leesgrenzen geven geen downloadtoestemming; targetidentiteit wordt niet uit een bestandsnaam geraden.

De historische OTA bevat daadwerkelijk verschillende OEM-fingerprintnamespaces en Android-labels. Geldige authenticiteit heft die compatibiliteitsafwijking niet op: `OEM_METADATA_REVIEW_REQUIRED` en de exacte broncontroles blijven gelden. Er bestaat geen algemene aliasregel of publieke Motorola-check-API die hieruit toestemming voor een volgende update kan afleiden.

## Begeleiding zonder onbewezen automatisering

Het openen van een geïnstalleerde app bewijst geen afgeronde actie. Een vinkje van de gebruiker is hoogstens een aantekening. Na terugkeer moeten nieuwe observaties de systeemtoestand bevestigen. De app kan de officiële Magisk-interface openen en uitleggen wat moet worden gecontroleerd, maar krijgt geen extern betrouwbare succesmelding voor de interne installer. Dit blijft een handmatige grens.

De updaterknop resolveert uitsluitend systeemapps met `MATCH_SYSTEM_ONLY`, positieve prioriteit en een ingeschakelde geëxporteerde activity van `com.motorola.ccc.ota`; zij opent een expliciet component. Een willekeurige intenthandler mag het update-intent niet onderscheppen. Ook de Magisk-launcher wordt aan zijn pakketcomponent gebonden. Ontbrekende geschikte componenten vragen handmatige navigatie.

Een mislukte OTA nadat stock is hersteld is een onzekere tussenstand: root kan nog in de draaiende sessie aanwezig zijn terwijl een volgende boot root mist. Start dan geen willekeurige restore, slotwissel of nieuwe update. Bewaar het rapport, de exacte build- en imagegegevens en de officiële foutmelding en laat de toestand gericht onderzoeken. Recovery leest onder dezelfde writerlock volledige hashes; zij schrijft geen partitie terug. Een expliciete nieuwe voorbereiding kan uitsluitend na volledig bewezen ongewijzigde oorspronkelijke gepatchte bron worden vrijgegeven en maakt de oude toestemming ongeldig.

De live schrijver is begrensd tot UID 0/SDK 37, XT2607-1/merc/reteu Android 17, Magisk 31100 en 8 MiB init_boot. Hij vereist een dubbele verse gatecontrole, automatische updates uit, volledige gepatchte backup, HMAC-gebonden boot/slot/bronhash, eenmalige duurzaam verbruikte toestemming en volledige readback. De helper heeft een eigen 55s watchdog en controleert de 60s monotonic- en wallclocktoestemming direct vóór schrijven. Er is geen atomair interlock met de Motorola-updater en geen garantie tegen een I/O-stall; een onderbreking blijft herstelvereist. De sleutel is app-private; dit is geen verdediging tegen andere reeds bevoegde rootprocessen.

## Rootuitvoering

Opdrachten zijn intern vastgelegd; OTA-URL's, bestandsnamen, JSON, XML en gebruikersinvoer worden geen shellcode. Tijdsoverschrijdingen, verkeerde exitcodes, ontbrekende tools en afgekapt resultaat worden fouten/onbekend. Het toestel hoeft niet in SELinux permissive te worden gezet. Magisk-policy, OTA-appdata en fouttellers worden niet aangepast.

Alleen ontbrekende allowlisted properties krijgen na bevestigde rootverlening een root-herlezing. De twee vaste Motorola-preference-uitlezingen, de expliciete verificatie van een bestaande Motorola-ZIP en het afzonderlijke gecontroleerde stockhelperprotocol gebruiken `su --target 0`. Dit kiest de bestaande globale Magisk-mountnamespace uitsluitend voor het nieuwe kindproces. Er is geen permanente namespacewijziging, aanpassing van appisolatie of wijziging van Superuser-policy. Alleen de expliciet bevestigde stockhelper kan via zijn eigen gecontroleerde pad schrijven.

De oude `bootctl`-probenamen blijven voor historische testfixtures bestaan, maar hun runtimecommando's zijn uitgeschakeld als `UNSAFE_INITIALIZER_DISABLED`. De diagnostiek gebruikt de read-only CRC-/kernel-/snapshotfallback; een aanwezige bootctl-tool wordt niet alsnog gestart.

Wanneer de commandotools ontbreken, leest de fallback begrensde boot-/Virtual A/B-records met formaat- en CRC-controle en gecontroleerde snapshotmetadata. De aparte roothelper ondersteunt uitsluitend SDK 37 en werkelijke frameworkbindings. Haar beperkte `ServiceManager`-reflectie is tot dit proces beperkt; de enige engineaanroepen zijn `bind` en `unbind`. Een watchdog begrenst het proces. Geen hidden-API-vrijstelling, installer-, cleanup-, property- of beleidsmutatie wordt uitgevoerd. Niet-ondersteunde toegang blijft `UNKNOWN`.

De afzonderlijke `FirmwareSignatureProbe` is eveneens begrensd tot UID 0/SDK 37. Zij accepteert uitsluitend een bestaand regulier ZIP-bestand in de gecanonicaliseerde Motorola-updatermap, controleert omvang en bestandsidentiteit, hasht de bytes en roept de publieke `RecoverySystem.verifyPackage` aan. Zij schrijft geen bestand en heeft geen installatiepad. Deze route is positief getest met de al aanwezige officiële OTA, zonder staging. Beide helpers verlaten na afloop alleen hun eigen proces; dit is geen telefoonherstart.

Voor Magisk 31.1 is vooraf verlenen van Superuser-toegang nodig; deze app kan dat niet veilig aan zichzelf geven. Ontbrekende toestemming is een verklaarbare diagnose, geen reden om beleid te omzeilen. Zie [het versieonderzoek](RESEARCH.md).

## Privacy en lokale verwerking

Er is geen automatische upload of telemetrie. Diagnostiek wordt lokaal gegenereerd en alleen op verzoek geëxporteerd. Gebruik uitsluitend toegestane velden: technische properties, statuscategorieën, foutcodes en niet-persoonlijke hashes. Exporteer geen ruwe preference-XML, volledige download-URL's, tokenquery's, accountgegevens, serienummers, IMEI, inhoud van gebruikersbestanden of onbeperkte logcat.

De lokaal gemeten bootidentificatie dient om herstarten te herkennen; zij hoort niet als herleidbare identifier in een gedeeld rapport. Ook app-private data en een debug-APK verdienen normale toegangsbeveiliging. Een gebruiker kan een geëxporteerd rapport vooraf zelf bekijken; de app deelt het niet zelfstandig met GitHub of een andere partij.

## Verboden productacties

Factory reset, userdata wissen, bootloader vergrendelen, verificatie uitschakelen, rollback omzeilen, `dd` naar partities, `fastboot flash`, `bootctl set-*`, `mark-boot-successful`, snapshotmutatie, willekeurige rootcommando's, moduleverwijdering en automatische herstart zijn niet onderdeel van deze release. Alleen het beschreven init_boot-stockherstel heeft een partitieschrijvende adapter; uitbreiding daarvan vereist eigen versiegebonden bewijs, review en expliciete toestemming.
