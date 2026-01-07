# Release Notes - v2.4.7

**Release Date:** January 6, 2026  
**Type:** Critical Bug Fixes

## Critical Fixes

### 1. BLE Notification Race Condition Fixed

This release fixes a **critical regression** that prevented physical device control states from updating in Home Assistant. The issue was caused by a race condition between BLE GATT callbacks that occurred when `onMtuChanged` and `onServicesDiscovered` execute on different threads.

**Problem:**
- The `onMtuChanged` callback could fire before `onServicesDiscovered` completed caching GATT characteristics
- This caused notification subscriptions to fail silently (characteristics were null during authentication)
- Physical device state changes (e.g., turning on a light from the RV panel) were not reflected in Home Assistant
- BLE trace logging was unable to capture NOTIFY events

**Solution:**
- Added synchronization flags (`servicesDiscovered` and `mtuReady`) to OneControlDevicePlugin
- Introduced `checkAndStartAuthentication()` method that ensures both callbacks complete before starting authentication sequence
- Both flags must be set to `true` before notification subscriptions begin
- Flags are properly reset during disconnect/cleanup

**Impact:**
- ✅ Physical device control states now properly update in Home Assistant
- ✅ BLE trace feature now captures NOTIFY events correctly
- ✅ Notification subscriptions complete successfully
- ✅ State synchronization fully functional

### 2. Friendly Name Race Condition Fixed

**Problem:**
- Users reported entities appearing with unfriendly names like "cover_160a" instead of "Awning"
- The issue occurred when `GetDevicesMetadata` response (containing friendly names) arrived **before** entity state events
- `republishDiscoveryWithFriendlyName()` only published if entity was already in `haDiscoveryPublished` set
- This timing race meant friendly names were never applied for some users

**Solution:**
- Removed guard checks in `republishDiscoveryWithFriendlyName()` 
- Always publish all entity types when metadata arrives, regardless of timing
- Add entities to tracking set to prevent duplicate publishes
- HA handles duplicate config messages gracefully (just updates the entity)

**Impact:**
- ✅ Entities now show friendly names like "Awning" instead of "cover_160a"
- ✅ Works regardless of metadata/state arrival order
- ✅ Consistent naming across all installations

## Additional Improvements

### Enhanced BLE Logging for All Plugins

Added comprehensive BLE event logging to **all plugins** (OneControl, EasyTouch, GoPower):

**What's Logged:**
- Connection state changes (CONNECTED, DISCONNECTED)
- Service discovery (status, service count)
- Characteristic reads/writes (UUID, hex data, status)
- Characteristic notifications (UUID, hex data)
- MTU changes
- Descriptor writes

**Purpose:**
Users can now send trace files showing BLE traffic from their specific hardware variants (multi-zone thermostats, different heat modes, uncommon device types) for debugging and adding support for new device features without requiring physical access to the hardware.

**Trace File Location:** `/sdcard/Download/trace_YYYYMMDD_HHMMSS.log`

**Example Trace Output:**
```
19:03:23.085 TRACE START ts=20260106_190323
19:03:23.180 NOTIFY 00000034-...: 00 C5 06 08 08 80 FF 40 01 6D 00
19:03:24.593 WRITE 00000033-...: 00 41 06 42 01 08 02 FF E8 00 (status=0)
19:03:26.399 WRITE 0000fff2-...: 20 (status=0)
19:03:26.656 WRITE 0000ee01-...: 7B 22 54 79 70 65 22... (status=0)
```

### Cleanup

- Removed obsolete `ServiceStatusActivity` (old diagnostic UI replaced by modern MainActivity compose UI)

## Technical Details

**Files Changed:**
- `app/build.gradle.kts` - Version bump to 2.4.7 (versionCode 28)
- `core/interfaces/MqttPublisher.kt` - Added `logBleEvent()` interface method
- `core/BaseBleService.kt` - Implemented `logBleEvent()` method
- `plugins/onecontrol/OneControlDevicePlugin.kt` - Race condition fix, enhanced logging
- `plugins/easytouch/EasyTouchDevicePlugin.kt` - Added BLE event logging
- `plugins/gopower/GoPowerDevicePlugin.kt` - Added BLE event logging
- `app/src/main/AndroidManifest.xml` - Removed ServiceStatusActivity
- `scripts/test-onecontrol-v2.sh` - Updated to launch MainActivity
- `ui/ServiceStatusActivity.kt` - **DELETED** (obsolete)

**Root Cause of Notification Issue:**
When BLE GATT callbacks began executing on different threads (Android platform behavior), the timing-sensitive authentication sequence became unreliable. The `onMtuChanged` callback could complete and attempt authentication before `onServicesDiscovered` had finished caching characteristic references, resulting in null pointer issues that prevented notification setup.

**Verification:**
Testing confirmed:
1. Notifications now properly enabled during connection setup
2. Physical control state changes (lights, switches) reflect in Home Assistant
3. BLE trace captures NOTIFY events with full hex data from all plugins
4. Stream reader processes queued notifications correctly
5. Friendly names appear consistently for all users

## Upgrade Notes

**This is a critical bug fix release.** All users experiencing issues with:
- State updates not appearing in Home Assistant when devices are controlled physically
- Entities showing names like "cover_160a" instead of "Awning"

Should upgrade immediately.

**Installation:**
1. Download `app-release.apk` from this release
2. Install via ADB: `adb install -r app-release.apk`
3. Or use the app's built-in APK sharing feature from Settings screen

**No configuration changes required** - all fixes are transparent to users.

## Known Issues

None introduced in this release.

## Download

- **Release APK:** `app-release.apk` (signed, production-ready)
- **Debug APK:** Available in build artifacts (includes verbose logging)

---

**Previous Release:** [v2.4.6](RELEASE_NOTES_v2.4.6.md)  
**Full Changelog:** See [CHANGELOG.md](docs/CHANGELOG.md)
