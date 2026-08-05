# Upstream attribution

This repository is a **curated fork**, not a clean-room rewrite. We thank the original authors and keep GPL-3.0 compatibility.

| Source | Role | Upstream |
|--------|------|----------|
| **CakesTwix** | Ukrainian (`uk`) streaming providers, Gradle layout, CI pattern | [CakesTwix/cloudstream-extensions-uk](https://github.com/CakesTwix/cloudstream-extensions-uk) |
| **hexated** (seed) | Russian providers **HDrezka** and **Anilibria** | [hexated/cloudstream-extensions-hexated](https://github.com/hexated/cloudstream-extensions-hexated) |
| **resoul/filmix** | Filmix player/search/stream decode reference (Swift → Kotlin port) | [resoul/filmix](https://github.com/resoul/filmix) |

**Do not port from Lampa / online_mod / aio_online** — broken and out of policy for this repo.

## Excluded from CakesTwix upstream

The following modules were **not** imported:

- `HentaiUkrProvider` (NSFW)
- `SimpsonsUATvProvider` (non-curated / joke provider)

Any other NSFW-tagged modules are omitted by the same policy.

## Maintenance

Bugfixes and site breakage should be fixed here or contributed back upstream when appropriate. When updating from donors, record the commit or tag in your PR description.
