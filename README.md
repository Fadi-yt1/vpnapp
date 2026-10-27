# Tervyn VPN for Android

The Android app version of the Tervyn VPN browser extension. Free, no accounts, no ads.

- **App name:** Tervyn VPN
- **Package id:** `com.tervyn.vpn` (Android package ids can't contain spaces, so "Tervyn Vpn" becomes `com.tervyn.vpn`)
- **Works on:** Android 8.0 and newer

## What it does

The same as the extension, for every app on your phone:

- **Zero setup.** Tap the power button. Tervyn downloads the same 18 public free-proxy lists, tests the servers and connects to the fastest, usually within 10–20 seconds.
- **Only servers that work.** Each server must load a Cloudflare page over HTTPS with a valid certificate. That proves it works for secure sites and doesn't tamper with traffic, and it shows the server's real country.
- **Automatic failover.** The fastest server is used, with backups kept ready. The servers in use are re-checked every 2 minutes. Dead ones are replaced without dropping the VPN, and the list refreshes every 30 minutes.
- **Two modes:**
  - **All apps:** all traffic goes through the VPN.
  - **Only my sites:** only the sites you list (and their subdomains) go through the VPN, in every app and browser. Everything else stays on your normal, fast connection. It has the same one-tap presets as the extension (YouTube, Instagram, X, Discord, Telegram, ChatGPT, Claude, Netflix…).
- **Share to add a site:** in any browser or app, tap **Share → Tervyn VPN** to add that site to your list.
- **Choose a country**, or keep "Fastest available".
- **Check my IP** shows the address and country websites see.
- **Quick Settings tile** to connect with one tap, plus support for Android's **Always-on VPN** and **Block connections without VPN**.
- **Your own servers (optional):** add a list link or paste `socks5://` / `http://` servers (with or without a login).

## Get the APK

Every push builds the app on GitHub:

1. Open the repository's **Actions** tab → **Build Android APK** → the latest run.
2. Download the **tervyn-vpn-apk** artifact and unzip it.
3. Copy `app-release.apk` to your phone and open it. Allow "install unknown apps" if Android asks.

The release APK is signed with a debug key so it installs right away. Before publishing on Google Play, create your own signing key and set it in `app/build.gradle.kts`.

## Build it yourself

Open the folder in Android Studio and press Run, or:

```bash
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

## How it works

```
apps ──► Android VPN (Tervyn) ──► fastest free server (+ backups) ──► website
```

- `vpn/TervynVpnService.kt` uses Android's VPN API to capture traffic. Tervyn's own traffic, like server tests and list downloads, stays outside the VPN.
- `vpn/TunEngine.kt` and `vpn/TcpConn.kt` are a small TCP/IP layer written in Kotlin. They finish each app connection on the phone and carry its data through the proxy, with flow control and retransmission.
- **Local DNS (`FakeDns`).** When an app looks up a site, Tervyn answers with a stand-in address from `198.18.0.0/15`. When the app connects to that address, Tervyn knows the site name and asks the proxy to connect to it by name. So:
  - DNS lookups don't leak to your internet provider.
  - "Only my sites" mode works in every app: only the stand-in addresses are routed into the VPN, so all other traffic never touches it.
- `net/ProxyDialer.kt` connects through SOCKS5 or HTTP `CONNECT` proxies, including ones that need a login.
- `net/ProxyTester.kt` runs the HTTPS test on Cloudflare's trace page, 64 servers at a time.
- `core/Core.kt` does the job of `background.js` in the extension: finding servers, choosing the fastest, health checks and failover.
- `ui/` is the Jetpack Compose interface, in the same design as the extension popup.

## Limits

- **VLESS / VMess / Trojan / Shadowsocks** servers need Xray-core, which the Android app doesn't include yet. They're skipped when importing. Free servers and your own SOCKS5/HTTP servers work.
- **UDP traffic** (such as video calls, some games and QUIC) can't go through free SOCKS/HTTP proxies. In "All apps" mode it's blocked, and most apps then fall back to TCP automatically. In "Only my sites" mode, everything not on your list uses your normal connection.
- **IPv6** is blocked in "All apps" mode so nothing leaks around the VPN. Sites are reached over IPv4.
- If Android's **Private DNS** is set to a specific provider, apps skip Tervyn's DNS. "All apps" mode still works, but "Only my sites" can't see the site names. Set Private DNS to Automatic or Off.

## Privacy and safety

Free public servers are run by unknown people. Secure (https) sites stay encrypted end to end, and Tervyn only keeps servers that pass the secure-site check. Still, avoid banking and other sensitive logins through free servers. "Only my sites" mode is the safest way to use them.
