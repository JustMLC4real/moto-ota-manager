# Installatie en eerste gebruik — 1.0.0

[English](../INSTALLATION.md) · [Compatibiliteit](COMPATIBILITY.md) · [Veiligheid](SAFETY.md)

Gebruik Android 13/API 33 of nieuwer met arm64-v8a of x86_64. Haal APK en checksum uit dezelfde beoordeelde levering of release. Controleer checksum en signingidentiteit. Debug en release gebruiken verschillende identiteiten en kunnen niet over elkaar worden bijgewerkt. Bewaar herstelgegevens vóór een bewust geplande migratie. [Releaseondertekening](RELEASING.md).

Open de APK via Androids pakketinstaller en geef zo nodig toestemming voor de gekozen bestandsbron. De app installeert zichzelf niet, ontgrendelt geen bootloader, maakt geen eerste Magisk/rootinstallatie en verleent zichzelf geen root.

## Eerste gebruik

De introductie legt route, root en bewijsgrenzen uit. Kies Nederlands, Engels of systeemtaal. Instellingen biedt systeem/licht/donker en het opnieuw openen van de introductie. De introductie vraagt geen root en start geen update. Android 13+ biedt native [apptalen](https://developer.android.com/guide/topics/resources/app-languages).

Begin met diagnostiek. Basisidentiteit werkt zonder root waar Android die beschikbaar maakt. Vraag voor beschermde metingen root in de app en geef **Moto OTA Manager** toegang in Magisk. ADB-root staat los van de Superuser-toestemming van deze app. Geweigerde of ontbrekende observaties blijven onbekend.

Lees het mogelijkhedenrapport. Identiteit, bootindeling, officiële updater, root en bewijs bepalen wat mag doorgaan. Een ander Motorola-model wordt niet alleen vanwege zijn naam afgewezen. Kritieke handelingen vereisen alle runtime- en herkomstcontroles.

## Een update vinden

De primaire onlinecontrole gebruikt de brongebaseerde Motorola-CDS-provider. Geef expliciet de getoonde deeltoestemming voordat identificaties worden gelezen of verstuurd. Het verzoek deelt serienummer/buildcontext en de genoemde toestel/buildgegevens met de productiedienst. Geen IMEI of OEM-credentials. Intrekken stopt volgende verzoeken; gewone diagnostiek verzamelt deze identificaties niet. [Provider en privacy](CDS-PROVIDER.md).

Een aanbod is geen geauthenticeerde installeerbare firmware. Controleer doel en resourcesoort. Metadata-only is geen volledige OTA. Een toegestane volledige verwijzing kan naar de downloader, met pauze/annuleren/hervatten. Je kunt ook een ZIP kiezen met Androids bestandskiezer of een bestaande OEM-ZIP via de begrensde importroute kopiëren.

Gebruik de officiële Motorola-updater bij weigering, onbekend schema of ontbrekende geschikte resource. Counters wijzigen of updaterdata wissen bewijst geen echt aanbod.

## De workflow starten en volgen

Lees reikwijdte, keuze voor rootbehoud, doel en bewijs vóór starten. Starttoestemming geldt voor die beoordeelde intentie; ontbrekend bewijs wordt geen toestemming. Niet-ondersteunde of onzekere overgangen moeten stoppen. De precieze overgangen en herstelregels staan in [UPDATE-WORKFLOW](UPDATE-WORKFLOW.md).

Lokaal klaarzetten is een aparte OEM-overdracht die verwerking kan starten. Geauthenticeerde kanaalcontrole blijft vereist; een downloadreceipt, cachepad of geldige ZIP-handtekening vervangt die niet.

Lees na een update build/slot/root/boot/snapshot/modules/back-up opnieuw uit. Handmatig gemelde stappen blijven apart van onafhankelijk toestelbewijs. Een simulatie is nooit een voltooide telefoonupdate.

Achtergrondcontrole staat eerst uit. Online achtergrondcontrole vereist daarnaast deeltoestemming. Geef Android-meldingstoestemming als je meldingen wilt; een melding start geen installatie. [Monitoring en downloads](MONITORING-AND-DOWNLOADS.md).

Zelf bouwen: [README](../../README.nl.md). Geleverde bestanden en echte testlogs: [BUILD-REPORT](../BUILD-REPORT.md).
