# Release Notes - v2.4.7

**Release Date:** January 6, 2026  
**Type:** Critical Bug Fix

## Critical Fix

### BLE Notification Race Condition Fixed

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

## Additional Improvements

### Enhanced BLE Logging
- Added `logBleEvent()` interface method to `MqttPublisher`
- Implemented `logBleEvent()` in `BaseBleService` calling `appendDebugLog()`
- Added comprehensive BLE event logging to OneControl GATT callbacks:
  - Connection state changes
  - Service discovery
  - Characteristic reads/writes
  - Characteristic notifications (onCharacteristicChanged)
  - MTU changes
- BLE trace files now properly capture all WRITE and NOTIFY events with timestamps and hex data

## Technical Details

**Files Changed:**
- `app/build.gradle.kts` - Version bump to 2.4.7 (versionCode 28)
- `core/interfaces/MqttPublisher.kt` - Added `logBleEvent()` interface method
- `core/BaseBleService.kt` - Implemented `logBleEvent()` method
- `plugins/onecontrol/OneControlDevicePlugin.kt` - Race condition fix and enhanced logging

**Root Cause:**
When BLE GATT callbacks began executing on different threads (Android platform behavior), the timing-sensitive authentication sequence became unreliable. The `onMtuChanged` callback could complete and attempt authentication before `onServicesDiscovered` had finished caching characteristic references, resulting in null pointer issues that prevented notification setup.

**Verification:**
Testing confirmed:
1. Notifications now properly enabled during connection setup
2. Physical control state changes (lights, switches) reflect in Home Assistant
3. BLE trace captures NOTIFY events with full hex data
4. Stream reader processes queued notifications correctly

## Upgrade Notes

**This is a critical bug fix release.** All users experiencing issues with state updates not appearing in Home Assistant should upgrade immediately.

**Installation:**
1. Download `app-release.apk` from this release
2. Install via ADB: `adb install -r app-release.apk`
3. Or use the app's built-in APK sharing feature from Settings screen

**No configuration changes required** - the fix is transparent to users.

## Known Issues

None introduced in this release.

## Download

- **Release APK:** `app-release.apk` (signed, production-ready)
- **Debug APK:** Available in build artifacts (includes verbose logging)

---

**Previous Release:** [v2.4.6](RELEASE_NOTES_v2.4.6.md)  
**Full Changelog:** See [CHANGELOG.md](docs/CHANGELOG.md)
