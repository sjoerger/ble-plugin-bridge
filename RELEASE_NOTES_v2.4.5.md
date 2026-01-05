# BLE-MQTT Bridge v2.4.5 Release Notes

## Android TV Power Fix

This release adds a critical fix for Android TV devices like the Onn 4K Pro where the foreground service was being killed when the TV entered standby mode.

### The Problem
When using an Android TV streaming device (like Onn, Chromecast, etc.), pressing the power button on the remote doesn't actually "power off" the device - it sends an HDMI-CEC standby command. If the `hdmi_control_auto_device_off_enabled` setting is enabled (default), the streaming device goes to sleep, which kills all running apps including our BLE-MQTT bridge service.

### The Solution
This release adds:

1. **AndroidTvHelper utility** - Detects Android TV devices and manages HDMI-CEC settings
2. **New "Android TV Power Fix" section** in System Settings (only visible on Android TV)
3. **Automatic CEC fix on service startup** - When permission is granted, the service automatically disables CEC auto-off when it starts

### How to Use

#### Option 1: Grant Permission (Recommended)
This allows the app to automatically manage the CEC setting:

1. Connect to your Android TV device via ADB:
   ```bash
   adb connect <device-ip>:5555
   ```

2. Grant the permission:
   ```bash
   adb shell pm grant com.blemqttbridge android.permission.WRITE_SECURE_SETTINGS
   ```

3. Restart the app - the service will now automatically disable CEC auto-off on startup

4. Navigate to **Settings → System Settings** to verify the status

#### Option 2: Manual ADB Fix
If you prefer not to grant the permission, you can manually disable CEC:

```bash
adb shell settings put global hdmi_control_auto_device_off_enabled 0
```

Note: This setting may reset after a factory reset or system update.

### Technical Details
- Detection method: `PackageManager.FEATURE_LEANBACK`
- Setting location: `Settings.Global.hdmi_control_auto_device_off_enabled`
- Permission required: `android.permission.WRITE_SECURE_SETTINGS`
- Permission grant method: ADB only (cannot be granted via UI)

### UI Features
- Status indicator shows current CEC setting state
- ADB commands with copy-to-clipboard buttons
- Toggle switch to enable/disable CEC (when permission granted)
- Only appears on Android TV devices (not phones/tablets)

---

## Tested Devices
- **Onn 4K Pro Streaming Device** (Android 14) - Verified fix works

## Download
- `ble-mqtt-bridge-v2.4.5-release.apk` - Signed release build
