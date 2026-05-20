# shelly-duo-gu10

A [Tessl](https://tessl.io) plugin encoding ground truth for the **Shelly Duo GU10 RGBW** smart bulb (Gen1) — the LAN-controllable RGBW bulb whose 30–80 ms local latency makes it a natural counterpoint to cloud-throttled devices like Govee H6056.

## What this plugin provides

| Kind | Name | Purpose |
|---|---|---|
| Skill | `shelly-duo-gu10-control` | LAN HTTP REST contract (`/color/0`, `/white/0`, `/status`), Kotlin/Ktor reference client, JmDNS discovery with the **bind-to-non-loopback-IPv4** gotcha, off semantics, latency expectations. |
| Rule  | `shelly-duo-gu10-gotchas` | Concise reminder card: endpoints, mDNS service names, **0.2 s min-interval for debounce** (vs 1.2 s for cloud APIs). |

## Why it exists

Two things agents commonly get wrong on Shelly:

1. **mDNS discovery on the JVM silently fails on macOS** because `JmDNS.create()` with no argument binds to the loopback interface. Discovery returns zero services. The plugin documents the exact "bind to the primary non-loopback IPv4 interface" pattern that fixes it.
2. **Debounce min-interval reuse from cloud APIs** — agents copy the 1.2 s value from a Govee plugin and apply it to Shelly. Shelly happily accepts ~5 req/s. The plugin spells out "0.2 s for LAN, 1.2 s for cloud" explicitly.

## Install

```bash
tessl install jbaruch/shelly-duo-gu10
```

## License

MIT — see [LICENSE](LICENSE).
