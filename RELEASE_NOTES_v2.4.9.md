# Release Notes - v2.4.9

**Release Date:** January 7, 2026  
**Build:** 30

## Critical Fixes

### EasyTouch Thermostat Stability

This release resolves critical connection stability and control issues affecting EasyTouch thermostats.

#### 1. Watchdog False Positive Fix

**Problem:** EasyTouch connections were dropping every 5 minutes despite working perfectly.

**Root Cause:** The connection watchdog was checking for "stale connections" by comparing against `lastSuccessfulOperationTime`, but this timestamp was only updated during authentication - never during normal status polling. After 5 minutes of successful operation, the watchdog would falsely detect a stale connection and force a reconnect.

**Fix:**
- Status polling responses now update `lastSuccessfulOperationTime` every 4 seconds
- Watchdog only triggers on genuine problems:
  - **Zombie state:** GATT exists but authentication never completed
  - **Actual stale connection:** No status responses for 5+ minutes (real hardware issue)

#### 2. Temperature Setpoint Control

**Problem:** Changing thermostat setpoint in Home Assistant would bounce back to the previous value immediately.

**Root Causes Identified:**
1. **Polling race condition:** 4-second polling + 2-second suppression = status poll at 3.8s overwrites command before device processes it
2. **Float parsing failure:** Home Assistant sends temperatures as floats ("64.0") but code expected integers, causing command to fail silently

**Fixes:**
- **Optimistic state updates:** New setpoint immediately published to MQTT for instant UI feedback
- **Extended suppression:** Increased from 2s to 8s to skip 2 full polling cycles (prevents race condition)
- **Verification reads:** After sending command, explicitly request status at 4s to confirm change applied
- **Float parsing:** Changed from `toIntOrNull()` to `toFloatOrNull()?.toInt()` to handle "64.0" → 64 conversion

**Result:** Temperature setpoint changes now apply reliably and persist correctly.

#### 3. Watchdog Infinite Loop Fix

**Problem:** When watchdog detected a zombie state, it would trigger cleanup but then immediately reschedule itself, creating an infinite loop logging "zombie state detected" every 60 seconds.

**Fix:**
- Added `shouldContinue` flag in watchdog runnable
- When cleanup is triggered (zombie state or stale connection), flag is set to false
- Watchdog only reschedules if `shouldContinue == true`
- Result: Clean shutdown after detecting and resolving zombie states

## Technical Details

### Files Modified

**EasyTouchDevicePlugin.kt:**
- Lines 753-788: Updated `handleJsonResponse()` to set `lastSuccessfulOperationTime` on status responses
- Lines 1285-1322: Enhanced `handleTemperatureCommand()` with optimistic updates, 8s suppression, verification reads, and float parsing
- Similar updates to `handleTemperatureHighCommand()` and `handleTemperatureLowCommand()`
- Lines 1558-1630: Added `shouldContinue` flag to watchdog to prevent infinite loop

### Testing Recommendations

1. **Monitor connection stability:** Observe EasyTouch connection for 10+ minutes - should remain stable
2. **Test setpoint changes:** Change temperature setpoint in Home Assistant - should apply immediately and persist
3. **Watch logs for watchdog:** Should only see watchdog checks every 60s showing healthy state, no "stale connection" or "zombie state" messages

### Upgrade Notes

No configuration changes required. Existing EasyTouch connections will benefit from improved stability immediately after upgrade.

## Previous Improvements (from v2.4.9 development)

### Connection Robustness Enhancements
- GATT 133 automatic retry (up to 3 attempts with 2s delays)
- Per-plugin connection watchdog (60s health monitoring)
- Success operation tracking for stale connection detection

### OneControl Persistent Metadata Cache
- Friendly names now cached in SharedPreferences
- Survives app restarts and reconnections
- Metadata loaded immediately on plugin initialization

## Known Issues

None specific to this release.

## Version History

- **v2.4.8:** Multi-gateway support, OneControl entity duplication fix
- **v2.4.7:** BLE notification race condition fix
- **v2.4.6:** Android TV power management fix
