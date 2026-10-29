# Veiligheid en bewijs — 1.0.0

[English](../SAFETY.md) · [Workflow](UPDATE-WORKFLOW.md)

Observatie, gebruikersintentie, byte-integriteit, geauthenticeerde herkomst en werkelijk voltooide handelingen blijven gescheiden. Onbekend of tegenstrijdig kan geen schrijven toestaan. Startbevestiging geldt alleen voor de getoonde reikwijdte, het toestel, doel en de keuze voor rootbehoud. Zij vervangt geen technisch bewijs.

## Stockschrijven

Productie-stockherstel schrijft een echte partitie. Vereist zijn verse ondersteunde Motorola-A/B-configuratie, bekende `boot`/`init_boot` met exact gemeten grootte, onderzochte SDK/Magisk, passende identiteit, geauthenticeerde stockbytes en onafhankelijke actuele Magisk-runtime-SHA1-koppeling. Accu, opslag, succesvolle ontgrendelde boot, kernelbeveiligingsbewijs, idle-engine, veilige snapshots, modules en automatische-updatesinstelling worden opnieuw gecontroleerd bij het schrijven. De huidige image wordt eerst duurzaam geback-upt. Readback en journalbewijs bepalen succes; alleen een exitcode niet.

Stockbewijs authentiseert exacte huidige partitiebytes, niet regio of installeerbaarheid van een nieuwe OTA. Gzipnaam, SHA1-directory, header, handmatige buildnaam of kopie van een gepatchte partitie volstaan niet. ZIP-authenticatie, imagehash/grootte en runtimekoppeling hebben aparte rollen. [Stockherkomst](../STOCK-PROVENANCE.md).

Onderbreking tijdens schrijven vereist uitlezing en transactieherstel. De app speelt die handeling niet opnieuw af en rolt niet automatisch terug. De writerlock vergrendelt Motorola's aparte updater niet atomair. Snapshotcontroles en uitgeschakelde automatische updates beperken races, maar vormen geen OEM-transactiegarantie. Opslagfouten en stroomverlies blijven mogelijk.

## Pakketten en OTA

HTTPS, CDS-tracking, host of transferreceipt authentiseert geen firmware. Volledige ZIP-handtekeningen bewijzen niet alle bronblokhashes, rollbackregels, kanaal- of OEM-voorwaarden. Serveraanbod en lokale compatibiliteit blijven apart. Android/Motorola moeten hun eigen handtekening-, payload- en rollbackcontroles behouden.

Motorola-klaarzetten kan direct OEM-verwerking starten. Apart ondertekend `reteu`-kanaalbewijs is vóór publicatie vereist; onbekend kanaal blokkeert. Cache of CDS-receipt vormt geen uitzondering. Native atomaire no-clobber-publicatie verbergt onvolledige bestanden, maar bewijst geen OEM-acceptatie of installatie. [MOTOROLA_STAGING](../MOTOROLA_STAGING.md).

Magisk/runtime-integratie mag alleen de onderzochte adapter en bewijscontracten gebruiken. App openen, shellexit of gebruikersmelding bewijst geen inactieve-slotpatch. Herstart en nieuwe-bootmetingen blijven apart van voorbereiding. Niet-ondersteunde integratie moet met een eerlijke terugval stoppen in plaats van succes te verzinnen.

## Privacy en simulatie

Onlinecontrole vereist expliciete deeltoestemming. Serienummer/GUID blijven buiten normale inspectie en export; geen IMEI of OEM-credentials. Downloads maken echte verbindingen met de toegestane bron. Rapporten bevatten alleen toegestane technische velden. Volledige rootcompromittering valt buiten bescherming door private opslag en Keystore. [CDS-provider](CDS-PROVIDER.md), [monitoring/downloadprivacy](MONITORING-AND-DOWNLOADS.md).

Sandboxtests schrijven gewone private bestanden en simuleren OTA, Magisk, slots, herstart en merge. Historisch toestelbewijs betreft uitlezing. Sandboxsucces of een APK-build bewijst geen geslaagde actuele fysieke update. [BUILD-REPORT](../BUILD-REPORT.md), [historische validatie](../DEVICE-VALIDATION.md).
