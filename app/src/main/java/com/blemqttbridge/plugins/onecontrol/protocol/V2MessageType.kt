package com.blemqttbridge.plugins.onecontrol.protocol

/**
 * V2MessageType - Message types used by the gateway for CAN-over-BLE communication
 */
enum class V2MessageType(val value: Byte) {
    Packed(1),
    ElevenBit(2),
    TwentyNineBit(3);

    companion object {
        fun fromByte(byte: Byte): V2MessageType? {
            return values().find { it.value == byte }
        }
    }
}

