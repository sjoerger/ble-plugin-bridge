# Bond Debugging Session - December 20, 2025

## Problem Statement
User reported: "The bond is getting invalidated, then removed if I restart the app/service."

## Key Findings

### 1. Bond State vs. Actual Link Key Validity
There's a critical distinction in Android BLE:
- **`BOND_BONDED`** - Android has stored link keys in its local database
- **Actual link key validity** - Whether those stored keys match what the gateway has

When the gateway "forgets" the bond (e.g., bond table overflow, gateway reset), Android still reports `BOND_BONDED` because its local database still has the entry. This causes:
- Connection attempts fail with **status 133** (GATT_ERROR)
- Or connection succeeds but the gateway terminates after ~17 seconds with `GATT_CONN_TERMINATE_PEER_USER`

### 2. Observed Bond History (from `dumpsys bluetooth_manager`)
```
20:36:21  BOND_STATE_BONDED (initial bond)
20:37:37  BOND_STATE_NONE   (bond removed!)
20:37:41  btif_dm_create_bond (rebonding started)
20:38:24  BOND_STATE_BONDED (bonded again after manual re-pair)
```

The pattern of metadata created/deleted events showed **repeated bond failures**:
- 20:37:37.780 - Metadata deleted (bond removed)
- 20:37:41.244 - Metadata created (rebond attempt)
- 20:37:41.558 - Metadata deleted (failed!)
- 20:37:58.838 - Created (another attempt)
- 20:37:59.447 - Deleted (failed again!)
- Multiple more attempts before finally succeeding at 20:38:19

### 3. Connection Sequence Analysis (Successful Connection)
When the bond was valid, the connection sequence worked:
```
20:38:30.571 - Found 1 bonded device matching plugin
20:38:30.635 - Bond state after connection: 12 (BONDED) ✅
20:38:30.758 - Services discovered ✅
20:38:32.269 - Authentication started (TEA challenge-response)
20:38:32.955 - Authentication verified - gateway unlocked! ✅
20:38:33.201 - Subscribed to SEED (00000011) notifications ✅
20:38:33.402 - Subscribed to Auth (00000014) notifications ✅
20:38:33.606 - Subscribed to DATA (00000034) notifications ✅
20:38:34.121 - Sent initial GetDevices command ✅
20:38:39.136 - Heartbeat sent (CommandId=0x2) ✅
20:38:44.147 - Heartbeat sent (CommandId=0x3) ✅
20:38:49.160 - Heartbeat sent (CommandId=0x4) ✅
20:38:50.299 - DISCONNECT: GATT_CONN_TERMINATE_PEER_USER ❌
```

### 4. Critical Issue: Gateway Not Responding
Despite successful authentication and notification subscriptions:
- **No notifications were ever received** from the gateway
- No `onCharacteristicChanged` callbacks occurred
- Gateway disconnected after ~17 seconds

This suggests the gateway accepted the connection and CCCD writes but was not actually sending data - likely because something in the protocol wasn't correct, or there's still a bond/encryption mismatch.

### 5. Current State (End of Session)
- Bond is **completely gone** from Android's bonded devices list
- Device needs to be re-paired fresh

## Code Changes Made

### Enhanced Connection Status Logging (`BaseBleService.kt`)
Added detailed status code interpretation in `onConnectionStateChange`:
```kotlin
val statusName = when (status) {
    BluetoothGatt.GATT_SUCCESS -> "GATT_SUCCESS"
    133 -> "GATT_ERROR(133) - likely stale bond/link key mismatch"
    8 -> "GATT_CONN_TIMEOUT"
    19 -> "GATT_CONN_TERMINATE_PEER_USER"
    22 -> "GATT_CONN_TERMINATE_LOCAL_HOST"
    34 -> "GATT_CONN_LMP_TIMEOUT"
    62 -> "GATT_CONN_FAIL_ESTABLISH"
    else -> "UNKNOWN($status)"
}
```

### Stale Bond Detection
Added detection for the "Android thinks bonded but gateway forgot" scenario:
```kotlin
if (status == 133 && device.bondState == BluetoothDevice.BOND_BONDED) {
    Log.e(TAG, "⚠️ STALE BOND DETECTED for ${device.address}!")
    Log.e(TAG, "   Android reports BOND_BONDED but connection failed with status 133")
    Log.e(TAG, "   This means the gateway has forgotten the bond - user must re-pair manually")
    // ... cleanup and notify user
}
```

### Enhanced Disconnect Logging
Added reason interpretation for disconnects to help diagnose why the gateway terminates:
```kotlin
if (status == 19) {
    Log.w(TAG, "⚠️ Gateway terminated connection - this may indicate:")
    Log.w(TAG, "   - Protocol issue (missing heartbeat, wrong commands)")
    Log.w(TAG, "   - Authentication/encryption mismatch")
    Log.w(TAG, "   - Gateway busy with another client")
}
```

## Next Steps

1. **Re-pair the device** fresh from Android Bluetooth settings
2. **Start the service** and observe the new detailed logging
3. **Watch for**:
   - Status 133 errors indicating stale bond
   - Gateway disconnect reasons (status 19)
   - Whether notifications are actually received after auth
4. **If gateway keeps disconnecting after ~17 seconds**:
   - May indicate protocol mismatch with heartbeat/command format
   - Compare COBS encoding output with working `android_ble_bridge` app
   - Check if gateway expects specific timing or sequence of operations

## Technical Details

### Gateway Information
- MAC: `24:DC:C3:ED:1E:0A`
- Name: `LCIRemotebzB9CztDZ`
- Type: Data Service gateway (has service `00000030-0200-a58e-e411-afe28044e62c`)
- Authentication: TEA challenge-response with cypher `612643285`, BIG-ENDIAN byte order

### Key BLE Characteristics
| UUID | Name | Purpose |
|------|------|---------|
| 00000012 | UNLOCK_STATUS | Read challenge (4 bytes) or "Unlocked" (8 bytes) |
| 00000013 | KEY | Write authentication response |
| 00000011 | SEED | Auth notifications |
| 00000014 | AUTH_STATUS | Auth notifications |
| 00000033 | DATA_WRITE | Write commands (WRITE_NO_RESPONSE) |
| 00000034 | DATA_READ | Receive data notifications |

### GATT Status Codes Reference
| Code | Name | Meaning |
|------|------|---------|
| 0 | GATT_SUCCESS | Operation successful |
| 8 | GATT_CONN_TIMEOUT | Connection timed out |
| 19 | GATT_CONN_TERMINATE_PEER_USER | Remote device terminated |
| 22 | GATT_CONN_TERMINATE_LOCAL_HOST | Local device terminated |
| 34 | GATT_CONN_LMP_TIMEOUT | Link manager timeout |
| 62 | GATT_CONN_FAIL_ESTABLISH | Failed to establish |
| 133 | GATT_ERROR (0x85) | Generic error - often stale bond |
