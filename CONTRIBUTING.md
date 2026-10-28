# Ontwikkelen

Gebruik JDK 17 en de meegeleverde Gradle Wrapper. Voer de taken uit de README uit vóór een wijzigingsvoorstel. Pure beslisregels horen in `core`; toestelinteracties staan uitsluitend in `app/platform`. Voeg regressietests toe voor echte fouten in compatibiliteit, vertrouwen, parsing of herstelgedrag.

Gebruik bij apparaatinteracties standaard mocks of een emulator. Voer geen firmware-, partitie-, slot- of rebootactie uit op een fysiek toestel als onderdeel van deze tests. Houd simulatie gescheiden van echte observaties en van het persistente journaal.

Verander een ontbrekend of tegenstrijdig gegeven niet in een positieve waarde. Bewaar de scheiding tussen integriteit, ondertekening, kanaalcompatibiliteit, OTA-toepassing, Magisk-melding, succesvolle boot en voltooide snapshot-merge. Een nieuwe API- of commandoclaim hoort bij een controleerbare primaire bron en de geteste versie.

De releasegate is bewust beperkt tot het onderzochte Android 17-/Magisk 31.1-profiel. Lees de compatibiliteitsmatrix voordat je een nieuw profiel toevoegt.
