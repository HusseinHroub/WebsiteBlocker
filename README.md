# WebsiteBlocker

An Android app that blocks user-configured websites across all browsers and apps, using a local VPN that intercepts DNS queries on-device — no root, no external server.

## How it works

When enabled, the app creates a local VPN (TUN interface). All DNS queries from the device pass through it. If the queried domain matches the blocklist, the app returns NXDOMAIN (domain not found) and the connection never happens. All other traffic is forwarded transparently.

```
App → DNS query for instagram.com
    → VPN intercepts
    → domain is blocked → returns NXDOMAIN → browser gets "site not found"
    → domain is allowed → forwarded to real DNS → resolves normally
```

## Features

- Block any domain across all browsers and apps
- Subdomain matching: blocking `instagram.com` also blocks `www.instagram.com`
- Domains persist across reboots (stored in SharedPreferences)
- Uses real device DNS as upstream, falls back to `8.8.8.8`

## Usage

1. Open the app
2. Tap the **Block websites** toggle → accept the system VPN permission dialog (one-time)
3. Type a domain (e.g. `instagram.com`) and tap **Add**
4. Long-press a domain in the list to remove it

## Limitations

- **Conflicts with real VPNs** — Android allows only one VPN at a time. If the user activates another VPN, this one is disconnected.
- **DNS-over-HTTPS (DoH)** — browsers with DoH enabled (e.g. Chrome, Firefox) may bypass DNS-level blocking by sending encrypted DNS over port 443.
- **User can disable** — the VPN can be turned off from Android quick settings.

## Project structure

```
app/src/main/java/com/example/websiteblocker/
├── MainActivity.kt          # UI: toggle VPN, manage blocklist
├── BlockerVpnService.kt     # VpnService: TUN setup and packet loop
├── DnsPacketProcessor.kt    # Parse DNS packets, apply blocklist, build responses
├── DnsForwarder.kt          # Forward allowed DNS queries to upstream
└── BlocklistRepository.kt   # Store/retrieve blocked domains (SharedPreferences)
```

## Requirements

- Android 7.0+ (API 24)
- No special permissions beyond the standard VPN consent dialog
