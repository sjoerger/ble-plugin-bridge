package com.blemqttbridge.plugins.device.onecontrol

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import com.blemqttbridge.core.interfaces.BlePluginInterface
import com.blemqttbridge.plugins.device.onecontrol.protocol.Constants
import com.blemqttbridge.plugins.device.onecontrol.protocol.DimmableLightStatusEvent
import com.blemqttbridge.plugins.device.onecontrol.protocol.GatewayInformationEvent
import com.blemqttbridge.plugins.device.onecontrol.protocol.RelayBasicLatchingStatusEvent
import com.blemqttbridge.plugins.device.onecontrol.protocol.RelayHBridgeStatusEvent
import com.blemqttbridge.plugins.device.onecontrol.protocol.RvStatusEvent
import com.blemqttbridge.plugins.device.onecontrol.protocol.DeviceOnlineStatusEvent
import com.blemqttbridge.plugins.device.onecontrol.protocol.TankSensorStatusEvent
import com.blemqttbridge.plugins.device.onecontrol.protocol.TankSensorStatusV2Event
import com.blemqttbridge.plugins.device.onecontrol.protocol.HvacStatusEvent
import com.blemqttbridge.plugins.device.onecontrol.protocol.MyRvLinkCommandEncoder
import com.blemqttbridge.plugins.device.onecontrol.protocol.MyRvLinkEventFactory
import com.blemqttbridge.plugins.device.onecontrol.protocol.MyRvLinkEventType
import kotlinx.coroutines.*

/**
 * OneControl BLE Gateway Plugin
 * 
 * Interfaces with Lippert OneControl RV system via BLE gateway.
 * Supports lighting, awnings, leveling, and other CAN-based devices.
 * 
 * Complete implementation with PIN unlock + TEA authentication for OneControl gateways.
 */
class OneControlPlugin : BlePluginInterface {
    // Device state tracker for event/state management
    private var deviceStateTracker: com.blemqttbridge.plugins.device.onecontrol.protocol.DeviceStateTracker? = null
    
    // State listener for MQTT output (set via setStateListener)
    private var stateListener: OneControlStateListener? = null
    private var mqttFormatter: OneControlMqttFormatter? = null
    
    // Coroutine scope for emitting state updates asynchronously
    private val pluginScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Track which devices have been discovered (for HA discovery publishing)
    private val discoveredDevices = mutableSetOf<Int>()
    
    /**
     * Simple COBS decoder state for byte-by-byte processing
     */
    data class CobsDecoderState(
        var buffer: ByteArray = ByteArray(256),
        var bufferIndex: Int = 0,
        var code: Int = 0,
        var codeIndex: Int = 0,
        var expectingFrame: Boolean = false
    ) {
        fun decodeByte(byte: Byte): ByteArray? {
            val unsignedByte = byte.toInt() and 0xFF
            
            if (unsignedByte == 0x00) {
                // Frame delimiter - process accumulated data
                if (bufferIndex > 0) {
                    val frame = buffer.copyOf(bufferIndex)
                    reset()
                    return frame
                }
                return null
            }
            
            if (codeIndex == 0) {
                // New block - store overhead byte
                code = unsignedByte
                codeIndex = 1
                if (code == 0xFF) return null  // Invalid
            } else {
                // Data byte
                if (bufferIndex < buffer.size) {
                    buffer[bufferIndex++] = byte
                }
                codeIndex++
                
                if (codeIndex >= code) {
                    // End of block - add delimiter if not at end
                    if (code < 0xFF && bufferIndex < buffer.size) {
                        buffer[bufferIndex++] = 0x00
                    }
                    codeIndex = 0
                }
            }
            
            return null
        }
        
        fun reset() {
            bufferIndex = 0
            code = 0
            codeIndex = 0
            expectingFrame = false
        }
    }
    
    companion object {
        private const val TAG = "OneControlPlugin"
        private const val PLUGIN_ID = "onecontrol"
        private const val PLUGIN_NAME = "OneControl Gateway"
        private const val PLUGIN_VERSION = "1.0.0"
        
        // Config keys
        private const val CONFIG_GATEWAY_MAC = "gateway_mac"
        private const val CONFIG_GATEWAY_PIN = "gateway_pin"
        private const val CONFIG_GATEWAY_CYPHER = "gateway_cypher"
        
        // Default config (from existing app)
        private const val DEFAULT_GATEWAY_MAC = "24:DC:C3:ED:1E:0A"
        private const val DEFAULT_GATEWAY_PIN = "090336"
        private const val DEFAULT_GATEWAY_CYPHER = 0x8100080DL
    }
    
    private lateinit var context: Context
    
    // Configuration
    private var gatewayMac: String = DEFAULT_GATEWAY_MAC
    private var gatewayPin: String = DEFAULT_GATEWAY_PIN
    private var gatewayCypher: Long = DEFAULT_GATEWAY_CYPHER
    
    // Connection state tracking
    private val connectedDevices = mutableSetOf<String>()  // Device addresses
    private val authenticatedDevices = mutableSetOf<String>()  // Authenticated devices
    private val unlockedDevices = mutableSetOf<String>()  // PIN-unlocked devices        
    
    // Gateway type tracking (Data Service vs CAN Service)
    // Data Service gateways require 2-byte header stripping on decoded frames
    private val dataServiceGateways = mutableSetOf<String>()
    
    // Discovery tracking (like legacy app)
    private val haDiscoveryPublished = mutableSetOf<String>()        
    
    // Protocol state tracking
    private var nextCommandId: UShort = 1u
    private var deviceTableId: Byte = 0x00
    private val streamReadingDevices = mutableSetOf<String>()
    private val heartbeatJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private val notificationProcessingJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private val notificationQueues = mutableMapOf<String, java.util.concurrent.ConcurrentLinkedQueue<ByteArray>>()
    private val DEFAULT_DEVICE_TABLE_ID: Byte = 0x01  // From original app
    private val gatewayInfoReceived = mutableMapOf<String, Boolean>()  // Per-device tracking
    
    // Active stream reading (from original app) - per device
    private val streamReadingThreads = mutableMapOf<String, Thread>()
    private val streamReadingFlags = mutableMapOf<String, Boolean>()
    private val streamReadingLocks = mutableMapOf<String, Object>()
    private val cobsDecoderStates = mutableMapOf<String, CobsDecoderState>()
    
    private var gattOperations: BlePluginInterface.GattOperations? = null
    private var currentDevice: BluetoothDevice? = null  // Store device for callbacks
    private var pendingSeedResponse: CompletableDeferred<ByteArray>? = null
    
    override fun getPluginId(): String = PLUGIN_ID
    
    override fun getPluginName(): String = PLUGIN_NAME
    
    override fun getPluginVersion(): String = PLUGIN_VERSION
    
    override fun canHandleDevice(device: BluetoothDevice, scanRecord: ByteArray?): Boolean {
        // Match by configured MAC address
        if (device.address.equals(gatewayMac, ignoreCase = true)) {
            Log.i(TAG, "✅ Device matches configured MAC: ${device.address}")
            return true
        }
        
        // Also check for OneControl discovery service UUID in scan record
        scanRecord?.let { record ->
            val serviceUuids = parseScanRecordServiceUuids(record)
            if (serviceUuids.any { it.equals(Constants.DISCOVERY_SERVICE_UUID, ignoreCase = true) }) {
                Log.i(TAG, "✅ Device has OneControl discovery service UUID")
                return true
            }
        }
        
        return false
    }
    
    override fun getDeviceId(device: BluetoothDevice): String {
        // Use MAC address as device ID (stable and unique)
        return device.address.replace(":", "").lowercase()
    }
    
    override suspend fun initialize(
        context: Context,
        config: Map<String, String>
    ): Result<Unit> {
        this.context = context
        
        // Load configuration
        gatewayMac = config[CONFIG_GATEWAY_MAC] ?: DEFAULT_GATEWAY_MAC
        gatewayPin = config[CONFIG_GATEWAY_PIN] ?: DEFAULT_GATEWAY_PIN
        gatewayCypher = config[CONFIG_GATEWAY_CYPHER]?.toLongOrNull() ?: DEFAULT_GATEWAY_CYPHER
        
        Log.i(TAG, "Initialized OneControl plugin")
        Log.i(TAG, "  Gateway MAC: $gatewayMac")
        Log.i(TAG, "  PIN: ${gatewayPin.take(2)}****")
        
        return Result.success(Unit)
    }
    
    override suspend fun onDeviceConnected(device: BluetoothDevice): Result<Unit> {
        Log.i(TAG, "🔗 Device connected: ${device.address}")
        
        connectedDevices.add(device.address)
        
        // Note: Service discovery and characteristic setup is handled by BaseBleService
        Log.i(TAG, "✅ Device ${device.address} ready")
        
        return Result.success(Unit)
    }
    
    override suspend fun onServicesDiscovered(
        device: BluetoothDevice,
        gattOperations: BlePluginInterface.GattOperations
    ): Result<Unit> {
        this.gattOperations = gattOperations
        this.currentDevice = device  // Store device for callbacks
        
        // Clear discovered devices to allow discovery republishing on each connection
        // This ensures Home Assistant gets discovery payloads even after HA/MQTT restart
        discoveredDevices.clear()
        
        // CRITICAL: 1.5s stabilization delay after service discovery
        // Without this, we get "already has a pending command" errors
        // See: context/session_summary_jan2025.md - "1.5s stabilization delay after service discovery"
        Log.i(TAG, "⏳ Waiting 1.5s for GATT stabilization...")
        kotlinx.coroutines.delay(1500)
        
        Log.i(TAG, "🔐 Starting authentication for ${device.address}...")
        
        return try {
            // STEP 1: Check gateway type by SERVICE EXISTENCE (NOT by reading characteristics!)
            // CRITICAL: Reading UNLOCK_CHAR triggers BLE encryption which can fail and remove the bond.
            // Instead, check if CAN Service (0x00000000) or Data Service (0x00000030) exists.
            // Legacy app does: (canService == null && dataService != null) -> Data Service gateway
            Log.i(TAG, "Step 1: Checking gateway type by service existence...")
            
            val hasCanService = gattOperations.hasService(Constants.CAN_SERVICE_UUID)
            val hasDataService = gattOperations.hasService(Constants.DATA_SERVICE_UUID)
            
            Log.i(TAG, "  CAN Service (${Constants.CAN_SERVICE_UUID}): ${if (hasCanService) "✅ FOUND" else "❌ NOT FOUND"}")
            Log.i(TAG, "  Data Service (${Constants.DATA_SERVICE_UUID}): ${if (hasDataService) "✅ FOUND" else "❌ NOT FOUND"}")
            
            // Data Service gateway = has Data Service but NOT CAN Service (matches legacy app logic)
            val isDataServiceGateway = !hasCanService && hasDataService
            
            // Track gateway type for frame processing (Data Service needs header stripping)
            if (isDataServiceGateway) {
                dataServiceGateways.add(device.address)
                Log.i(TAG, "📡 Gateway ${device.address} identified as Data Service gateway")
            } else {
                dataServiceGateways.remove(device.address)
                Log.i(TAG, "📡 Gateway ${device.address} identified as CAN Service gateway")
            }
            
            if (isDataServiceGateway) {
                // This is a Data Service gateway - REQUIRES challenge-response authentication!
                // From AUTHENTICATION_ALGORITHM.md:
                // "Authentication is REQUIRED for MyRvLink Data Service gateways"
                // "Without this authentication, the gateway will accept CCCD subscription but 
                //  will not send any notifications"
                
                Log.i(TAG, "🔐 Data Service gateway detected - performing challenge-response authentication...")
                
                // STEP 1: Read challenge from UNLOCK_STATUS (00000012)
                val challengeResult = gattOperations.readCharacteristic(Constants.UNLOCK_STATUS_CHAR_UUID)
                if (challengeResult.isFailure) {
                    return Result.failure(Exception("Failed to read UNLOCK_STATUS challenge: ${challengeResult.exceptionOrNull()?.message}"))
                }
                
                val challengeBytes = challengeResult.getOrThrow()
                Log.d(TAG, "📥 UNLOCK_STATUS response: ${challengeBytes.joinToString(" ") { String.format("%02X", it) }} (${challengeBytes.size} bytes)")
                
                // Check if already unlocked (returns "Unlocked" ASCII string)
                if (challengeBytes.size == 8) {
                    val statusString = String(challengeBytes, Charsets.UTF_8)
                    if (statusString.equals("Unlocked", ignoreCase = true)) {
                        Log.i(TAG, "✅ Gateway already unlocked - skipping authentication")
                        authenticatedDevices.add(device.address)
                    }
                }
                
                // If 4 bytes, it's a challenge - calculate and write KEY
                if (challengeBytes.size == 4 && !authenticatedDevices.contains(device.address)) {
                    // Parse as BIG-ENDIAN uint32 (critical - Data Service uses big-endian!)
                    val challenge = ((challengeBytes[0].toLong() and 0xFF) shl 24) or
                                   ((challengeBytes[1].toLong() and 0xFF) shl 16) or
                                   ((challengeBytes[2].toLong() and 0xFF) shl 8) or
                                   (challengeBytes[3].toLong() and 0xFF)
                    
                    Log.d(TAG, "🔑 Challenge (big-endian): 0x${challenge.toString(16).padStart(8, '0')}")
                    
                    // STEP 2: Calculate KEY using TEA encryption (cypher = 612643285)
                    val keyValue = calculateAuthKey(challenge)
                    Log.d(TAG, "🔑 Calculated KEY: 0x${keyValue.toString(16).padStart(8, '0')}")
                    
                    // Convert to BIG-ENDIAN bytes
                    val keyBytes = byteArrayOf(
                        ((keyValue shr 24) and 0xFF).toByte(),
                        ((keyValue shr 16) and 0xFF).toByte(),
                        ((keyValue shr 8) and 0xFF).toByte(),
                        (keyValue and 0xFF).toByte()
                    )
                    Log.d(TAG, "🔑 KEY bytes (big-endian): ${keyBytes.joinToString(" ") { String.format("%02X", it) }}")
                    
                    // STEP 3: Write KEY to 00000013 (CRITICAL: WRITE_TYPE_NO_RESPONSE!)
                    val keyWriteResult = gattOperations.writeCharacteristicNoResponse(Constants.KEY_CHAR_UUID, keyBytes)
                    if (keyWriteResult.isFailure) {
                        return Result.failure(Exception("Failed to write KEY: ${keyWriteResult.exceptionOrNull()?.message}"))
                    }
                    Log.i(TAG, "✅ KEY written successfully")
                    
                    // STEP 4: Wait 500ms for gateway to enter data mode
                    delay(500)
                    
                    // STEP 5: Read UNLOCK_STATUS again to verify "Unlocked"
                    val verifyResult = gattOperations.readCharacteristic(Constants.UNLOCK_STATUS_CHAR_UUID)
                    if (verifyResult.isSuccess) {
                        val verifyBytes = verifyResult.getOrThrow()
                        val verifyString = String(verifyBytes, Charsets.UTF_8)
                        Log.i(TAG, "🔓 Verify status: '$verifyString' (${verifyBytes.size} bytes)")
                        
                        if (verifyString.equals("Unlocked", ignoreCase = true)) {
                            Log.i(TAG, "✅ Authentication verified - gateway unlocked!")
                            authenticatedDevices.add(device.address)
                        } else {
                            Log.w(TAG, "⚠️ Unexpected unlock status after KEY write: $verifyString")
                            // Continue anyway - some gateways may not return "Unlocked"
                            authenticatedDevices.add(device.address)
                        }
                    } else {
                        Log.w(TAG, "⚠️ Failed to verify unlock status: ${verifyResult.exceptionOrNull()?.message}")
                        // Continue anyway - KEY write is the critical step
                        authenticatedDevices.add(device.address)
                    }
                }
                
                // STEP 6: Enable notifications (MUST be after KEY write!)
                // CRITICAL: Original app subscribes in THIS ORDER for Data Service gateways:
                // 1. 00000034 (DATA_READ) - Main data stream FIRST
                // 2. 00000011 (SEED) - Auth Service notifications
                // 3. 00000014 (Auth Service) - Additional auth notifications
                
                Log.i(TAG, "📝 Enabling notifications (all 3 characteristics)...")
                delay(200)  // Wait for gateway to enter data mode (from technical_spec.md)
                
                // Subscribe to Data Service READ (00000034) - FIRST like legacy app
                val notifyResult = gattOperations.enableNotifications(Constants.DATA_READ_CHAR_UUID)
                if (notifyResult.isFailure) {
                    Log.w(TAG, "⚠️ Failed to subscribe to DATA (00000034): ${notifyResult.exceptionOrNull()?.message}")
                    // Continue anyway - may still work
                } else {
                    Log.i(TAG, "✅ Subscribed to DATA (00000034) notifications")
                }
                delay(150)  // Small delay between subscriptions (from original app)
                
                // Subscribe to Auth Service SEED (00000011)
                val seedNotifyResult = gattOperations.enableNotifications(Constants.SEED_CHAR_UUID)
                if (seedNotifyResult.isFailure) {
                    Log.w(TAG, "⚠️ Failed to subscribe to SEED (00000011): ${seedNotifyResult.exceptionOrNull()?.message}")
                } else {
                    Log.i(TAG, "✅ Subscribed to SEED (00000011) notifications")
                }
                delay(150)  // Small delay between subscriptions
                
                // Subscribe to Auth Service 00000014
                val auth14NotifyResult = gattOperations.enableNotifications(Constants.AUTH_STATUS_CHAR_UUID)
                if (auth14NotifyResult.isFailure) {
                    Log.w(TAG, "⚠️ Failed to subscribe to Auth 00000014: ${auth14NotifyResult.exceptionOrNull()?.message}")
                } else {
                    Log.i(TAG, "✅ Subscribed to Auth (00000014) notifications")
                }
                
                // Start stream reader
                startActiveStreamReading(device)
                
                // CRITICAL: CAN-based Data Service gateways need an initial GetDevices command
                // to "wake up" and start sending data (including GatewayInformation event).
                // Per legacy app (android_ble_bridge line 1349): "CAN-based gateways need an 
                // initial command to 'wake up' and start sending data".
                // Send GetDevices to trigger GatewayInformation and data flow.
                // Once GatewayInformation arrives, heartbeat will be started from onGatewayInfoReceived().
                GlobalScope.launch {
                    delay(500)  // Small delay to ensure stream reader is ready
                    Log.i(TAG, "📤 Sending initial GetDevices to wake up CAN-based Data Service gateway")
                    sendInitialCanCommand(device)
                }
                
                Log.i(TAG, "✅ Data Service gateway connected - waiting for GatewayInformation...")
                return Result.success(Unit)
            } else {
                // CAN Service gateway - perform full PIN unlock + TEA authentication
                // First, read the unlock status from UNLOCK_CHAR
                Log.i(TAG, "📖 Reading unlock status from CAN Service gateway...")
                val unlockStatusResult = gattOperations.readCharacteristic(Constants.UNLOCK_CHAR_UUID)
                if (unlockStatusResult.isFailure) {
                    return Result.failure(Exception("Failed to read unlock status: ${unlockStatusResult.exceptionOrNull()?.message}"))
                }
                
                val unlockStatus = unlockStatusResult.getOrThrow()
                if (unlockStatus.isEmpty()) {
                    return Result.failure(Exception("Unlock status returned empty data"))
                }
                
                val status = unlockStatus[0].toInt() and 0xFF
                Log.d(TAG, "Unlock status: 0x${status.toString(16)}")
                
                if (status == 0) {
                    // Gateway is locked - write PIN
                    Log.i(TAG, "Gateway is locked, writing PIN...")
                    val pinBytes = gatewayPin.toByteArray(Charsets.UTF_8)
                    val writeResult = gattOperations.writeCharacteristic(Constants.UNLOCK_CHAR_UUID, pinBytes)
                    
                    if (writeResult.isFailure) {
                        return Result.failure(Exception("Failed to write PIN: ${writeResult.exceptionOrNull()?.message}"))
                    }
                    
                    // Wait for unlock to settle
                    Log.d(TAG, "Waiting ${Constants.UNLOCK_VERIFY_DELAY_MS}ms for unlock to complete...")
                    delay(Constants.UNLOCK_VERIFY_DELAY_MS)
                    
                    // Verify unlock
                    val verifyResult = gattOperations.readCharacteristic(Constants.UNLOCK_CHAR_UUID)
                    if (verifyResult.isFailure) {
                        return Result.failure(Exception("Failed to verify unlock: ${verifyResult.exceptionOrNull()?.message}"))
                    }
                    
                    val verifyStatus = verifyResult.getOrThrow()
                    if (verifyStatus.isEmpty() || (verifyStatus[0].toInt() and 0xFF) == 0) {
                        return Result.failure(Exception("Gateway unlock failed (PIN incorrect?)"))
                    }
                    
                    Log.i(TAG, "✅ Gateway unlocked successfully!")
                } else {
                    Log.i(TAG, "✅ Gateway already unlocked")
                }
                
                unlockedDevices.add(device.address)
                
                // STEP 2: TEA SEED/KEY Authentication (Only for CAN Service gateways)
                Log.i(TAG, "🔐 Starting TEA SEED/KEY authentication...")
                
                // Read SEED from AUTH service
                val seedResult = gattOperations.readCharacteristic(Constants.SEED_CHAR_UUID)
                if (seedResult.isFailure) {
                    return Result.failure(Exception("Failed to read SEED: ${seedResult.exceptionOrNull()?.message}"))
                }
                
                val seedBytes = seedResult.getOrThrow()
                if (seedBytes.size != 4) {
                    return Result.failure(Exception("Invalid SEED size: ${seedBytes.size}, expected 4"))
                }
                
                // Extract 32-bit seed (little-endian for CAN Service)
                val seed = ((seedBytes[3].toLong() and 0xFF) shl 24) or
                          ((seedBytes[2].toLong() and 0xFF) shl 16) or
                          ((seedBytes[1].toLong() and 0xFF) shl 8) or
                          (seedBytes[0].toLong() and 0xFF)
                
                Log.d(TAG, "Read SEED: 0x${seed.toString(16).padStart(8, '0')}")
                
                // Encrypt seed with TEA (using CAN Service cypher = 0x8100080DL)
                val encryptedKey = calculateTeaKey(gatewayCypher, seed)
                Log.d(TAG, "Encrypted KEY: 0x${encryptedKey.toString(16).padStart(8, '0')}")
                
                // Write encrypted key back (little-endian for CAN Service)
                val keyBytes = byteArrayOf(
                    (encryptedKey and 0xFF).toByte(),
                    ((encryptedKey shr 8) and 0xFF).toByte(),
                    ((encryptedKey shr 16) and 0xFF).toByte(),
                    ((encryptedKey shr 24) and 0xFF).toByte()
                )
                
                // Write KEY (CRITICAL: Must use WRITE_TYPE_NO_RESPONSE to enable data mode)
                val keyWriteResult = gattOperations.writeCharacteristicNoResponse(Constants.KEY_CHAR_UUID, keyBytes)
                if (keyWriteResult.isFailure) {
                    return Result.failure(Exception("Failed to write KEY: ${keyWriteResult.exceptionOrNull()?.message}"))
                }
                
                // Wait for gateway to enter data mode (critical timing from technical spec)
                delay(200)
                
                Log.i(TAG, "✅ TEA authentication complete!")
                
                // Subscribe to CAN notifications
                Log.d(TAG, "Subscribing to CAN notifications: ${Constants.CAN_READ_CHAR_UUID}")
                val notifyResult = gattOperations.enableNotifications(Constants.CAN_READ_CHAR_UUID)
                if (notifyResult.isFailure) {
                    Log.w(TAG, "⚠️ Failed to subscribe to CAN: ${notifyResult.exceptionOrNull()?.message}")
                } else {
                    Log.i(TAG, "✅ Subscribed to CAN notifications")
                }
                
                // Start complete post-authentication protocol (like original app)
                Log.i(TAG, "✅ CAN Service authentication complete: Starting full protocol flow")
                startActiveStreamReading(device)
                sendInitialCanCommand(device)
                startHeartbeat(device)
                
                authenticatedDevices.add(device.address)
                return Result.success(Unit)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Authentication failed: ${e.message}", e)
            pendingSeedResponse = null
            Result.failure(e)
        }
    }
    
    override suspend fun onDeviceDisconnected(device: BluetoothDevice) {
        Log.i(TAG, "🔌 Device disconnected: ${device.address}")
        
        // Stop heartbeat and stream reading
        heartbeatJobs[device.address]?.cancel()
        heartbeatJobs.remove(device.address)
        
        // Stop stream reading threads
        streamReadingFlags[device.address] = true  // Signal thread to stop
        streamReadingLocks[device.address]?.let { lock ->
            synchronized(lock) {
                lock.notify() // Wake up waiting thread
            }
        }
        streamReadingThreads[device.address]?.interrupt()
        streamReadingThreads.remove(device.address)
        streamReadingFlags.remove(device.address)
        streamReadingLocks.remove(device.address)
        cobsDecoderStates.remove(device.address)  // Clean up COBS decoder state
        gatewayInfoReceived.remove(device.address)  // Clean up gateway info state
        
        notificationProcessingJobs[device.address]?.cancel()
        notificationProcessingJobs.remove(device.address)
        notificationQueues.remove(device.address)
        streamReadingDevices.remove(device.address)
        
        connectedDevices.remove(device.address)
        authenticatedDevices.remove(device.address)
        unlockedDevices.remove(device.address)
        gattOperations = null
        currentDevice = null
        pendingSeedResponse = null
    }
    
    override fun onCharacteristicNotification(
        device: BluetoothDevice,
        characteristicUuid: String,
        value: ByteArray
    ): Map<String, String> {
        Log.d(TAG, "📨 Notification from $characteristicUuid: ${value.toHexString()}")
        
        // Queue notification for background processing (like original app)
        if (characteristicUuid.lowercase() == Constants.CAN_READ_CHAR_UUID.lowercase() || 
            characteristicUuid.lowercase() == Constants.DATA_READ_CHAR_UUID.lowercase()) {
            notificationQueues[device.address]?.offer(value)
            // Wake up the reading thread (like original app)
            streamReadingLocks[device.address]?.let { lock ->
                synchronized(lock) {
                    lock.notify()
                }
            }
        }
        
        // Match characteristic UUID and parse data
        when (characteristicUuid.lowercase()) {
            Constants.SEED_CHAR_UUID.lowercase() -> {
                Log.d(TAG, "📨 Received SEED notification: ${value.toHexString()}")
                // Complete the pending SEED request
                pendingSeedResponse?.complete(value)
                pendingSeedResponse = null
            }
            Constants.CAN_READ_CHAR_UUID.lowercase() -> {
                Log.d(TAG, "📨 CAN data received: ${value.toHexString()}")
                // Processed by background thread via queue
                return mapOf(
                    "status" to "online",
                    "last_update" to System.currentTimeMillis().toString()
                )
            }
            Constants.DATA_READ_CHAR_UUID.lowercase() -> {
                Log.d(TAG, "📨 DATA notification received: ${value.toHexString()}")
                // Processed by background thread via queue
                return mapOf(
                    "status" to "online",
                    "last_update" to System.currentTimeMillis().toString()
                )
            }
            Constants.UNLOCK_STATUS_CHAR_UUID.lowercase() -> {
                val status = value.decodeToString()
                Log.i(TAG, "🔓 Unlock status: $status")
            }
        }
        
        return emptyMap()
    }
    
    override suspend fun handleCommand(
        device: BluetoothDevice,
        commandTopic: String,
        payload: String
    ): Result<Unit> {
        Log.i(TAG, "📥 Command received - Topic: $commandTopic, Payload: $payload")
        
        // Parse topic to extract device info
        // Format: {device_type}/{device_id}/{command}
        val parts = commandTopic.split("/")
        if (parts.size < 3) {
            return Result.failure(Exception("Invalid topic format: $commandTopic"))
        }
        
        val deviceType = parts[0]  // e.g., "light", "awning"
        val deviceId = parts[1]
        val command = parts[2]  // e.g., "set", "brightness"
        
        Log.i(TAG, "📤 Command parsed: type=$deviceType id=$deviceId cmd=$command payload=$payload")
        
        // TODO: Build CAN command using MyRvLinkCommandBuilder
        // TODO: Encode using MyRvLinkCommandEncoder
        // TODO: Write to CAN_WRITE_CHAR or DATA_WRITE_CHAR via gattOperations
        
        return Result.success(Unit)
    }
    
    override suspend fun getDiscoveryPayloads(device: BluetoothDevice): Map<String, String> {
        // Discovery is now handled by OneControlMqttFormatter to avoid duplicate devices
        // The formatter creates a single unified device with all entities
        Log.d(TAG, "Discovery handled by OneControlMqttFormatter")
        return emptyMap()
    }
    
    override fun getPollingIntervalMs(): Long? {
        // OneControl uses notifications, no polling needed
        return null
    }
    
    override fun getTargetDeviceAddresses(): List<String> {
        // Return configured gateway MAC for scan filtering
        // This allows BLE scanning to continue even when screen is locked
        return if (gatewayMac.isNotBlank()) {
            listOf(gatewayMac.uppercase())
        } else {
            emptyList()
        }
    }
    
    override suspend fun cleanup() {
        Log.i(TAG, "🧹 Cleaning up OneControl plugin")
        
        // Cancel plugin scope
        pluginScope.cancel()
        
        // Cancel all heartbeats
        heartbeatJobs.values.forEach { it.cancel() }
        heartbeatJobs.clear()
        
        connectedDevices.clear()
        authenticatedDevices.clear()
        unlockedDevices.clear()
        streamReadingDevices.clear()
        discoveredDevices.clear()
        stateListener = null
    }
    
    // ============================================================================
    // Private Helper Methods
    // ============================================================================
    
    private fun parseScanRecordServiceUuids(scanRecord: ByteArray): List<String> {
        val uuids = mutableListOf<String>()
        var currentPos = 0
        
        while (currentPos < scanRecord.size) {
            val length = scanRecord[currentPos].toInt() and 0xFF
            if (length == 0 || currentPos + length >= scanRecord.size) break
            
            val type = scanRecord[currentPos + 1].toInt() and 0xFF
            
            // 0x06 = Complete list of 128-bit UUIDs
            // 0x07 = Incomplete list of 128-bit UUIDs
            if (type == 0x06 || type == 0x07) {
                val uuidBytes = scanRecord.copyOfRange(currentPos + 2, currentPos + 1 + length)
                val uuid = parseUuid128(uuidBytes)
                uuid?.let { uuids.add(it) }
            }
            
            currentPos += length + 1
        }
        
        return uuids
    }
    
    /**
     * Start active stream reading loop - matches original app implementation
     * Based on DirectConnectionMyRvLinkBle.BackgroundOperationAsync()
     */
    private fun startActiveStreamReading(device: BluetoothDevice) {
        if (streamReadingDevices.contains(device.address)) {
            Log.d(TAG, "🔄 Active stream reading already active for ${device.address}")
            return
        }
        
        streamReadingDevices.add(device.address)
        notificationQueues[device.address] = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
        streamReadingFlags[device.address] = false  // shouldStopStreamReading = false
        streamReadingLocks[device.address] = Object()
        cobsDecoderStates[device.address] = CobsDecoderState()  // Initialize COBS decoder
        gatewayInfoReceived[device.address] = false  // Initialize gateway info status
        
        Log.i(TAG, "🔄 Active stream reading started for ${device.address}")
        
        // Start background thread exactly like original app
        val thread = Thread {
            val queue = notificationQueues[device.address]
            val lock = streamReadingLocks[device.address]
            Log.i(TAG, "🔄 Background stream reading thread started for ${device.address}")
            
            // Use safe null checks to avoid crash when device disconnects and maps are cleared
            while (streamReadingFlags[device.address] == false && streamReadingDevices.contains(device.address)) {
                try {
                    if (lock == null) break  // Device was cleaned up
                    synchronized(lock) {
                        if (queue?.isEmpty() == true) {
                            lock.wait(8000)  // 8-second timeout like original
                        }
                    }
                    
                    // Process all queued notification packets
                    while (queue?.isNotEmpty() == true && streamReadingFlags[device.address] == false) {
                        val notificationData = queue.poll() ?: continue
                        Log.d(TAG, "📥 Processing queued notification: ${notificationData.size} bytes")
                        
                        // Feed bytes one at a time to COBS decoder (like original app)
                        for (byte in notificationData) {
                            // TODO: Implement COBS byte-by-byte decoding like original
                            processNotificationByte(device.address, byte)
                        }
                    }
                } catch (e: InterruptedException) {
                    Log.i(TAG, "Stream reading thread interrupted for ${device.address}")
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Stream reading error for ${device.address}: ${e.message}")
                }
            }
            
            Log.i(TAG, "🔄 Background stream reading thread stopped for ${device.address}")
        }
        
        streamReadingThreads[device.address] = thread
        thread.start()
    }
    
    /**
     * Process individual notification bytes through COBS decoder (like original app)
     */
    private fun processNotificationByte(deviceAddress: String, byte: Byte) {
        cobsDecoderStates[deviceAddress]?.let { decoder ->
            val decodedFrame = decoder.decodeByte(byte)
            if (decodedFrame != null) {
                Log.d(TAG, "✅ Decoded COBS frame: ${decodedFrame.size} bytes - ${decodedFrame.toHexString()}")
                processDecodedFrame(deviceAddress, decodedFrame)
            }
        }
    }
    
    /**
     * Process completed COBS-decoded frame (like original app)
     * Parses event types and emits state updates via listener.
     * 
     * Frame formats:
     * - Events: [EventType (1 byte)][Event data...]
     * - Command Responses: [ClientCommandId (2 bytes LE)][CommandType (1 byte)][Response data...]
     */
    private fun processDecodedFrame(deviceAddress: String, frame: ByteArray) {
        Log.d(TAG, "📥 Processing decoded frame: ${frame.size} bytes - ${frame.toHexString()}")

        if (frame.isEmpty()) return

        // Data Service gateways may have a 2-byte header that needs stripping
        var eventData = frame
        val isDataServiceGateway = dataServiceGateways.contains(deviceAddress)
        var triedStripping = false
        
        // Try to decode as event first (like legacy app)
        // Events have format: [EventType (1 byte)][Data...]
        val knownEventTypes = setOf(
            MyRvLinkEventType.GatewayInformation,
            MyRvLinkEventType.DeviceCommand,
            MyRvLinkEventType.DeviceOnlineStatus,
            MyRvLinkEventType.DeviceLockStatus,
            MyRvLinkEventType.RelayBasicLatchingStatusType1,
            MyRvLinkEventType.RelayBasicLatchingStatusType2,
            MyRvLinkEventType.RvStatus,
            MyRvLinkEventType.DimmableLightStatus,
            MyRvLinkEventType.RgbLightStatus,
            MyRvLinkEventType.GeneratorGenieStatus,
            MyRvLinkEventType.HvacStatus,
            MyRvLinkEventType.TankSensorStatus,
            MyRvLinkEventType.RelayHBridgeMomentaryStatusType1,
            MyRvLinkEventType.RelayHBridgeMomentaryStatusType2,
            MyRvLinkEventType.TankSensorStatusV2,
            MyRvLinkEventType.RealTimeClock
        )
        
        // First check if byte[0] is a known event type
        var maybeEventType = eventData.getOrNull(0) ?: 0
        var isEvent = knownEventTypes.contains(maybeEventType)
        
        // For Data Service gateways, if first decode fails, try with 2-byte header stripped
        if (isDataServiceGateway && !isEvent && !MyRvLinkEventFactory.isCommandResponse(eventData)) {
            if (frame.size >= 3) {
                val headerHex = String.format("%02X %02X", frame[0].toInt() and 0xFF, frame[1].toInt() and 0xFF)
                eventData = frame.sliceArray(2 until frame.size)
                triedStripping = true
                Log.d(TAG, "🔍 Data Service: First decode failed, trying with 2-byte header stripped (0x$headerHex)")
                
                // Try again with stripped data
                maybeEventType = eventData.getOrNull(0) ?: 0
                isEvent = knownEventTypes.contains(maybeEventType)
            }
        }
        
        // Process as event if byte[0] is a known event type
        if (isEvent) {
            val eventType = maybeEventType
            if (triedStripping) {
                Log.d(TAG, "✅ Decoded as event (with header stripped): 0x${eventType.toUByte().toString(16)}")
            } else {
                Log.d(TAG, "✅ Decoded as event (no stripping): 0x${eventType.toUByte().toString(16)}")
            }
            processEvent(deviceAddress, eventType, eventData)
            return
        }
        
        // Otherwise check for command response
        // Command responses have format: [ClientCommandId (2 bytes LE)][CommandType (1 byte)][Data...]
        // Valid command types: 0x01 (GetDevices), 0x02 (GetDevicesMetadata)
        if (MyRvLinkEventFactory.isCommandResponse(eventData)) {
            val commandId = ((eventData[1].toInt() and 0xFF) shl 8) or (eventData[0].toInt() and 0xFF)
            val commandType = eventData[2].toInt() and 0xFF
            Log.i(TAG, "📦 Command Response: CommandId=0x${commandId.toString(16)}, Type=0x${commandType.toString(16)}, size=${eventData.size} bytes")
            
            when (commandType) {
                0x01 -> handleGetDevicesResponse(deviceAddress, eventData)      // GetDevices
                0x02 -> handleGetDevicesMetadataResponse(deviceAddress, eventData)  // GetDevicesMetadata
            }
            return
        }

        // Not a recognized event or command response - log for debugging
        Log.w(TAG, "⚠️ Unknown frame format: ${eventData.toHexString()}")
        if (triedStripping) {
            Log.w(TAG, "⚠️ Tried both with and without header stripping - both failed")
        }
        Log.w(TAG, "⚠️ First byte: 0x${(eventData.getOrNull(0)?.toInt()?.and(0xFF) ?: 0).toString(16)}")
    }

    /**
     * Process a decoded MyRvLink event
     */
    private fun processEvent(deviceAddress: String, eventType: Byte, eventData: ByteArray) {
        Log.i(TAG, "📦 Processing EventType=0x${eventType.toUByte().toString(16).padStart(2, '0')} from ${deviceAddress}")
        
        // Lazy init state tracker (per device if needed)
        if (deviceStateTracker == null) {
            deviceStateTracker = com.blemqttbridge.plugins.device.onecontrol.protocol.DeviceStateTracker()
        }
        
        // Use eventData (potentially header-stripped) for the rest of processing
        val frame = eventData  // Shadow the original frame with stripped version

        // Process based on event type and emit state updates
        when (eventType) {
            MyRvLinkEventType.GatewayInformation -> {
                handleGatewayInformationEvent(deviceAddress, frame)
                // Emit gateway info update
                val event = GatewayInformationEvent.decode(frame)
                event?.let {
                    emitStateUpdate(deviceAddress, OneControlStateUpdate.GatewayInfo(
                        tableId = OneControlStateUpdate.GatewayInfo.GATEWAY_TABLE_ID,
                        deviceId = OneControlStateUpdate.GatewayInfo.GATEWAY_DEVICE_ID,
                        protocolVersion = it.protocolVersion,
                        deviceCount = it.deviceCount,
                        deviceTableId = it.deviceTableId
                    ))
                }
            }
            
            MyRvLinkEventType.RvStatus -> {
                val event = RvStatusEvent.decode(frame)
                event?.let {
                    emitStateUpdate(deviceAddress, OneControlStateUpdate.SystemStatus(
                        tableId = OneControlStateUpdate.SystemStatus.SYSTEM_TABLE_ID,
                        deviceId = OneControlStateUpdate.SystemStatus.SYSTEM_DEVICE_ID,
                        batteryVoltage = it.batteryVoltage,
                        externalTempC = it.externalTemperatureCelsius
                    ))
                }
            }
            
            MyRvLinkEventType.DimmableLightStatus -> {
                val event = DimmableLightStatusEvent.decode(frame)
                if (event == null) {
                    Log.w(TAG, "⚠️ DimmableLightStatus decode failed, frame: ${frame.toHexString()}")
                } else {
                    val tableId = ((event.deviceAddress shr 8) and 0xFF).toByte()
                    val deviceId = (event.deviceAddress and 0xFF).toByte()
                    Log.i(TAG, "💡 DimmableLight event: table=$tableId, device=$deviceId, isOn=${event.isOn}, brightness=${event.brightness}")
                    
                    // Publish HA discovery (like legacy app - directly in handler)
                    val keyHex = "%02x%02x".format(tableId.toUByte().toInt(), deviceId.toUByte().toInt())
                    val discoveryKey = "light_$keyHex"
                    val isNewDevice = !haDiscoveryPublished.contains(discoveryKey)
                    Log.d(TAG, "🔍 Discovery check: key=$discoveryKey, mqttFormatter=${mqttFormatter != null}, isNewDevice=$isNewDevice")
                    if (isNewDevice) {
                        haDiscoveryPublished.add(discoveryKey)
                        Log.i(TAG, "📢 Publishing discovery for DimmableLight $tableId:$deviceId")
                        val update = OneControlStateUpdate.DimmableLight(
                            tableId = tableId,
                            deviceId = deviceId,
                            isOn = event.isOn,
                            brightnessRaw = (event.brightness * 255) / 100,
                            brightnessPct = event.brightness
                        )
                        pluginScope.launch {
                            Log.d(TAG, "🚀 Launching discovery publish coroutine")
                            mqttFormatter?.publishDimmableLightDiscovery(update)
                                ?: Log.w(TAG, "⚠️ mqttFormatter is NULL - cannot publish discovery!")
                        }
                    }
                    
                    // Publish state
                    emitStateUpdate(deviceAddress, OneControlStateUpdate.DimmableLight(
                        tableId = tableId,
                        deviceId = deviceId,
                        isOn = event.isOn,
                        brightnessRaw = (event.brightness * 255) / 100,
                        brightnessPct = event.brightness
                    ))
                }
            }
            
            MyRvLinkEventType.RelayBasicLatchingStatusType1 -> {
                // Type1 and Type2 have same parsing structure for our purposes
                val event = RelayBasicLatchingStatusEvent.decode(frame)
                event?.let {
                    val tableId = ((it.deviceAddress shr 8) and 0xFF).toByte()
                    val deviceId = (it.deviceAddress and 0xFF).toByte()
                    Log.i(TAG, "🔌 RelayType1 event: table=$tableId, device=$deviceId, isOn=${it.isOn}")
                    emitStateUpdate(deviceAddress, OneControlStateUpdate.Switch(
                        tableId = tableId,
                        deviceId = deviceId,
                        isOn = it.isOn
                    ))
                }
            }
            
            MyRvLinkEventType.RelayBasicLatchingStatusType2 -> {
                val event = RelayBasicLatchingStatusEvent.decode(frame)
                event?.let {
                    val tableId = ((it.deviceAddress shr 8) and 0xFF).toByte()
                    val deviceId = (it.deviceAddress and 0xFF).toByte()
                    Log.i(TAG, "🔌 RelayType2 event: table=$tableId, device=$deviceId, isOn=${it.isOn}")
                    emitStateUpdate(deviceAddress, OneControlStateUpdate.Switch(
                        tableId = tableId,
                        deviceId = deviceId,
                        isOn = it.isOn
                    ))
                }
            }
            
            MyRvLinkEventType.RelayHBridgeMomentaryStatusType2 -> {
                val event = RelayHBridgeStatusEvent.decode(frame)
                event?.let {
                    val tableId = ((it.deviceAddress shr 8) and 0xFF).toByte()
                    val deviceId = (it.deviceAddress and 0xFF).toByte()
                    // Track last direction for open/closed inference
                    val lastDir = if (it.status == 0xC2 || it.status == 0xC3) it.status else null
                    emitStateUpdate(deviceAddress, OneControlStateUpdate.Cover(
                        tableId = tableId,
                        deviceId = deviceId,
                        status = it.status,
                        position = if (it.position in 0..100) it.position else null,
                        lastDirection = lastDir
                    ))
                }
            }
            
            MyRvLinkEventType.DeviceOnlineStatus -> {
                val event = DeviceOnlineStatusEvent.decode(frame)
                event?.let {
                    val tableId = ((it.deviceAddress shr 8) and 0xFF).toByte()
                    val deviceId = (it.deviceAddress and 0xFF).toByte()
                    emitStateUpdate(deviceAddress, OneControlStateUpdate.DeviceOnline(
                        tableId = tableId,
                        deviceId = deviceId,
                        isOnline = it.isOnline
                    ))
                }
            }
            
            MyRvLinkEventType.TankSensorStatus -> {
                val event = TankSensorStatusEvent.decode(frame)
                event?.let { tankEvent ->
                    // This event contains multiple tanks, emit update for each
                    tankEvent.tanks.forEach { tank ->
                        Log.i(TAG, "💧 Tank event: table=${tankEvent.deviceTableId}, device=${tank.deviceId}, level=${tank.percent}%")
                        emitStateUpdate(deviceAddress, OneControlStateUpdate.Tank(
                            tableId = tankEvent.deviceTableId,
                            deviceId = tank.deviceId,
                            level = tank.percent,
                            fluidType = null  // Type not provided in this event
                        ))
                    }
                }
            }
            
            MyRvLinkEventType.TankSensorStatusV2 -> {
                val event = TankSensorStatusV2Event.decode(frame)
                event?.let {
                    // Only emit if we have a valid percent value
                    val level = it.percent ?: return@let
                    Log.i(TAG, "💧 TankV2 event: table=${it.deviceTableId}, device=${it.deviceId}, level=$level%")
                    emitStateUpdate(deviceAddress, OneControlStateUpdate.Tank(
                        tableId = it.deviceTableId,
                        deviceId = it.deviceId,
                        level = level,
                        fluidType = null  // Type not provided in V2 event either
                    ))
                }
            }
            
            MyRvLinkEventType.HvacStatus -> {
                val event = HvacStatusEvent.decode(frame)
                event?.let { hvacEvent ->
                    // This event contains multiple HVAC zones, emit update for each
                    hvacEvent.zones.forEach { zone ->
                        emitStateUpdate(deviceAddress, OneControlStateUpdate.Hvac(
                            tableId = hvacEvent.deviceTableId,
                            deviceId = zone.deviceId,
                            heatMode = zone.heatMode,
                            heatSource = zone.heatSource,
                            fanMode = zone.fanMode,
                            zoneMode = zone.zoneMode,
                            heatSetpointF = zone.lowTripTempF,
                            coolSetpointF = zone.highTripTempF,
                            indoorTempF = zone.indoorTempF,
                            outdoorTempF = zone.outdoorTempF
                        ))
                    }
                }
            }
            
            else -> {
                Log.d(TAG, "❓ Unhandled event type: 0x${eventType.toUByte().toString(16)}")
            }
        }
        
    }
    
    /**
     * Emit a state update to the listener
     */
    private fun emitStateUpdate(gatewayAddress: String, update: OneControlStateUpdate) {
        val deviceKey = (update.tableId.toInt() shl 8) or (update.deviceId.toInt() and 0xFF)
        val isNewDevice = discoveredDevices.add(deviceKey)
        
        Log.i(TAG, "🔄 emitStateUpdate called: type=${update::class.simpleName}, deviceKey=$deviceKey, isNewDevice=$isNewDevice, stateListener=${stateListener != null}")
        
        pluginScope.launch {
            if (isNewDevice) {
                Log.i(TAG, "📱 New device discovered: table=${update.tableId}, device=${update.deviceId}, type=${update::class.simpleName}")
                stateListener?.onDeviceDiscovered(gatewayAddress, update)
            }
            stateListener?.onStateUpdate(gatewayAddress, update)
        }
    }
    
    /**
     * Set the state listener for receiving device updates
     */
    fun setStateListener(listener: OneControlStateListener?) {
        stateListener = listener
        // Also store mqttFormatter reference for direct discovery calls
        mqttFormatter = listener as? OneControlMqttFormatter
    }
    
    /**
     * Handle GatewayInformation response (like original app)
     */
    private fun handleGatewayInformationEvent(deviceAddress: String, data: ByteArray) {
        Log.d(TAG, "📋 GatewayInformation received: ${data.toHexString()}")
        
        if (data.size >= 5) {
            val newDeviceTableId = data[4]
            if (newDeviceTableId != 0x00.toByte()) {
                val oldTableId = deviceTableId
                deviceTableId = newDeviceTableId
                Log.i(TAG, "✅ Updated DeviceTableId: 0x${deviceTableId.toString(16).padStart(2, '0')} (was 0x${oldTableId.toString(16).padStart(2, '0')})")
            }
        }

        if (!gatewayInfoReceived[deviceAddress]!!) {
            gatewayInfoReceived[deviceAddress] = true
            onGatewayInfoReceived(deviceAddress)
        }
    }
    
    /**
     * Handle first GatewayInformation response (like original app)
     * CRITICAL: This is when we START sending commands (not immediately after connection!)
     */
    private fun onGatewayInfoReceived(deviceAddress: String) {
        Log.i(TAG, "✅ GatewayInformation received - protocol fully established for $deviceAddress")
        
        val device = currentDevice
        if (device == null) {
            Log.e(TAG, "❌ Cannot send commands: device reference is null")
            return
        }
        
        // Send GetDevices with correct table ID (like original app)
        GlobalScope.launch {
            delay(500)
            Log.i(TAG, "📤 Sending GetDevices after GatewayInfo (DeviceTableId=0x${deviceTableId.toString(16).padStart(2, '0')})")
            sendInitialCanCommand(device)
        }

        // Start heartbeat now that gateway is ready
        startHeartbeat(device)
    }
    
    /**
     * Handle GetDevices command response
     * This response contains all device definitions from the gateway.
     * Format: [ClientCommandId (2 LE)][CommandType=0x01][DeviceTableId][StartDeviceId][DeviceCount][Device entries...]
     */
    private fun handleGetDevicesResponse(deviceAddress: String, data: ByteArray) {
        if (data.size < 6) {
            Log.w(TAG, "GetDevices response too short: ${data.size} bytes")
            return
        }
        
        // Skip command header (3 bytes: 2 for commandId, 1 for commandType)
        val extended = data.copyOfRange(3, data.size)
        if (extended.size < 3) {
            Log.w(TAG, "GetDevices extended data too short: ${extended.size} bytes")
            return
        }
        
        val tableId = extended[0]
        val startDeviceId = extended[1].toInt() and 0xFF
        val deviceCount = extended[2].toInt() and 0xFF
        
        // Update DeviceTableId if we didn't have one yet
        if (deviceTableId == 0x00.toByte() && tableId != 0x00.toByte()) {
            deviceTableId = tableId
            Log.i(TAG, "✅ Updated DeviceTableId from GetDevices response: 0x${deviceTableId.toString(16)}")
        }
        
        var offset = 3
        var index = 0
        Log.i(TAG, "📋 GetDevices: tableId=0x${tableId.toString(16)}, startId=$startDeviceId, count=$deviceCount, extLen=${extended.size}")
        
        while (index < deviceCount && offset + 2 <= extended.size) {
            val protocol = extended[offset].toInt() and 0xFF
            val payloadSize = extended[offset + 1].toInt() and 0xFF
            val entrySize = payloadSize + 2
            if (offset + entrySize > extended.size) {
                Log.w(TAG, "GetDevices entry truncated at index=$index, offset=$offset, payloadSize=$payloadSize, extLen=${extended.size}")
                break
            }
            
            val deviceId = ((startDeviceId + index) and 0xFF).toByte()
            
            if (protocol == 2 && payloadSize == 10) {
                // IdsCan physical device
                val base = offset
                val deviceType = extended[base + 2].toInt() and 0xFF
                val deviceInstance = extended[base + 3].toInt() and 0xFF
                val productId = ((extended[base + 5].toInt() and 0xFF) shl 8) or (extended[base + 4].toInt() and 0xFF)
                val macBytes = extended.copyOfRange(base + 6, base + 12)
                val macStr = macBytes.joinToString(":") { "%02X".format(it) }
                
                Log.i(TAG, "📋 Device[${tableId.toInt() and 0xFF}:${deviceId.toInt() and 0xFF}]: proto=$protocol, type=0x${deviceType.toString(16)}, instance=$deviceInstance, productId=0x${productId.toString(16)}, mac=$macStr")
                
                // Emit discovery for this device based on its type
                emitDeviceDiscoveryFromDefinition(deviceAddress, tableId, deviceId, deviceType, deviceInstance)
            } else if (protocol == 1) {
                // Virtual device - still emit discovery
                Log.i(TAG, "📋 Device[${tableId.toInt() and 0xFF}:${deviceId.toInt() and 0xFF}]: proto=$protocol (virtual), payloadSize=$payloadSize")
                // Virtual devices might not have deviceType in the same format
            } else {
                Log.i(TAG, "📋 Device entry index=$index uses protocol=$protocol, payloadSize=$payloadSize")
            }
            
            offset += entrySize
            index++
        }
        
        if (index != deviceCount) {
            Log.w(TAG, "GetDevices decoded $index devices, expected $deviceCount")
        } else {
            Log.i(TAG, "✅ GetDevices: Successfully parsed $deviceCount device definitions")
        }
    }
    
    /**
     * Handle GetDevicesMetadata command response
     * Contains device names and other metadata
     */
    private fun handleGetDevicesMetadataResponse(deviceAddress: String, data: ByteArray) {
        if (data.size < 6) {
            Log.w(TAG, "GetDevicesMetadata response too short: ${data.size} bytes")
            return
        }
        
        val extended = data.copyOfRange(3, data.size)
        if (extended.size < 3) {
            Log.w(TAG, "GetDevicesMetadata extended data too short: ${extended.size} bytes")
            return
        }
        
        val tableId = extended[0]
        val startDeviceId = extended[1].toInt() and 0xFF
        val deviceCount = extended[2].toInt() and 0xFF
        
        Log.i(TAG, "📋 GetDevicesMetadata: tableId=0x${tableId.toString(16)}, startId=$startDeviceId, count=$deviceCount")
        // TODO: Parse metadata entries and update device names
    }
    
    /**
     * Emit device discovery based on device type from GetDevices response
     * Maps IdsCan device types to our state update types for discovery
     */
    private fun emitDeviceDiscoveryFromDefinition(
        deviceAddress: String,
        tableId: Byte,
        deviceId: Byte,
        deviceType: Int,
        deviceInstance: Int
    ) {
        // Map IdsCan device types to our known types
        // These mappings are based on the protocol documentation and legacy app behavior
        when (deviceType) {
            0x08 -> {
                // DimmableLight (type 8)
                Log.i(TAG, "💡 Discovered DimmableLight from GetDevices: ${tableId.toInt() and 0xFF}:${deviceId.toInt() and 0xFF}")
                emitStateUpdate(deviceAddress, OneControlStateUpdate.DimmableLight(
                    tableId = tableId,
                    deviceId = deviceId,
                    isOn = false,  // Unknown initial state
                    brightnessRaw = 0,
                    brightnessPct = 0
                ))
            }
            0x06 -> {
                // Relay/Switch (type 6)
                Log.i(TAG, "🔌 Discovered Switch from GetDevices: ${tableId.toInt() and 0xFF}:${deviceId.toInt() and 0xFF}")
                emitStateUpdate(deviceAddress, OneControlStateUpdate.Switch(
                    tableId = tableId,
                    deviceId = deviceId,
                    isOn = false  // Unknown initial state
                ))
            }
            0x0E -> {
                // H-Bridge/Cover (type 14)
                Log.i(TAG, "🪟 Discovered Cover from GetDevices: ${tableId.toInt() and 0xFF}:${deviceId.toInt() and 0xFF}")
                emitStateUpdate(deviceAddress, OneControlStateUpdate.Cover(
                    tableId = tableId,
                    deviceId = deviceId,
                    status = 0xC0,  // Default: stopped
                    position = null,
                    lastDirection = null
                ))
            }
            0x0C -> {
                // Tank Sensor (type 12)
                Log.i(TAG, "💧 Discovered Tank from GetDevices: ${tableId.toInt() and 0xFF}:${deviceId.toInt() and 0xFF}")
                emitStateUpdate(deviceAddress, OneControlStateUpdate.Tank(
                    tableId = tableId,
                    deviceId = deviceId,
                    level = 0,  // Unknown initial level
                    fluidType = null
                ))
            }
            0x0B -> {
                // HVAC (type 11)
                Log.i(TAG, "🌡️ Discovered HVAC from GetDevices: ${tableId.toInt() and 0xFF}:${deviceId.toInt() and 0xFF}")
                emitStateUpdate(deviceAddress, OneControlStateUpdate.Hvac(
                    tableId = tableId,
                    deviceId = deviceId,
                    heatMode = 0,
                    heatSource = 0,
                    fanMode = 0,
                    zoneMode = 0,
                    heatSetpointF = 70,  // Default setpoints
                    coolSetpointF = 75,
                    indoorTempF = null,
                    outdoorTempF = null
                ))
            }
            else -> {
                // Unknown device type - log for debugging
                Log.d(TAG, "❓ Unknown device type 0x${deviceType.toString(16)} for ${tableId.toInt() and 0xFF}:${deviceId.toInt() and 0xFF}")
            }
        }
    }

    /**
     * Send initial MyRvLink GetDevices command to "wake up" the gateway
     * This is what the official app sends to establish communication
     */
    private suspend fun sendInitialCanCommand(device: BluetoothDevice) {
        Log.i(TAG, "📤 Sending initial GetDevices command to ${device.address}")
        
        try {
            // Encode MyRvLink GetDevices command
            // Format: [ClientCommandId (2 bytes, little-endian)][CommandType=0x01][DeviceTableId][StartDeviceId][MaxDeviceRequestCount]
            val commandId = getNextCommandId()
            val effectiveTableId = if (deviceTableId == 0x00.toByte()) DEFAULT_DEVICE_TABLE_ID else deviceTableId
            
            val command = byteArrayOf(
                (commandId.toInt() and 0xFF).toByte(),           // ClientCommandId low byte
                ((commandId.toInt() shr 8) and 0xFF).toByte(),   // ClientCommandId high byte
                0x01.toByte(),                                   // CommandType: GetDevices
                effectiveTableId,                                // DeviceTableId
                0x00.toByte(),                                   // StartDeviceId (0 = start from beginning)
                0xFF.toByte()                                    // MaxDeviceRequestCount (255 = get all)
            )
            
            // Encode with COBS (Consistent Overhead Byte Stuffing)
            val encoded = cobsEncode(command, prependStartFrame = true, useCrc = true)
            val encodedHex = encoded.joinToString(" ") { "%02X".format(it) }
            
            Log.d(TAG, "📤 GetDevices: CommandId=0x${commandId.toString(16)}, DeviceTableId=0x${effectiveTableId.toString(16)}")
            Log.d(TAG, "📤 Encoded: $encodedHex (${encoded.size} bytes)")
            
            // Send via DATA_WRITE characteristic (WRITE_TYPE_NO_RESPONSE per technical spec)
            val writeResult = gattOperations?.writeCharacteristicNoResponse(Constants.DATA_WRITE_CHAR_UUID, encoded)
            
            if (writeResult?.isSuccess == true) {
                Log.i(TAG, "📤 Sent initial GetDevices command (${encoded.size} bytes)")
            } else {
                Log.e(TAG, "❌ Failed to send GetDevices command: ${writeResult?.exceptionOrNull()?.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send initial CAN command: ${e.message}", e)
        }
    }
    
    /**
     * Start heartbeat/keepalive mechanism
     * Sends MyRvLink GetDevices command periodically to keep connection alive
     */
    private fun startHeartbeat(device: BluetoothDevice) {
        // Stop any existing heartbeat for this device
        heartbeatJobs[device.address]?.cancel()
        
        val job = GlobalScope.launch {
            Log.i(TAG, "💓 Heartbeat started for ${device.address}")
            
            while (authenticatedDevices.contains(device.address)) {
                try {
                    delay(5000)  // 5 second intervals like original app (NOT 30 seconds!)
                    
                    if (!authenticatedDevices.contains(device.address)) break
                    
                    // Send heartbeat GetDevices command
                    val commandId = getNextCommandId()
                    val effectiveTableId = if (deviceTableId == 0x00.toByte()) DEFAULT_DEVICE_TABLE_ID else deviceTableId
                    
                    val command = byteArrayOf(
                        (commandId.toInt() and 0xFF).toByte(),
                        ((commandId.toInt() shr 8) and 0xFF).toByte(),
                        0x01.toByte(),  // CommandType: GetDevices
                        effectiveTableId,
                        0x00.toByte(),
                        0xFF.toByte()
                    )
                    
                    val encoded = cobsEncode(command, prependStartFrame = true, useCrc = true)
                val writeResult = gattOperations?.writeCharacteristicNoResponse(Constants.DATA_WRITE_CHAR_UUID, encoded)
                    if (writeResult?.isSuccess == true) {
                        Log.i(TAG, "💓 Heartbeat sent to ${device.address} (CommandId=0x${commandId.toString(16)})")
                    } else {
                        Log.w(TAG, "💓 Heartbeat failed for ${device.address}: ${writeResult?.exceptionOrNull()?.message}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Heartbeat error for ${device.address}: ${e.message}")
                    break
                }
            }
            
            Log.i(TAG, "💓 Heartbeat stopped for ${device.address}")
        }
        
        heartbeatJobs[device.address] = job
    }
    
    /**
     * Get next command ID (increments and wraps around)
     */
    private fun getNextCommandId(): UShort {
        val id = nextCommandId
        nextCommandId = if (nextCommandId >= 0xFFFEu) 1u else (nextCommandId + 1u).toUShort()
        return id
    }
    
    /**
     * COBS (Consistent Overhead Byte Stuffing) encoding with CRC8
     * Exact implementation matching decompiled CobsEncoder.cs from OneControl app
     */
    private fun cobsEncode(data: ByteArray, prependStartFrame: Boolean = true, useCrc: Boolean = true): ByteArray {
        val FRAME_CHAR: Byte = 0x00
        val MAX_DATA_BYTES = 63  // 2^6 - 1
        val FRAME_BYTE_COUNT_LSB = 64  // 2^6
        val MAX_COMPRESSED_FRAME_BYTES = 192  // 255 - 63
        
        val output = ByteArray(382)  // Max output buffer size
        var outputIndex = 0
        
        // Prepend start frame character if requested
        if (prependStartFrame) {
            output[outputIndex++] = FRAME_CHAR
        }
        
        if (data.isEmpty()) {
            return output.copyOf(outputIndex)
        }
        
        // Build source data with CRC appended (CRC calculated incrementally during encoding)
        val sourceCount = data.size
        val totalCount = if (useCrc) sourceCount + 1 else sourceCount
        
        // CRC calculator - initialized to 85 (0x55)
        val crc = Crc8()
        
        var srcIndex = 0
        
        while (srcIndex < totalCount) {
            // Save position for code byte placeholder
            val codeIndex = outputIndex
            var code = 0
            output[outputIndex++] = 0xFF.toByte()  // Placeholder (official uses 0xFF)
            
            // Encode non-frame bytes
            while (srcIndex < totalCount) {
                val byteVal: Byte
                if (srcIndex < sourceCount) {
                    byteVal = data[srcIndex]
                    if (byteVal == FRAME_CHAR) {
                        break  // Stop at frame character (zero)
                    }
                    crc.update(byteVal)
                } else {
                    // This is the CRC byte position
                    byteVal = crc.value
                    if (byteVal == FRAME_CHAR) {
                        break
                    }
                }
                
                srcIndex++
                output[outputIndex++] = byteVal
                code++
                
                if (code >= MAX_DATA_BYTES) {
                    break
                }
            }
            
            // Handle consecutive frame characters (zeros)
            while (srcIndex < totalCount) {
                val byteVal = if (srcIndex < sourceCount) data[srcIndex] else crc.value
                if (byteVal != FRAME_CHAR) {
                    break
                }
                crc.update(FRAME_CHAR)
                srcIndex++
                code += FRAME_BYTE_COUNT_LSB
                if (code >= MAX_COMPRESSED_FRAME_BYTES) {
                    break
                }
            }
            
            // Write actual code byte
            output[codeIndex] = code.toByte()
        }
        
        // Append frame terminator
        output[outputIndex++] = FRAME_CHAR
        
        return output.copyOf(outputIndex)
    }
    
    /**
     * Data Service gateway authentication (challenge-response)
     * From original OneControlBleService: MyRvLinkBleGatewayScanResult.RvLinkKeySeedCypher = 612643285
     * Byte order: BIG-ENDIAN for both challenge and KEY (Data Service only)
     */
    private fun calculateAuthKey(seed: Long): Long {
        val cypher = 612643285L  // MyRvLink RvLinkKeySeedCypher = 0x2483FFD5
        
        var cypherVar = cypher
        var seedVar = seed
        var num = 2654435769L  // TEA delta = 0x9E3779B9
        
        // BleDeviceUnlockManager.Encrypt() algorithm - exact copy from original app
        for (i in 0 until 32) {
            seedVar += ((cypherVar shl 4) + 1131376761L) xor (cypherVar + num) xor ((cypherVar shr 5) + 1919510376L)
            seedVar = seedVar and 0xFFFFFFFFL
            cypherVar += ((seedVar shl 4) + 1948272964L) xor (seedVar + num) xor ((seedVar shr 5) + 1400073827L)
            cypherVar = cypherVar and 0xFFFFFFFFL
            num += 2654435769L
            num = num and 0xFFFFFFFFL
        }
        
        // Return the calculated value
        return seedVar and 0xFFFFFFFFL
    }
    
    /**
     * TEA encryption for CAN Service gateways (different algorithm)
     * From original OneControlBleService: TeaEncryption.encrypt() with cypher 0x8100080DL
     * Byte order: LITTLE-ENDIAN for both seed and key (CAN Service only)
     */
    private fun calculateTeaKey(cypher: Long, seed: Long): Long {
        var v0 = seed
        var v1 = cypher
        val delta = 0x9e3779b9L
        var sum = 0L
        
        // 32 rounds of TEA encryption
        for (i in 0 until 32) {
            sum += delta
            v0 += ((v1 shl 4) + (cypher and 0xFFFFL)) xor (v1 + sum) xor ((v1 shr 5) + ((cypher shr 16) and 0xFFFFL))
            v0 = v0 and 0xFFFFFFFFL
            v1 += ((v0 shl 4) + ((cypher shr 32) and 0xFFFFL)) xor (v0 + sum) xor ((v0 shr 5) + ((cypher shr 48) and 0xFFFFL))
            v1 = v1 and 0xFFFFFFFFL
        }
        
        return v0 and 0xFFFFFFFFL
    }
    
    private fun parseUuid128(bytes: ByteArray): String? {
        if (bytes.size != 16) return null
        
        // UUID bytes are in reverse order
        val reversed = bytes.reversedArray()
        return "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x".format(
            reversed[0], reversed[1], reversed[2], reversed[3],
            reversed[4], reversed[5],
            reversed[6], reversed[7],
            reversed[8], reversed[9],
            reversed[10], reversed[11], reversed[12], reversed[13], reversed[14], reversed[15]
        )
    }
    
    private fun ByteArray.toHexString(): String {
        return joinToString(" ") { "%02x".format(it) }
    }
    
    /**
     * Process notification data - keeps connection active through continuous processing
     * Based on original app's notification handling that prevents disconnections
     */
    private fun processNotificationData(deviceAddress: String, data: ByteArray) {
        try {
            // COBS decode the notification (simplified for now)
            Log.d(TAG, "📨 Processing notification from $deviceAddress: ${data.toHexString()} (${data.size} bytes)")
            
            // The key is continuous processing - this activity prevents Android from timing out BLE connection
            // Original app does full COBS decoding and MyRvLink parsing here
        } catch (e: Exception) {
            Log.w(TAG, "Error processing notification: ${e.message}")
        }
    }
    
    // ============================================================================
    // Command Handling (for MQTT -> BLE control)
    // ============================================================================
    
    /**
     * Handle a typed command for a device.
     * 
     * @param gatewayAddress MAC address of the gateway
     * @param command The typed command to execute
     * @return Result with Unit on success, Exception on failure
     */
    suspend fun handleCommand(gatewayAddress: String, command: OneControlCommand): Result<Unit> {
        val gattOps = gattOperations
        if (gattOps == null) {
            return Result.failure(IllegalStateException("GATT operations not available"))
        }
        
        if (!authenticatedDevices.contains(gatewayAddress)) {
            return Result.failure(IllegalStateException("Device not authenticated: $gatewayAddress"))
        }
        
        return when (command) {
            is OneControlCommand.DimmableLight -> handleDimmableLightCommand(gattOps, command)
            is OneControlCommand.Switch -> handleSwitchCommand(gattOps, command)
            is OneControlCommand.Cover -> handleCoverCommand(gattOps, command)
            is OneControlCommand.Hvac -> handleHvacCommand(gattOps, command)
        }
    }
    
    private suspend fun handleDimmableLightCommand(
        gattOps: BlePluginInterface.GattOperations,
        command: OneControlCommand.DimmableLight
    ): Result<Unit> {
        val commandId = getNextCommandId()
        val brightness = command.brightness ?: 255
        
        val dimmableCmd = when {
            !command.turnOn -> MyRvLinkCommandEncoder.DimmableLightCommand.Off
            else -> MyRvLinkCommandEncoder.DimmableLightCommand.On
        }
        
        val commandBytes = MyRvLinkCommandEncoder.encodeActionDimmable(
            commandId = commandId,
            deviceTableId = command.tableId,
            deviceId = command.deviceId,
            command = dimmableCmd,
            brightness = brightness
        )
        
        val encoded = cobsEncode(commandBytes, prependStartFrame = true, useCrc = true)
        
        Log.i(TAG, "📤 Sending dimmable light command: table=${command.tableId}, device=${command.deviceId}, " +
                   "on=${command.turnOn}, brightness=$brightness")
        
        return gattOps.writeCharacteristicNoResponse(Constants.DATA_WRITE_CHAR_UUID, encoded)
    }
    
    private suspend fun handleSwitchCommand(
        gattOps: BlePluginInterface.GattOperations,
        command: OneControlCommand.Switch
    ): Result<Unit> {
        val commandId = getNextCommandId()
        
        val commandBytes = MyRvLinkCommandEncoder.encodeActionSwitch(
            commandId = commandId,
            deviceTableId = command.tableId,
            deviceId = command.deviceId,
            turnOn = command.turnOn
        )
        
        val encoded = cobsEncode(commandBytes, prependStartFrame = true, useCrc = true)
        
        Log.i(TAG, "📤 Sending switch command: table=${command.tableId}, device=${command.deviceId}, " +
                   "on=${command.turnOn}")
        
        return gattOps.writeCharacteristicNoResponse(Constants.DATA_WRITE_CHAR_UUID, encoded)
    }
    
    private suspend fun handleCoverCommand(
        gattOps: BlePluginInterface.GattOperations,
        command: OneControlCommand.Cover
    ): Result<Unit> {
        // Cover commands use H-bridge relay control
        // OPEN = extend (0x02), CLOSE = retract (0x03), STOP = 0x00
        val commandId = getNextCommandId()
        
        val hbridgeCommand: Byte = when (command.command.uppercase()) {
            "OPEN" -> 0x02  // Extend
            "CLOSE" -> 0x03 // Retract
            "STOP" -> 0x00  // Stop
            "SET_POSITION" -> {
                // Position control not yet implemented - would need motor timing
                Log.w(TAG, "Cover position control not yet implemented")
                return Result.failure(UnsupportedOperationException("Cover position control not yet implemented"))
            }
            else -> {
                Log.w(TAG, "Unknown cover command: ${command.command}")
                return Result.failure(IllegalArgumentException("Unknown cover command: ${command.command}"))
            }
        }
        
        // H-bridge command format: [CmdId_lo][CmdId_hi][CommandType=0x41][DeviceTableId][DeviceId][HBridgeCommand]
        val commandBytes = byteArrayOf(
            (commandId.toInt() and 0xFF).toByte(),
            ((commandId.toInt() shr 8) and 0xFF).toByte(),
            0x41.toByte(),  // ActionHBridge command type
            command.tableId,
            command.deviceId,
            hbridgeCommand
        )
        
        val encoded = cobsEncode(commandBytes, prependStartFrame = true, useCrc = true)
        
        Log.i(TAG, "📤 Sending cover command: table=${command.tableId}, device=${command.deviceId}, " +
                   "action=${command.command}")
        
        return gattOps.writeCharacteristicNoResponse(Constants.DATA_WRITE_CHAR_UUID, encoded)
    }
    
    private suspend fun handleHvacCommand(
        gattOps: BlePluginInterface.GattOperations,
        command: OneControlCommand.Hvac
    ): Result<Unit> {
        // HVAC command format is more complex - uses ActionHvac (0x45)
        // For now, just implement mode changes
        val commandId = getNextCommandId()
        
        // Map HA mode strings to MyRvLink heat mode values
        val heatMode: Byte = when (command.mode?.lowercase()) {
            "off" -> 0x00
            "heat" -> 0x01
            "cool" -> 0x02
            "heat_cool" -> 0x03  // Both heat and cool available
            else -> {
                // If no mode change, we might be changing setpoints or fan
                // For now, require mode to be specified
                if (command.mode == null && (command.fanMode != null || command.heatSetpoint != null || command.coolSetpoint != null)) {
                    Log.w(TAG, "HVAC setpoint/fan changes without mode not yet implemented")
                    return Result.failure(UnsupportedOperationException("Partial HVAC changes not yet implemented"))
                }
                return Result.failure(IllegalArgumentException("Invalid HVAC mode: ${command.mode}"))
            }
        }
        
        val fanMode: Byte = when (command.fanMode?.lowercase()) {
            "auto" -> 0x00
            "high" -> 0x01
            "low" -> 0x02
            else -> 0x00  // Default to auto
        }
        
        // ActionHvac format (simplified - full format has more fields):
        // [CmdId_lo][CmdId_hi][CommandType=0x45][DeviceTableId][DeviceId][HeatMode][HeatSource][FanMode][ZoneMode][HeatSetpoint][CoolSetpoint]
        val heatSetpoint = (command.heatSetpoint ?: 68).toByte()
        val coolSetpoint = (command.coolSetpoint ?: 72).toByte()
        
        val commandBytes = byteArrayOf(
            (commandId.toInt() and 0xFF).toByte(),
            ((commandId.toInt() shr 8) and 0xFF).toByte(),
            0x45.toByte(),  // ActionHvac command type
            command.tableId,
            command.deviceId,
            heatMode,
            0x00,  // HeatSource: 0=PreferGas
            fanMode,
            0x00,  // ZoneMode: 0=Off (will be set by gateway based on heatMode)
            heatSetpoint,
            coolSetpoint
        )
        
        val encoded = cobsEncode(commandBytes, prependStartFrame = true, useCrc = true)
        
        Log.i(TAG, "📤 Sending HVAC command: table=${command.tableId}, device=${command.deviceId}, " +
                   "mode=${command.mode}, fan=${command.fanMode}")
        
        return gattOps.writeCharacteristicNoResponse(Constants.DATA_WRITE_CHAR_UUID, encoded)
    }
}