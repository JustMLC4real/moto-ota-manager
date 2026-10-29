# Compatibiliteit en bewijs — 1.0.0

[English](../COMPATIBILITY.md)

Implementatie, emulatordekking en fysieke kwalificatie zijn verschillende bewijsniveaus. [BUILD-REPORT](../BUILD-REPORT.md) beschrijft echte uitvoeringen; [DEVICE-VALIDATION](../DEVICE-VALIDATION.md) beschrijft historische uitlezende toestelobservaties.

| Mogelijkheid | Grens |
|---|---|
| App | Android 13/API 33+, compile/target SDK 35, JVM 17 |
| Native bibliotheken | arm64-v8a en x86_64; NDK 27.2.12479018/CMake 3.22.1 |
| Weergave | Volledige standaard-Engelse en Nederlandse resources; native apptaal; systeem/licht/donker |
| Basisdiagnostiek | Werkelijk gelezen properties; ontbrekend of afgeschermd blijft onbekend |
| Motorola-begeleiding | Gemeten Motorola-identiteit, verse build/toestelfeiten en echte officiële-updaterresolver |
| Rootbehoudbegeleiding | Bekende A/B-indeling en slots, verse root/Magisk en back-upkoppeling |
| Stockschrijfadapter | Motorola A/B `boot` of `init_boot`, onderzocht API 33–37, Magisk-code31100, geauthenticeerde exacte huidige bytes, verse runtime-SHA1-koppeling en alle live controles |
| Directe OTA-adapter | Onderzochte framework-UpdateEngine `applyPayloadFd`, originele OEM-geauthenticeerde payload, gekoppelde bron/doel, begrensde actie en werkelijk voltooiings/need-rebootbewijs; OEM-permissions/SELinux vereisen runtimekwalificatie |
| Inactieve Magisk-adapter | Exact officieel geïnstalleerd `com.topjohnwu.magisk`, code31100/bekende volledige APK-SHA256, ondersteunde 64bit-ABI, signed doelstockhash, private patchbewijs, volgende-slotbewijs en verbruikte write/readbackactie; verborgen/herverpakt gebruikt terugval |
| Enkele/onbekende slotindeling | Diagnostiek en toepasselijke officiële begeleiding; geen geraden A/B-schrijfdoel |
| CDS-detector | Expliciete getoonde deeltoestemming, gemeten serienummer/GUID en ondersteund globaal productieschema; geen geraden identiteit of China-endpoint |
| Downloads | Consistente gemeten Motorola-identiteit, exacte toegestane HTTPS-host en volledige resource; overdracht is geen installatiegoedkeuring |
| OEM-import/klaarzetten | Versie/profiel/padspecifieke adapters; smaller bereik dan begeleiding of downloads |
| Achtergrondmonitor | Expliciete opt-in; CDS vereist ook onlinetoestemming; lokale terugval houdt de onderzochte profielparser |
| Toekomstig Android/onbekend | Diagnostiek waar beschikbaar; kritieke schrijfadapters worden niet stilzwijgend uitgebreid |

`DeviceCapabilities` gebruikt bewijs, geen geraden modellencatalogus. `QUALIFIED_MERC` identificeert het referentieprofiel; `PROOF_QUALIFIED` een andere configuratie die aan de huidige codecontroles voldoet. Geen van beide beweert een geslaagde fysieke OTA op ieder model. Historische referentie: XT2607-1/merc/reteu. Een andere SKU, regio, firmware of truststore vereist eigen kwalificatie.

Stockbewijs voor de huidige partitie kan de exacte gemeten bytes authentiseren via een OEM-ondertekend manifest en onafhankelijke actuele back-upkoppeling. Het authentiseert geen nieuw OTA-kanaal. Ontbrekende ondertekende model/kanaalvelden mogen voor dit beperkte bytebewijs als gemeten context worden bewaard; expliciete ondertekende tegenspraak blijft blokkerend. [Stockherkomst](../STOCK-PROVENANCE.md).

Motorola-klaarzetten vereist apart ondertekend `motorola-channel`/`pre-channel` exact `reteu` plus `report.regionVerified`. Onbekend kanaal blokkeert publicatie met `STAGING_CHANNEL_UNPROVEN`. Handtekening, host, cache, CDS-tracking of downloadreceipt vervangt dit niet. Motorola's normale aanbod blijft terugvalroute. [Stagingcontract](../MOTOROLA_STAGING.md).

De onderzochte AOSP-API 33–37 behoudt `bind`/`unbind`-volgorde en one-way status/completion-callbacks; Android 16 voegt alleen een laatste methode toe. Dit onderbouwt wirevergelijking, geen universele OEM-toegang. SELinux beperkt ook rootprocessen. [AOSP-interface](https://android.googlesource.com/platform/system/update_engine/+/android-13.0.0_r1/binder_bindings/android/os/IUpdateEngine.aidl), [SELinux](https://source.android.com/docs/security/features/selinux).

Herkwalificeer na firmware-, Magisk-, certificaat-, boot-, snapshot-, activity-, namespace- of OEM-padwijzigingen. Controleer voor native staging ook no-clobber, directoryduurzaamheid en ABI-loading. [Bekende beperkingen](KNOWN-LIMITATIONS.md).
