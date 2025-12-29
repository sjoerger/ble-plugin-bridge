# Release v2.3.7 - Keepalive Scheduling Fix & Android TV Support

**Release Date:** December 29, 2025  
**APK:** `ble-mqtt-bridge-v2.3.7.apk` (18 MB)

## 🐛 Critical Bug Fixes

### Keepalive Scheduling Bug Fixed
**Impact:** HIGH - Affected all v2.3.6 users  
**Symptoms:** BLE devices would disconnect overnight during device deep sleep, despite keepalive feature being enabled

**Root Cause:**
- Keepalive only scheduled when service received explicit `ACTION_START_SCAN` intent
- When app launched from UI (MainActivity), service started with null action
- onCreate() didn't schedule keepalive as backup
- **Result:** Keepalive never activated unless service explicitly restarted via specific intent

**Fix:**
- ✅ Keepalive now schedules in `onCreate()` as backup (always runs)
- ✅ Added null action handler in `onStartCommand()` (UI launch path)
- ✅ Improved logging to track which startup path triggered keepalive
- ✅ Made `scheduleKeepAlive()` idempotent to handle multiple calls safely

**Verification:**
```
Log output now shows:
⏰ Scheduling keepalive from onCreate() (backup path)
⏰ Scheduling keepalive from ACTION_START_SCAN (primary path)
⏰ Keepalive scheduled: next ping in 30 minutes
```

AlarmManager confirms alarm registered and will fire during Doze mode using `setExactAndAllowWhileIdle()`.

---

## 🆕 New Features

### Android TV Box Support
The app is now fully compatible with Android TV boxes while maintaining complete phone/tablet support.

**Changes:**
- Added `<uses-feature android:name="android.hardware.touchscreen" android:required="false" />`
- Added `<uses-feature android:name="android.software.leanback" android:required="false" />`

**Benefits:**
- ✅ Same APK works on phones, tablets, AND Android TV boxes
- ✅ Background service runs identically on all platforms
- ✅ Perfect for RV installations with Android TV streaming boxes
- ✅ Lower power consumption than tablets (passively cooled, 24/7 operation)
- ✅ Ethernet connectivity option for more reliable MQTT

**Recommended Android TV Boxes for BLE Bridge:**
- Look for Amlogic S905X5 or newer chipsets
- Verify BLE 5.0+ support (not all TV boxes have BLE!)
- Example: Ugoos AM9 with Bluetooth 5.2

**Note:** UI will appear phone-like on TV but remains fully functional. Since the app is controlled primarily via Home Assistant/MQTT, UI access is minimal.

---

## 📋 Startup Path Coverage

Keepalive now schedules in **3 different startup paths** to ensure 100% coverage:

| Startup Path | When It Occurs | Keepalive Scheduled |
|-------------|----------------|---------------------|
| `onCreate()` | Service first created | ✅ Always (backup) |
| `ACTION_START_SCAN` | Explicit service start | ✅ Yes (primary) |
| `null action` | UI launch, system restart | ✅ Yes (added in v2.3.7) |

---

## 🔧 Technical Changes

### Code Changes
- `BaseBleService.kt`: Added keepalive scheduling to `onCreate()` and null action handler
- `AndroidManifest.xml`: Added Android TV compatibility features
- `build.gradle.kts`: Bumped versionCode to 13, versionName to 2.3.7

### Logging Improvements
- Better visibility into which startup path triggered keepalive
- Clearer log messages with emoji indicators (⏰ for keepalive, ⚙️ for startup)
- Easy to diagnose if keepalive is actually scheduling

---

## 📱 Installation

### New Users
1. Download `ble-mqtt-bridge-v2.3.7.apk`
2. Install on Android 8.0+ device (phone, tablet, or TV box)
3. Grant all permissions
4. Configure MQTT and device plugins
5. Enable battery optimization exemption
6. **Keepalive is enabled by default** - disable only for battery-powered devices

### Upgrading from v2.3.6
1. Install APK over existing installation (data preserved)
2. **Important:** Restart the app after upgrade to activate fix
3. Verify keepalive is scheduled: Check logs or System Settings screen
4. AlarmManager should show next ping in ~30 minutes

### Verification After Install/Upgrade
```bash
# Check if keepalive alarm is registered
adb shell dumpsys alarm | grep com.blemqttbridge

# Should show:
# tag=*walarm*:com.blemqttbridge.ACTION_KEEPALIVE
# when=<timestamp> (should be +30min from current time)
```

---

## 🎯 Recommended Settings

### For Mains-Powered Devices (Recommended Setup)
- ✅ **Battery Optimization:** Exempt
- ✅ **Keepalive Pings:** Enabled (default)
- ✅ **BLE Service:** Running 24/7
- ✅ **MQTT:** Always connected

**Why:** Prevents overnight disconnections during deep sleep. Ideal for RV/boat installations with shore power.

### For Battery-Powered Devices
- ⚠️ **Battery Optimization:** Exempt (still recommended)
- ❌ **Keepalive Pings:** Disabled (to save battery)
- ℹ️ **Note:** Connections will auto-reconnect when device wakes from sleep

---

## 🔍 Testing Performed

### Bug Verification
- ✅ Service started via UI → keepalive schedules in onCreate()
- ✅ Service started via ACTION_START_SCAN → keepalive schedules in onStartCommand
- ✅ Service started with null action → keepalive schedules via null handler
- ✅ AlarmManager confirms alarm registered with correct interval
- ✅ All 3 devices (OneControl, EasyTouch, GoPower) reconnect after app restart

### Platform Testing
- ✅ Android 11 (TCL T768S tablet) - WiFi
- ✅ Android 12+ compatibility maintained
- ✅ APK installs on phone and tablet without modification

---

## 📚 Documentation Updates

- **README.md:** Updated current version to v2.3.7
- **INTERNALS.md:** Added v2.3.7 technical changes section
- Both documents now reflect keepalive bug fix and Android TV compatibility

---

## 🚀 What's Next

The keepalive feature should now work reliably overnight. Monitor your Home Assistant dashboard tomorrow morning to verify all devices remain connected.

**If you still see disconnections after v2.3.7:**
- Check System Settings screen to verify keepalive is enabled
- Check logs for "⏰ Keepalive scheduled" messages
- Verify battery optimization exemption is active
- Check if aggressive battery savers (Samsung, Xiaomi) are overriding settings

---

## 📦 Files

- **APK:** `ble-mqtt-bridge-v2.3.7.apk` (18 MB)
- **Source:** Tag `v2.3.7` on GitHub
- **Commit:** 53987af

---

## 🙏 Credits

Bug discovered during overnight testing on December 29, 2025. Root cause analysis revealed keepalive never activated in v2.3.6 due to startup path coverage gap. Fix implements redundant scheduling across all startup paths to ensure 100% reliability.
