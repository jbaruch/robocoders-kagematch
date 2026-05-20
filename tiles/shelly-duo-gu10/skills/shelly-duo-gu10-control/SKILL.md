---
name: shelly-duo-gu10-control
description: Controls Shelly Duo GU10 RGBW smart bulbs (Gen1) over LAN HTTP REST. Includes mDNS discovery (with the critical 'bind to non-loopback IPv4' gotcha), color/temperature/off endpoints, status probe, and the 0.2s min-interval for LAN debounce. Use when the user wants to control a Shelly bulb directly without their cloud (Gen1 Shelly Color, Shelly Duo, Shelly RGBW2 share most of this contract), do mDNS discovery on a JVM, or build a low-latency IoT pipeline against a local Shelly device.
---

# Shelly Duo GU10 Control

The Shelly Duo GU10 is a LAN-controllable RGBW bulb with a simple, undocumented-but-stable HTTP API. Sub-100 ms latency on local WiFi makes it the IoT-counterpart-of-choice for high-rate producers (compare to Govee cloud at ~1 s).

## API contract

- **Base URL:** `http://<bulb-ip>` — no HTTPS, no auth by default.
- **Color mode:** `GET /color/0?turn={on|off}&red=R&green=G&blue=B&gain=N`
  - R/G/B: 0..255
  - gain: 0..100 (brightness)
- **White / temperature mode:** `GET /white/0?turn={on|off}&temp=K&brightness=N`
  - temp: 3000..6500
- **Off:** `GET /color/0?turn=off` (or `/white/0?turn=off`)
- **Status:** `GET /status` → JSON with `lights[0].ison`, `wifi_sta.ip`, `rssi`, `mac`
- **Identity:** `GET /shelly` → JSON with `type`, `mac`, `fw_ver`

## Latency

- Local WiFi (5 GHz, line-of-sight): **30–80 ms** typical
- Local WiFi (2.4 GHz, walls): **80–200 ms**
- Anything > 500 ms = the bulb is offline or the router is overloaded — don't blame Shelly.

For debounce controllers, **`min-interval = 0.2 s`** is the right starting point (compare to 1.2 s for Govee cloud). The bulb will happily accept ~5 req/s. Higher is wasteful, not broken.

## Kotlin client

```kotlin
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*

class ShellyBulb(private val ip: String) {
    private val client = HttpClient(CIO)

    suspend fun setColor(r: Int, g: Int, b: Int, gain: Int = 100) {
        client.get("http://$ip/color/0") {
            parameter("turn", "on")
            parameter("red", r)
            parameter("green", g)
            parameter("blue", b)
            parameter("gain", gain)
        }
    }

    suspend fun setWhite(tempK: Int = 4750, brightness: Int = 100) {
        client.get("http://$ip/white/0") {
            parameter("turn", "on")
            parameter("temp", tempK)
            parameter("brightness", brightness)
        }
    }

    suspend fun off() {
        client.get("http://$ip/color/0?turn=off")
    }

    suspend fun isReachable(timeoutMs: Long = 1500): Boolean = runCatching {
        withTimeout(timeoutMs) { client.get("http://$ip/status").status.value == 200 }
    }.getOrDefault(false)
}
```

## mDNS discovery — the bind gotcha

Discovery uses `javax.jmdns.JmDNS`. **The default `JmDNS.create()` binds to the JVM's default address, which on macOS is `localhost` (127.0.0.1) — and discovery returns nothing.**

Bind to the **primary non-loopback IPv4 interface** explicitly:

```kotlin
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import java.net.InetAddress
import java.net.NetworkInterface

fun primaryIPv4(): InetAddress {
    val candidates = NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual && !it.displayName.startsWith("utun") }
        .flatMap { it.inetAddresses.asSequence() }
        .filter { !it.isLoopbackAddress && it.address.size == 4 }
        .toList()
    return candidates.firstOrNull()
        ?: error("No non-loopback IPv4 interface found. Are you offline?")
}

fun discoverShelly(timeoutMs: Long = 4000): List<String> {
    val jmdns = JmDNS.create(primaryIPv4())   // <-- THIS is the load-bearing argument
    val services: Array<ServiceInfo> = jmdns.list("_http._tcp.local.", timeoutMs)
    return services
        .filter {
            it.name.startsWith("shellycolorbulb-") ||
            it.name.startsWith("shellybulbduo-")
        }
        .flatMap { it.inet4Addresses.map { addr -> addr.hostAddress } }
        .also { jmdns.close() }
}

// usage
val ips = discoverShelly()  // e.g., ["192.168.8.135"]
```

Gradle dependency: `implementation("org.jmdns:jmdns:3.6.0")`.

## When mDNS discovery isn't worth it

For a stable demo setup, **reserve the bulb's IP in your router** (DHCP reservation by MAC) and hardcode the IP:

```kotlin
val bulbIp = System.getenv("SHELLY_BULB_IP") ?: "192.168.8.135"
```

mDNS adds 4 s of cold-start latency, a dependency (`jmdns`), and a venue-network failure mode (some conference WiFi blocks multicast). For production agents, mDNS-with-fallback is the pattern. For demos, static IP is one less thing to break.

## "off" semantics

Unlike Govee, Shelly **does not have the `rgb=(0,0,0)` no-op problem**. `turn=off` reliably extinguishes the bulb and the bulb retains its last color/gain for the next `turn=on`. You can use `turn=off` freely.

On script shutdown:

```kotlin
Runtime.getRuntime().addShutdownHook(Thread {
    runBlocking { bulb.off() }
})
```

## Anti-patterns

- ❌ `JmDNS.create()` with no argument on macOS — silently binds to localhost.
- ❌ Reusing the 1.2 s cloud debounce min-interval for Shelly — wastes most of the achievable update rate.
- ❌ Hardcoding the IP without DHCP reservation — power-cycling the bulb often shifts its IP.
- ❌ Using `/white/0` after `/color/0` without an explicit color reset — mode transitions can leave the bulb in a stale state.
- ❌ Assuming HTTPS or basic-auth — neither is set by default; factory firmware is plain HTTP.

## Diagnostic invocations

```bash
# Reachability
curl --max-time 2 "http://192.168.8.135/status" | jq .lights[0].ison

# Set red, sit for 2s, off
curl "http://192.168.8.135/color/0?turn=on&red=255&green=0&blue=0&gain=100"
sleep 2
curl "http://192.168.8.135/color/0?turn=off"

# List network services on the LAN (macOS):
dns-sd -B _http._tcp local.
```
