# Multi-Packet BLE Advertising Implementation

This document describes the multi-packet functionality implemented for iOS in the React Native BLE Advertiser module.

## Overview

While Android supports extended advertising that allows for larger advertising payloads, iOS is limited to 31 bytes of advertising data. To overcome this limitation, we've implemented a multi-packet system that automatically splits large payloads into multiple packets and rotates through them during advertising.

## Features

### iOS Implementation

- **Automatic Packet Splitting**: Payloads exceeding the maximum size are automatically split into multiple packets
- **Packet Rotation**: Multiple packets are rotated every 500ms to ensure all data is transmitted
- **Packet Reassembly**: Scanning devices can reassemble multi-packet messages automatically
- **Timeout Handling**: Incomplete packet buffers are cleaned up after 10 seconds
- **Memory Management**: Proper cleanup of timers and buffers when advertising stops

### Android Implementation

- **Extended Advertising Support**: Uses Android's native extended advertising when available
- **Fallback to Multi-Packet**: Falls back to multi-packet mode when extended advertising isn't supported
- **Dynamic Max Length Detection**: Automatically detects the maximum advertising length supported by the device

## API Changes

### New Method

```typescript
getMaxAdvertisingDataLength(): Promise<number>
```

Returns the maximum advertising data length supported by the platform:
- **iOS**: Always returns 31 bytes
- **Android**: Returns the actual maximum supported by the device (31 bytes for legacy, up to 1650 bytes for extended advertising)

### Enhanced broadcast() Method

The existing `broadcast()` method now automatically handles multi-packet scenarios:

```typescript
broadcast(uid: string, payload: number[], options?: BroadcastOptions): Promise<string | object>
```

**Return Values:**
- Single packet: Returns a string (e.g., "Broadcasting single packet")
- Multi-packet: Returns an object with packet information:
  ```typescript
  {
    totalPackets: number,
    packetId: number,
    dataPerPacket: number,
    status: "multi_packet_broadcast_started"
  }
  ```

### Enhanced Scanning

The scanning functionality now includes packet reassembly information in the `onDeviceFound` event:

```typescript
{
  // ... existing fields
  manufData: number[],           // Reassembled data (or single packet data)
  isReassembled: boolean,        // true if data was reassembled from multiple packets
  originalPackets?: number,      // Number of original packets (only if reassembled)
  companyId: number             // Company ID used for manufacturer data
}
```

## Packet Format

Multi-packet messages use a 3-byte header format:

```
[Total Packets (1 byte)][Packet Index (1 byte)][Packet ID (1 byte)][Data...]
```

- **Total Packets**: Number of packets in the complete message (1-255)
- **Packet Index**: Zero-based index of this packet (0 to Total Packets - 1)
- **Packet ID**: Random ID to group packets from the same message (0-255)
- **Data**: The actual payload data for this packet

## Implementation Details

### iOS Specifics

1. **Overhead Calculation**: Approximately 24 bytes of overhead for service UUID and manufacturer data structure
2. **Effective Payload**: ~7 bytes per packet (31 - 24 - 3 for header)
3. **Rotation Interval**: 100ms between packet rotations (optimized for fast transmission)
4. **Cleanup Timer**: Runs every 5 seconds to remove stale packet buffers
5. **Memory Management**: Automatic cleanup on module deallocation

### Android Specifics

1. **Extended Advertising**: Preferred method when available (Android 8.0+)
2. **Dynamic Testing**: Binary search to find actual maximum advertising length
3. **Fallback Support**: Graceful fallback to multi-packet when extended advertising fails
4. **PHY Support**: Supports different PHY modes for extended range

## Usage Example

```javascript
import BLEAdvertiser from 'react-native-ble-advertiser';

// Set company ID
BLEAdvertiser.setCompanyId(0x4C); // Apple's company ID

// Get max advertising length
const maxLength = await BLEAdvertiser.getMaxAdvertisingDataLength();
console.log('Max advertising length:', maxLength);

// Create a large message
const message = "This is a very long message that will be automatically split into multiple packets when it exceeds the maximum advertising data length supported by the platform.";
const messageBytes = message.split('').map(c => c.charCodeAt(0));

// Broadcast (automatically handles multi-packet if needed)
const result = await BLEAdvertiser.broadcast(
  "12345678-1234-1234-1234-123456789000",
  messageBytes,
  {
    advertiseMode: BLEAdvertiser.ADVERTISE_MODE_BALANCED,
    txPowerLevel: BLEAdvertiser.ADVERTISE_TX_POWER_MEDIUM,
    connectable: false
  }
);

if (typeof result === 'object') {
  console.log(`Multi-packet broadcast: ${result.totalPackets} packets`);
} else {
  console.log('Single packet broadcast');
}

// Scanning automatically handles reassembly
BLEAdvertiser.scan(null, {
  scanMode: BLEAdvertiser.SCAN_MODE_LOW_LATENCY
});

// Listen for devices
const eventEmitter = new NativeEventEmitter(NativeModules.BLEAdvertiser);
eventEmitter.addListener('onDeviceFound', (event) => {
  if (event.manufData) {
    const message = event.manufData.map(b => String.fromCharCode(b)).join('');
    console.log('Received message:', message);
    console.log('Was reassembled:', event.isReassembled);
    if (event.isReassembled) {
      console.log('Original packets:', event.originalPackets);
    }
  }
});
```

## Demo Application

A complete demo application is available at `example/app/MultiPacketDemo.js` that demonstrates:

- Real-time max advertising length detection
- Custom message input with automatic packet splitting indication
- Live broadcast status updates
- Device discovery with reassembly information
- Message reconstruction and display

## Limitations

1. **iOS Packet Size**: Limited to ~7 bytes of actual data per packet due to BLE overhead
2. **Transmission Time**: Large messages take longer to transmit due to packet rotation
3. **Reliability**: No guaranteed delivery - packets may be missed in noisy environments
4. **Battery Impact**: Continuous packet rotation may impact battery life
5. **Compatibility**: Receiving devices must implement the same packet reassembly logic

## Best Practices

1. **Keep Messages Short**: While multi-packet is supported, shorter messages are more reliable
2. **Handle Timeouts**: Implement proper timeout handling for incomplete messages
3. **Error Handling**: Always handle broadcast and scan errors gracefully
4. **Battery Optimization**: Stop advertising when not needed to preserve battery
5. **Testing**: Test with various message sizes and in different environments

## Technical Notes

- The implementation is thread-safe and handles concurrent operations properly
- Memory usage is optimized with automatic cleanup of stale data
- The packet ID system prevents cross-contamination between different broadcasts
- Both platforms maintain compatibility with existing single-packet implementations
