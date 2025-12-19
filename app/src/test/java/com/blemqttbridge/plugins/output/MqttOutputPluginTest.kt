package com.blemqttbridge.plugins.output

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Unit tests for MqttOutputPlugin.
 * Note: These are basic structure tests. Integration tests with real MQTT broker
 * should be done in androidTest.
 */
class MqttOutputPluginTest {
    
    @Mock
    private lateinit var mockContext: Context
    
    private lateinit var plugin: MqttOutputPlugin
    
    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        whenever(mockContext.applicationContext).thenReturn(mockContext)
        plugin = MqttOutputPlugin(mockContext)
    }
    
    @Test
    fun testPluginMetadata() {
        assertEquals("mqtt", plugin.getOutputId())
        assertEquals("MQTT Broker", plugin.getOutputName())
    }
    
    @Test
    fun testInitialStateDisconnected() {
        assertFalse(plugin.isConnected())
        assertEquals("Not initialized", plugin.getConnectionStatus())
    }
    
    @Test
    fun testInitializeRequiresBrokerUrl() = runTest {
        val config = mapOf(
            "username" to "test",
            "password" to "test"
        )
        
        val result = try {
            plugin.initialize(config)
            null
        } catch (e: IllegalArgumentException) {
            e
        }
        
        assert(result is IllegalArgumentException)
    }
}
