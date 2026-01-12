## Breaking Changes

⚠️ **Device Identification Changed**: Device IDs now use Bluetooth MAC address instead of Android ID to prevent Home Assistant entity duplication after app updates.

**Migration Required:**
- New entities will appear in Home Assistant with different IDs
- Old entities will show as "unavailable"
- Manually remove old entities and update dashboards/automations

**Device ID Format:**
- Old: `ble_mqtt_bridge_929334`
- New: `ble_mqtt_bridge_0c9919` (last 6 chars of Bluetooth MAC)

## Bug Fixes

- **OneControl**: Fixed PIN pairing failures caused by case-sensitive MAC address comparison (#5)
- **OneControl**: Normalized MAC addresses to uppercase-with-colons format
- **Android TV**: Enhanced boot receiver with LOCKED_BOOT_COMPLETED and QUICKBOOT_POWERON broadcasts (#7)
- **Build**: Added FORCE_DEBUG_LOG to debug builds (was release-only)

## Improvements

- **Battery Optimization**: Better boot-on-startup reliability through battery whitelist exemption
- **Device Stability**: Home Assistant entities no longer duplicate after app updates (#6)

## Testing

Tested on Android TV (Android 14) with OneControl gateway.

## Installation

1. Download `app-release.apk`
2. Install on Android device
3. Configure plugins and start service
4. Remove old Home Assistant entities (filter by "unavailable")
5. Update dashboards to use new entity IDs
