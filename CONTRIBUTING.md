# Contributing

Use JDK17 and the checked-in Gradle wrapper. Run the README build/check tasks before submitting a change. Pure policies/parsers belong in `core`; Android transport, storage and privileged interactions belong in the `app` adapters. Add regression tests for concrete compatibility, parsing, trust or recovery errors.

Use mocks and a disposable explicitly selected emulator for ordinary tests. No physical flash, OTA, slot mutation or phone reboot belongs in CI or the normal development suite. Keep simulated observations and journals separate from live state.

Preserve the distinctions between integrity, OEM signature, source/channel compatibility, stock provenance, applied OTA, inactive-image proof, new target boot and completed merge. Do not replace an absent or conflicting fact with a successful default. New API/command claims need a checkable primary source and the tested version.

Cross-model support uses measured capabilities and authenticated exact bytes; it is not limited to a marketing-name list. Critical adapters remain versioned. Review [Compatibility](docs/COMPATIBILITY.md), [Architecture](docs/ARCHITECTURE.md) and [Safety](docs/SAFETY.md) before changing a privileged boundary.

Keep English/Dutch presentation keys and formatting arguments aligned. Include source evidence, useful validation and remaining device-specific limits in a pull request. Never attach raw Motorola preferences, signed URLs, user firmware, identifiers or signing material. [Security reporting](SECURITY.md), [release process](docs/RELEASING.md).

## Nederlands

Gebruik JDK17, de Gradlewrapper en de controles uit de README. Beleid/parsers horen in `core`; Android- en rootinteracties in de adapters van `app`. Gebruik mocks en een expliciet gekozen wegwerpemulator. Geen echte firmware-, partitie-, slot- of herstartactie in gewone tests.

Houd integriteit, ondertekening, bron/kanaal, stockherkomst, OTA, imagebewijs, nieuwe boot en merge gescheiden. Onbekend wordt geen succes. Onderbouw nieuwe API's en commando's met primaire bron en onderzochte versie. Ondersteuning volgt werkelijk gemeten mogelijkheden; kritieke adapters houden hun versiegrens. Houd Engelse/Nederlandse resources gelijk en voeg geen private toesteldata of sleutels toe.
