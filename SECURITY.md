# Veiligheidsmeldingen en wijzigingen

Deze release is een uitlezende diagnostiek- en begeleidingsapp. `ReadOnlyProbe` is de volledige shell-allowlist. Er is geen vrije shellinvoer, partitieschrijver, OTA-installer, Magisk-installer of rebootfunctie. Een nieuwe muterende adapter valt buiten dit releaseprofiel en vereist afzonderlijk technisch bewijs, expliciete autorisatie en nieuwe toesteltests.

Meld parserfouten, onbedoelde acceptatie van firmware of het exporteren van gevoelige informatie met een minimaal synthetisch voorbeeld. Voeg geen IMEI, serienummer, accountgegevens, getekende OTA-URL's of ongeschoonde updater-XML toe aan openbare issues. Gebruik het ingebouwde geanonimiseerde rapport.

Automatische tests zijn geen toestelcertificering. Updates aan Android, Magisk, Motorola's updater of het toestelprofiel kunnen nieuwe blokkades opleveren. Maak een onbekende toestand niet kunstmatig groen om verder te kunnen.

Geïmporteerde OTA's, images en het workflowjournaal staan in app-private opslag. Cloudback-up en device transfer zijn uitgesloten. Bij deïnstalleren kunnen deze lokale kopieën verdwijnen; bewaar je oorspronkelijke, onafhankelijk geverifieerde firmwarebestanden zelf veilig. Er is geen automatische upload.
