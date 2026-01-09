package com.blemqttbridge.plugins.onecontrol.protocol

import android.bluetooth.le.ScanRecord
import android.util.Log

/**
 * Parses OneControl gateway advertisement data to detect capabilities.
 * 
 * OneControl gateways advertise their pairing capabilities in manufacturer-specific data.
 * Newer gateways support push-to-pair (physical button), while older gateways require PIN entry.
 */
object AdvertisementParser {
    
    private const val TAG = "AdvertisementParser"
    private const val LIPPERT_MANUFACTURER_ID = 0x0499  // Lippert Components Inc.
    
    enum class PairingMethod {
        UNKNOWN,
        NONE,
        PIN,
        PUSH_BUTTON
    }
    
    data class GatewayCapabilities(
        val pairingMethod: PairingMethod,
        val supportsPushToPair: Boolean,
        val pairingEnabled: Boolean  // True when physical button is pressed
    )
    
    /**
     * Parse manufacturer-specific data from scan record.
     * 
     * Expected format (from official app analysis):
     * - Bytes 0-1: Manufacturer ID (0x0499 for Lippert)
     * - Byte 2: PairingInfo byte
     *   - Bit 0: IsPushToPairButtonPresentOnBus (0 = legacy PIN, 1 = push-to-pair)
     *   - Bit 1: PairingEnabled (button currently pressed)
     * 
     * @param scanRecord BLE scan record from device advertisement
     * @return GatewayCapabilities indicating pairing method and state
     */
    fun parseCapabilities(scanRecord: ScanRecord?): GatewayCapabilities {
        if (scanRecord == null) {
            Log.w(TAG, "No scan record available - assuming push-to-pair (default)")
            return GatewayCapabilities(
                pairingMethod = PairingMethod.UNKNOWN,
                supportsPushToPair = true,  // Default to newer behavior
                pairingEnabled = false
            )
        }
        
        // Get manufacturer-specific data
        val manufacturerData = scanRecord.manufacturerSpecificData
        val lippertData = manufacturerData?.get(LIPPERT_MANUFACTURER_ID)
        
        if (lippertData == null || lippertData.isEmpty()) {
            Log.w(TAG, "No manufacturer data found - assuming push-to-pair (default)")
            // No manufacturer data - assume newer gateway for backwards compatibility
            return GatewayCapabilities(
                pairingMethod = PairingMethod.PUSH_BUTTON,
                supportsPushToPair = true,
                pairingEnabled = false
            )
        }
        
        // Parse PairingInfo byte (first byte after manufacturer ID)
        val pairingInfoByte = lippertData[0].toInt() and 0xFF
        val hasPushButton = (pairingInfoByte and 0x01) != 0
        val pairingActive = (pairingInfoByte and 0x02) != 0
        
        val pairingMethod = when {
            hasPushButton -> PairingMethod.PUSH_BUTTON
            else -> PairingMethod.PIN  // Legacy gateway
        }
        
        Log.d(TAG, "Parsed capabilities:")
        Log.d(TAG, "  PairingInfo byte: 0x${pairingInfoByte.toString(16).uppercase()}")
        Log.d(TAG, "  Push-to-pair support: $hasPushButton")
        Log.d(TAG, "  Pairing active: $pairingActive")
        Log.d(TAG, "  Pairing method: $pairingMethod")
        
        return GatewayCapabilities(
            pairingMethod = pairingMethod,
            supportsPushToPair = hasPushButton,
            pairingEnabled = pairingActive
        )
    }
    
    /**
     * Check if gateway is currently in pairing mode (button pressed).
     * Only applicable for push-to-pair gateways.
     */
    fun isPairingActive(scanRecord: ScanRecord?): Boolean {
        return parseCapabilities(scanRecord).pairingEnabled
    }
}
