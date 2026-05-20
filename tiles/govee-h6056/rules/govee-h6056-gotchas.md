# Govee H6056 Gotchas

When working with Govee Flow Plus light bars (H6056), remember:

- **Segments 12, 13, 14 are phantom.** API returns 200 OK, nothing lights. Physical range is `0..11` only. Never send commands targeting 12+.
- **Two physical bars = one API device.** The bar-to-segment mapping is:
  - **Yankee** (call it the "top bar" or whichever you've labeled): segments `0..5`
  - **Golf** (the other bar): segments `6..11`
  - **Within each bar, `segment[0]` is at the TOP** — natural index order is top-down. Bottom-up fill = high-to-low index range.
- **Discovery is `GET /router/api/v1/user/devices` with header `Govee-API-Key`.** Not mDNS, not Bluetooth, not BLE.
- **Capability name is verbose:** `devices.capabilities.segment_color_setting` / `segmentedColorRgb`. `rgb` is a packed int: `(r << 16) or (g << 8) or b`.
- **Rate limit reality:** ~7 req/min sustained is the measured ceiling. Burst-tolerant for short windows. Pair with `iot-actuator-patterns-kotlin` (or its `rate-limited-iot-debounce` predecessor) if you're emitting more than one update per second. **min-interval 1.2 s is the sweet spot.**
- **`rgb=(0,0,0)` is unreliable as "off".** Some firmware paths treat packed RGB `0x000000` as a no-op and silently retain the prior segment state. To reliably clear a segment, use `rgb=(1,1,1)` (near-black but non-zero). On session shutdown, always issue an **explicit all-segments command** to every physical segment (0..11) so nothing is left lit when the script exits.

See the full skill at `skills/govee-h6056-control/SKILL.md` for the Kotlin/Ktor reference client.
