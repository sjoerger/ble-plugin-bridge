package com.blemqttbridge.utils

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * Utility for managing Android TV-specific settings.
 * 
 * HDMI-CEC (Consumer Electronics Control) allows devices to control each other
 * over HDMI. When enabled, the TV can put the streaming device to sleep when
 * the TV powers off, which kills our foreground service.
 * 
 * This helper manages the "auto device off" CEC setting to prevent the service
 * from being killed when the TV enters standby mode.
 * 
 * IMPORTANT: Writing to Settings.Global requires WRITE_SECURE_SETTINGS permission,
 * which cannot be granted through normal UI. User must run:
 *   adb shell pm grant com.blemqttbridge android.permission.WRITE_SECURE_SETTINGS
 */
object AndroidTvHelper {
    
    private const val TAG = "AndroidTvHelper"
    
    /** Settings.Global key for HDMI-CEC auto device off behavior */
    private const val HDMI_CONTROL_AUTO_DEVICE_OFF_ENABLED = "hdmi_control_auto_device_off_enabled"
    
    /**
     * Check if this device is an Android TV (has Leanback feature).
     * 
     * Leanback is the TV-optimized UI framework for Android TV devices.
     * This is the standard way to detect if running on Android TV.
     */
    fun isAndroidTv(context: Context): Boolean {
        val hasLeanback = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        Log.d(TAG, "Android TV check: hasLeanback=$hasLeanback")
        return hasLeanback
    }
    
    /**
     * Check if the app has WRITE_SECURE_SETTINGS permission.
     * 
     * This permission cannot be granted through normal Android UI.
     * User must grant it via ADB command:
     *   adb shell pm grant com.blemqttbridge android.permission.WRITE_SECURE_SETTINGS
     */
    fun hasWriteSecureSettingsPermission(context: Context): Boolean {
        val result = context.checkCallingOrSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        val hasPermission = result == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "WRITE_SECURE_SETTINGS permission: $hasPermission")
        return hasPermission
    }
    
    /**
     * Get the current HDMI-CEC auto device off setting.
     * 
     * @return 1 if enabled (device sleeps with TV), 0 if disabled, -1 if error
     */
    fun getCecAutoOffSetting(context: Context): Int {
        return try {
            val value = Settings.Global.getInt(
                context.contentResolver,
                HDMI_CONTROL_AUTO_DEVICE_OFF_ENABLED,
                -1  // Default if not found
            )
            Log.d(TAG, "CEC auto device off setting: $value")
            value
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read CEC setting", e)
            -1
        }
    }
    
    /**
     * Check if HDMI-CEC auto device off is enabled.
     * 
     * When enabled (1), the device will enter sleep mode when the TV sends
     * a CEC standby command (typically when TV powers off).
     */
    fun isCecAutoOffEnabled(context: Context): Boolean {
        return getCecAutoOffSetting(context) == 1
    }
    
    /**
     * Disable HDMI-CEC auto device off to prevent service from being killed.
     * 
     * Requires WRITE_SECURE_SETTINGS permission.
     * 
     * @return true if successful, false if failed (usually due to missing permission)
     */
    fun disableCecAutoOff(context: Context): Boolean {
        if (!hasWriteSecureSettingsPermission(context)) {
            Log.w(TAG, "Cannot disable CEC auto-off: missing WRITE_SECURE_SETTINGS permission")
            return false
        }
        
        return try {
            val result = Settings.Global.putInt(
                context.contentResolver,
                HDMI_CONTROL_AUTO_DEVICE_OFF_ENABLED,
                0  // Disabled
            )
            Log.i(TAG, "Disabled CEC auto device off: success=$result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable CEC auto-off", e)
            false
        }
    }
    
    /**
     * Enable HDMI-CEC auto device off (restore default behavior).
     * 
     * Requires WRITE_SECURE_SETTINGS permission.
     * 
     * @return true if successful, false if failed
     */
    fun enableCecAutoOff(context: Context): Boolean {
        if (!hasWriteSecureSettingsPermission(context)) {
            Log.w(TAG, "Cannot enable CEC auto-off: missing WRITE_SECURE_SETTINGS permission")
            return false
        }
        
        return try {
            val result = Settings.Global.putInt(
                context.contentResolver,
                HDMI_CONTROL_AUTO_DEVICE_OFF_ENABLED,
                1  // Enabled
            )
            Log.i(TAG, "Enabled CEC auto device off: success=$result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable CEC auto-off", e)
            false
        }
    }
    
    /**
     * Set HDMI-CEC auto device off setting.
     * 
     * @param enabled true to enable (device sleeps with TV), false to disable
     * @return true if successful
     */
    fun setCecAutoOff(context: Context, enabled: Boolean): Boolean {
        return if (enabled) enableCecAutoOff(context) else disableCecAutoOff(context)
    }
    
    /**
     * Apply recommended settings for Android TV service reliability.
     * 
     * This disables CEC auto device off to prevent the service from being
     * killed when the TV enters standby mode.
     * 
     * @return true if settings were applied, false if failed or not needed
     */
    fun applyRecommendedSettings(context: Context): Boolean {
        if (!isAndroidTv(context)) {
            Log.d(TAG, "Not Android TV - skipping CEC settings")
            return false
        }
        
        if (!hasWriteSecureSettingsPermission(context)) {
            Log.d(TAG, "Missing permission - cannot apply CEC settings automatically")
            return false
        }
        
        if (isCecAutoOffEnabled(context)) {
            Log.i(TAG, "CEC auto-off is enabled, disabling for service reliability")
            return disableCecAutoOff(context)
        }
        
        Log.d(TAG, "CEC auto-off already disabled")
        return true
    }
    
    /**
     * Get user-friendly status message for the CEC setting.
     */
    fun getStatusMessage(context: Context): String {
        val cecEnabled = isCecAutoOffEnabled(context)
        val cecValue = getCecAutoOffSetting(context)
        
        return when {
            cecValue == -1 -> "⚠ Unable to read CEC setting"
            cecEnabled -> "⚠ CEC Auto-Off Enabled\nService will be killed when TV sleeps"
            else -> "✓ CEC Auto-Off Disabled\nService survives TV power state changes"
        }
    }
    
    /**
     * Get the ADB command to grant WRITE_SECURE_SETTINGS permission.
     */
    fun getGrantPermissionCommand(context: Context): String {
        return "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
    }
    
    /**
     * Get the ADB command to manually disable CEC auto-off.
     */
    fun getDisableCecCommand(): String {
        return "adb shell settings put global hdmi_control_auto_device_off_enabled 0"
    }
}
