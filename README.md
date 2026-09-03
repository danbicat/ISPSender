# ISPSender

A [TransferProxy](https://github.com/YvanMazy/TransferProxy) plugin that transfers every player to a server chosen
from the **ASN** (ISP) and the **country** of their IP address.

The [IPinfo Lite](https://ipinfo.io/lite) database (MMDB) is downloaded when the plugin starts, then every incoming
connection is resolved against it and transferred to the server configured for its ASN or its country.

```
player connects ─▶ READY event ─▶ IP ─▶ IPinfo Lite lookup ─▶ ASN / country ─▶ rule ─▶ transfer
```

## Features

- Downloads a fresh `ipinfo_lite.mmdb` on startup, and optionally on a fixed interval.
- Non-blocking updates: when a database is already on disk it is loaded immediately and the download runs in the
  background, then the new database is hot-swapped in — the proxy startup is never delayed.
- A failed or corrupted download never replaces a working database: the file is downloaded to a temporary file,
  opened to validate it, and only then moved into place.
- Rules by ASN (`AS4766` / `4766`) and by country (ISO code `KR` or name `South Korea`, case-insensitive).
- Every rule accepts a **list of servers**: one is picked at random per player, spreading the load over a pool.
- Default server (or pool) for everything else, or a kick message when no rule matches.

## Requirements

- TransferProxy 1.2.1 or newer
- Java 17 or newer
- A free [IPinfo](https://ipinfo.io/signup) token (IPinfo Lite is free and unlimited)

## Install

1. Download or build `ISPSender-1.0.0.jar` (see below) and drop it in the `plugins` folder of your proxy.
2. Start the proxy once: `plugins/ISPSender/config.yml` is created.
3. Put your IPinfo token in it, write your rules, restart the proxy.

## Build

The Gradle wrapper is included, so no local Gradle install is needed:

```bash
./gradlew shadowJar
```

The plugin jar is written to `build/libs/ISPSender-1.0.0.jar` — a single shaded jar containing the MMDB reader
(relocated under `net.transferproxy.ispsender.libs`).

## Configuration

`plugins/ISPSender/config.yml`:

```yaml
token: "YOUR_IPINFO_TOKEN"      # free token from https://ipinfo.io/signup
database-url: "https://ipinfo.io/data/ipinfo_lite.mmdb?token={token}"
database-file: "ipinfo_lite.mmdb"

update-on-start: true           # download a fresh database at startup
update-interval-minutes: 0      # 0 = disabled, 1440 = once a day
connect-timeout-seconds: 15
read-timeout-seconds: 180

log-lookups: true               # logs "player (ip, ASN, country) -> server"

asn-servers:                    # checked first
  "AS4766":                     # a list is balanced randomly
    - "kt1.example.com:25565"
    - "kt2.example.com:25565"
  "AS9318": "lgu.example.com:25565"

country-servers:                # checked next
  "KR":
    - "kr1.example.com:25565"
    - "kr2.example.com:25565"
  "JP": "jp.example.com:25565"

default-server: "global.example.com:25565"   # a list works here too, "" kicks instead
no-server-message: "<red>No server is available for your connection."
```

| Key | Default | Role |
| --- | --- | --- |
| `token` | `YOUR_IPINFO_TOKEN` | IPinfo token. The `IPINFO_TOKEN` environment variable takes priority over it. |
| `database-url` | IPinfo Lite URL | Download URL, `{token}` is replaced by the token. |
| `database-file` | `ipinfo_lite.mmdb` | File name inside `plugins/ISPSender/`. |
| `update-on-start` | `true` | Download a fresh database when the plugin starts. |
| `update-interval-minutes` | `0` | Re-download every X minutes, `0` disables it. |
| `connect-timeout-seconds` | `15` | Connection timeout of the downloader. |
| `read-timeout-seconds` | `180` | Timeout of the whole download. |
| `log-lookups` | `true` | Log every resolved connection. |
| `asn-servers` | *(empty)* | ASN → server (or list of servers) rules. |
| `country-servers` | *(empty)* | Country → server (or list of servers) rules. |
| `default-server` | *(empty)* | Fallback server or list, empty kicks the player instead. |
| `no-server-message` | *(red message)* | Kick message, in MiniMessage format. |

### Resolution order

1. `asn-servers`, matched on the ASN of the address (`AS4766` and `4766` are the same key).
2. `country-servers`, matched first on the ISO country code (`KR`), then on the country name (`South Korea`).
3. `default-server`, also used when the address is unknown to the database (private/local addresses, for example).
4. If `default-server` is empty, the player is kicked with `no-server-message`.

Ports are optional in every server value: `lobby.example.com` is `lobby.example.com:25565`.

### Load balancing

Any rule — including `default-server` — takes a list instead of a single server, and one entry is picked at random
for every player:

```yaml
country-servers:
  "KR":
    - "kr1.example.com:25565"
    - "kr2.example.com:25565"
    - "kr3.example.com:25565"
```

Weights come for free: listing the same server twice in a pool doubles its share of the players.

```yaml
default-server:
  - "big.example.com:25565"     # ~2/3 of the players
  - "big.example.com:25565"
  - "small.example.com:25565"   # ~1/3
```

Invalid entries are logged and dropped, and a rule whose servers are all invalid is ignored entirely, so a typo in
one host never takes the whole pool down.

Use `log-lookups: true` for a while to discover the ASNs your players actually come from, then write your rules:

```
[ISPSender] Notch (121.135.10.1, AS4766 (Korea Telecom) / KR (South Korea)) -> kt2.example.com:25565 [AS4766, random of 2]
```

## Notes

- The database is loaded fully in memory (~22 MiB) so the file on disk can be replaced while the proxy is running.
- Lookups use the address of the socket. If something else sits in front of the proxy (a TCP load balancer, a tunnel),
  every player will be resolved to that machine's ASN.
- ISPSender registers a `READY` listener. Any other plugin registering one on the same event will also run, so do not
  install two plugins that both transfer players.
- The IPinfo Lite database is redistributed under [CC BY-SA 4.0](https://ipinfo.io/lite); it is downloaded at runtime
  and never bundled in the jar.

## Layout

| Path | Role |
| --- | --- |
| `ISPSenderPlugin` | Startup: config, database, update scheduling, listener registration |
| `config/ISPSenderConfiguration` | `config.yml` mapping |
| `data/Server` | `HOST:PORT` parsing |
| `data/ServerPool` | The servers of a rule, and the random pick |
| `database/DatabaseDownloader` | Download, validation and atomic replacement of the MMDB |
| `database/IPInfoDatabase` | Holds the active reader, hot-swap and lookups |
| `database/IPInfoRecord` | An IPinfo Lite record (`asn`, `as_name`, `country`, `country_code`, ...) |
| `routing/RoutingTable` | Rules parsing and resolution (ASN → country → default) |
| `listener/ISPReadyListener` | Transfers the connection to the resolved server |
