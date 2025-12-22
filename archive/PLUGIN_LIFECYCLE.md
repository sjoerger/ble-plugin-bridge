# Plugin Management & State Persistence

## Architecture Overview

**Key Principle: Nothing loads automatically on app startup**

The app uses a **lazy loading** architecture where:
- Plugin **factories** are registered at app startup (not instances)
- Plugins are **only loaded when user enables them**
- Service state persists across app restarts
- If service was running, it auto-starts on app launch

## Plugin Lifecycle

### 1. App Startup
```
BlePluginBridgeApplication.onCreate():
  ✓ Register plugin factories (onecontrol, mock_battery, mqtt)
  ✓ NO plugins loaded yet
  ✓ Check if service should auto-start
```

### 2. User Enables Plugin
```
User action (via UI or ADB):
  → ServiceStateManager.enableBlePlugin("onecontrol")
  → Plugin ID saved to SharedPreferences
  → Plugin NOT loaded until service starts
```

### 3. Service Starts
```
User taps "Start Service" (or auto-start on app launch):
  → BaseBleService.onStartCommand()
  → ServiceStateManager.setServiceRunning(true)
  → Load enabled plugins from ServiceStateManager
  → Initialize plugins
  → Start BLE scanning
```

### 4. App Closes (Service Running)
```
App closed, service still running:
  → Service stays in foreground
  → State persisted: wasServiceRunning() = true
  → On app relaunch: Service auto-starts
```

### 5. Service Stops
```
User taps "Stop Service":
  → BaseBleService.onDestroy()
  → ServiceStateManager.setServiceRunning(false)
  → On app relaunch: Service does NOT auto-start
```

## State Persistence (SharedPreferences)

### Service State
```kotlin
// Check if service was running
val wasRunning = ServiceStateManager.wasServiceRunning(context)

// Mark service as running
ServiceStateManager.setServiceRunning(context, true)

// Check if should auto-start
val shouldStart = ServiceStateManager.shouldAutoStart(context)
// Returns true if: autoStartEnabled && wasServiceRunning
```

### Plugin Configuration
```kotlin
// Get enabled plugins
val enabled: Set<String> = ServiceStateManager.getEnabledBlePlugins(context)
// Returns: ["onecontrol"] or [] if none enabled

// Enable a plugin
ServiceStateManager.enableBlePlugin(context, "onecontrol")

// Disable a plugin
ServiceStateManager.disableBlePlugin(context, "onecontrol")

// Check if plugin is enabled
val isEnabled = ServiceStateManager.isBlePluginEnabled(context, "onecontrol")
```

### Output Plugin
```kotlin
// Get enabled output plugin (currently only one supported)
val outputPlugin = ServiceStateManager.getEnabledOutputPlugin(context)
// Returns: "mqtt" (default)

// Change output plugin
ServiceStateManager.setEnabledOutputPlugin(context, "mqtt")
```

## Current Plugin Management (Temporary)

**Until UI is built, use ADB commands:**

### Enable OneControl Plugin
```bash
adb shell am broadcast \
  -a com.blemqttbridge.ENABLE_PLUGIN \
  --es plugin_id onecontrol
```

### Disable Plugin
```bash
adb shell am broadcast \
  -a com.blemqttbridge.DISABLE_PLUGIN \
  --es plugin_id onecontrol
```

### Check Current State
View in ServiceStatusActivity or logcat:
```bash
adb logcat -s ServiceStateManager BaseBleService
```

## Expected Behavior Examples

### Scenario 1: First Launch
```
1. User installs app
2. Launches app
   ✓ NO plugins loaded
   ✓ Service NOT started
3. User enables onecontrol plugin (via ADB)
   ✓ Plugin ID saved: ["onecontrol"]
4. User starts service
   ✓ OneControl plugin loads
   ✓ BLE scanning starts
   ✓ Service marked as running
5. User closes app
   ✓ Service stays running in foreground
6. User reopens app
   ✓ Service auto-starts (was previously running)
   ✓ OneControl plugin loads automatically
```

### Scenario 2: Service Stopped
```
1. User has service running
2. User taps "Stop Service"
   ✓ Service stops
   ✓ State cleared: wasServiceRunning() = false
3. User closes app
4. User reopens app
   ✓ Service does NOT auto-start
   ✓ NO plugins loaded
```

### Scenario 3: Auto-Start Disabled
```
1. User disables auto-start
   ServiceStateManager.setAutoStart(context, false)
2. User starts service
3. User closes app (service running)
4. User reopens app
   ✓ Service does NOT auto-start (user preference)
   ✓ User must manually tap "Start Service"
```

## Testing State Persistence

### Test 1: Service Auto-Start
```bash
# 1. Enable plugin
adb shell am broadcast -a com.blemqttbridge.ENABLE_PLUGIN --es plugin_id onecontrol

# 2. Start service
adb shell am start -n com.blemqttbridge/.ui.ServiceStatusActivity
# Tap "Start Service"

# 3. Verify running
adb logcat -s BaseBleService | grep "marked as running"

# 4. Kill app
adb shell am force-stop com.blemqttbridge

# 5. Relaunch app
adb shell am start -n com.blemqttbridge/.ui.ServiceStatusActivity

# 6. Check logs - service should auto-start
adb logcat -s BaseBleService ServiceStateManager | grep "auto-start\|marked as running"
```

### Test 2: Service Stays Stopped
```bash
# 1. Ensure service is stopped
# Tap "Stop Service" in UI

# 2. Verify stopped
adb logcat -s BaseBleService | grep "marked as stopped"

# 3. Kill app
adb shell am force-stop com.blemqttbridge

# 4. Relaunch app
adb shell am start -n com.blemqttbridge/.ui.ServiceStatusActivity

# 5. Check logs - service should NOT auto-start
adb logcat -s ServiceStateManager | grep "should auto-start: false"
```

### Test 3: Plugin Persistence
```bash
# 1. Enable plugin
adb shell am broadcast -a com.blemqttbridge.ENABLE_PLUGIN --es plugin_id onecontrol

# 2. Kill app
adb shell am force-stop com.blemqttbridge

# 3. Relaunch and start service
adb shell am start -n com.blemqttbridge/.ui.ServiceStatusActivity
# Tap "Start Service"

# 4. Check that onecontrol plugin loads
adb logcat -s PluginRegistry OneControlPlugin | grep "Loading BLE plugin\|Initialized OneControl"
```

## Debugging State

### View All State
In ServiceStatusActivity, the debug info shows:
```
⚙️ Service State:
  Running: ✅ Yes / ❌ No
  Auto-start: ✅ Enabled / ❌ Disabled

🔌 Plugin Configuration:
  Enabled BLE Plugins:
    ✅ onecontrol
  
  Enabled Output Plugin:
    mqtt

📦 Available Plugins:
  - onecontrol (OneControl Gateway)
  - mock_battery (Test Plugin)
  
  Currently Loaded:
    ✅ onecontrol: OneControl Gateway v1.0.0
```

### Programmatic State Check
```kotlin
// Get debug string
val debugInfo = ServiceStateManager.getDebugInfo(context)
Log.d("StateDebug", debugInfo)

// Outputs:
// Service State:
//   Running: true
//   Auto-start: true
//   Should auto-start: true
//
// Plugins:
//   Enabled BLE: onecontrol
//   Enabled Output: mqtt
```

### Clear All State (Reset)
```kotlin
// Useful for testing or debugging
ServiceStateManager.clearAll(context)
// Clears: service state, plugin config, auto-start preference
```

## Future: UI for Plugin Management

**Planned UI (not yet implemented):**

```
Settings Screen:
  ┌─────────────────────────────────┐
  │ 🔌 BLE Device Plugins           │
  │                                 │
  │ ☐ OneControl Gateway            │
  │   Configure MAC, PIN, Cypher    │
  │                                 │
  │ ☐ Mock Battery (Test)           │
  │                                 │
  │ ☐ EasyTouch Climate             │
  │   (Coming soon)                 │
  │                                 │
  │ 📤 Output Plugin                │
  │ ● MQTT                          │
  │   Configure broker, credentials │
  │                                 │
  │ ⚙️ Service Settings             │
  │ ☑ Auto-start on app launch      │
  │                                 │
  │ [Save Configuration]            │
  └─────────────────────────────────┘
```

When implemented, UI will:
- Replace ADB commands for enabling plugins
- Allow per-plugin configuration (MAC, PIN, etc.)
- Provide visual feedback on plugin status
- Allow disabling auto-start

## Migration from Old Behavior

**Before this change:**
- All plugins loaded automatically on app startup
- No state persistence
- Service required manual start every time

**After this change:**
- Plugins require explicit user enablement
- Service state persists
- Auto-start based on last state

**For existing users:**
- Need to manually enable desired plugins
- Service will not auto-load on first launch after update
- After enabling plugins and starting service once, behavior is automatic

## Summary

**Key Takeaways:**
1. ✅ **No auto-loading**: Plugins must be explicitly enabled
2. ✅ **State persistence**: Service remembers on/off state
3. ✅ **Auto-start**: Restores last running state on app launch
4. ✅ **User control**: Auto-start can be disabled
5. ✅ **Lightweight startup**: Fast app launch with lazy loading
6. 🚧 **Temporary ADB**: UI for plugin management coming soon

This architecture ensures:
- User has full control over what runs
- No unexpected battery drain from unwanted plugins
- Proper state restoration for RV use (service keeps running)
- Clean separation between available and active plugins
