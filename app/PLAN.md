# WebsiteBlocker — Implementation Plan (VPN/DNS Approach)

## Project Context
- Package: `com.example.websiteblocker`
- Language: Kotlin
- Min SDK: 24 (Android 7.0)
- Target SDK: 36

---

## Architecture Overview

```
MainActivity (UI)
    ↓ start/stop + blocklist management
BlocklistRepository (SharedPreferences)
    ↓ read blocklist
BlockerVpnService (VpnService)
    ↓ creates TUN interface
DnsPacketProcessor
    ↓ intercepts DNS queries (UDP port 53)
    → if domain in blocklist → return 0.0.0.0
    → else → forward to upstream DNS → return real IP
```

---

## Files to Create

```
src/main/java/com/example/websiteblocker/
├── MainActivity.kt               # UI: toggle VPN on/off, manage blocklist
├── BlockerVpnService.kt          # VpnService: TUN setup + packet loop
├── DnsPacketProcessor.kt         # Parse DNS query, check blocklist, build response
├── BlocklistRepository.kt        # CRUD for blocked domains (SharedPreferences)
└── DnsForwarder.kt               # Forward allowed DNS queries to upstream (8.8.8.8)

src/main/res/layout/
└── activity_main.xml             # Toggle switch + RecyclerView list + add domain input

src/main/AndroidManifest.xml      # Add BIND_VPN_SERVICE, FOREGROUND_SERVICE permissions
```

---

## Step-by-Step Implementation

### Step 1 — AndroidManifest.xml
- Add `<uses-permission android:name="android.permission.BIND_VPN_SERVICE" />`
- Add `<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />`
- Register `BlockerVpnService` with `android:permission="android.permission.BIND_VPN_SERVICE"`

### Step 2 — BlocklistRepository
- Store blocked domains as a `Set<String>` in `SharedPreferences`
- Expose: `add(domain)`, `remove(domain)`, `getAll(): Set<String>`
- Normalize domains on save (lowercase, strip `www.`, strip `https://`)

### Step 3 — BlockerVpnService
- Extend `VpnService`
- In `onStartCommand`:
  - Call `VpnService.Builder` to configure the TUN interface:
    - Assign a local IP: `10.0.0.2/32`
    - Set DNS server to self: `10.0.0.2`
    - Add route `0.0.0.0/0` to capture all traffic
  - Call `builder.establish()` → get a `ParcelFileDescriptor` (the TUN fd)
  - Start a coroutine/thread that reads packets from the TUN fd in a loop
- Post a foreground notification (required for background service)
- On `onDestroy`: close the TUN fd, cancel the coroutine

### Step 4 — DnsPacketProcessor
- Read raw bytes from TUN fd
- Parse the IP header → check if it's UDP and destination port is 53
- If yes: parse the DNS query to extract the queried hostname
- Check hostname against `BlocklistRepository.getAll()`
  - **Blocked**: build a DNS response with `RCODE=3 (NXDOMAIN)` or answer `0.0.0.0`, write back to TUN fd
  - **Allowed**: pass to `DnsForwarder`
- If not a DNS packet: write the packet back to TUN fd as-is (transparent passthrough)

### Step 5 — DnsForwarder
- Open a UDP socket to upstream DNS (`8.8.8.8:53`)
- Forward the raw DNS query bytes
- Wait for response
- Write the response back into the TUN fd as a UDP/IP packet addressed back to the original requester

### Step 6 — MainActivity
- UI with a toggle (Switch/Button) to start/stop the VPN
  - On enable: call `VpnService.prepare(context)` → if returns an Intent, launch it (shows system VPN dialog); on `RESULT_OK` → start `BlockerVpnService`
  - On disable: call `stopService()`
- RecyclerView showing current blocklist from `BlocklistRepository`
- Input field + Add button to add a new domain
- Swipe-to-delete on list items

---

## DNS Packet Structure (reference)

```
IP Header (20 bytes)
  └── UDP Header (8 bytes)
        └── DNS Payload
              ├── Transaction ID (2 bytes)
              ├── Flags (2 bytes)
              ├── Questions (2 bytes)
              └── Question section: QNAME (variable) + QTYPE + QCLASS
```

To block: reply with same Transaction ID, set QR=1 (response), RCODE=3 (NXDOMAIN), zero answers.

---

## Key Decisions

| Decision | Choice | Reason |
|---|---|---|
| Upstream DNS | Device real DNS, fallback `8.8.8.8` | Respects user's network (ISP/corporate/router DNS). Read via `ConnectivityManager.getLinkProperties()` **before** VPN is established (after establish, device DNS is overridden to our TUN). Fall back to `8.8.8.8` if unavailable or resolves to our own TUN address. |
| Blocklist storage | SharedPreferences | No database dependency for a simple string set |
| Concurrency | Kotlin coroutines | Already in stdlib, clean cancellation |
| Domain matching | Exact + subdomain match | Block `instagram.com` also blocks `www.instagram.com` |
| VPN address | `10.0.0.2` | Non-routable, won't conflict with real network |

---

## Domain Matching Logic

When a DNS query arrives for `sub.instagram.com`:
1. Check exact match: `sub.instagram.com` in blocklist → block
2. Check parent domains: `instagram.com` in blocklist → block
3. Otherwise → allow

---

## Limitations to Document for Users
- Cannot run simultaneously with another VPN app
- DNS-over-HTTPS (DoH) in browsers can bypass blocking (mitigate: block `8.8.8.8`, `1.1.1.1`, `9.9.9.9` on port 443 — but this requires full traffic inspection, out of scope for v1)
- Blocking is ineffective if the user manually disables the VPN from Android quick settings
