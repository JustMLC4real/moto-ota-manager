# Compatibiliteit en bewijsniveau

## Toestelprofielen

| Profiel | Status | Toelichting |
|---|---|---|
| Motorola Edge 70 Pro, XT2607-1, merc, reteu | Exclusief onderzocht doelprofiel | Werkelijke uitlezende metingen vastgelegd; definitieve APK-testresultaten afzonderlijk in het buildrapport |
| Android 17, SDK 37, A171WMH.44-25 | Werkelijk gemeten huidige situatie | Slot b, Magisk 31100, snapshot `NONE` en live engine `IDLE` bevestigd; iedere nieuwe controle leest opnieuw |
| Android 16, W3WMS36.40-75-5-2 | Historische situatie | De oude stock-image en OTA zijn geen basis voor een volgende Android 17-update |
| Andere XT2607-varianten, regio's of codenames | Geblokkeerd | Vereisen een apart onderzocht en getest profiel |
| Android 18 | Nog niet ondersteund als gevalideerde updateketen | Nieuwe platform- en OEM-controles vereist |

De marketingnaam `motorola edge 70 pro` is op het toestel gemeten, met technisch SKU `XT2607-1`. De mapper accepteert de marketingnaam niet zelfstandig: een passend technisch model/SKU is vereist en tegenstrijdige technische modelnummers blokkeren. Voor het kanaal worden alleen bekende carrierproperties gebruikt; een lege of tegenstrijdige waarde blijft onduidelijk. Zie [de werkelijke identiteit en bronconflicten](DEVICE-VALIDATION.md).

De begeleide voorbereiding is bewust strenger dan de technische minimum-SDK: zij vereist een werkelijk gemeten Android-versie 17, Magisk-code 31100, een ontgrendelde bootloader met verified-bootstatus `orange`, een als succesvol gemarkeerde huidige bootslot en een bekende module-inventaris. Ontbrekende of andere waarden blokkeren. De app blijft dan wel uitlezende diagnostiek aanbieden. Dit beleid beweert niet dat andere combinaties per definitie onveilig zijn; zij zijn voor dit product nog niet onderzocht.

## Integraties

| Integratie | Onderzochte referentie | Bewijs op dit toestel |
|---|---|---|
| Magisk 31.1 | Officiële tag v31.1, commit `0972f67d97f9b6302b67c5171283330c74114b4a` | Werkelijke root UID 0, versiecode 31100 en back-up gemeten; APK-hash matcht officiële release |
| Latere Magisk-versies | Geen automatische compatibiliteitsbelofte | Nieuwe CLI-/backup-/installercontrole vereist |
| Motorola com.motorola.ccc.ota | Werkelijk gelezen private schema en systeemactivity | Nested downloadjob is historische metadata; secure resolver kiest ingeschakelde geëxporteerde Motorola-component met positieve prioriteit |
| Android UpdateEngine | Gepind AOSP-interfacecontract en werkelijk geladen SDK 37-frameworkbindings | Uitsluitend rootcallback `bind`/`unbind` gaf live `IDLE`; geen directe installatie of cleanup |
| Boot-controlmetadata | AOSP-layout, 32-byte record met CRC | `bootctl` ontbreekt; directe beperkte uitlezing bevestigt twee slots en succesvolle slot b |
| Snapshotmetadata / Virtual A/B | AOSP-protobuf en 64-byte VAB-record | `snapshotctl` ontbreekt; consistente bestands-/directory-/VAB-bronnen bevestigen geen actieve merge |
| Toestel-OTA-truststore | Werkelijk gelezen otacerts-publickey | Historische whole-file-OTA-signatuur onafhankelijk op host geverifieerd met Python en OpenSSL; definitieve appverificatie apart gerapporteerd |
| Geauditeerde stockcatalogus | Authentiek OTA-doelmanifest en volledige actuele back-upbytes | Alleen exacte huidige fingerprint/build/model/device/kanaal/Android 17 plus passende toesteltrustanchor; geen algemene stockdatabase |

De gewone app kon een Virtual A/B-property ondanks exitcode 0 niet lezen; de root-herlezing leverde de werkelijke waarde. Private preferences vereisten op dit toestel de tijdelijke globale Magisk-mountnamespace voor uitsluitend twee vaste uitlezingen. Deze bevindingen zijn versiegebonden en vervangen geen nieuw onderzoek na een firmwarewijziging.

De actuele systeemfingerprint verschilt van vendor-/systeemaliases in de historische OTA. Die verschillen worden niet algemeen gelijkgesteld. De historische doelbuild is al geïnstalleerd en de afwijkende OEM-metadata blijft review vereisen, ook wanneer de handtekening geldig is.

## Begrippen in schermen en rapporten

- **Werkend geïmplementeerd:** de app voert de functie echt uit; tests/buildbewijs staan in het buildrapport. Dit zegt op zichzelf niets over geteste OEM-runtime.
- **Gesimuleerd:** testdata of een mockadapter. Geen waarneming van de echte telefoon.
- **Uitlezend op toestel bevestigd:** het genoemde diagnostische pad leverde een echte meting op het vastgelegde profiel. Dit bewijst geen geslaagde OTA-, restore- of rebootflow.
- **Nog op toestel te testen:** de implementatie is aanwezig maar het specifieke runtimepad of de definitieve APK-test is niet als voltooid gerapporteerd.
- **Onbewezen/uitgeschakeld:** er is geen betrouwbare externe integratie aangetoond; de app begeleidt of stopt.

## Herkwalificatie na een grote upgrade

Controleer opnieuw properties en fingerprints, kernel/init_boot-indeling, bootslot-HAL, snapshotenum en dumpformaat, engine-statuscallback, Magisk-back-upschema, rootrechtenmodel, Motorola-activiteiten/prefs, certificaatstore, metadataregels, batterij/opslagbeleid en het exportfilter. Test eerst zonder schrijfacties. Een versiecode alleen promoveert een nieuw platform niet tot ondersteund.

De onderbouwde metingen staan in [DEVICE-VALIDATION.md](DEVICE-VALIDATION.md); uitgevoerde build-, APK- en instrumentatiechecks staan afzonderlijk in [BUILD-REPORT.md](BUILD-REPORT.md). Er is geen uitgevoerde volledige OTA-keten vanuit deze app en Android 18 blijft ongetest.
