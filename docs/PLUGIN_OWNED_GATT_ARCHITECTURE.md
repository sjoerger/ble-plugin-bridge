# Plugin-Owned GATT Callback Architecture

## Executive Summary

This document describes a refactored architecture where **each BLE plugin owns its complete BLE interaction**, including the `BluetoothGattCallback`. The core application becomes a lightweight container providing service lifecycle, MQTT connectivity, and UI - but delegates ALL BLE protocol handling to plugins.

## Problem Statement

### Current Architecture (Broken)

```
┌─────────────────────────────────────────────────────────────┐
│                      BaseBleService                         │
│  ┌─────────────────────────────────────────────────────┐   │
│  │            BluetoothGattCallback                     │   │
│  │  - onConnectionStateChange() ──────┐                │   │
│  │  - onServicesDiscovered() ─────────┤                │   │
│  │  - onCharacteristicChanged() ──────┤  FORWARDING    │   │
│  │  - onDescriptorWrite() ────────────┤  LAYER         │   │
│  └────────────────────────────────────┼────────────────┘   │
│                                       ▼                     │
│                              plugin.onXxx()                 │
└─────────────────────────────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────┐
│                    OneControlPlugin                         │
│  - Receives forwarded events                                │
│  - Cannot control callback timing/threading                 │
│  - Must work within BaseBleService's abstraction           │
└─────────────────────────────────────────────────────────────┘
```

**Why this breaks:**
1. The forwarding layer introduces timing/threading differences
2. Plugin cannot control when/how callbacks are processed
3. Even "direct" calls pass through BaseBleService's context
4. BLE devices (especially OneControl gateway) are sensitive to exact callback behavior
5. The legacy app works because it has NO forwarding layer

### Evidence

- Legacy `OneControlBleService.kt` works perfectly with the same gateway
- Plugin bridge authenticates successfully but receives ZERO notifications
- Gateway terminates connection (status 19) or times out (status 8)
- Same phone, same gateway, same bond - only difference is the architecture

## Proposed Architecture (Plugin-Owned Callbacks)

```
┌─────────────────────────────────────────────────────────────┐
│                      BaseBleService                         │
│                                                             │
│  Responsibilities:                                          │
│  - Android Service lifecycle (foreground service)          │
│  - MQTT client management                                   │
│  - Plugin registry and loading                             │
│  - BLE scanning (finding devices)                          │
│  - Calling connectGatt() with plugin's callback            │
│  - UI notifications                                         │
│                                                             │
│  Does NOT:                                                  │
│  - Own BluetoothGattCallback                               │
│  - Process BLE events                                       │
│  - Know anything about device protocols                    │
└──────────────────────┬──────────────────────────────────────┘
                       │ connectGatt(plugin.createGattCallback())
                       ▼
┌─────────────────────────────────────────────────────────────┐
│                    OneControlPlugin                         │
│  ┌─────────────────────────────────────────────────────┐   │
│  │            BluetoothGattCallback                     │   │
│  │  - onConnectionStateChange()                        │   │
│  │  - onServicesDiscovered()                           │   │
│  │  - onCharacteristicChanged()   ALL HANDLED          │   │
│  │  - onCharacteristicRead()      DIRECTLY BY          │   │
│  │  - onCharacteristicWrite()     PLUGIN               │   │
│  │  - onDescriptorWrite()                              │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  - Complete control over BLE interaction                   │
│  - Can copy legacy app code directly                       │
│  - Publishes state via MqttPublisher interface             │
└─────────────────────────────────────────────────────────────┘
```

## Detailed Design

### 1. New Plugin Interface

```kotlin
interface BleDevicePlugin {
    
    // ===== IDENTIFICATION =====
    
    /** Unique plugin identifier (e.g., "onecontrol", "victron") */
    val pluginId: String
    
    /** Human-readable name */
    val displayName: String
    
    // ===== DEVICE MATCHING =====
    
    /**
     * Check if this plugin can handle the given device.
     * Called during BLE scan to match devices to plugins.
     * 
     * @param device The scanned BLE device
     * @param scanRecord The scan record (advertisement data)
     * @return true if this plugin handles this device
     */
    fun matchesDevice(device: BluetoothDevice, scanRecord: ScanRecord?): Boolean
    
    /**
     * Get configured device MAC addresses (if any).
     * Used for direct connection to known/bonded devices.
     */
    fun getConfiguredDevices(): List<String>
    
    // ===== BLE CONNECTION =====
    
    /**
     * Create the BluetoothGattCallback for this device.
     * 
     * THIS IS THE KEY CHANGE: Plugin owns the entire callback.
     * The callback handles ALL BLE events directly.
     * 
     * @param device The device being connected
     * @param context Android context for BLE operations
     * @param mqttPublisher Interface to publish MQTT messages
     * @param onDisconnect Callback to notify BaseBleService of disconnect
     * @return Complete BluetoothGattCallback implementation
     */
    fun createGattCallback(
        device: BluetoothDevice,
        context: Context,
        mqttPublisher: MqttPublisher,
        onDisconnect: (BluetoothDevice, Int) -> Unit
    ): BluetoothGattCallback
    
    /**
     * Called after connectGatt() succeeds.
     * Plugin can store the GATT reference for later operations.
     */
    fun onGattConnected(device: BluetoothDevice, gatt: BluetoothGatt)
    
    // ===== MQTT TOPICS =====
    
    /**
     * Get the base MQTT topic for this device.
     * Example: "onecontrol/24dcc3ed1e0a"
     */
    fun getMqttBaseTopic(device: BluetoothDevice): String
    
    /**
     * Get Home Assistant discovery payloads for this device.
     * Called after connection is established.
     */
    fun getDiscoveryPayloads(device: BluetoothDevice): List<Pair<String, String>>
    
    // ===== COMMANDS =====
    
    /**
     * Handle incoming MQTT command.
     * Plugin is responsible for translating to BLE writes.
     */
    suspend fun handleCommand(
        device: BluetoothDevice,
        commandTopic: String,
        payload: String
    ): Result<Unit>
    
    // ===== LIFECYCLE =====
    
    /** Initialize plugin (called once at startup) */
    fun initialize(context: Context, config: PluginConfig)
    
    /** Clean up when device disconnects */
    fun onDeviceDisconnected(device: BluetoothDevice)
    
    /** Clean up when plugin is unloaded */
    fun destroy()
}
```

### 2. MQTT Publisher Interface

Plugins need a way to publish MQTT messages without coupling to MQTT implementation:

```kotlin
interface MqttPublisher {
    /**
     * Publish a state update.
     * @param topic Full MQTT topic
     * @param payload JSON or string payload
     * @param retained Whether message should be retained
     */
    fun publishState(topic: String, payload: String, retained: Boolean = true)
    
    /**
     * Publish Home Assistant discovery config.
     */
    fun publishDiscovery(topic: String, payload: String)
    
    /**
     * Publish availability status.
     */
    fun publishAvailability(topic: String, online: Boolean)
}
```

### 3. BaseBleService Changes

BaseBleService becomes much simpler:

```kotlin
class BaseBleService : Service() {
    
    // Plugin registry
    private val plugins = mutableMapOf<String, BleDevicePlugin>()
    
    // Connected devices: MAC -> (GATT, PluginId)
    private val connectedDevices = mutableMapOf<String, Pair<BluetoothGatt, String>>()
    
    // MQTT client
    private var mqttClient: MqttAsyncClient? = null
    
    // MQTT publisher implementation
    private val mqttPublisher = object : MqttPublisher {
        override fun publishState(topic: String, payload: String, retained: Boolean) {
            mqttClient?.publish(topic, MqttMessage(payload.toByteArray()).apply {
                isRetained = retained
            })
        }
        // ... other methods
    }
    
    /**
     * Connect to a device using the appropriate plugin.
     */
    private fun connectToDevice(device: BluetoothDevice, plugin: BleDevicePlugin) {
        Log.i(TAG, "Connecting to ${device.address} with plugin ${plugin.pluginId}")
        
        // Plugin creates its own callback - this is the key change!
        val gattCallback = plugin.createGattCallback(
            device = device,
            context = this,
            mqttPublisher = mqttPublisher,
            onDisconnect = { dev, status -> handleDisconnect(dev, status) }
        )
        
        // Connect using plugin's callback
        val gatt = device.connectGatt(this, false, gattCallback, TRANSPORT_LE)
        
        if (gatt != null) {
            connectedDevices[device.address] = Pair(gatt, plugin.pluginId)
            plugin.onGattConnected(device, gatt)
        }
    }
    
    private fun handleDisconnect(device: BluetoothDevice, status: Int) {
        val (_, pluginId) = connectedDevices.remove(device.address) ?: return
        plugins[pluginId]?.onDeviceDisconnected(device)
        
        // Handle reconnection logic...
    }
    
    // ... scanning, service lifecycle, MQTT setup, etc.
}
```

### 4. OneControlPlugin Implementation

The OneControl plugin can now directly incorporate the working legacy code:

```kotlin
class OneControlPlugin : BleDevicePlugin {
    
    override val pluginId = "onecontrol"
    override val displayName = "OneControl/LCI Gateway"
    
    // State from legacy app
    private var bluetoothGatt: BluetoothGatt? = null
    private var isAuthenticated = false
    private var notificationQueue = LinkedBlockingQueue<ByteArray>()
    private val handler = Handler(Looper.getMainLooper())
    
    // ... all the legacy state variables
    
    override fun createGattCallback(
        device: BluetoothDevice,
        context: Context,
        mqttPublisher: MqttPublisher,
        onDisconnect: (BluetoothDevice, Int) -> Unit
    ): BluetoothGattCallback {
        
        // Return a callback that is essentially the legacy app's callback
        return object : BluetoothGattCallback() {
            
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                // Exact same logic as legacy OneControlBleService
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.i(TAG, "✅ Connected to ${device.address}")
                        // Check bond state, discover services, etc.
                        handler.postDelayed({
                            gatt.discoverServices()
                        }, 500)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.w(TAG, "❌ Disconnected: status=$status")
                        onDisconnect(device, status)
                    }
                }
            }
            
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                // Exact same logic as legacy app
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    discoverCharacteristics(gatt)
                    // Start authentication...
                }
            }
            
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                // Exact same handling as legacy app
                val uuid = characteristic.uuid.toString().lowercase()
                Log.i(TAG, "📨 Notification from $uuid: ${value.size} bytes")
                
                // Queue for stream reading
                notificationQueue.offer(value)
                synchronized(streamReadLock) {
                    streamReadLock.notify()
                }
            }
            
            // ... all other callbacks exactly as legacy app
            
            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                // Track notification subscriptions, start stream reader, etc.
            }
        }
    }
    
    // All the private methods from legacy app:
    // - discoverCharacteristics()
    // - authenticate()
    // - calculateAuthKey()
    // - enableDataNotifications()
    // - startActiveStreamReading()
    // - handleCharacteristicNotification()
    // - processMyRvLinkEvent()
    // - etc.
    
    override suspend fun handleCommand(device: BluetoothDevice, commandTopic: String, payload: String): Result<Unit> {
        // Use the existing command handling logic
        // Write to GATT characteristics as needed
    }
}
```

## Migration Strategy

### Phase 1: Interface Changes (No Behavior Change)

1. Define new `BleDevicePlugin` interface
2. Define `MqttPublisher` interface
3. Keep existing code working while adding new interface

### Phase 2: BaseBleService Refactor

1. Remove `BluetoothGattCallback` from BaseBleService
2. Remove all `onXxx()` forwarding methods
3. Add `connectToDevice()` that uses plugin's callback
4. Implement `MqttPublisher` wrapper around existing MQTT client

### Phase 3: OneControlPlugin Migration

1. Copy working BLE code from legacy `OneControlBleService.kt`
2. Package it inside `createGattCallback()` return value
3. Use `MqttPublisher` for state publishing
4. Test thoroughly

### Phase 4: Cleanup

1. Remove old plugin interface methods
2. Remove unused BaseBleService code
3. Update MockBatteryPlugin (or remove if unused)
4. Update documentation

## Benefits of This Architecture

1. **Proven Code Works**: OneControl plugin can use exact legacy code
2. **Complete Isolation**: Each device type fully controls its BLE behavior
3. **No Forwarding Layer**: Callbacks go directly to plugin code
4. **Device Quirks Handled**: Each plugin handles its own device's quirks
5. **Easy to Add Devices**: New device = new plugin with its own callback
6. **Easy to Debug**: One plugin's issues don't affect others

## Tradeoffs

1. **Code Duplication**: Common BLE patterns repeated in each plugin
   - Mitigation: Create utility classes for common operations
   
2. **More Code Per Plugin**: Plugins are larger/more complex
   - Mitigation: This complexity was always there, just hidden in BaseBleService
   
3. **Plugin Must Know BLE**: Plugin authors need BLE knowledge
   - Mitigation: Provide examples and documentation

## File Changes Required

### New Files
- `app/src/main/java/com/blemqttbridge/core/BleDevicePlugin.kt` - New interface
- `app/src/main/java/com/blemqttbridge/core/MqttPublisher.kt` - Publisher interface

### Modified Files
- `app/src/main/java/com/blemqttbridge/core/BaseBleService.kt` - Remove callback, simplify
- `app/src/main/java/com/blemqttbridge/plugins/device/onecontrol/OneControlPlugin.kt` - Major rewrite

### Possibly Removed
- `app/src/main/java/com/blemqttbridge/plugins/BlePluginInterface.kt` - Replaced by BleDevicePlugin

---

## TODO List

### Setup & Preparation
- [ ] 1. Create backup of current working code state
- [ ] 2. Create `BleDevicePlugin` interface file
- [ ] 3. Create `MqttPublisher` interface file

### BaseBleService Refactor
- [ ] 4. Implement `MqttPublisher` wrapper in BaseBleService
- [ ] 5. Add `connectToDevice(device, plugin)` method using plugin callback
- [ ] 6. Remove `gattCallback` from BaseBleService
- [ ] 7. Remove all `handleXxx()` forwarding methods
- [ ] 8. Update scan result handling to use new plugin interface
- [ ] 9. Update reconnection logic to use new plugin interface

### OneControlPlugin Rewrite
- [ ] 10. Copy `BluetoothGattCallback` implementation from legacy OneControlBleService
- [ ] 11. Implement `createGattCallback()` returning the legacy-style callback
- [ ] 12. Copy authentication code (calculateAuthKey, challenge-response)
- [ ] 13. Copy notification subscription code (enableDataNotifications)
- [ ] 14. Copy stream reading code (startActiveStreamReading, notificationQueue)
- [ ] 15. Copy event processing code (processMyRvLinkEvent, handleMyRvLinkEvent)
- [ ] 16. Update to use `MqttPublisher` instead of direct MQTT calls
- [ ] 17. Implement remaining interface methods (matchesDevice, etc.)

### Testing
- [ ] 18. Build and deploy to test device
- [ ] 19. Test fresh connection (unpaired state)
- [ ] 20. Test bonded reconnection
- [ ] 21. Verify notifications are received
- [ ] 22. Verify GatewayInformation event processing
- [ ] 23. Verify device discovery and MQTT publishing
- [ ] 24. Test MQTT commands (light on/off, etc.)

### Cleanup
- [ ] 25. Remove old `BlePluginInterface`
- [ ] 26. Update/remove MockBatteryPlugin
- [ ] 27. Update README and documentation
- [ ] 28. Remove dead code from BaseBleService

---

## Success Criteria

1. Plugin bridge connects to OneControl gateway
2. Notifications are received (📨 logs appear)
3. GatewayInformation event is processed
4. Devices are discovered and published to MQTT
5. Commands from MQTT control devices
6. Connection is stable (no status 19/133 disconnects)
7. Behavior matches legacy app exactly
