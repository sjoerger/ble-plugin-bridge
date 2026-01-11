package com.blemqttbridge.util

import android.util.Log
import com.blemqttbridge.BuildConfig
import com.blemqttbridge.core.BaseBleService

/**
 * Debug logging utilities that respect BLE trace capture mode.
 * 
 * Debug logs are only output when:
 * - BuildConfig.DEBUG is true (debug build), OR
 * - BLE trace capture is active
 * 
 * This reduces production logging overhead while maintaining full logging
 * when users enable trace capture for debugging.
 */
object DebugLog {
    
    /**
     * Check if debug logging should be enabled.
     * Returns true if in debug build OR trace capture is active OR force debug is enabled.
     * Must be internal and annotated for use in inline functions.
     */
    @PublishedApi
    internal fun isDebugEnabled(): Boolean {
        return BuildConfig.DEBUG || BuildConfig.FORCE_DEBUG_LOG || BaseBleService.traceActive.value
    }
    
    /**
     * Log at DEBUG level - only outputs if debug enabled or trace active.
     */
    fun d(tag: String, message: String) {
        if (isDebugEnabled()) {
            Log.d(tag, message)
        }
    }
    
    /**
     * Log at DEBUG level with lazy evaluation - only outputs if debug enabled or trace active.
     * Use this for expensive string operations.
     */
    inline fun d(tag: String, message: () -> String) {
        if (isDebugEnabled()) {
            Log.d(tag, message())
        }
    }
    
    /**
     * Log at VERBOSE level - only outputs if debug enabled or trace active.
     */
    fun v(tag: String, message: String) {
        if (isDebugEnabled()) {
            Log.v(tag, message)
        }
    }
    
    /**
     * Log at VERBOSE level with lazy evaluation.
     */
    inline fun v(tag: String, message: () -> String) {
        if (isDebugEnabled()) {
            Log.v(tag, message())
        }
    }
    
    /**
     * Log at INFO level - always outputs (not gated by debug flag).
     * Use sparingly for important operational events.
     */
    fun i(tag: String, message: String) {
        Log.i(tag, message)
    }
    
    /**
     * Log at WARNING level - always outputs.
     */
    fun w(tag: String, message: String) {
        Log.w(tag, message)
    }
    
    /**
     * Log at WARNING level with throwable - always outputs.
     */
    fun w(tag: String, message: String, throwable: Throwable) {
        Log.w(tag, message, throwable)
    }
    
    /**
     * Log at ERROR level - always outputs.
     */
    fun e(tag: String, message: String) {
        Log.e(tag, message)
    }
    
    /**
     * Log at ERROR level with throwable - always outputs.
     */
    fun e(tag: String, message: String, throwable: Throwable) {
        Log.e(tag, message, throwable)
    }
}
