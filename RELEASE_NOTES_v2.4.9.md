# Release Notes - v2.4.9

**Release Date:** January 7, 2026

## Major Improvements

### 🔄 Connection Robustness Enhancements

**Problem Solved:** GoPower and EasyTouch plugins required multiple service restarts to establish initial connections, especially on first launch or after app restarts.

**Root Causes Addressed:**
1. **GATT 133 errors** caused immediate connection failure with no retry logic
2. **Zombie states** (GATT exists but disconnected) were never detected or recovered
3. **Stale connections** hung indefinitely without automatic recovery

**Solutions Implemented:**

#### GATT 133 Automatic Retry
- Up to **3 automatic reconnection attempts** with 2-second delays
- Matches proven OneControl implementation
- Handles transient BLE stack issues common on Android

#### Per-Plugin Connection Watchdog
- **60-second health monitoring** for all plugins
- Detects and recovers from zombie states (GATT exists but not authenticated)
- Forces reconnection if no BLE activity for 5+ minutes
- Self-healing without manual intervention

#### Success Operation Tracking
- Tracks timestamp of last successful BLE operation
- Used by watchdog to detect stale connections
- Updated on every characteristic read/write/notification

**Expected Results:**
- **80-90% reduction** in initial connection failures
- **Automatic recovery** from zombie states within 60 seconds
- **Consistent reliability** matching OneControl plugin
- **Seamless operation** after service restarts

### 🏷️ OneControl Persistent Metadata Cache

**Problem Solved:** Friendly names lost on app restart, causing entities to revert to hex IDs like "cover_160a".

**Solution:**
- Metadata now cached in SharedPreferences using gateway MAC address as key
- Cache loaded immediately on plugin initialization
- Friendly names available from first state update
- Survives app restarts and reconnections
- Perfect for factory-installed devices that never change

**Benefits:**
- No more hex-name entities in Home Assistant
- No more metadata request delays
- No more race conditions between state and metadata arrival
- Instant friendly names: "Awning", "Slide Out", "Tank Fresh" from connection start

### 🌡️ EasyTouch Fan Mode Fix

**Problem Solved:** Home Assistant logs filled with warnings: "Invalid fan_modes mode: off"

**Root Cause:** Plugin published `fan_mode="off"` when system was off, but "off" wasn't in the discovery `fan_modes` list.

**Solution:**
- Now publishes actual fan speed setting (auto/low/high) regardless of system on/off state
- Matches MQTT Climate specification: fan mode setting persists when system off
- Safety conversion: "off" → "auto" if ever encountered

## Files Changed

**Connection Robustness:**
- `GoPowerDevicePlugin.kt`: Added retry logic + watchdog
- `EasyTouchDevicePlugin.kt`: Added retry logic + watchdog

**Metadata Persistence:**
- `OneControlDevicePlugin.kt`: Added SharedPreferences caching with JSON serialization

**Bug Fixes:**
- `EasyTouchDevicePlugin.kt`: Fixed fan_mode publication logic

**Documentation:**
- `INTERNALS.md`: Updated to v2.4.9, documented all changes
- `build.gradle.kts`: Version bump to 2.4.9 (versionCode 30)

## Upgrade Notes

- No configuration changes required
- Existing installations will automatically benefit from improvements
- OneControl metadata cache will populate on first connection after upgrade
- Recommend service restart after install for cleanest upgrade experience

## Known Issues

None.

## Testing Performed

- Debug build tested on Onn 4K Pro (10.115.19.214:35605)
- All three plugins (OneControl, GoPower, EasyTouch) verified functional
- Metadata cache persistence verified across app restarts
- Connection retry logic tested with simulated GATT 133 errors
- Watchdog verified detecting and recovering zombie states

## Credits

- Connection robustness patterns adapted from proven OneControl implementation
- Watchdog architecture copied from OneControl's battle-tested design
- Metadata caching optimized for static factory-installed device scenarios

---

**Full Changelog:** See [GitHub Releases](https://github.com/phurth/ble-plugin-bridge/releases) for complete version history.
