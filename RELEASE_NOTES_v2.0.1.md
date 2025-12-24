# Release v2.0.1: Cover Safety & State Sensors

## ⚠️ SAFETY CRITICAL CHANGES

After thorough testing with current monitoring, **RV awning and slide control has been disabled**. Testing revealed these devices have:

- **NO limit switches** - motors will not auto-stop at endpoints
- **NO overcurrent protection** - motors continue drawing dangerous current when stalled
- **Operator-dependent safety** - relies entirely on manual control judgment

### Current Draw Testing Results:
- **Awning motor at limit**: 19A continuous (vs. 4-6A normal operation)
- **Slide motor at limit**: 39A continuous (vs. normal operation)
- **No automatic cutoff observed** - motor held at limit until button released

## 🎯 What Changed

### Covers → State-Only Sensors
Awnings and slides now appear as **sensor entities** in Home Assistant that report state only:
- States: `open`, `opening`, `closed`, `closing`, `stopped`
- No control buttons (OPEN/CLOSE/STOP removed)
- Position data still unavailable (hardware limitation)

**Why this matters:** Remote control of these devices could cause:
- Equipment damage (awnings rolling incorrectly, slide mechanisms jamming)
- Safety hazards (no warning when approaching limits)
- Electrical stress (continuous high current draw)

### ✅ Features Added
- **Friendly device names** - Covers and tanks now show metadata-derived names (e.g., "Awning" instead of "Cover 0803")
- **DTC fault codes** - Added diagnostic trouble code definitions for troubleshooting
- **Enhanced state reporting** - Real-time cover state updates when operated manually
- **Improved function mapping** - Better device type identification

### 🐛 Bug Fixes
- Fixed cover discovery republishing after metadata retrieval
- Corrected sensor discovery key mismatch (`cover_state_*` vs `cover_*`)
- Enhanced debug logging for device naming and discovery

## 📦 Installation

1. Download `app-debug.apk` from this release
2. Install on Android device via ADB:
   ```bash
   adb install -r app-debug.apk
   ```
3. Restart the app to apply changes
4. **Important**: Remove old cover entities from Home Assistant if they still appear

## 🔧 Technical Details

### Code Changes
- `OneControlDevicePlugin.kt`: Disabled `controlCover()` function with safety documentation
- `HomeAssistantMqttDiscovery.kt`: Added `getCoverStateSensorDiscovery()` for state-only covers
- `DtcCodes.kt`: New file with diagnostic trouble code mappings
- `FunctionNameMapper.kt`: Enhanced device identification
- `MyRvLinkCommandBuilder.kt`: H-bridge command support (for potential future use)

### Safety Implementation
- Control handler returns failure with message: "Cover control disabled for safety"
- Discovery publishes as `sensor` entities, not `cover`
- Extensive code comments documenting hardware limitations
- Renamed control function to `controlCoverDISABLED` with `@Suppress("unused")`

## 📋 Migration Notes

### If upgrading from v2.0.0:
1. Old cover entities (with control buttons) will become unavailable
2. New sensor entities will appear with state-only reporting
3. Delete old cover entities manually from HA Settings → Devices & Services
4. New sensors will use friendly names automatically

### Home Assistant Configuration:
No configuration changes needed - entities auto-discover with new sensor type.

## 🔮 Future Considerations

While remote control is disabled for safety, future enhancements could include:
- **Pulse-based control** - Require hold-to-operate (prevents accidental activation)
- **Current monitoring alerts** - Warn when motor draws excessive current
- **Custom Lovelace cards** - Momentary button UI matching physical controls
- **Position estimation** - Software tracking based on run time

These would require additional safety mechanisms and user acknowledgment of risks.

## 🙏 Acknowledgments

Special thanks to comprehensive testing that revealed the hardware limitations. This release prioritizes user safety over convenience - better to have limited features than damaged equipment.

---

**Full Changelog**: https://github.com/phurth/ble-plugin-bridge/compare/v2.0.0...v2.0.1
