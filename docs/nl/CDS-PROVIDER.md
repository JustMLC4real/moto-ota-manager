# Motorola-CDS-provider — 1.0.0

[English](../CDS-PROVIDER.md) · [Nederlands](CDS-PROVIDER.md)

De primaire online detector is een onafhankelijk geschreven Kotlin-client voor het productie-CDS-protocol uit [FixeQD/motorola-update, commit 272f1c9](https://github.com/FixeQD/motorola-update/tree/272f1c9bbe930a00ed76fe65d481f5cb108dacdf). Standaard gebruikt de app echt HTTPS; tests injecteren synthetische identiteiten en antwoorden. Tijdens implementatie is geen echt verzoek met een toestelidentificatie verstuurd.

Dit is een interne integratie op basis van brononderzoek, geen gedocumenteerde publieke Motorola-SDK. Serveracceptatie, uitrolbeslissingen en toekomstig schemagedrag zijn niet gegarandeerd. De officiële updater, herkenbare lokale cache en expliciete pakketimport blijven terugvalroutes.

## Toestemming en gegevens

Vóór de online-identiteitsuitlezing en HTTP is expliciete deeltoestemming vereist. Alleen de toestemmingsboolean wordt opgeslagen. Daarna leest een afzonderlijke vaste uitlezing serienummer en buildcontext-GUID. Deze komen niet in standaardprobes, `Inspection`, rapporten, WorkManager-input of geschiedenis. De client gebruikt geen IMEI, telefoonpermission, OEM-credentials of accountgegevens.

Het verzoek verstuurt serienummer, GUID, gemeten fabrikant/model/product/toestel/kanaal, build-ID/fingerprint/incremental, Android-versie, apptaal en beschikbare merk/hardware/localegegevens. De server ziet normale verbindingsgegevens, waaronder het netwerk-IP. Identificaties en ruwe antwoorden blijven in geheugen. Alleen een toegestane download-URL gaat naar de versleutelde downloaderopslag. Intrekken stopt volgende verzoeken en wist het controllerresultaat; een al verstuurd verzoek kan niet worden teruggehaald.

## Protocolgrenzen

Uitsluitend `https://moto-cds.appspot.com`, zonder redirects, andere cloud of geraden China-/stagingserver. De check gaat naar `/cds/upgrade/1/check/ctx/ota/key/<gemeten GUID>`. Het verzoek gebruikt de aangetoonde appstructuur met alleen `serialNumber` in `identityInfo`.

Antwoorden vereisen begrensde strikte JSON zonder dubbele keys, passend `ota`-context/GUID, expliciete doelfingerprint en passende bron-GUID als deze aanwezig is. De resourceaanvraag volgt alleen op een aanbod; teruggegeven timestamp/tracking/reportinggegevens binden de tweede aanvraag, waarvan tracking exact moet passen. Alleen bekende Wi-Fi-resources op de exacte downloadhost met een volledige `.zip`-verwijzing zijn pakketkandidaten. Metadata-only blijft apart. Niet-lege willekeurige resourceheaders worden niet doorgestuurd.

Grenzen: verzoek 16 KiB, antwoord 256 KiB, JSON-diepte 12, 4096 waarden, strings 16 KiB, 16 resources en begrensde netwerkwachttijden. [CDS-constanten](https://github.com/FixeQD/motorola-update/blob/272f1c9bbe930a00ed76fe65d481f5cb108dacdf/app-src/src/java/com/motorola/otalib/cdsservice/utils/CDSUtils.java), [app-checkroute](https://github.com/FixeQD/motorola-update/blob/272f1c9bbe930a00ed76fe65d481f5cb108dacdf/app-src/src/java/com/motorola/ccc/ota/sources/bota/BotaUpgradeSource.java), [BuildPropReader](https://github.com/FixeQD/motorola-update/blob/272f1c9bbe930a00ed76fe65d481f5cb108dacdf/app-src/src/java/com/motorola/ccc/ota/utils/BuildPropReader.java).

De client beperkt optionele telemetrie; acceptatie daarvan vereist een apart toegestaan praktijktestverzoek. De libraryvariant `CheckUpdateHandler` gebruikt een extern aangeleverde numerieke bronversie. De client raadt deze niet uit een timestamp en maakt van het Android-build-ID geen getal. Hij gebruikt de appvariant met de echte string-build-ID. Het IMEI-rijke README-voorbeeld is geen protocolauthoriteit.

## Betekenis en bewijs

`NO_OFFER` betekent geen aanbod voor deze aanvraag. `OFFER` identificeert een andere doelbuild. De resource kan ontbreken, onbekend zijn, verlopen of alleen metadata bevatten. De client voert geen serverinstructies uit, uploadt geen gevraagde logs en wijzigt geen updatercounters.

Het schema toont omvang/MD5, doelfingerprint en bron/doel-GUIDs. Volledige pakket-SHA256 of onafhankelijk ondertekend kanaal is hiermee niet aangetoond. HTTPS-binding blijft gescheiden van ZIP-authenticatie, stockherkomst, compatibiliteit en installatiebevoegdheid. [Metadatabuilder](https://github.com/FixeQD/motorola-update/blob/272f1c9bbe930a00ed76fe65d481f5cb108dacdf/app-src/src/java/com/motorola/otalib/common/metaData/builder/MetaDataBuilder.java), [resourceschema](https://github.com/FixeQD/motorola-update/blob/272f1c9bbe930a00ed76fe65d481f5cb108dacdf/app-src/src/java/com/motorola/otalib/cdsservice/responsedataobjects/builders/ContentResourcesBuilder.java).

Achtergrondcontrole vereist monitor-opt-in plus online toestemming. Alleen een verse andere doelbuild geeft een melding; deduplicatie gebruikt geen identificaties of URLs. Fouten blijven stil en kunnen naar lokale cache terugvallen. Een melding start geen download of installatie.

De onderzoeksrepository draagt [AGPL 3.0](https://github.com/FixeQD/motorola-update/blob/272f1c9bbe930a00ed76fe65d481f5cb108dacdf/LICENSE) en bevat gedecompileerde OEM-bron. Die bestanden staan buiten deze distributie. Deze onafhankelijk geschreven client bevat geen gekopieerde OEM-implementatie, credentials, firmware of binary.

`CdsProtocolTest`, `CdsMonitorPolicyTest`, `HttpsCdsTransportTest` en emulator-only `CdsControllerRuntimeTest` beschrijven synthetische dekking. Werkelijk uitgevoerde tests en resultaten staan in [BUILD-REPORT](../BUILD-REPORT.md).
