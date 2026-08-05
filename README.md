# CloudStream RUUA Extensions

Curated CloudStream plugin repository for **Russian and Ukrainian** audio tracks, maintained for the [CloudStream RUUA](https://github.com/lxnhere/cloudstream-tv-ruua) app fork.

This repo cherry-picks stable UA providers from [CakesTwix/cloudstream-extensions-uk](https://github.com/CakesTwix/cloudstream-extensions-uk) and selected RU providers from the hexated extensions lineage. NSFW and joke-only modules are excluded by policy.

## Install in CloudStream

In the app: **Settings → Extensions → Add repository**, then paste:

```
https://raw.githubusercontent.com/lxnhere/cloudstream-ruua-extensions/master/repo.json
```

Built plugin binaries are published on the [`builds`](https://github.com/lxnhere/cloudstream-ruua-extensions/tree/builds) branch (`plugins.json` + `.cs3` files).

## Build locally

Requires JDK 17+ and the Android SDK.

```bash
./gradlew make makePluginsJson
```

Outputs appear under each module’s `build/` directory and `build/plugins.json` at the repo root.

## License

This repository is distributed under **GNU General Public License v3.0** (see [LICENSE](LICENSE)). Plugin sources retain attribution to their upstream authors; see [UPSTREAM.md](UPSTREAM.md).

## Related

- App fork: [lxnhere/cloudstream-tv-ruua](https://github.com/lxnhere/cloudstream-tv-ruua)
- Upstream CloudStream: [recloudstream/cloudstream](https://github.com/recloudstream/cloudstream)
