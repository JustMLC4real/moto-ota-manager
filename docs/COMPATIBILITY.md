# Compatibiliteit en bewijsniveau — 0.3.0

Een geïmplementeerde functie, een emulatorproef en een meting op de Motorola hebben verschillende bewijsniveaus. Dit document beschrijft de broncodegrenzen. Alleen [BUILD-REPORT.md](BUILD-REPORT.md) en [TEST-MATRIX.md](TEST-MATRIX.md) bepalen welke builds en tests daadwerkelijk zijn uitgevoerd.

## Android, ABI en toestelprofiel

| Onderdeel | Huidige grens |
|---|---|
| Android-app | `minSdk 33`, `compileSdk 35`, `targetSdk 35`; Kotlin/JVM 17 |
| Native stagingbibliotheek | `arm64-v8a` en `x86_64`; NDK `27.2.12479018` |
| Onderzocht toestelprofiel | Motorola, technisch SKU `XT2607-1`, codenaam `merc`, kanaal `reteu` |
| Begeleide updatevoorbereiding | Android `17`, gemeten Magisk-code `31100`, actuele succesvolle A/B-boot, bekende modules en veilige boot-/snapshot-/enginetoestand |
| Live roothelpers voor engine, firmwarecontrole, stockherstel en staging | Werkelijke UID `0` en SDK `37`; geen toegang via alleen een manifestpermission |
| Stockherstel naar partitie | Alleen de huidige `init_boot_a` of `init_boot_b`, precies `8.388.608` bytes; geen willekeurig partitiepad |
| Externe Motorola-staging | Alleen Android-gebruiker `0` en vaste externe appmappen; ondertekend `motorola-channel`/`pre-channel` exact `reteu` én `report.regionVerified` vereist vóór publicatie |
| Achtergrondmonitor | Exact profiel en Android `17`; melding alleen bij gemeten root, verse diagnostiek, snapshot `NONE`, engine `IDLE` en herkende nieuwe lokale metadata |
| Andere modellen, kanalen of onbekende layout/SDK | Diagnostiek kan informatie tonen; versiegebonden live acties stoppen bij ontbrekend profiel-/runtimebewijs |
| Android 18 of andere Magisk-code | Geen gevalideerde begeleide updateketen; geen automatische uitbreiding van SDK-37-helpers |

Installatie op API 33 of hoger is geen ondersteuning van alle rootacties op die Android-versie. Downloads zijn een afzonderlijke byteoverdracht met profiel- en broncontroles; zij autoriseren geen stockherstel, staging of installatie.

De mapper accepteert de marketingnaam `motorola edge 70 pro` niet zelfstandig. Een technisch model/SKU is vereist; tegenstrijdige technische modellen of carrierproperties maken het profiel onbekend. Android-metadata, payloadproperties, vendor en de draaiende systeemimage behouden hun afzonderlijke fingerprints.

## Historisch toestelbewijs en de nieuwe ontwikkelfase

De uitlezende validatie van **0.2.0-safe** staat in [DEVICE-VALIDATION.md](DEVICE-VALIDATION.md): Android 17/SDK 37, build `A171WMH.44-25`, slot `_b`, Magisk `31100`, succesvolle boot, snapshot `NONE`, actuele engine `IDLE` en de geauthenticeerde huidige stockback-up. De historische handmatige Android-16-naar-17-update is geen door 0.3.0 uitgevoerde OTA.

De nieuwe ontwikkelfase gebruikt de echte telefoon niet via ADB. JVM-, emulator- en native tests promoveren de nieuwe partitieschrijver of Motorola-mapintegratie niet tot hardwarebewezen functies. Nieuwe fysieke kwalificatie van 0.3.0 blijft afzonderlijk nodig. Hiermee is geen nieuwe OTA, restore, slotwissel, Magisk-installatie of reboot op de telefoon aangetoond.

## Integraties in 0.3.0

| Integratie | Geïmplementeerd gedrag | Resterende grens |
|---|---|---|
| Magisk 31.1 | Root/versie meten, bestaande back-ups lezen en valideren, officiële app openen | Geen stabiele externe installer-API aangetoond; installatie naar de andere slot blijft handmatig gemeld tot nieuwe boot/root is gemeten |
| Motorola-updater | Beveiligde systeemactivity-resolver, getypeerde lokale metadata, expliciete overdracht | Geen publiek bevestigd aanbod- of installatiecontract voor dit model |
| WorkManager 2.10.5 | Opt-in periodieke lokale controle en expliciete foregrounddownload | Android bepaalt uitvoeringstijd; ontbrekende metadata bewijst geen ontbrekend serveraanbod |
| Firmwareverificatie | Structuur, payload-/metadatahashes, bronvoorwaarden en `RecoverySystem.verifyPackage` tegen toestelcertificaten | Authenticiteit bewijst geen kanaal, passende bronblokken, OEM-geschiktheid of aanbod |
| FirmwareVault | Private pin via hardlink/gecontroleerde kopie; selectie en ondertekende init_boot-targetfeiten bewaren | Een pad is geen verificatiebewijs; targetfeiten worden pas na een passende waargenomen doelboot aan de systeemfingerprint gebonden |
| Bestaande Motorola-ZIP importeren | Begrensde lijst en expliciet gekozen file uit de vaste OEM-map naar een private transactie kopiëren en terughashen | Uitlezende bronoverdracht via UID 0/SDK 37; geen aanbod-, ondertekenings- of compatibiliteitsbewijs |
| Begeleid stockherstel | Expliciet plan, kort geldig HMAC, éénmalige bevestiging, volledige gepatchte back-up, tweede scan en readback | Echte partitieschrijfactie; nieuwe hardwarekwalificatie ontbreekt; geen automatische retry/rollback |
| Motorola-staging | Apart bevestigde voorbereide kopie via JNI atomair zonder overschrijven publiceren, uitsluitend met ondertekend bewezen `reteu`-kanaal | Onbekend kanaal blokkeert met `STAGING_CHANNEL_UNPROVEN`; intern mapgedrag kan meteen OEM-verwerking starten en is geen publieke API |
| Sandbox | Begeleide keten en herstelcontracten tegen private gewone bestanden | OTA, Magisk, slot en reboot worden gesimuleerd; geen live autorisatie |

WorkManager 2.10 is met SDK 35 gebouwd; 2.10.5 is de vastgelegde patchversie. [Officiële releasegegevens](https://developer.android.com/jetpack/androidx/releases/work#version_2105). De Magisk A/B-volgorde is een referentie, maar de officiële gids noemt Pixel als getest platform en geeft geen Motorola-garantie. [Magisk OTA-gids](https://topjohnwu.github.io/Magisk/ota.html).

Een geldige ZIP-ondertekening, downloadhost, cacheverwijzing of transferreceipt bewijst het pakketkanaal niet. Bij ontbrekend ondertekend kanaalbewijs blijven importeren, downloaden en onderzoeken beschikbaar; de fallback is Motorola's normale officiële aanbodroute, zonder het lokale pakket te publiceren.

## Herkwalificatie

Hercontroleer bij nieuwe firmware, Magisk, toestelvariant of certificaatstore properties en kernelbootfeiten, init_boot-layout/grootte, bootmetadata, snapshots, frameworkcallback, namespace, back-upschema, Motorola-mappen/activities en herkomstketen. Voor staging horen daar FUSE-mount, `renameat2(RENAME_NOREPLACE)`, directory-fsync en native libraryloading bij. Onbekend blijft een blokkade.

Zie [MONITORING-AND-DOWNLOADS.md](MONITORING-AND-DOWNLOADS.md), [UPDATE-WORKFLOW.md](UPDATE-WORKFLOW.md) en [KNOWN-LIMITATIONS.md](KNOWN-LIMITATIONS.md).
