#!/usr/bin/env bash
# Connect an Android device to WSL2 via ADB over WiFi so `flutter run` works.
#
# One-time setup on the Android device:
#   1. Enable Developer Options and USB debugging.
#   2. Connect the device to Windows via USB.
#   3. On Windows (or any host with the device authorized), run:
#        adb tcpip 5555
#   4. Disconnect USB.
#   5. Find the device's WiFi IP (Settings > Wi-Fi > tap the network).
#
# Usage:
#   ./scripts/connect_android_wsl.sh 192.168.1.123
#   ./scripts/connect_android_wsl.sh          # prompts for IP

set -euo pipefail

IP="${1:-}"
if [ -z "$IP" ]; then
    read -rp "Enter the Android device WiFi IP: " IP
fi

if [ -z "$IP" ]; then
    echo "Error: no device IP provided." >&2
    exit 1
fi

if ! command -v adb >/dev/null 2>&1; then
    echo "Error: adb not found in PATH. Make sure Android SDK platform-tools is installed and on PATH." >&2
    exit 1
fi

echo "Connecting to $IP:5555..."
adb connect "$IP:5555"
adb devices
echo
echo "Run 'flutter devices' to confirm the device is visible from WSL."
echo "Then run 'flutter run' as usual."
