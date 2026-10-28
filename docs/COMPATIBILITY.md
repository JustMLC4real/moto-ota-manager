# Compatibiliteit en bewijsniveau

## Toestelprofielen

| Profiel | Status | Toelichting |
|---|---|---|
| Motorola Edge 70 Pro, XT2607-1, merc, reteu | Exclusief doelprofiel | Gebruikersspecificatie; echte app-runtime op dit toestel nog te valideren |
| Android 17, A171WMH.44-25 | Aangeleverde huidige situatie | App moet de actuele properties uitlezen, niet deze waarden invullen |
| Android 16, W3WMS36.40-75-5-2 | Historische situatie | De oude stock-image en OTA zijn geen basis voor een volgende Android 17-update |
| Andere XT2607-varianten, regio's of codenames | Geblokkeerd | Vereisen een apart onderzocht en getest profiel |
| Android 18 | Nog niet ondersteund als gevalideerde updateketen | Nieuwe platform- en OEM-controles vereist |

Een afwijkende marketingnaam in `ro.product.model` wordt niet zonder bewijs gelijkgesteld met het exacte modelnummer. Diagnostiek moet de ontbrekende/afwijkende eigenschap melden en ondersteuning kan later met een afzonderlijk onderbouwd propertycontract worden uitgebreid.

De begeleide voorbereiding is bewust strenger dan de technische minimum-SDK: zij vereist een werkelijk gemeten Android-versie 17, Magisk-code 31100, een ontgrendelde bootloader met verified-bootstatus `orange`, een als succesvol gemarkeerde huidige bootslot en een bekende module-inventaris. Ontbrekende of andere waarden blokkeren. De app blijft dan wel uitlezende diagnostiek aanbieden. Dit beleid beweert niet dat andere combinaties per definitie onveilig zijn; zij zijn voor dit product nog niet onderzocht.

## Integraties

| Integratie | Onderzochte referentie | Bewijs op dit toestel |
|---|---|---|
| Magisk 31.1 | Officiële tag v31.1, commit `0972f67d97f9b6302b67c5171283330c74114b4a` | Werking uit gebruikersspecificatie; app-roottoegang nog te testen |
| Latere Magisk-versies | Geen automatische compatibiliteitsbelofte | Nieuwe CLI-/backup-/installercontrole vereist |
| Motorola com.motorola.ccc.ota | Geïnstalleerde systeemapp, private prefs zoals aangeleverd | Schema en beschikbare activiteit moeten lokaal worden vastgesteld |
| Android UpdateEngine | Android 17 AOSP-bron | OEM Binder-/SELinux-toegang onbekend; geen directe installatie |
| Boot HAL/bootctl | Android 17 AOSP-bron | Binary, versie en toegangsrechten moeten worden gemeten |
| SnapshotManager/snapshotctl | Android 17 AOSP `system/fs/fs_mgr` | Dump-output en bereikbaarheid nog te testen |
| RecoverySystem.verifyPackage | Publieke Android-API | Certificaatstore en echte Motorola-OTA nog op toestel te testen |

## Begrippen in schermen en rapporten

- **Werkend geïmplementeerd:** de app voert de functie echt uit; tests/buildbewijs staan in het buildrapport. Dit zegt op zichzelf niets over geteste OEM-runtime.
- **Gesimuleerd:** testdata of een mockadapter. Geen waarneming van de echte telefoon.
- **Nog op toestel te testen:** de implementatie is aanwezig maar binary, properties, policy of OEM-gedrag zijn niet in deze sessie geverifieerd.
- **Onbewezen/uitgeschakeld:** er is geen betrouwbare externe integratie aangetoond; de app begeleidt of stopt.

## Herkwalificatie na een grote upgrade

Controleer opnieuw properties en fingerprints, kernel/init_boot-indeling, bootslot-HAL, snapshotenum en dumpformaat, engine-statuscallback, Magisk-back-upschema, rootrechtenmodel, Motorola-activiteiten/prefs, certificaatstore, metadataregels, batterij/opslagbeleid en het exportfilter. Test eerst zonder schrijfacties. Een versiecode alleen promoveert een nieuw platform niet tot ondersteund.
