#import "BLEAdvertiser.h"
@import CoreBluetooth;

@implementation BLEAdvertiser {
    NSMutableDictionary *packetRotationTimers;
    NSMutableDictionary *packetBuffers;
    NSTimer *packetCleanupTimer;
    int companyId;
}

#define REGION_ID @"com.privatekit.ibeacon"
#define MAX_ADVERTISING_DATA_LENGTH 31
#define PACKET_TIMEOUT_SECONDS 10.0
#define PACKET_ROTATION_INTERVAL 0.02  // 100ms for faster packet rotation

// Packet buffer structure for reassembly
@interface PacketBuffer : NSObject
@property (nonatomic) NSUInteger totalPackets;
@property (nonatomic) NSUInteger packetId;
@property (nonatomic, strong) NSMutableDictionary *packets;
@property (nonatomic, strong) NSDate *firstSeenTime;
@end

@implementation PacketBuffer
- (instancetype)init {
    self = [super init];
    if (self) {
        _packets = [[NSMutableDictionary alloc] init];
        _firstSeenTime = [NSDate date];
    }
    return self;
}
@end

- (dispatch_queue_t)methodQueue
{
    return dispatch_get_main_queue();
}

RCT_EXPORT_MODULE(BLEAdvertiser)

- (NSArray<NSString *> *)supportedEvents {
    return @[@"onDeviceFound", @"onBTStatusChange"];
}

- (instancetype)init {
    self = [super init];
    if (self) {
        packetRotationTimers = [[NSMutableDictionary alloc] init];
        packetBuffers = [[NSMutableDictionary alloc] init];
        companyId = 0x0000;
        
        // Start packet cleanup timer
        [self startPacketCleanupTimer];
    }
    return self;
}

- (void)startPacketCleanupTimer {
    packetCleanupTimer = [NSTimer scheduledTimerWithTimeInterval:5.0
                                                          target:self
                                                        selector:@selector(cleanupOldPackets)
                                                        userInfo:nil
                                                         repeats:YES];
}

- (void)cleanupOldPackets {
    NSDate *now = [NSDate date];
    NSMutableArray *keysToRemove = [[NSMutableArray alloc] init];
    
    for (NSString *key in packetBuffers) {
        PacketBuffer *buffer = packetBuffers[key];
        NSTimeInterval timeSinceFirstSeen = [now timeIntervalSinceDate:buffer.firstSeenTime];
        
        if (timeSinceFirstSeen > PACKET_TIMEOUT_SECONDS) {
            [keysToRemove addObject:key];
            NSLog(@"Removing incomplete packet buffer for device: %@", key);
        }
    }
    
    for (NSString *key in keysToRemove) {
        [packetBuffers removeObjectForKey:key];
    }
}

RCT_EXPORT_METHOD(setCompanyId: (nonnull NSNumber *)companyId){
    RCTLogInfo(@"setCompanyId function called %@", companyId);
    self->companyId = [companyId intValue];
    self->centralManager = [[CBCentralManager alloc] initWithDelegate:self queue: nil options:@{CBCentralManagerOptionShowPowerAlertKey: @(YES)}];
    self->peripheralManager = [[CBPeripheralManager alloc] initWithDelegate:self queue:nil options:nil];
}

RCT_EXPORT_METHOD(getMaxAdvertisingDataLength:(RCTPromiseResolveBlock)resolve
    rejecter:(RCTPromiseRejectBlock)reject){
    // iOS always returns 31 bytes as the maximum advertising data length
    NSLog(@"Returning max advertising length: %d bytes", MAX_ADVERTISING_DATA_LENGTH);
    resolve(@(MAX_ADVERTISING_DATA_LENGTH));
}

RCT_EXPORT_METHOD(broadcast: (NSString *)uid payload:(NSArray *)payload options:(NSDictionary *)options
    resolve: (RCTPromiseResolveBlock)resolve
    rejecter:(RCTPromiseRejectBlock)reject){

    RCTLogInfo(@"Broadcast function called %@ with payload size: %lu", uid, (unsigned long)[payload count]);
    
    if (!peripheralManager) {
        reject(@"Peripheral manager not available", @"Peripheral manager is null", nil);
        return;
    }
    
    if (companyId == 0x0000) {
        reject(@"Invalid company id", @"Company ID not set", nil);
        return;
    }
    
    if (peripheralManager.state != CBManagerStatePoweredOn) {
        reject(@"Bluetooth not ready", @"Peripheral manager not powered on", nil);
        return;
    }
    
    // Convert payload to NSData
    NSMutableData *payloadData = [[NSMutableData alloc] init];
    for (NSNumber *byte in payload) {
        uint8_t byteValue = [byte unsignedCharValue];
        [payloadData appendBytes:&byteValue length:1];
    }
    
    // Calculate overhead: Service UUID (3 bytes structure + 16 bytes UUID) + Manufacturer data structure (3 bytes) + company ID (2 bytes)
    NSUInteger bleOverhead = 24; // Approximate overhead for iOS
    NSUInteger maxPayloadSize = MAX_ADVERTISING_DATA_LENGTH - bleOverhead;
    
    NSLog(@"Payload size: %lu, max allowed: %lu", (unsigned long)[payloadData length], (unsigned long)maxPayloadSize);
    
    // Check if we need to split the payload
    if ([payloadData length] > maxPayloadSize) {
        NSLog(@"Payload exceeds max size, splitting into multiple packets");
        [self broadcastMultiPacket:uid payloadData:payloadData maxPacketSize:maxPayloadSize options:options resolve:resolve reject:reject];
    } else {
        NSLog(@"Payload fits in single packet");
        [self broadcastSinglePacket:uid payloadData:payloadData options:options resolve:resolve reject:reject];
    }
}

- (void)broadcastSinglePacket:(NSString *)uid payloadData:(NSData *)payloadData options:(NSDictionary *)options
                      resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
    
    // Stop any existing advertising
    [peripheralManager stopAdvertising];
    
    // Build advertising data
    NSMutableDictionary *advertisingData = [[NSMutableDictionary alloc] init];
    
    // Add service UUID
    advertisingData[CBAdvertisementDataServiceUUIDsKey] = @[[CBUUID UUIDWithString:uid]];
    
    // Add manufacturer data
    advertisingData[CBAdvertisementDataManufacturerDataKey] = @{@(companyId): payloadData};
    
    // Add optional settings
    if (options) {
        if (options[@"includeDeviceName"] && [options[@"includeDeviceName"] boolValue]) {
            advertisingData[CBAdvertisementDataLocalNameKey] = [[UIDevice currentDevice] name];
        }
        if (options[@"includeTxPowerLevel"] && [options[@"includeTxPowerLevel"] boolValue]) {
            advertisingData[CBAdvertisementDataTxPowerLevelKey] = @(0); // iOS doesn't allow custom TX power
        }
    }
    
    [peripheralManager startAdvertising:advertisingData];
    resolve(@"Broadcasting single packet");
}

- (void)broadcastMultiPacket:(NSString *)uid payloadData:(NSData *)payloadData maxPacketSize:(NSUInteger)maxPacketSize
                     options:(NSDictionary *)options resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
    
    // Calculate number of packets needed
    // Reserve 3 bytes for packet header: [total packets(1)][packet index(1)][packet id(1)]
    NSUInteger dataPerPacket = maxPacketSize - 3;
    NSUInteger totalPackets = ([payloadData length] + dataPerPacket - 1) / dataPerPacket; // Ceiling division
    
    if (totalPackets > 255) {
        reject(@"Payload too large", @"Payload requires more than 255 packets", nil);
        return;
    }
    
    NSLog(@"Splitting payload into %lu packets", (unsigned long)totalPackets);
    NSLog(@"Bytes per packet: %lu (plus 3 byte header)", (unsigned long)dataPerPacket);
    
    // Generate a random packet ID to group packets together
    uint8_t packetId = arc4random_uniform(256);
    
    // Start packet rotation
    [self startPacketRotation:uid payloadData:payloadData totalPackets:totalPackets dataPerPacket:dataPerPacket
                     packetId:packetId options:options resolve:resolve reject:reject];
}

- (void)startPacketRotation:(NSString *)uid payloadData:(NSData *)payloadData totalPackets:(NSUInteger)totalPackets
              dataPerPacket:(NSUInteger)dataPerPacket packetId:(uint8_t)packetId options:(NSDictionary *)options
                    resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
    
    // Stop any existing rotation for this UID
    [self stopPacketRotationForUID:uid];
    
    // Create rotation state
    NSMutableDictionary *rotationState = [[NSMutableDictionary alloc] init];
    rotationState[@"payloadData"] = payloadData;
    rotationState[@"totalPackets"] = @(totalPackets);
    rotationState[@"dataPerPacket"] = @(dataPerPacket);
    rotationState[@"packetId"] = @(packetId);
    rotationState[@"currentPacketIndex"] = @(0);
    rotationState[@"options"] = options ?: @{};
    
    // Create timer for packet rotation
    NSTimer *rotationTimer = [NSTimer scheduledTimerWithTimeInterval:PACKET_ROTATION_INTERVAL
                                                              target:self
                                                            selector:@selector(rotatePacket:)
                                                            userInfo:@{@"uid": uid, @"state": rotationState}
                                                             repeats:YES];
    
    // Store timer
    packetRotationTimers[uid] = rotationTimer;
    
    // Fire first packet immediately
    [self rotatePacket:rotationTimer];
    
    // Return success with packet info
    NSMutableDictionary *result = [[NSMutableDictionary alloc] init];
    result[@"totalPackets"] = @(totalPackets);
    result[@"packetId"] = @(packetId);
    result[@"dataPerPacket"] = @(dataPerPacket);
    result[@"status"] = @"multi_packet_broadcast_started";
    resolve(result);
}

- (void)rotatePacket:(NSTimer *)timer {
    NSDictionary *userInfo = timer.userInfo;
    NSString *uid = userInfo[@"uid"];
    NSMutableDictionary *state = userInfo[@"state"];
    
    NSData *payloadData = state[@"payloadData"];
    NSUInteger totalPackets = [state[@"totalPackets"] unsignedIntegerValue];
    NSUInteger dataPerPacket = [state[@"dataPerPacket"] unsignedIntegerValue];
    uint8_t packetId = [state[@"packetId"] unsignedCharValue];
    NSUInteger currentPacketIndex = [state[@"currentPacketIndex"] unsignedIntegerValue];
    NSDictionary *options = state[@"options"];
    
    // Calculate data range for this packet
    NSUInteger startIdx = currentPacketIndex * dataPerPacket;
    NSUInteger endIdx = MIN(startIdx + dataPerPacket, [payloadData length]);
    NSUInteger packetDataLength = endIdx - startIdx;
    
    // Create packet with header
    NSMutableData *packet = [[NSMutableData alloc] init];
    uint8_t header[3] = {(uint8_t)totalPackets, (uint8_t)currentPacketIndex, packetId};
    [packet appendBytes:header length:3];
    [packet appendData:[payloadData subdataWithRange:NSMakeRange(startIdx, packetDataLength)]];
    
    NSLog(@"Broadcasting packet %lu/%lu, size: %lu bytes", 
          (unsigned long)(currentPacketIndex + 1), (unsigned long)totalPackets, (unsigned long)[packet length]);
    
    // Broadcast this packet
    [self broadcastSinglePacket:uid payloadData:packet options:options resolve:nil reject:nil];
    
    // Move to next packet
    currentPacketIndex++;
    if (currentPacketIndex >= totalPackets) {
        currentPacketIndex = 0; // Loop back to first packet
    }
    state[@"currentPacketIndex"] = @(currentPacketIndex);
}

- (void)stopPacketRotationForUID:(NSString *)uid {
    NSTimer *existingTimer = packetRotationTimers[uid];
    if (existingTimer) {
        [existingTimer invalidate];
        [packetRotationTimers removeObjectForKey:uid];
        NSLog(@"Stopped packet rotation for UID: %@", uid);
    }
}

RCT_EXPORT_METHOD(stopBroadcast:(RCTPromiseResolveBlock)resolve
    rejecter:(RCTPromiseRejectBlock)reject){

    [peripheralManager stopAdvertising];
    
    // Stop all packet rotations
    NSArray *uids = [packetRotationTimers allKeys];
    for (NSString *uid in uids) {
        [self stopPacketRotationForUID:uid];
    }
    
    NSMutableArray *stoppedUIDs = [[NSMutableArray alloc] init];
    [stoppedUIDs addObjectsFromArray:uids];

    resolve(stoppedUIDs);
}

RCT_EXPORT_METHOD(scan: (NSArray *)payload options:(NSDictionary *)options 
    resolve: (RCTPromiseResolveBlock)resolve
    rejecter:(RCTPromiseRejectBlock)reject){

    if (!centralManager) { reject(@"Device does not support Bluetooth", @"Adapter is Null", nil); return; }
    
    switch (centralManager.state) {
        case CBManagerStatePoweredOn:    break;
        case CBManagerStatePoweredOff:   reject(@"Bluetooth not ON",@"Powered off", nil);   return;
        case CBManagerStateResetting:    reject(@"Bluetooth not ON",@"Resetting", nil);     return;
        case CBManagerStateUnauthorized: reject(@"Bluetooth not ON",@"Unauthorized", nil);  return;
        case CBManagerStateUnknown:      reject(@"Bluetooth not ON",@"Unknown", nil);       return;
        case CBManagerStateUnsupported:  reject(@"STATE_OFF",@"Unsupported", nil);          return;
    }
 
    [centralManager scanForPeripheralsWithServices:nil options:@{CBCentralManagerScanOptionAllowDuplicatesKey:[NSNumber numberWithBool:YES]}];
}

 
RCT_EXPORT_METHOD(scanByService: (NSString *)uid options:(NSDictionary *)options 
    resolve: (RCTPromiseResolveBlock)resolve
    rejecter:(RCTPromiseRejectBlock)reject){

    if (!centralManager) { reject(@"Device does not support Bluetooth", @"Adapter is Null", nil); return; }
    
    switch (centralManager.state) {
        case CBManagerStatePoweredOn:    break;
        case CBManagerStatePoweredOff:   reject(@"Bluetooth not ON",@"Powered off", nil);   return;
        case CBManagerStateResetting:    reject(@"Bluetooth not ON",@"Resetting", nil);     return;
        case CBManagerStateUnauthorized: reject(@"Bluetooth not ON",@"Unauthorized", nil);  return;
        case CBManagerStateUnknown:      reject(@"Bluetooth not ON",@"Unknown", nil);       return;
        case CBManagerStateUnsupported:  reject(@"STATE_OFF",@"Unsupported", nil);          return;
    }
 
    [centralManager scanForPeripheralsWithServices:@[[CBUUID UUIDWithString:uid]] options:@{CBCentralManagerScanOptionAllowDuplicatesKey:[NSNumber numberWithBool:YES]}];
}


RCT_EXPORT_METHOD(stopScan:(RCTPromiseResolveBlock)resolve
    rejecter:(RCTPromiseRejectBlock)reject){

    [centralManager stopScan];
    resolve(@"Stopping Scan");
}

RCT_EXPORT_METHOD(enableAdapter){
    RCTLogInfo(@"enableAdapter function called");
}

RCT_EXPORT_METHOD(disableAdapter){
    RCTLogInfo(@"disableAdapter function called");
}

RCT_EXPORT_METHOD(getAdapterState:(RCTPromiseResolveBlock)resolve
    rejecter:(RCTPromiseRejectBlock)reject){
    
    switch (centralManager.state) {
        case CBManagerStatePoweredOn:       resolve(@"STATE_ON"); return;
        case CBManagerStatePoweredOff:      resolve(@"STATE_OFF"); return;
        case CBManagerStateResetting:       resolve(@"STATE_TURNING_ON"); return;
        case CBManagerStateUnauthorized:    resolve(@"STATE_OFF"); return;
        case CBManagerStateUnknown:         resolve(@"STATE_OFF"); return;
        case CBManagerStateUnsupported:     resolve(@"STATE_OFF"); return;
    }
}

RCT_EXPORT_METHOD(isActive: 
     (RCTPromiseResolveBlock)resolve
    rejecter:(RCTPromiseRejectBlock)reject){
  
    resolve(([centralManager state] == CBManagerStatePoweredOn) ? @YES : @NO);
}

-(void)centralManager:(CBCentralManager *)central didDiscoverPeripheral:(CBPeripheral *)peripheral advertisementData:(NSDictionary<NSString *,id> *)advertisementData RSSI:(NSNumber *)RSSI {
    RCTLogInfo(@"Found Name: %@", [peripheral name]);
    RCTLogInfo(@"Found Services: %@", [peripheral services]);
    RCTLogInfo(@"Found Id : %@", [peripheral identifier]);
    RCTLogInfo(@"Found UUID String : %@", [[peripheral identifier] UUIDString]);

    NSMutableDictionary *params = [[NSMutableDictionary alloc] initWithCapacity:1];      
    NSMutableArray *paramsUUID = [[NSMutableArray alloc] init];

    // Process service UUIDs
    NSObject *kCBAdvDataServiceUUIDs = [advertisementData objectForKey:@"kCBAdvDataServiceUUIDs"];
    if ([kCBAdvDataServiceUUIDs isKindOfClass:[NSArray class]]) {
        NSArray *uuids = (NSArray *)kCBAdvDataServiceUUIDs;
        for (int j = 0; j < [uuids count]; ++j) {
            NSObject *aValue = [uuids objectAtIndex:j];
            [paramsUUID addObject:[aValue description]];
        }
    }

    RSSI = RSSI && RSSI.intValue < 127 ? RSSI : nil;

    params[@"serviceUuids"] = paramsUUID;
    params[@"rssi"] = RSSI;
    params[@"deviceName"] = [peripheral name];
    params[@"deviceAddress"] = [[peripheral identifier] UUIDString];
    params[@"txPower"] = [advertisementData objectForKey:@"kCBAdvDataTxPowerLevel"];
    
    // Process manufacturer data
    NSDictionary *manufacturerData = [advertisementData objectForKey:@"kCBAdvDataManufacturerData"];
    if (manufacturerData && [manufacturerData isKindOfClass:[NSDictionary class]]) {
        NSNumber *companyIdKey = @(companyId);
        NSData *manufData = manufacturerData[companyIdKey];
        
        if (manufData && [manufData isKindOfClass:[NSData class]]) {
            params[@"companyId"] = companyIdKey;
            
            // Check if this is a multi-packet message
            if ([manufData length] >= 3) {
                const uint8_t *bytes = (const uint8_t *)[manufData bytes];
                uint8_t totalPackets = bytes[0];
                uint8_t packetIndex = bytes[1];
                uint8_t packetId = bytes[2];
                
                // If totalPackets > 1, this is part of a multi-packet message
                if (totalPackets > 1 && totalPackets <= 255 && packetIndex < totalPackets) {
                    NSLog(@"Received packet %d/%d with ID: %d", packetIndex + 1, totalPackets, packetId);
                    
                    // Handle packet reassembly
                    NSString *deviceKey = [NSString stringWithFormat:@"%@_%d", [[peripheral identifier] UUIDString], packetId];
                    PacketBuffer *buffer = packetBuffers[deviceKey];
                    
                    if (!buffer) {
                        buffer = [[PacketBuffer alloc] init];
                        buffer.totalPackets = totalPackets;
                        buffer.packetId = packetId;
                        packetBuffers[deviceKey] = buffer;
                        NSLog(@"Created new packet buffer for device: %@", deviceKey);
                    }
                    
                    // Extract the actual data (skip the 3-byte header)
                    NSData *packetData = [manufData subdataWithRange:NSMakeRange(3, [manufData length] - 3)];
                    
                    // Store this packet
                    buffer.packets[@(packetIndex)] = packetData;
                    NSLog(@"Stored packet %d for device: %@", packetIndex + 1, deviceKey);
                    
                    // Check if we have all packets
                    if ([buffer.packets count] == buffer.totalPackets) {
                        NSLog(@"All packets received, reassembling message");
                        
                        // Calculate total size
                        NSUInteger totalSize = 0;
                        for (NSData *packet in [buffer.packets allValues]) {
                            totalSize += [packet length];
                        }
                        
                        // Reassemble the complete message
                        NSMutableData *completeData = [[NSMutableData alloc] initWithCapacity:totalSize];
                        
                        for (NSUInteger i = 0; i < buffer.totalPackets; i++) {
                            NSData *packet = buffer.packets[@(i)];
                            if (packet) {
                                [completeData appendData:packet];
                            }
                        }
                        
                        NSLog(@"Reassembled complete message, size: %lu bytes", (unsigned long)[completeData length]);
                        
                        // Remove from buffer
                        [packetBuffers removeObjectForKey:deviceKey];
                        
                        // Convert to array for JavaScript
                        NSMutableArray *manufArray = [[NSMutableArray alloc] init];
                        const uint8_t *completeBytes = (const uint8_t *)[completeData bytes];
                        for (NSUInteger i = 0; i < [completeData length]; i++) {
                            [manufArray addObject:@(completeBytes[i])];
                        }
                        
                        // Send the complete reassembled data
                        params[@"manufData"] = manufArray;
                        params[@"isReassembled"] = @YES;
                        params[@"originalPackets"] = @(totalPackets);
                    } else {
                        // Still waiting for more packets
                        NSLog(@"Waiting for more packets: %lu/%lu", (unsigned long)[buffer.packets count], (unsigned long)buffer.totalPackets);
                        // Don't send incomplete data to JavaScript
                        return;
                    }
                } else {
                    // Single packet message
                    NSMutableArray *manufArray = [[NSMutableArray alloc] init];
                    const uint8_t *bytes = (const uint8_t *)[manufData bytes];
                    for (NSUInteger i = 0; i < [manufData length]; i++) {
                        [manufArray addObject:@(bytes[i])];
                    }
                    params[@"manufData"] = manufArray;
                    params[@"isReassembled"] = @NO;
                }
            } else {
                // Not enough data for packet header, treat as single packet
                NSMutableArray *manufArray = [[NSMutableArray alloc] init];
                const uint8_t *bytes = (const uint8_t *)[manufData bytes];
                for (NSUInteger i = 0; i < [manufData length]; i++) {
                    [manufArray addObject:@(bytes[i])];
                }
                params[@"manufData"] = manufArray;
                params[@"isReassembled"] = @NO;
            }
        }
    }
    
    [self sendEventWithName:@"onDeviceFound" body:params];
}

-(void)centralManager:(CBCentralManager *)central didDisconnectPeripheral:(CBPeripheral *)peripheral error:(NSError *)error {
}

- (void)centralManagerDidUpdateState:(CBCentralManager *)central {
    NSLog(@"Check BT status");
    NSMutableDictionary *params =  [[NSMutableDictionary alloc] initWithCapacity:1];      
    switch (central.state) {
        case CBManagerStatePoweredOff:
            params[@"enabled"] = @NO;
            NSLog(@"CoreBluetooth BLE hardware is powered off");
            break;
        case CBManagerStatePoweredOn:
            params[@"enabled"] = @YES;
            NSLog(@"CoreBluetooth BLE hardware is powered on and ready");
            break;
        case CBManagerStateResetting:
            params[@"enabled"] = @NO;
            NSLog(@"CoreBluetooth BLE hardware is resetting");
            break;
        case CBManagerStateUnauthorized:
            params[@"enabled"] = @NO;
            NSLog(@"CoreBluetooth BLE state is unauthorized");
            break;
        case CBManagerStateUnknown:
            params[@"enabled"] = @NO;
            NSLog(@"CoreBluetooth BLE state is unknown");
            break;
        case CBManagerStateUnsupported:
            params[@"enabled"] = @NO;
            NSLog(@"CoreBluetooth BLE hardware is unsupported on this platform");
            break;
        default:
            break;
    }
    [self sendEventWithName:@"onBTStatusChange" body:params];
}

#pragma mark - CBPeripheralManagerDelegate
- (void)peripheralManagerDidUpdateState:(CBPeripheralManager *)peripheral
{
    switch (peripheral.state) {
        case CBManagerStatePoweredOn:
            NSLog(@"%ld, CBPeripheralManagerStatePoweredOn", peripheral.state);
            break;
        case CBManagerStatePoweredOff:
            NSLog(@"%ld, CBPeripheralManagerStatePoweredOff", peripheral.state);
            break;
        case CBManagerStateResetting:
            NSLog(@"%ld, CBPeripheralManagerStateResetting", peripheral.state);
            break;
        case CBManagerStateUnauthorized:
            NSLog(@"%ld, CBPeripheralManagerStateUnauthorized", peripheral.state);
            break;
        case CBManagerStateUnsupported:
            NSLog(@"%ld, CBPeripheralManagerStateUnsupported", peripheral.state);
            break;
        case CBManagerStateUnknown:
            NSLog(@"%ld, CBPeripheralManagerStateUnknown", peripheral.state);
            break;
        default:
            break;
    }
}

- (void)dealloc {
    // Clean up timers and resources
    if (packetCleanupTimer) {
        [packetCleanupTimer invalidate];
        packetCleanupTimer = nil;
    }
    
    // Stop all packet rotations
    NSArray *uids = [packetRotationTimers allKeys];
    for (NSString *uid in uids) {
        [self stopPacketRotationForUID:uid];
    }
    
    // Clear buffers
    [packetBuffers removeAllObjects];
    [packetRotationTimers removeAllObjects];
    
    NSLog(@"BLEAdvertiser deallocated and cleaned up");
}

@end
