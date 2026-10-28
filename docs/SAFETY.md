# Veiligheidsmodel

## Grenzen van de geleverde app

Moto OTA Manager is in deze release een uitlezende diagnostiek- en validatieapp met begeleiding. De rootlaag accepteert een beperkte set vaste uitleesopdrachten. Er is geen algemene rootterminal voor gebruikers, geen partitieschrijver, geen directe UpdateEngine-installer en geen herstartfunctie. Het schrijven van appinstellingen, een geïmporteerd bestand in de eigen opslag of een expliciet geëxporteerd rapport is geen wijziging van systeempartities.

De echte telefoon is tijdens de ontwikkeling niet gebruikt om OTA, restore, Magisk-installatie of reboot te testen. Gesimuleerde root, engine en updater leveren uitsluitend testdata. Een mockresultaat is zichtbaar als simulatie en mag nooit echte gereedheid of een geslaagde toestelupdate bewijzen.

## Toestelprofiel en harde blokkades

Het doelprofiel is Motorola `XT2607-1`, codename `merc`, kanaal `reteu`. Een ontbrekende property is niet gelijk aan de gewenste waarde. De app moet stoppen bij een afwijkend model, onduidelijk kanaal, onbekende slot, onleesbare snapshotstatus, actieve merge, enginefout, onvoldoende accu/opslag, ontbrekende root of onbewezen stock-imageherkomst. Een resultaat van een oudere sessie is geen actuele veiligheidsmeting.

`MERGING`, `INITIATED`, `UNVERIFIED`, `MERGE_NEEDS_REBOOT`, `FAILED`, `CANCELLED` en `UNKNOWN` zijn geen toestemming voor een volgende OTA. Ook een gemelde merge-afronding moet in samenhang met engine-IDLE en de nieuwe toestand worden beoordeeld. Bij tegenstrijdige bronnen wint de onveilige of onbekende interpretatie.

## Back-ups en images

Een volledige partitieback-up moet exact de werkelijk gemeten partitiegrootte bevatten. Binaire bytes lopen niet door tekstdecodering; stderr en rootmeldingen worden nooit aan een image toegevoegd. De bekende fout met 83 extra bytes moet door omvangcontrole worden afgewezen.

Voor een stock-image zijn afzonderlijk nodig:

1. Geldig, ondersteund imageformaat en het verwachte partitietype.
2. Correcte volledige omvang; een gzip-compressiegrootte is geen partitiegrootte.
3. Hash van de uitgepakte bytes en intacte gzip-trailer indien gecomprimeerd.
4. Aantoonbare koppeling aan de actuele firmwarebuild en het toestelprofiel.
5. Onafhankelijke herkomst uit betrouwbare stock-firmware.

Een kopie van de huidige gepatchte partitie is een nuttige herstelkopie maar is geen originele stock-image. Een `boot.img.gz` in een Magisk-directory kan `init_boot` bevatten; de naam alleen identificeert het type niet. Een geslaagde hashcontrole bewijst byte-integriteit, niet de firmwareversie. Deze versie voert onder geen van deze omstandigheden zelf een restore uit.

## Firmware en authenticiteit

Statussen worden gescheiden: bestand ontvangen, pakketstructuur gelezen, inhoudshashes correct, ZIP-ondertekening door toesteltruststore gecontroleerd, compatibiliteit vastgesteld, OTA daadwerkelijk toegepast, Magisk voorbereid, nieuwe boot bevestigd en merge afgerond. Een gematchte SHA-256 is geen ondertekening. Een ondertekende ZIP is geen bewijs dat een incrementele payload past op de gewijzigde bronpartities.

De Android-updateketen blijft verantwoordelijk voor payloadsignatures, bronblokhashes, rollbackregels en OEM-controles. Moto OTA Manager schakelt geen van die controles uit. Het uitlezen van een interne Motorola-URL maakt die kandidaat niet automatisch geschikt. Onbekende regio, onduidelijke bronfingerprint, beta- of downgradeflags en tegenstrijdige metadata houden de installatie geblokkeerd.

## Begeleiding zonder onbewezen automatisering

Het openen van een geïnstalleerde app bewijst geen afgeronde actie. Een vinkje van de gebruiker is hoogstens een aantekening. Na terugkeer moeten nieuwe observaties de systeemtoestand bevestigen. De app kan de officiële Magisk-interface openen en uitleggen wat moet worden gecontroleerd, maar krijgt geen extern betrouwbare succesmelding voor de interne installer. Dit blijft een handmatige grens.

Een mislukte OTA nadat stock is hersteld is een onzekere tussenstand: root kan nog in de draaiende sessie aanwezig zijn terwijl een volgende boot root mist. Start dan geen willekeurige restore, slotwissel of nieuwe update. Bewaar het rapport, de exacte build- en imagegegevens en de officiële foutmelding en laat de toestand gericht onderzoeken. Deze app probeert geen herstel door partities te wijzigen.

## Rootuitvoering

Opdrachten zijn intern vastgelegd; OTA-URL's, bestandsnamen, JSON, XML en gebruikersinvoer worden geen shellcode. Tijdsoverschrijdingen, verkeerde exitcodes, ontbrekende tools en afgekapt resultaat worden fouten/onbekend. Het toestel hoeft niet in SELinux permissive te worden gezet. Magisk-policy, OTA-appdata en fouttellers worden niet aangepast.

Voor Magisk 31.1 is vooraf verlenen van Superuser-toegang nodig; deze app kan dat niet veilig aan zichzelf geven. Ontbrekende toestemming is een verklaarbare diagnose, geen reden om beleid te omzeilen. Zie [het versieonderzoek](RESEARCH.md).

## Privacy en lokale verwerking

Er is geen automatische upload of telemetrie. Diagnostiek wordt lokaal gegenereerd en alleen op verzoek geëxporteerd. Gebruik uitsluitend toegestane velden: technische properties, statuscategorieën, foutcodes en niet-persoonlijke hashes. Exporteer geen ruwe preference-XML, volledige download-URL's, tokenquery's, accountgegevens, serienummers, IMEI, inhoud van gebruikersbestanden of onbeperkte logcat.

De lokaal gemeten bootidentificatie dient om herstarten te herkennen; zij hoort niet als herleidbare identifier in een gedeeld rapport. Ook app-private data en een debug-APK verdienen normale toegangsbeveiliging. Een gebruiker kan een geëxporteerd rapport vooraf zelf bekijken; de app deelt het niet zelfstandig met GitHub of een andere partij.

## Verboden productacties

Factory reset, userdata wissen, bootloader vergrendelen, verificatie uitschakelen, rollback omzeilen, `dd` naar partities, `fastboot flash`, `bootctl set-*`, `mark-boot-successful`, snapshotmutatie, willekeurige rootcommando's, moduleverwijdering en automatische herstart zijn niet onderdeel van deze release. Een toekomstige implementatie van een schrijfactie vereist opnieuw ontwerp, aantoonbaar versiegebonden bewijs, onafhankelijke review en expliciete toestemming op het echte toestel.
