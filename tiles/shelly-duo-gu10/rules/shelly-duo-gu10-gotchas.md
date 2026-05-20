# Shelly Duo GU10 Gotchas

When working with Shelly Duo GU10 RGBW smart bulbs (Gen1 / `shellycolorbulb-` family) over **LAN HTTP**:

- **LAN-only API. No cloud round-trip required** — this is the killer feature vs Govee. Latency ~30–80 ms on a healthy WiFi network. **Use min-interval `0.2 s`** for any debounce controller, not the `1.2 s` you'd use for cloud APIs.
- **mDNS discovery name:** `_http._tcp.local.` with service name pattern `shellycolorbulb-<MAC>` (last 6 hex digits of the MAC, lowercase). For Duo GU10 specifically the prefix may also be `shellybulbduo-<MAC>`.
- **mDNS bind gotcha:** `JmDNS.create()` with no argument binds to the JVM's default `InetAddress`, which on macOS is **`localhost` (127.0.0.1) by default**. Discovery returns nothing. **You must bind to the primary non-loopback IPv4 interface explicitly.**
- **Color endpoint:** `GET /color/0?turn=on&red=R&green=G&blue=B&gain=N` where R/G/B are 0..255 and gain is 0..100.
- **Off:** `GET /color/0?turn=off`. The bulb retains last R/G/B for the next `turn=on`.
- **White / temperature mode:** `GET /white/0?turn=on&temp=K&brightness=N` where temp is 3000..6500. **Mode is sticky** — if you set white mode, the next `/color/0?turn=on` may not pick the previous color cleanly. Reset color explicitly.
- **Status / health:** `GET /status` returns JSON with `lights[0].ison`, `wifi_sta.ip`, `rssi`, etc. Use for liveness probe.
- **No HTTPS.** No auth by default (factory defaults). If you've set a Shelly password, requests need HTTP Basic auth.
- **Power-cycling resets the IP unless you've reserved it in the router.** For demos, use a router DHCP reservation OR set a static IP via the Shelly web UI.

See the full skill at `skills/shelly-duo-gu10-control/SKILL.md` for the Kotlin/Ktor reference client and JmDNS discovery example.
