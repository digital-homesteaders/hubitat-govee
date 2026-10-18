# Govee Connect for Hubitat

Govee lights and fans on Hubitat Elevation, with **live updates**. The app signs in
to your Govee account the way the Govee Home app does, including Govee's emailed
verification code. It then keeps one live connection to Govee's cloud (AWS IoT MQTT)
for every device: changes you make in the Govee app, with a Govee button or on a
schedule show up in Hubitat within about a second, and Hubitat's commands go out the
same way.

An optional **Govee API key** adds the official Platform API, for:
- fan speed, mode and oscillation (the live connection only reports a fan's power)
- devices that don't answer on the live connection (seen: H605B, H6095)
- a fallback: a live command that the device doesn't carry out within about 6 s (the
  device's report must show the new value) is sent again over the API. Some firmware
  reports over the live connection but ignores commands there (seen: H6052). After two
  misses in a row, that device's commands go straight to the API for 6 hours.

## Install

Paste each file into Hubitat (**Apps Code** / **Drivers Code** → New → paste → Save):

| File | Where |
|---|---|
| `apps/govee-connect.groovy` | Apps Code |
| `drivers/govee-cloud-connection.groovy` | Drivers Code |
| `drivers/govee-light.groovy` | Drivers Code |
| `drivers/govee-fan.groovy` | Drivers Code |

Then go to **Apps → Add User App → Govee Connect**:
1. Enter your Govee email and password, then press **Sign in**. If Govee emails a code,
   enter it and press **Submit code**.
2. Optionally add your API key (Govee Home app → Profile → Settings → Apply for API Key).
3. Pick devices, then press **Done**.

Hubitat keeps a sign-in token and the account's device certificate. It never keeps
your password: that's dropped as soon as the sign-in finishes.

## Devices

| Kind | Driver | Live (MQTT) | Needs API key |
|---|---|---|---|
| Lights, strips, lamps, bars | Govee Light | on/off, level, RGB, colour temperature, online | for devices that don't report live |
| Fans (H710x, H711x) | Govee Fan | on/off | speed, mode, oscillation |

Not yet: scenes, per-segment colour, LAN control, Matter linking, sensors and other
appliances. See [`../govee-research/DESIGN.md`](../govee-research/DESIGN.md) for the plan.

## How it works

- **Govee Connect** (app) holds the account, discovery, the Platform API, and all
  Govee mapping (`goveeEvents`, `planCommand`, `normalizeIot`, `normalizeApi`) as pure
  functions.
- **Govee Cloud Connection** (driver) holds the MQTT session. It turns Govee's
  certificate into PEM with the JVM's KeyStore, connects to
  `ssl://<endpoint>:8883` as `AP/<accountId>/<clientId>`, subscribes to the account
  topic, sends a `status` query to every device every 5 minutes, and reconnects with
  backoff (plus a watchdog each minute).
- **Govee Light / Govee Fan** are thin shells. They keep the merged state and ask the
  app for events and commands.
- The client id is `uuid5(DNS, "hubitat-govee:" + email)`, so it's stable (Govee asks
  for a code only the first time) and doesn't clash with a Home Assistant install on the
  same account.
- Rate limits: the API's remaining-request headers are shown on the app page. Polling
  only reads fans and devices with no live report in 10 minutes.

## Testing

```bash
tests/run.sh
```

It compiles everything with Groovy 2.4 against hub stand-ins and runs
`CompileCheck`, `MappingTest` and `DriverFlowTest` (sign-in through to MQTT, with
Govee's cloud and MQTT faked). `tests/setup-tools.sh` downloads the pinned JDK and
Groovy into `.tools/` if they're missing.

### What needs testing on the hub

Proven on the maintainers' C-8 by the spike probes (`../govee-research/spike-p12/`):
sign-in with 2FA, KeyStore conversion, MQTT with a PKCS#8 key, status replies, and
unprompted pushes.

Not yet tried on the hub:
- [x] The app's sign-in pages (2026-10-17: signed in, no new code needed for this client id)
- [x] Discovery with an API key (2026-10-17: 10 of 10 found and added, H7101 typed as a fan by the API)
- [ ] Discovery without an API key
- [ ] Commands over MQTT (turn, brightness, colorwc RGB and CT) on each light.
      H6022: `turn` works. H6052: reports over MQTT but ignores `turn`, so after two
      misses its commands go over the API (automatic; retried over MQTT after 6 h).
      Confirmed on the hub 2026-10-17 (0.1.5): first miss failed fast at 3.2 s, then
      the API took about 5 s to reach the lamp; after the second miss it switched to API-only.
- [ ] H6052 delay once it's API-only (Govee's cloud; the Govee app may be using Bluetooth)
- [ ] The API fallback after an unconfirmed command
- [ ] Fan speed, mode and oscillation on the H7111 and H7101 (H7101 capabilities are unseen)
- [ ] Reconnect after a hub reboot and after a network drop
