package com.blemqttbridge.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Simple configuration manager using SharedPreferences.
 * Provides default MQTT broker settings for quick setup.
 */
object AppConfig {
    
    private const val PREFS_NAME = "ble_bridge_config"
    
    // MQTT Configuration Keys
    private const val KEY_MQTT_BROKER_URL = "mqtt_broker_url"
    private const val KEY_MQTT_USERNAME = "mqtt_username"
    private const val KEY_MQTT_PASSWORD = "mqtt_password"
    private const val KEY_MQTT_CLIENT_ID = "mqtt_client_id"
    
    // Default values (your working broker from tests)
    private const val DEFAULT_BROKER_URL = "tcp://10.115.19.131:1883"
    private const val DEFAULT_USERNAME = "mqtt"
    private const val DEFAULT_PASSWORD = "mqtt"
    
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * Get MQTT configuration as a map for plugin initialization.
     */
    fun getMqttConfig(context: Context): Map<String, String> {
        val prefs = getPrefs(context)
        
        return mapOf(
            "broker_url" to prefs.getString(KEY_MQTT_BROKER_URL, DEFAULT_BROKER_URL)!!,
            "username" to prefs.getString(KEY_MQTT_USERNAME, DEFAULT_USERNAME)!!,
            "password" to prefs.getString(KEY_MQTT_PASSWORD, DEFAULT_PASSWORD)!!,
            "client_id" to prefs.getString(KEY_MQTT_CLIENT_ID, "ble_bridge_${System.currentTimeMillis()}")!!
        )
    }
    
    /**
     * Get BLE plugin configuration.
     * Currently empty, but allows for future device-specific config.
     */
    fun getBlePluginConfig(context: Context, pluginId: String): Map<String, String> {
        // For now, return empty map
        // Future: Could store device addresses, pairing codes, etc.
        return emptyMap()
    }
    
    /**
     * Update MQTT broker settings.
     */
    fun setMqttBroker(context: Context, brokerUrl: String, username: String, password: String) {
        getPrefs(context).edit().apply {
            putString(KEY_MQTT_BROKER_URL, brokerUrl)
            putString(KEY_MQTT_USERNAME, username)
            putString(KEY_MQTT_PASSWORD, password)
            apply()
        }
    }
    
    /**
     * Reset to default MQTT settings.
     */
    fun resetMqttToDefaults(context: Context) {
        getPrefs(context).edit().apply {
            putString(KEY_MQTT_BROKER_URL, DEFAULT_BROKER_URL)
            putString(KEY_MQTT_USERNAME, DEFAULT_USERNAME)
            putString(KEY_MQTT_PASSWORD, DEFAULT_PASSWORD)
            apply()
        }
    }
}
