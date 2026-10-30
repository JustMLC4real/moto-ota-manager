# Monitoring, detectie en downloads — 1.0.1

[English](../MONITORING-AND-DOWNLOADS.md)

## Detectie

De primaire online detector is de onafhankelijk geschreven CDS-client. Na expliciete deeltoestemming gebruikt hij gemeten identiteit voor een echte HTTPS-check en alleen bij herkend aanbod een resourceaanvraag. Protocol, velden, grenzen en het ontbreken van een live identificatieverzoek staan in [CDS-PROVIDER](CDS-PROVIDER.md). Dit is een interne integratie, geen publieke Motorola-SDK.

De officiële updater blijft terugvalroute. Lokale updaterpreferences en geïmporteerde bestanden zijn aparte aanwijzingen. Cache kan oud zijn; ontbrekende metadata bewijst geen ontbrekende update. Metadata-only, volledige OTA-ZIP en onbekende resource hebben aparte typen. Serveraanbod, ontvangen bytes, ZIP-authenticiteit, compatibiliteit en werkelijke installatie blijven gescheiden.

## Achtergrondcontrole

Monitoring staat standaard uit. Opt-in bewaart interval en plant WorkManager 2.10.5; standaard 6 uur, controllerbereik 1–24 uur, eerste vertraging 15 minuten en accu-niet-laag. Android bepaalt het uitvoermoment; dit is geen exacte wekker. [Periodieke planning](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work).

Met aparte onlinetoestemming probeert de worker eerst CDS. Zonder die toestemming of bij ontbrekende onlineroute blijft de begrensde lokale cache terugval. De root-cacheparser is voor het historische exacte referentieprofiel onderzocht en raadt geen schema of identiteit. Intrekken verhindert volgende identificatie-uitlezingen/verzoeken. Monitoring uitzetten annuleert werk en verhindert commits uit een oude generatie; een al verstuurd verzoek is niet terug te halen.

Verse serverinformatie heet een gemelde release, geen geverifieerde installeerbare firmware. Lokale metadata en pakketverwijzingen hebben eigen informatieve labels. Herhaling, huidige builds, onbekende/mislukte observaties en niet-bruikbare resultaten blijven stil. Een melding opent Firmware en start geen download, partitieschrijven of installatie. Android 13+ vereist meldingstoestemming. [Meldingspermission](https://developer.android.com/develop/ui/views/notifications/notification-permission).

De atomaire lokale geschiedenis bevat maximaal 12 records met tijd, uitkomst, veilige bron/doelbuild en meldingsstatus. Deduplicatie bevat geen signed URLs, serienummers, GUIDs, boot-ID of modulenamen. Geweigerde meldingstoestemming wordt niet omzeild. Corrupte instellingen schakelen de monitor uit.

## Downloads en import

Overdracht vereist een toegestane volledige verwijzing van exact `dlmgredg-vz.gtm.svcmot.com` via HTTPS en consistente gemeten Motorola-identiteit. Geen willekeurige URLs of brede `appspot.com`-toelating. CDS is een checkendpoint, geen pakkethost. Willekeurige niet-lege CDS-resourceheaders worden niet doorgestuurd.

WorkManager voert de overdracht uit met voortgangsmelding en pauze/annuleren. Alleen een ondoorzichtig job-ID staat in workerinput. De gevoelige URL wordt versleuteld met een Android-Keystore-sleutel en staat niet in logs, rapporten of gewone manifests. [Android Keystore](https://developer.android.com/privacy-and-security/keystore).

Partial bytes blijven apart van het geselecteerde pakket. Hervatten vereist passende sterke ETag en geldige 206/Content-Range; anders herstart of stopt de transfer. Redirects, onverwachte lengte, verlopen URLs en te weinig ruimte stoppen zonder gedeeltelijk pakket te publiceren. Volledige lengte/hashcontrole en atomaire publicatie gaan vooraf aan voltooiing. [HTTP-rangevoorwaarden](https://www.rfc-editor.org/rfc/rfc9110.html#name-range-requests).

Hervatten hangt van de server af. Een nieuwe CDS-check kan een nieuwe verwijzing opleveren; de downloader raadt geen URL-vernieuwing. Android-quota kunnen werk uitstellen of onderbreken. Voltooide overdracht vereist nog firmwareauthenticatie en compatibiliteit.

Documentimport en de begrensde OEM-kopie zijn eveneens byteoverdrachten. OEM-ontdekking toont maximaal 12 ondersteunde namen bij begrensde directoryscan. Kopiëren gebruikt een unieke private transactie, stabiele bronidentiteit, volledige terughash en opruimen bij annulering. De OEM-bron blijft onaangeroerd; handtekeningcontrole blijft nodig.

## Opslag en privacy

Vault-pinning gebruikt zo mogelijk een hardlink en anders een duurzame volledige kopie. Pins isoleren verificatie van atomaire downloadvervanging. Huidige pin, download en tijdelijke kopie kunnen tegelijk bestaan en veel ruimte vragen. Oude reguliere UUID-pins worden pas na geslaagde nieuwe selectie opgeruimd; een fout behoudt de vorige selectie.

Geen telemetrie of automatische diagnose-upload. Onlinecontrole deelt de afzonderlijk toegestane identiteit met CDS; downloaden deelt normale netwerkgegevens en de tijdelijke aanvraag met de toegestane host. Geen IMEI. Rapporten exporteren een allowlist zonder ruwe antwoorden, signed URLs, serienummers of GUIDs. Keystore/private opslag beschermt niet tegen volledige controle over een roottoestel.

Zie [SAFETY](SAFETY.md), [COMPATIBILITY](COMPATIBILITY.md) en [BUILD-REPORT](../BUILD-REPORT.md) voor grenzen en echte testresultaten.
