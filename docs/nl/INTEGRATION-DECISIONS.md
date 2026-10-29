# Integratiekeuzes — 1.0.0

[English](../INTEGRATION-DECISIONS.md) · [Onderzoek](../RESEARCH.md)

De gekozen route behoudt de originele Motorola-ondertekende update en meet het ontvangende toestel. Detectie, pakketauthenticatie, stockherstel, OTA, root op het inactieve slot en de eindcontrole hebben eigen bewijscontracten binnen één begrijpelijke workflow. Als de eerste beoordeelde starttoestemming ze omvat, vragen de fases geen losse bevestigingen.

## Motorola CDS als primaire detector

[FixeQD/motorola-update](https://github.com/FixeQD/motorola-update/tree/272f1c9bbe930a00ed76fe65d481f5cb108dacdf) biedt protocolobservaties, geen ondersteunde Motorola-SDK. De app implementeert het productieprotocol en de begrensde parser onafhankelijk. Online CDS is primair; de officiële updater en lokale gegevens blijven terugval. Gedecompileerde OEM-code, libraries en ingebouwde credentials worden niet verspreid.

Het voorbeeldverzoek is onvoldoende om installatie toe te staan. Deze client vereist gemeten identiteit, toestemming, context/doel/resourcekoppeling en strikt HTTPS. Een aanbod blijft apart van geauthenticeerde bytes en installatiegeschiktheid. [Bronpinnen, schema, privacy en testgrenzen](CDS-PROVIDER.md).

Brononderzoek vereist geen verzoek met echte identificatie. Acceptatie van beperkte optionele telemetrie, werkelijk aanbod en overdracht moeten nog met toegestaan runtimebewijs worden gekwalificeerd. Weigering of `NO_OFFER` bewijst geen permanente toestelblokkade of ontbrekende Motorola-update.

## Magisk met een versie- en bewijsgrens

De [officiële OTA-gids](https://topjohnwu.github.io/Magisk/ota.html) beschrijft stockherstel, OTA zonder herstart, Magisk op het bijgewerkte inactieve slot en daarna herstart. Toestellen kunnen verschillen; dit is geen publieke automatiserings-API.

De onderzochte grens is **Magisk 31.1/code31100**, met bronpin [0972f67](https://github.com/topjohnwu/Magisk/tree/0972f67d97f9b6302b67c5171283330c74114b4a) en de [bestandspatcher](https://github.com/topjohnwu/Magisk/blob/0972f67d97f9b6302b67c5171283330c74114b4a/scripts/boot_patch.sh). Een private image patchen, een echte doelslot schrijven en de volgende boot kiezen zijn verschillende handelingen. Een script of succesmelding bewijst die laatste twee niet. De app verlangt onafhankelijk geauthenticeerde doelstock, een werkelijk gemeten doel, volledige imagecontrole en schrijf/readbackbewijs.

`MagiskInactiveController`/`MagiskInactiveProbe` accepteert alleen de geïnstalleerde officiële `com.topjohnwu.magisk`-APK met code31100, exact **10.679.283 bytes**, SHA256 **`89cefac0a318afa39fb3880c45c87b036c05bc1ebf02a7a715b7f8d7c26ac6e7`**. Dit klopt met het [officiële v31.1-releasebestand](https://github.com/topjohnwu/Magisk/releases/tag/v31.1). Verborgen/herverpakte managers, andere versies en ontbrekende tools stoppen deze adapter; officiële begeleiding blijft terugval.

De helper pakt alleen onderzochte scripts en vijf vaste ABI-tools uit die gecontroleerde geïnstalleerde APK. Hij leest de voltooide inactieve stockpartitie naar een private file, controleert de ondertekende doelhash, patcht privaat, controleert de ingebouwde ramdisk-stock-SHA1 onafhankelijk en bewaart een gevalideerde nieuwe Magisk-gzip zonder onbekende bestaande back-up te overschrijven. Een apart verbruikte korte actie staat de echte inactieve `boot`/`init_boot`-write toe, met bron/doelslot/enginecontroles en volledige readback op dezelfde descriptor. Geen `direct_install`, `post_ota`, `bootctl`, nieuwe AVB-keys of modulewijzigingen. Dit beschrijft code; uitvoeringsbewijs en toestelkwalificatie staan apart in het buildrapport.

Het updatepakket houdt Motorola's handtekening. De app schakelt geen signature-, bronblok-, payload-, verity- of rollbackcontrole uit. Nieuwe Android-/Magisk-versies vereisen adapteronderzoek. [Compatibiliteit](COMPATIBILITY.md), [stockherkomst](../STOCK-PROVENANCE.md).

## Waarom avbroot niet standaard is

[avbroot](https://github.com/chenxiaolong/avbroot/tree/ddddae031463636f2342157ac29a16490024cdde) patcht en ondertekent OTA/AVB-onderdelen met eigen beheerde sleutels. De locked-bootloaderopzet geeft een andere vertrouwens- en herstelketen. Dat past niet bij deze standaardroute met originele Motorola-OTA op een reeds geroot ontgrendeld toestel. Het is een aparte geavanceerde inrichting, geen onzichtbare extra downloader.

Motorola is niet categorisch ongeschikt: de [ondersteuningslijst](https://github.com/chenxiaolong/avbroot/issues/299) noemt meerdere door gebruikers geteste modellen. Dat bewijst geen Edge 70 Pro-ondersteuning. Op de telefoon worden geen eigen keys, bootloaderlocks, wipes of vervangende trustketens uitgeprobeerd.

## Betekenis van één start

Eén start kan alle getoonde handelingen voor één begrensde run omvatten. De adapter moet bij uitvoering nog steeds voorwaarden bewijzen, bij tegenspraak stoppen en herstelbewijs bewaren. Een achtergrondvoorkeur maakt een oude pakkettoestemming niet geldig voor alle toekomstige doelen.

De code gebruikt gemeten mogelijkheden en run-/actieautorisatie, geen universeel modelnaamvinkje of vrije rootshell. Het journaal bewaart voortgang en speelt na een crash geen opdracht opnieuw af. Waar bewijs ontbreekt blijven reden en officiële terugval zichtbaar. [Workflow en herstel](UPDATE-WORKFLOW.md).

[BUILD-REPORT](../BUILD-REPORT.md) beschrijft wat werkelijk is uitgevoerd. Synthetisch netwerkverkeer, bestandssimulatie van OTA/Magisk/herstart en historische fysieke uitlezing blijven verschillende bewijzen. Brononderzoek vervangt geen apart geplande werkelijke toestelupdate.
