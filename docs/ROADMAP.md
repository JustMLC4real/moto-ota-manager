# Development and qualification roadmap

Version 1.0.0 is the first public release. Current capabilities and their guards are documented in [COMPATIBILITY](COMPATIBILITY.md); actual build/test evidence is in [BUILD-REPORT](BUILD-REPORT.md). The roadmap is not a claim that future integrations already work.

| Area | Next evidence to collect |
|---|---|
| CDS | Separately consented real-service acceptance of the minimized request, actual no-offer/offer/resource responses and server-dependent expiry/resume behavior; no identifier fixture in the public repository |
| Cross-model stock restore | Actual device qualification for API 33–37 A/B boot/init_boot configurations, matching signed image bytes and runtime backup association |
| Update automation | Qualify each reviewed OEM/Magisk transition and interrupted-operation recovery; retain a truthful pause/fallback when a contract is absent |
| Future Android/Magisk | Compare sources and runtime security/Binder/snapshot/layout behavior before adding new critical-adapter versions |
| Motorola staging | Actual OEM recognition, filesystem publication durability and mandatory channel evidence; no relaxation based on host/cache/CDS alone |
| Accessibility/localization | Continue English/Dutch resource parity, cached-state language switching, screen-reader and small/large-display verification |
| Release operations | Maintain signing identity, clean-build evidence, dependency inventory, immutable checksums and public privacy review |

No roadmap item authorizes unattended experimentation on a real phone. Destructive qualification requires a separately planned device session with explicit scope and recovery preparation. Existing historical reports remain labelled by the version and actions that produced them.

Nederlandse gebruikersgidsen staan onder [docs/nl](nl/COMPATIBILITY.md).
