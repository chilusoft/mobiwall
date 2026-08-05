# MobiWall – Mobile Firewall

A free and open-source Flutter app that acts as a **firewall for Android**, letting you block selected apps from using **WiFi** and **mobile data**. It is available for Android only because iOS does not allow third-party apps to inspect or control other apps' network traffic — a restriction we cannot bypass.

## Features

- **App list**: See all installed apps (except MobiWall).
- **Per-app blocking**: Toggle each app to block or allow network access (WiFi and mobile data).
- **VPN-based firewall**: Uses Android’s VPN API to intercept traffic and block by app (UID).
- **Block domains & IPs**: Block incoming/outgoing requests by domain or IPv4. When active, DNS and UDP are filtered; TCP is not forwarded.
- **Persistent rules**: Block lists (apps, domains, IPs) are saved and applied whenever the firewall is on.

## Platform support

- **Android**: Supported. Requires VPN permission; no root.
- **iOS**: Not supported. Apple does not allow third-party apps on iOS to inspect or control other apps' network traffic, and there is no public API that provides this level of access. This is a platform-level restriction that we cannot bypass.

## Technical overview

MobiWall is built with **Flutter** for the cross-platform UI and **Kotlin** for the Android-specific firewall engine.

- **Frontend**: Flutter / Dart screens handle the app list, blocking toggles, domain/IP rules, and active-connections monitoring.
- **Platform channel**: Dart calls are forwarded to a custom Android plugin (`FirewallPlugin`) that exposes the native firewall API.
- **VPN service**: `FirewallVpnService` extends Android's `VpnService` and creates a local VPN tunnel. No traffic is sent to an external server.
- **App blocking**: By default, only the UIDs of blocked apps are routed through the VPN; all other apps are added as disallowed applications so they bypass the tunnel and keep normal connectivity.
- **Domain/IP blocking & monitoring**: When domain/IP rules are active or connection monitoring is enabled, all traffic is routed through the VPN. The packet tunnel (`PacketTunnel`) parses IPv4/UDP/DNS packets, drops blocked IP addresses, returns fake DNS responses for blocked domains, and records per-remote-endpoint traffic statistics.
- **Persistence**: Block lists and monitoring settings are stored locally using Android `SharedPreferences`.
- **Foreground service**: The VPN runs as a foreground service with a persistent notification so Android does not kill it while the firewall is active.

## How to run

```bash
cd mobiwall
flutter pub get
flutter run
```

Build an APK:

```bash
flutter build apk
```

## How to use

1. **Grant VPN permission**  
   When you first turn the firewall ON, Android will ask you to allow a VPN connection. You must accept for the firewall to work.

2. **Choose apps to block**  
   In the list, turn the switch ON for any app you want to block from the internet (WiFi and mobile data).

3. **Turn firewall ON**  
   Use the main switch to start the firewall. Blocked apps will have no network access while it’s on.

4. **Turn firewall OFF**  
   Use the main switch again to stop the firewall and restore normal connectivity for all apps.

## How it works (Android)

- The app uses a **local VPN** (no data is sent to an external VPN server).
- With the firewall ON, the VPN is active and shows in the status bar.
- Traffic is filtered by **UID** (Linux user ID of each app). Blocked UIDs are not allowed network access.
- The VPN currently uses a **limited route** (`1.0.0.0/8`) so that normal internet traffic is not routed through the VPN and the device stays online. For a full firewall that blocks all traffic from selected apps, the VPN would need to use a default route and implement full packet forwarding (see “Limitations” below).

## Limitations

- **No per-connection type (WiFi vs mobile)**: Blocking applies to both WiFi and mobile data. Separate WiFi-only or mobile-only blocking would require more logic (e.g. checking network type before allowing/blocking).
- **VPN route**: The demo uses a non-default route so the phone’s main internet keeps working. To block selected apps from *all* traffic while allowing others, you’d need to:
  - Use a default route (`0.0.0.0/0`) in the VPN, and
  - Implement full packet forwarding (parse packets, match UID via `/proc/net/tcp`, and forward allowed traffic). This is a larger change and often done with native code (e.g. C) for performance.
- **iOS**: Not supported due to platform restrictions.

## Permissions

- **INTERNET** – Used by the app (e.g. if you add update checks or analytics).
- **ACCESS_NETWORK_STATE** – To know if the device is on WiFi or mobile data.
- **BIND_VPN_SERVICE** – To run the local firewall VPN.
- **FOREGROUND_SERVICE** / **POST_NOTIFICATIONS** – So the firewall can run as a foreground service with a persistent notification while active.

## License

MobiWall is **free and open-source software**. This project is provided as-is for learning and use on your own device. Contributions and feedback are welcome.
