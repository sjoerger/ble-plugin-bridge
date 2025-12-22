# Changelog

All notable changes to the BLE-MQTT Plugin Bridge project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.0.4] - 2024-12-22

### Fixed
- **Critical:** Brightness and mode bytes were swapped in DimmableLightStatus parsing
  - Corrected to: `data[3]=mode`, `data[4]=brightness` (matching MyRvLink protocol spec)
  - Previously: brightness showed mode value (e.g., "brightness=1 mode=150")
- Brightness slider unable to reach 100%
  - Removed incorrect brightness=255 "restore" logic that prevented max brightness
  - All brightness values (1-255) now treated as literal brightness levels
- Added spurious status guard to filter incorrect gateway brightness=0 updates
  - Gateway sometimes sends stale `brightness=0 mode=0` status even when light is on
  - Now filtered using `lastKnownDimmableBrightness` tracking

### Improved
- Dimmable light restore-on-ON behavior now works correctly
  - Uses `lastKnownDimmableBrightness` tracking instead of brightness=255 special case
  - Light restores to previous brightness when turned ON after being OFF
- Enhanced logging for debugging brightness issues

## [0.0.3] - 2024-12-21

### Added
- MQTT command subscription and routing
  - Subscribe to `homeassistant/onecontrol/{MAC}/command/#` topics
  - Route commands to plugin's `handleCommand()` method
- Dimmable light control with debouncing
  - 200ms debounce window to coalesce rapid slider changes
  - Prevents UI bouncing during brightness adjustment
- Pending status guard for dimmable lights
  - 12-second window to suppress conflicting gateway status updates
  - Prevents slider from jumping back during command execution
- Restore-on-ON behavior for dimmable lights
  - Tracks last known brightness value
  - Restores previous brightness when light turned ON

### Fixed
- Dimmable light command format (8-byte payload)
- Command routing from MQTT to BLE

## [0.0.2] - 2024-12-20

### Added
- Switch (relay) control via MQTT commands
  - ON/OFF commands work correctly
  - Optimistic state updates
- Enhanced BLE notification handling
  - Fixed notification callback registration
  - Proper stream reading queue

### Fixed
- Switch state parsing (use low nibble of status byte)
- Temperature sensor 0x7FFF handling (invalid/unavailable marker)
- BLE notification callbacks not firing

## [0.0.1] - 2024-12-19

### Added
- Initial plugin architecture
  - `BleDevicePlugin` interface
  - `OneControlDevicePlugin` implementation
  - Plugin owns GATT callback (no forwarding layer)
- OneControl gateway connection
  - BLE scanning and device matching
  - Authentication via Data Service (UNLOCK_STATUS/KEY characteristics)
  - MTU negotiation (185 bytes)
- COBS frame decoding
  - Stream reading from notification queue
  - CRC8 validation
  - Event processing
- MQTT integration
  - Device status publishing
  - Home Assistant auto-discovery
- Device support
  - Switches (relay status)
  - Dimmable lights (status only)
  - Temperature sensors
  - Voltage sensors
  - Tank level sensors
  - Cover/awning status

### Technical
- Android foreground service with wake lock
- Background notification handling
- Heartbeat mechanism (GetDevices every 5s)
- Event-driven architecture

## [Unreleased]

### Planned
- Cover/awning control (OPEN/CLOSE/STOP commands)
- HVAC control
- RGB light support
- Generator control
- Multiple gateway support
- Custom device naming in Home Assistant
- State persistence across restarts

---

## Version History Legend

- **Added** - New features
- **Changed** - Changes in existing functionality
- **Deprecated** - Soon-to-be removed features
- **Removed** - Removed features
- **Fixed** - Bug fixes
- **Security** - Vulnerability fixes
