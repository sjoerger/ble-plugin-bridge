# BLE-MQTT Plugin Bridge

Android application that bridges BLE (Bluetooth Low Energy) devices to MQTT, enabling Home Assistant integration for OneControl RV automation systems and other BLE devices.

## 🎯 Project Status

**Current Version:** v0.0.4  
**Status:** OneControl Gateway - **Fully Functional** ✅

### What Works
- ✅ BLE connection and authentication with OneControl gateway
- ✅ Real-time device status updates (switches, dimmable lights, sensors)
- ✅ MQTT command handling (control switches and dimmable lights from Home Assistant)
- ✅ Home Assistant auto-discovery for all device types
- ✅ Debounced brightness control with restore-on-ON behavior
- ✅ Background service with automatic reconnection

## 🚀 Quick Start

### Prerequisites
- Android device (API 26+, Android 8.0+)
- MQTT broker (e.g., Mosquitto)
- Home Assistant (optional, for auto-discovery)

### Installation

1. **Download and install APK** from [Releases](https://github.com/phurth/ble-plugin-bridge/releases)
2. **Configure MQTT broker** - see [MQTT_SETUP_GUIDE.md](MQTT_SETUP_GUIDE.md)
3. **Start the service** - app will auto-scan and connect to configured gateway
4. **Check Home Assistant** - devices will appear automatically

### Basic Configuration

Edit your gateway settings in the plugin initialization:

```kotlin
gatewayMac = "24:DC:C3:ED:1E:0A"  // Your gateway MAC
gatewayPin = "090336"              // Your gateway PIN
```

## 📦 Supported Devices

### OneControl Gateway (LCI/Lippert)
- **Switches** - Binary relays and latching switches
- **Dimmable Lights** - Full 0-255 brightness control with debouncing
- **Sensors** - Temperature, voltage, tank levels
- **Covers** - Awnings, slides (status monitoring)
- **HVAC** - Status monitoring

## 🏗️ Architecture

### Plugin-Based Design

Each BLE device type is handled by a dedicated plugin that owns its GATT callback:

```
BaseBleService
  └─> OneControlDevicePlugin
       └─> OneControlGattCallback (owns BLE connection)
            ├─> Stream reading & COBS decoding
            ├─> Event processing & MQTT publishing  
            └─> Command handling (MQTT → BLE)
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for details.

## 📖 Documentation

### Getting Started
- **[MQTT_SETUP_GUIDE.md](MQTT_SETUP_GUIDE.md)** - MQTT broker configuration
- **[REMOTE_CONTROL_QUICKSTART.md](REMOTE_CONTROL_QUICKSTART.md)** - Remote control usage
- **[TESTING.md](TESTING.md)** - Testing procedures

### Technical Details
- **[AUTHENTICATION_ALGORITHM.md](AUTHENTICATION_ALGORITHM.md)** - OneControl authentication protocol
- **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** - System architecture and design
- **[REMOTE_CONTROL_API.md](REMOTE_CONTROL_API.md)** - Complete remote control API

## 🔧 Development

### Building

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Testing

```bash
# Monitor logs
adb logcat -s OneControlGattCallback:I BaseBleService:I

# Send test command via MQTT
mosquitto_pub -h <broker> -u mqtt -P mqtt \
  -t "homeassistant/onecontrol/<MAC>/command/switch/8/7" -m "ON"
```

## 📝 Release History

### v0.0.4 (2024-12-22)
**Fixed:**
- Critical bug: brightness and mode bytes were swapped in DimmableLightStatus parsing
- Slider not able to reach 100% brightness - removed incorrect brightness=255 restore logic
- Added spurious status guard to filter incorrect gateway brightness=0 updates

**Improved:**
- Dimmable light restore-on-ON now works correctly via lastKnownDimmableBrightness tracking
- All brightness values (1-255) now treated as literal values

### v0.0.3 (2024-12-21)
- Dimmable light control with debouncing (200ms)
- Restore-on-ON behavior for lights
- Command subscription via MQTT
- Pending guard to prevent UI bouncing

### v0.0.2 (2024-12-20)
- Switch control working
- BLE notifications fixed
- Initial MQTT command integration

### v0.0.1 (2024-12-19)
- Initial plugin architecture
- Gateway connection and authentication
- Basic status monitoring

## 🐛 Known Issues

None currently reported.

## 🔮 Planned Features

### Near Term
- Cover/awning control (OPEN/CLOSE/STOP commands)
- HVAC control
- RGB light support
- Generator control

### Future Enhancements
- Multiple gateway support
- Custom device naming in Home Assistant
- State persistence across restarts
- Advanced scheduling/automation

## 🤝 Contributing

This project was developed for personal use but contributions are welcome. Please open an issue first to discuss major changes.

## 📄 License

MIT License - See LICENSE file for details

## 🙏 Acknowledgments

Based on reverse engineering of the OneControl iOS/Android app and MyRvLink protocol specification.
