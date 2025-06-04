package com.vitorpamplona.bleavertiser;

import com.facebook.react.uimanager.*;
import com.facebook.react.bridge.*;
import com.facebook.systrace.Systrace;
import com.facebook.systrace.SystraceMessage;
import com.vitorpamplona.bleavertiser.ReactLogger;
import com.facebook.react.ReactInstanceManager;
import com.facebook.react.ReactRootView;
import com.facebook.react.modules.core.DefaultHardwareBackBtnHandler;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.facebook.react.shell.MainReactPackage;
import com.facebook.soloader.SoLoader;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.AdvertisingSet;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.ParcelUuid;
import android.os.Build;
import android.os.Handler;

import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.facebook.react.bridge.WritableMap;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.lang.Thread;
import java.lang.Object;
import java.util.Hashtable;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;

public class BLEAdvertiserModule extends ReactContextBaseJavaModule {

    public static final String TAG = "BleAdvertiserXX0";
    private BluetoothAdapter mBluetoothAdapter;
    
    private static Hashtable<String, BluetoothLeAdvertiser> mAdvertiserList;
    private static Hashtable<String, AdvertiseCallback> mAdvertiserCallbackList;
    private static Hashtable<String, AdvertisingSet> mAdvertisingSetList;
    private static Hashtable<String, AdvertisingSetCallback> mAdvertisingSetCallbackList;
    private static Hashtable<String, Handler> mPacketRotationHandlers;
    private static Hashtable<String, Runnable> mPacketRotationRunnables;
    private static BluetoothLeScanner mScanner;
    private static ScanCallback mScannerCallback;
    private int companyId;
    private Boolean mObservedState;
    private int mCachedMaxAdvertisingLength = 31; // Default to legacy max
    
    // Packet reassembly structures
    private static class PacketBuffer {
        byte totalPackets;
        byte packetId;
        Map<Integer, byte[]> packets = new HashMap<>();
        long firstSeenTime = System.currentTimeMillis();
    }
    
    private static Hashtable<String, PacketBuffer> mPacketBuffers = new Hashtable<>();
    private static final long PACKET_TIMEOUT_MS = 10000; // 10 seconds timeout for incomplete packets
    private static Handler mPacketCleanupHandler = new Handler();
    private static Runnable mPacketCleanupRunnable;

    //Constructor
    public BLEAdvertiserModule(ReactApplicationContext reactContext) {
        super(reactContext);

        mAdvertiserList = new Hashtable<String, BluetoothLeAdvertiser>();
        mAdvertiserCallbackList = new Hashtable<String, AdvertiseCallback>();
        mAdvertisingSetList = new Hashtable<String, AdvertisingSet>();
        mAdvertisingSetCallbackList = new Hashtable<String, AdvertisingSetCallback>();
        mPacketRotationHandlers = new Hashtable<String, Handler>();
        mPacketRotationRunnables = new Hashtable<String, Runnable>();
        mPacketBuffers = new Hashtable<String, PacketBuffer>();

        BluetoothManager bluetoothManager = (BluetoothManager) reactContext.getApplicationContext()
                .getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            mBluetoothAdapter = bluetoothManager.getAdapter();
        } 

        if (mBluetoothAdapter != null) {
            mObservedState = mBluetoothAdapter.isEnabled();
            
            // Test and cache the max advertising length on initialization
            if (mObservedState) {
                testAndCacheMaxAdvertisingLength();
            }
        }

        this.companyId = 0x0000;

        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        reactContext.registerReceiver(mReceiver, filter);
        
        // Start packet cleanup timer
        startPacketCleanupTimer();
    }
    
    private void startPacketCleanupTimer() {
        mPacketCleanupRunnable = new Runnable() {
            @Override
            public void run() {
                cleanupOldPackets();
                mPacketCleanupHandler.postDelayed(this, 5000); // Run every 5 seconds
            }
        };
        mPacketCleanupHandler.postDelayed(mPacketCleanupRunnable, 5000);
    }
    
    private void cleanupOldPackets() {
        long now = System.currentTimeMillis();
        Set<String> keysToRemove = new HashSet<>();
        
        for (Map.Entry<String, PacketBuffer> entry : mPacketBuffers.entrySet()) {
            if (now - entry.getValue().firstSeenTime > PACKET_TIMEOUT_MS) {
                keysToRemove.add(entry.getKey());
                Log.w(TAG, "Removing incomplete packet buffer for device: " + entry.getKey());
            }
        }
        
        for (String key : keysToRemove) {
            mPacketBuffers.remove(key);
        }
    }
    
    private void testAndCacheMaxAdvertisingLength() {
        Log.i(TAG, "Testing device advertising capabilities...");
        
        BluetoothLeAdvertiser advertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            Log.w(TAG, "No advertiser available, using default max: 31");
            mCachedMaxAdvertisingLength = 31;
            return;
        }
        
        boolean extendedSupported = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            extendedSupported = mBluetoothAdapter.isLeExtendedAdvertisingSupported();
        }
        
        // Test extended advertising first if supported
        if (extendedSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.i(TAG, "Testing extended advertising...");
            int extendedMax = binarySearchMaxLength(advertiser, 32, 1650, true);
            
            if (extendedMax > 31) {
                // Extended advertising works, use this as our max
                mCachedMaxAdvertisingLength = extendedMax;
                Log.w(TAG, "Device supports extended advertising with max: " + extendedMax + " bytes");
                return; // No need to test legacy
            }
        }
        
        // If extended didn't work or isn't supported, test legacy
        Log.i(TAG, "Testing legacy advertising...");
        int legacyMax = binarySearchMaxLength(advertiser, 20, 31, false);
        mCachedMaxAdvertisingLength = legacyMax;
        Log.w(TAG, "Device max advertising length: " + legacyMax + " bytes (legacy mode)");
    }
    
    private int binarySearchMaxLength(BluetoothLeAdvertiser advertiser, int minLength, int maxLength, boolean useExtended) {
        int actualMax = minLength;
        
        while (minLength <= maxLength) {
            int testLength = (minLength + maxLength) / 2;
            
            // Create test data of the target length
            byte[] testData = new byte[testLength];
            for (int i = 0; i < testLength; i++) {
                testData[i] = (byte)(i % 256);
            }
            
            // Try to advertise with this length
            boolean success;
            if (useExtended) {
                success = testExtendedAdvertisingLength(advertiser, testData, false, 
                                                       BluetoothDevice.PHY_LE_1M, BluetoothDevice.PHY_LE_1M);
            } else {
                success = testAdvertisingLength(advertiser, testData);
            }
            
            if (success) {
                actualMax = testLength;
                minLength = testLength + 1;
            } else {
                maxLength = testLength - 1;
            }
            
            // Small delay between tests
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                // Ignore
            }
        }
        
        return actualMax;
    }
    
    @Override
    public String getName() {
        return "BLEAdvertiser";
    }

    @Override
    public Map<String, Object> getConstants() {
        final Map<String, Object> constants = new HashMap<>();
        constants.put("ADVERTISE_MODE_BALANCED",        AdvertiseSettings.ADVERTISE_MODE_BALANCED);
        constants.put("ADVERTISE_MODE_LOW_LATENCY",     AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY);
        constants.put("ADVERTISE_MODE_LOW_POWER",       AdvertiseSettings.ADVERTISE_MODE_LOW_POWER);
        constants.put("ADVERTISE_TX_POWER_HIGH",        AdvertiseSettings.ADVERTISE_TX_POWER_HIGH);
        constants.put("ADVERTISE_TX_POWER_LOW",         AdvertiseSettings.ADVERTISE_TX_POWER_LOW);
        constants.put("ADVERTISE_TX_POWER_MEDIUM",      AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM);
        constants.put("ADVERTISE_TX_POWER_ULTRA_LOW",   AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW);

        constants.put("SCAN_MODE_BALANCED",             ScanSettings.SCAN_MODE_BALANCED);
        constants.put("SCAN_MODE_LOW_LATENCY",          ScanSettings.SCAN_MODE_LOW_LATENCY);
        constants.put("SCAN_MODE_LOW_POWER",            ScanSettings.SCAN_MODE_LOW_POWER);
        constants.put("SCAN_MODE_OPPORTUNISTIC",        ScanSettings.SCAN_MODE_OPPORTUNISTIC);
        constants.put("MATCH_MODE_AGGRESSIVE",          ScanSettings.MATCH_MODE_AGGRESSIVE);
        constants.put("MATCH_MODE_STICKY",              ScanSettings.MATCH_MODE_STICKY);
        constants.put("MATCH_NUM_FEW_ADVERTISEMENT",    ScanSettings.MATCH_NUM_FEW_ADVERTISEMENT);
        constants.put("MATCH_NUM_MAX_ADVERTISEMENT",    ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT);
        constants.put("MATCH_NUM_ONE_ADVERTISEMENT",    ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT);

        return constants;
    }

    @ReactMethod
    public void setCompanyId(int companyId) {
        this.companyId = companyId;
    }

    @ReactMethod
    public void getMaxAdvertisingDataLength(Promise promise) {
        // Simply return the cached value
        Log.i(TAG, "Returning cached max advertising length: " + mCachedMaxAdvertisingLength + " bytes");
        promise.resolve(mCachedMaxAdvertisingLength);
    }
    
    @ReactMethod
    public void broadcast(String uid, ReadableArray payload, ReadableMap options, Promise promise) {
        if (mBluetoothAdapter == null) {
            Log.w("BLEAdvertiserModule", "Device does not support Bluetooth. Adapter is Null");
            promise.reject("Device does not support Bluetooth. Adapter is Null");
            return;
        } 
        
        if (companyId == 0x0000) {
            Log.w("BLEAdvertiserModule", "Invalid company id");
            promise.reject("Invalid company id");
            return;
        } 
        
        if (mBluetoothAdapter == null) {
            Log.w("BLEAdvertiserModule", "mBluetoothAdapter unavailable");
            promise.reject("mBluetoothAdapter unavailable");
            return;
        } 

        if (mObservedState != null && !mObservedState) {
            Log.w("BLEAdvertiserModule", "Bluetooth disabled");
            promise.reject("Bluetooth disabled");
            return;
        }

        // Convert payload to byte array first
        byte[] payloadBytes = toByteArray(payload);
        
        // Calculate overhead for BLE packet structure
        // Service UUID (3 bytes structure + 16 bytes UUID) + Manufacturer data structure (3 bytes) + company ID (2 bytes)
        int bleOverhead = 27; // Approximate overhead
        int maxPayloadSize = mCachedMaxAdvertisingLength - bleOverhead;
        
        Log.i(TAG, "Payload size: " + payloadBytes.length + ", max allowed: " + maxPayloadSize);
        
        // Check if we need to split the payload
        if (payloadBytes.length > maxPayloadSize) {
            Log.w(TAG, "Payload exceeds max size, splitting into multiple packets");
            broadcastMultiPacket(uid, payloadBytes, maxPayloadSize, options, promise);
        } else {
            // Original single packet broadcast
            Log.i(TAG, "Payload fits in single packet");
            
            // Check if we should use extended advertising
            boolean useExtendedAdvertising = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && 
                options != null && 
                options.hasKey("useExtendedAdvertising") && 
                options.getBoolean("useExtendedAdvertising")) {
                useExtendedAdvertising = mBluetoothAdapter.isLeExtendedAdvertisingSupported();
            }

            if (useExtendedAdvertising) {
                broadcastExtended(uid, payload, options, promise);
            } else {
                broadcastLegacy(uid, payload, options, promise);
            }
        }
    }
    
    private void broadcastMultiPacket(String uid, byte[] fullPayload, int maxPacketSize, ReadableMap options, Promise promise) {
        try {
            // Calculate number of packets needed
            // Reserve 3 bytes for packet header: [total packets(1)][packet index(1)][packet id(1)]
            int dataPerPacket = maxPacketSize - 3;
            int totalPackets = (int) Math.ceil((double) fullPayload.length / dataPerPacket);
            
            if (totalPackets > 255) {
                promise.reject("Payload too large", "Payload requires more than 255 packets");
                return;
            }
            
            Log.w(TAG, "Splitting payload into " + totalPackets + " packets");
            Log.w(TAG, "Bytes per packet: " + dataPerPacket + " (plus 3 byte header)");
            
            // Generate a random packet ID to group packets together
            byte packetId = (byte)(Math.random() * 256);
            
            // Start packet rotation
            startPacketRotation(uid, fullPayload, totalPackets, dataPerPacket, packetId, options, promise);
            
        } catch (Exception e) {
            Log.e(TAG, "Error in multi-packet broadcast", e);
            promise.reject("Multi-packet broadcast failed", e.getMessage());
        }
    }
    
    private void startPacketRotation(String uid, byte[] fullPayload, int totalPackets, int dataPerPacket, 
                                    byte packetId, ReadableMap options, Promise promise) {
        // Create a timer to rotate through packets
        final Handler handler = new Handler();
        final AtomicInteger currentPacketIndex = new AtomicInteger(0);
        
        // Store the runnable so we can stop it later
        final String rotationKey = uid + "_rotation";
        
        Runnable packetRotation = new Runnable() {
            @Override
            public void run() {
                int packetIndex = currentPacketIndex.get();
                
                if (packetIndex < totalPackets) {
                    // Calculate data range for this packet
                    int startIdx = packetIndex * dataPerPacket;
                    int endIdx = Math.min(startIdx + dataPerPacket, fullPayload.length);
                    int packetDataLength = endIdx - startIdx;
                    
                    // Create packet with header
                    byte[] packet = new byte[packetDataLength + 3];
                    packet[0] = (byte) totalPackets;
                    packet[1] = (byte) packetIndex;
                    packet[2] = packetId;
                    
                    // Copy data
                    System.arraycopy(fullPayload, startIdx, packet, 3, packetDataLength);
                    
                    Log.d(TAG, "Broadcasting packet " + (packetIndex + 1) + "/" + totalPackets + 
                                      ", size: " + packet.length + " bytes");
                    
                    // Convert to ReadableArray
                    WritableArray packetArray = Arguments.createArray();
                    for (byte b : packet) {
                        packetArray.pushInt(b & 0xFF);
                    }
                    
                    // Broadcast this packet
                    boolean useExtendedAdvertising = false;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && 
                        options != null && 
                        options.hasKey("useExtendedAdvertising") && 
                        options.getBoolean("useExtendedAdvertising")) {
                        useExtendedAdvertising = mBluetoothAdapter.isLeExtendedAdvertisingSupported();
                    }
                    
                    if (useExtendedAdvertising) {
                        broadcastExtended(uid, packetArray, options, null);
                    } else {
                        broadcastLegacy(uid, packetArray, options, null);
                    }
                    
                    // Move to next packet
                    currentPacketIndex.incrementAndGet();
                    if (currentPacketIndex.get() >= totalPackets) {
                        currentPacketIndex.set(0); // Loop back to first packet
                    }
                    
                    // Schedule next packet (rotate every 500ms)
                    handler.postDelayed(this, 500);
                } else {
                    // All packets sent, loop back to start
                    currentPacketIndex.set(0);
                    handler.postDelayed(this, 500);
                }
            }
        };
        
        // Store the handler so we can stop it on stopBroadcast
        mPacketRotationHandlers.put(rotationKey, handler);
        mPacketRotationRunnables.put(rotationKey, packetRotation);
        
        // Start the rotation
        handler.post(packetRotation);
        
        // Return success with packet info
        WritableMap result = Arguments.createMap();
        result.putInt("totalPackets", totalPackets);
        result.putInt("packetId", packetId & 0xFF);
        result.putInt("dataPerPacket", dataPerPacket);
        result.putString("status", "multi_packet_broadcast_started");
        promise.resolve(result);
    }
    
    private boolean testAdvertisingLength(BluetoothLeAdvertiser advertiser, byte[] testData) {
        final Object lock = new Object();
        final boolean[] result = {false};
        final boolean[] callbackReceived = {false};
        
        try {
            // Build advertise settings - EXACTLY matching your usage
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED) // You use BALANCED
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(false)
                .setTimeout(0)
                .build();
            
            // Build advertise data with our test payload
            AdvertiseData.Builder dataBuilder = new AdvertiseData.Builder();
            dataBuilder.setIncludeDeviceName(false);
            dataBuilder.setIncludeTxPowerLevel(false);
            
            // Add manufacturer data with your company ID
            dataBuilder.addManufacturerData(companyId, testData);
            
            // Add service UUID just like in your actual usage
            dataBuilder.addServiceUuid(ParcelUuid.fromString("00001234-0000-1000-8000-00805f9b34fb"));
            
            AdvertiseData data = dataBuilder.build();
            
            // Test advertise with callback
            AdvertiseCallback testCallback = new AdvertiseCallback() {
                @Override
                public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                    synchronized (lock) {
                        result[0] = true;
                        callbackReceived[0] = true;
                        lock.notify();
                    }
                }
                
                @Override
                public void onStartFailure(int errorCode) {
                    synchronized (lock) {
                        result[0] = false;
                        callbackReceived[0] = true;
                        String errorMsg = "Unknown error";
                        switch (errorCode) {
                            case ADVERTISE_FAILED_DATA_TOO_LARGE:
                                errorMsg = "Data too large";
                                break;
                            case ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                                errorMsg = "Too many advertisers";
                                break;
                            case ADVERTISE_FAILED_ALREADY_STARTED:
                                errorMsg = "Already started";
                                break;
                            case ADVERTISE_FAILED_INTERNAL_ERROR:
                                errorMsg = "Internal error";
                                break;
                            case ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                                errorMsg = "Feature unsupported";
                                break;
                        }
                        Log.d(TAG, "Advertising failed for length " + testData.length + ": " + errorMsg);
                        lock.notify();
                    }
                }
            };
            
            // Use legacy advertising
            advertiser.startAdvertising(settings, data, testCallback);
            
            // Wait for callback with timeout
            synchronized (lock) {
                if (!callbackReceived[0]) {
                    try {
                        lock.wait(500); // 500ms timeout
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }
            }
            
            // Stop advertising if it started
            if (result[0]) {
                advertiser.stopAdvertising(testCallback);
            }
            
            return result[0];
            
        } catch (Exception e) {
            Log.e(TAG, "Error testing advertising length " + testData.length, e);
            return false;
        }
    }
    
    private boolean testExtendedAdvertisingLength(BluetoothLeAdvertiser advertiser, byte[] testData, 
                                                  boolean includeScanResponse, int primaryPhy, int secondaryPhy) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false;
        }
        
        final Object lock = new Object();
        final boolean[] result = {false};
        final boolean[] callbackReceived = {false};
        
        try {
            ExtendedAdvertiseCallback callback = new ExtendedAdvertiseCallback("test", null) {
                @Override
                public void onAdvertisingSetStarted(AdvertisingSet advertisingSet, int txPower, int status) {
                    synchronized (lock) {
                        result[0] = (status == AdvertisingSetCallback.ADVERTISE_SUCCESS);
                        callbackReceived[0] = true;
                        if (!result[0]) {
                            Log.d(TAG, "Extended advertising failed, status: " + status);
                        }
                        lock.notify();
                    }
                }
            };
            
            // Build parameters for extended advertising
            AdvertisingSetParameters.Builder paramsBuilder = new AdvertisingSetParameters.Builder();
            paramsBuilder.setInterval(AdvertisingSetParameters.INTERVAL_MEDIUM);
            paramsBuilder.setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM);
            paramsBuilder.setConnectable(false);
            paramsBuilder.setLegacyMode(false); // Enable extended advertising
            paramsBuilder.setPrimaryPhy(primaryPhy);
            paramsBuilder.setSecondaryPhy(secondaryPhy);
            
            if (includeScanResponse) {
                paramsBuilder.setScannable(true);
            }
            
            AdvertisingSetParameters params = paramsBuilder.build();
            
            // Build advertise data
            AdvertiseData.Builder dataBuilder = new AdvertiseData.Builder();
            dataBuilder.setIncludeDeviceName(false);
            dataBuilder.setIncludeTxPowerLevel(false);
            dataBuilder.addManufacturerData(companyId, testData);
            dataBuilder.addServiceUuid(ParcelUuid.fromString("00001234-0000-1000-8000-00805f9b34fb"));
            AdvertiseData data = dataBuilder.build();
            
            // Build scan response if needed
            AdvertiseData scanResponse = null;
            if (includeScanResponse) {
                // Create scan response with additional data
                byte[] scanRespData = new byte[Math.min(testData.length, 1650)];
                for (int i = 0; i < scanRespData.length; i++) {
                    scanRespData[i] = (byte)((i + 200) % 256);
                }
                
                AdvertiseData.Builder scanRespBuilder = new AdvertiseData.Builder();
                scanRespBuilder.setIncludeDeviceName(false);
                scanRespBuilder.setIncludeTxPowerLevel(false);
                scanRespBuilder.addManufacturerData(companyId, scanRespData);
                scanResponse = scanRespBuilder.build();
            }
            
            // Start extended advertising
            advertiser.startAdvertisingSet(params, data, scanResponse, null, null, callback);
            
            synchronized (lock) {
                if (!callbackReceived[0]) {
                    try {
                        lock.wait(500);
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }
            }
            
            if (result[0]) {
                advertiser.stopAdvertisingSet(callback);
            }
            
            return result[0];
            
        } catch (Exception e) {
            Log.e(TAG, "Error testing extended advertising", e);
            return false;
        }
    }

    @ReactMethod
    public void checkBluetooth5Support(Promise promise) {
        if (mBluetoothAdapter == null) {
            promise.resolve(false);
            return;
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            boolean isLeExtendedAdvertisingSupported = mBluetoothAdapter.isLeExtendedAdvertisingSupported();
            boolean isLeCodedPhySupported = mBluetoothAdapter.isLeCodedPhySupported();
            boolean isLe2MPhySupported = mBluetoothAdapter.isLe2MPhySupported();
            
            WritableMap result = Arguments.createMap();
            result.putBoolean("extendedAdvertising", isLeExtendedAdvertisingSupported);
            result.putBoolean("codedPhy", isLeCodedPhySupported);
            result.putBoolean("le2MPhy", isLe2MPhySupported);
            result.putBoolean("supported", isLeExtendedAdvertisingSupported);
            promise.resolve(result);
        } else {
            WritableMap result = Arguments.createMap();
            result.putBoolean("supported", false);
            result.putString("reason", "Android version too low (requires Android 8.0+)");
            promise.resolve(result);
        }
    }

    private void broadcastExtended(String uid, ReadableArray payload, ReadableMap options, Promise promise) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            if (promise != null) promise.reject("Extended advertising requires Android 8.0+");
            return;
        }

        AdvertisingSetCallback existingCallback = mAdvertisingSetCallbackList.get(uid);
        if (existingCallback != null) {
            // Stop existing advertising set
            mBluetoothAdapter.getBluetoothLeAdvertiser().stopAdvertisingSet(existingCallback);
            mAdvertisingSetCallbackList.remove(uid);
            mAdvertisingSetList.remove(uid);
        }

        ExtendedAdvertiseCallback callback = new ExtendedAdvertiseCallback(uid, promise);

        // Build parameters for extended advertising
        AdvertisingSetParameters.Builder paramsBuilder = new AdvertisingSetParameters.Builder();
        
        // Set advertising mode
        if (options != null && options.hasKey("advertiseMode")) {
            paramsBuilder.setInterval(getIntervalFromMode(options.getInt("advertiseMode")));
        }

        // Set TX power
        if (options != null && options.hasKey("txPowerLevel")) {
            paramsBuilder.setTxPowerLevel(getTxPowerFromOption(options.getInt("txPowerLevel")));
        }

        // Set connectable
        if (options != null && options.hasKey("connectable")) {
            paramsBuilder.setConnectable(options.getBoolean("connectable"));
        }

        // Enable extended advertising
        paramsBuilder.setLegacyMode(false);
        
        // Use primary and secondary PHY for extended range
        if (options != null && options.hasKey("useLongRange") && options.getBoolean("useLongRange")) {
            paramsBuilder.setPrimaryPhy(BluetoothDevice.PHY_LE_CODED);
            paramsBuilder.setSecondaryPhy(BluetoothDevice.PHY_LE_CODED);
        }

        AdvertisingSetParameters params = paramsBuilder.build();

        // Build advertising data
        AdvertiseData data = buildAdvertiseData(ParcelUuid.fromString(uid), toByteArray(payload), options);

        // Start extended advertising
        mBluetoothAdapter.getBluetoothLeAdvertiser().startAdvertisingSet(
            params,
            data,
            null, // scan response
            null, // periodic parameters
            null, // periodic data
            callback
        );

        mAdvertisingSetCallbackList.put(uid, callback);
    }

    private void broadcastLegacy(String uid, ReadableArray payload, ReadableMap options, Promise promise) {
        BluetoothLeAdvertiser tempAdvertiser;
        AdvertiseCallback tempCallback;

        if (mAdvertiserList.containsKey(uid)) {
            tempAdvertiser = mAdvertiserList.remove(uid);
            tempCallback = mAdvertiserCallbackList.remove(uid);

            tempAdvertiser.stopAdvertising(tempCallback);
        } else {
            tempAdvertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
            tempCallback = new BLEAdvertiserModule.SimpleAdvertiseCallback(promise);
        }
         
        if (tempAdvertiser == null) {
            Log.w("BLEAdvertiserModule", "Advertiser Not Available unavailable");
            if (promise != null) promise.reject("Advertiser unavailable on this device");
            return;
        }
        
        AdvertiseSettings settings = buildAdvertiseSettings(options);
        AdvertiseData data = buildAdvertiseData(ParcelUuid.fromString(uid), toByteArray(payload), options);

        tempAdvertiser.startAdvertising(settings, data, tempCallback);

        mAdvertiserList.put(uid, tempAdvertiser);
        mAdvertiserCallbackList.put(uid, tempCallback);
    }

    private int getIntervalFromMode(int mode) {
        switch (mode) {
            case AdvertiseSettings.ADVERTISE_MODE_LOW_POWER:
                return AdvertisingSetParameters.INTERVAL_HIGH;
            case AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY:
                return AdvertisingSetParameters.INTERVAL_LOW;
            default:
                return AdvertisingSetParameters.INTERVAL_MEDIUM;
        }
    }

    private int getTxPowerFromOption(int option) {
        switch (option) {
            case AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW:
                return AdvertisingSetParameters.TX_POWER_ULTRA_LOW;
            case AdvertiseSettings.ADVERTISE_TX_POWER_LOW:
                return AdvertisingSetParameters.TX_POWER_LOW;
            case AdvertiseSettings.ADVERTISE_TX_POWER_HIGH:
                return AdvertisingSetParameters.TX_POWER_HIGH;
            default:
                return AdvertisingSetParameters.TX_POWER_MEDIUM;
        }
    }

    private byte[] toByteArray(ReadableArray payload) {
        byte[] temp = new byte[payload.size()];
        for (int i = 0; i < payload.size(); i++) {
            temp[i] = (byte)payload.getInt(i);
        }
        return temp;
    }

    private WritableArray toByteArray(byte[] payload) {
        WritableArray array = Arguments.createArray();
        for (byte data : payload) {
            array.pushInt(data);
        }
        return array;
    }

   @ReactMethod
    public void stopBroadcast(final Promise promise) {
        Log.w("BLEAdvertiserModule", "Stop Broadcast call");

        if (mBluetoothAdapter == null) {
            Log.w("BLEAdvertiserModule", "mBluetoothAdapter unavailable");
            promise.reject("mBluetoothAdapter unavailable");
            return;
        } 

        if (mObservedState != null && !mObservedState) {
            Log.w("BLEAdvertiserModule", "Bluetooth disabled");
            promise.reject("Bluetooth disabled");
            return;
        }

        WritableArray promiseArray=Arguments.createArray();

        // Stop legacy advertising
        Set<String> keys = mAdvertiserList.keySet();
        for (String key : keys) {
            BluetoothLeAdvertiser tempAdvertiser = mAdvertiserList.remove(key);
            AdvertiseCallback tempCallback = mAdvertiserCallbackList.remove(key);
            if (tempAdvertiser != null) {
                tempAdvertiser.stopAdvertising(tempCallback);
                promiseArray.pushString(key);
            }
        }

        // Stop extended advertising
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Set<String> extKeys = mAdvertisingSetCallbackList.keySet();
            for (String key : extKeys) {
                AdvertisingSetCallback callback = mAdvertisingSetCallbackList.remove(key);
                if (callback != null) {
                    mBluetoothAdapter.getBluetoothLeAdvertiser().stopAdvertisingSet(callback);
                    promiseArray.pushString(key);
                }
            }
        }
        
        // Stop packet rotations
        Set<String> rotationKeys = new HashSet<>(mPacketRotationHandlers.keySet());
        for (String key : rotationKeys) {
            Handler handler = mPacketRotationHandlers.remove(key);
            Runnable runnable = mPacketRotationRunnables.remove(key);
            if (handler != null && runnable != null) {
                handler.removeCallbacks(runnable);
                Log.i(TAG, "Stopped packet rotation for: " + key);
            }
        }

        promise.resolve(promiseArray);
    }

    @ReactMethod
    public void scanByService(String uid, ReadableMap options, Promise promise) {
        scan(uid, null, options, promise);
    }

    @ReactMethod
    public void scan(ReadableArray manufacturerPayload, ReadableMap options, Promise promise) {
        scan(null, manufacturerPayload, options, promise);
    }

    public void scan(String uid, ReadableArray manufacturerPayload, ReadableMap options, Promise promise) {
        if (mBluetoothAdapter == null) {
            promise.reject("Device does not support Bluetooth. Adapter is Null");
            return;
        }

        if (mObservedState != null && !mObservedState) {
            Log.w("BLEAdvertiserModule", "Bluetooth disabled");
            promise.reject("Bluetooth disabled");
            return;
        }

        if (mScannerCallback == null) {
            // Cannot change. 
            mScannerCallback = new SimpleScanCallback();
        } 
        
        if (mScanner == null) {
            mScanner = mBluetoothAdapter.getBluetoothLeScanner();
        } else {
            // was running. Needs to stop first. 
            mScanner.stopScan(mScannerCallback);
        }

        if (mScanner == null) {
            Log.w("BLEAdvertiserModule", "Scanner Not Available unavailable");
            promise.reject("Scanner unavailable on this device");
            return;
        } 

        ScanSettings scanSettings = buildScanSettings(options);

        // Initialize filters list properly
        List<ScanFilter> filters = new ArrayList<>();
        
        // Only set filters to null if we don't want any filtering at all
        boolean shouldUseFilters = (manufacturerPayload != null || uid != null);
        
        if (shouldUseFilters) {
            if (manufacturerPayload != null) {
                filters.add(new ScanFilter.Builder().setManufacturerData(companyId, toByteArray(manufacturerPayload)).build());
            }
            if (uid != null) {
                filters.add(new ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(uid)).build());
            }
        } else {
            // If no filters needed, pass null to scan for all devices
            filters = null;
        }
        
        mScanner.startScan(filters, scanSettings, mScannerCallback);
        promise.resolve("Scanner started");
    }

    @ReactMethod
    public void addListener(String eventName) {

    }

    @ReactMethod
    public void removeListeners(Integer count) {

    }

    @ReactMethod
	public void stopScan(Promise promise) {
        if (mBluetoothAdapter == null) {
            promise.reject("Device does not support Bluetooth. Adapter is Null");
            return;
        } 

        if (mObservedState != null && !mObservedState) {
            Log.w("BLEAdvertiserModule", "Bluetooth disabled");
            promise.reject("Bluetooth disabled");
            return;
        }

        if (mScanner != null) {
            mScanner.stopScan(mScannerCallback);
            mScanner = null;
            promise.resolve("Scanner stopped");
        } else {
            promise.resolve("Scanner not started");
        }
    }

    private ScanSettings buildScanSettings(ReadableMap options) {
        ScanSettings.Builder scanSettingsBuilder = new ScanSettings.Builder();

        if (options != null && options.hasKey("scanMode")) {
            scanSettingsBuilder.setScanMode(options.getInt("scanMode"));
        } 

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (options != null && options.hasKey("numberOfMatches")) {
                scanSettingsBuilder.setNumOfMatches(options.getInt("numberOfMatches"));
            }
            if (options != null && options.hasKey("matchMode")) {
                scanSettingsBuilder.setMatchMode(options.getInt("matchMode"));
            }
        }

        if (options != null && options.hasKey("reportDelay")) {
            scanSettingsBuilder.setReportDelay(options.getInt("reportDelay"));
        }

        // Enable extended scanning if available
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (options != null && options.hasKey("useLongRange") && options.getBoolean("useLongRange")) {
                scanSettingsBuilder.setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED);
            }
            if (options != null && options.hasKey("useExtendedScan") && options.getBoolean("useExtendedScan")) {
                scanSettingsBuilder.setLegacy(false);
            }
        }

        return scanSettingsBuilder.build();
    }

    private class SimpleScanCallback extends ScanCallback {
		@Override
		public void onScanResult(int callbackType, ScanResult result) {
            Log.w("BLEAdvertiserModule", "Scanned: " + result.toString());

            WritableMap params = Arguments.createMap();
            WritableArray paramsUUID = Arguments.createArray();

            if (result.getScanRecord().getServiceUuids()!=null) {
                for (ParcelUuid uuid : result.getScanRecord().getServiceUuids()) {
                    paramsUUID.pushString(uuid.toString());
                }
            }

            params.putArray("serviceUuids", paramsUUID);
            params.putInt("rssi", result.getRssi());
            
            if (result.getScanRecord() != null) {
                params.putInt("txPower", result.getScanRecord().getTxPowerLevel());
                params.putString("deviceName", result.getScanRecord().getDeviceName());
                params.putInt("advFlags", result.getScanRecord().getAdvertiseFlags());
                
                // Get manufacturer data
                byte[] manufData = result.getScanRecord().getManufacturerSpecificData(companyId);
                if (manufData != null) {
                    params.putInt("companyId", companyId);
                    
                    // Check if this is a multi-packet message
                    if (manufData.length >= 3) {
                        byte totalPackets = manufData[0];
                        byte packetIndex = manufData[1];
                        byte packetId = manufData[2];
                        
                        // If totalPackets > 1, this is part of a multi-packet message
                        if (totalPackets > 1 && totalPackets <= 255 && packetIndex < totalPackets) {
                            Log.i(TAG, "Received packet " + (packetIndex + 1) + "/" + totalPackets + 
                                             " with ID: " + (packetId & 0xFF));
                            
                            // Handle packet reassembly
                            String deviceKey = result.getDevice().getAddress() + "_" + (packetId & 0xFF);
                            PacketBuffer buffer = mPacketBuffers.get(deviceKey);
                            
                            if (buffer == null) {
                                buffer = new PacketBuffer();
                                buffer.totalPackets = totalPackets;
                                buffer.packetId = packetId;
                                mPacketBuffers.put(deviceKey, buffer);
                                Log.i(TAG, "Created new packet buffer for device: " + deviceKey);
                            }
                            
                            // Extract the actual data (skip the 3-byte header)
                            byte[] packetData = new byte[manufData.length - 3];
                            System.arraycopy(manufData, 3, packetData, 0, packetData.length);
                            
                            // Store this packet
                            buffer.packets.put((int)packetIndex, packetData);
                            Log.i(TAG, "Stored packet " + (packetIndex + 1) + " for device: " + deviceKey);
                            
                            // Check if we have all packets
                            if (buffer.packets.size() == buffer.totalPackets) {
                                Log.i(TAG, "All packets received, reassembling message");
                                
                                // Calculate total size
                                int totalSize = 0;
                                for (byte[] packet : buffer.packets.values()) {
                                    totalSize += packet.length;
                                }
                                
                                // Reassemble the complete message
                                byte[] completeData = new byte[totalSize];
                                int offset = 0;
                                
                                for (int i = 0; i < buffer.totalPackets; i++) {
                                    byte[] packet = buffer.packets.get(i);
                                    if (packet != null) {
                                        System.arraycopy(packet, 0, completeData, offset, packet.length);
                                        offset += packet.length;
                                    }
                                }
                                
                                Log.w(TAG, "Reassembled complete message, size: " + completeData.length + " bytes");
                                
                                // Remove from buffer
                                mPacketBuffers.remove(deviceKey);
                                
                                // Send the complete reassembled data
                                params.putArray("manufData", toByteArray(completeData));
                                params.putBoolean("isReassembled", true);
                                params.putInt("originalPackets", totalPackets);
                            } else {
                                // Still waiting for more packets
                                Log.i(TAG, "Waiting for more packets: " + buffer.packets.size() + "/" + buffer.totalPackets);
                                // Don't send incomplete data to JavaScript
                                return;
                            }
                        } else {
                            // Single packet message
                            params.putArray("manufData", toByteArray(manufData));
                            params.putBoolean("isReassembled", false);
                        }
                    } else {
                        // Not enough data for packet header, treat as single packet
                        params.putArray("manufData", toByteArray(manufData));
                        params.putBoolean("isReassembled", false);
                    }
                }
                
                // Check if this is an extended advertisement
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    params.putBoolean("isLegacy", result.isLegacy());
                    params.putBoolean("isConnectable", result.isConnectable());
                    params.putInt("dataStatus", result.getDataStatus());
                    params.putInt("primaryPhy", result.getPrimaryPhy());
                    params.putInt("secondaryPhy", result.getSecondaryPhy());
                }
            }
            
            if (result.getDevice() != null) {
                params.putString("deviceAddress", result.getDevice().getAddress());
            }

            sendEvent("onDeviceFound", params);
		}

		@Override
		public void onBatchScanResults(final List<ScanResult> results) {

		}

		@Override
		public void onScanFailed(final int errorCode) {
            /*
           switch (errorCode) {
                case SCAN_FAILED_ALREADY_STARTED:
                    promise.reject("Fails to start scan as BLE scan with the same settings is already started by the app."); break;
                case SCAN_FAILED_APPLICATION_REGISTRATION_FAILED:
                    promise.reject("Fails to start scan as app cannot be registered."); break;
                case SCAN_FAILED_FEATURE_UNSUPPORTED:
                    promise.reject("Fails to start power optimized scan as this feature is not supported."); break;
                case SCAN_FAILED_INTERNAL_ERROR:
                    promise.reject("Fails to start scan due an internal error"); break;
                default: 
                    promise.reject("Scan failed: " + errorCode);
            }
            promise.reject("Scan failed: Should not be here. ");*/
		}
	};

    @ReactMethod
    public void enableAdapter() {
        if (mBluetoothAdapter == null) {
            return;
        }

        if (mBluetoothAdapter.getState() != BluetoothAdapter.STATE_ON && mBluetoothAdapter.getState() != BluetoothAdapter.STATE_TURNING_ON) {
            mBluetoothAdapter.enable();
        }
    }

    @ReactMethod
    public void disableAdapter() {
        if (mBluetoothAdapter == null) {
            return;
        }

        if (mBluetoothAdapter.getState() != BluetoothAdapter.STATE_OFF && mBluetoothAdapter.getState() != BluetoothAdapter.STATE_TURNING_OFF) {
            mBluetoothAdapter.disable();
        }
    }

    @ReactMethod
    public void getAdapterState(Promise promise) {
        if (mBluetoothAdapter == null) {
            Log.w("BLEAdvertiserModule", "Device does not support Bluetooth. Adapter is Null");
            promise.reject("Device does not support Bluetooth. Adapter is Null");
            return;
        }

        Log.d(TAG, "GetAdapter State" + String.valueOf(mBluetoothAdapter.getState()));

        switch (mBluetoothAdapter.getState()) {
            case BluetoothAdapter.STATE_OFF:
                promise.resolve("STATE_OFF"); break;
            case BluetoothAdapter.STATE_TURNING_ON:
                promise.resolve("STATE_TURNING_ON"); break;
            case BluetoothAdapter.STATE_ON:
                promise.resolve("STATE_ON"); break;
            case BluetoothAdapter.STATE_TURNING_OFF:
                promise.resolve("STATE_TURNING_OFF"); break;
        }

        promise.resolve(String.valueOf(mBluetoothAdapter.getState()));
    }

    @ReactMethod
    public void isActive(Promise promise) {
        if (mBluetoothAdapter == null) {
            Log.w("BLEAdvertiserModule", "Device does not support Bluetooth. Adapter is Null");
            promise.resolve(false);
            return;
        }

        Log.d(TAG, "GetAdapter State" + String.valueOf(mBluetoothAdapter.getState()));
        promise.resolve(mBluetoothAdapter.getState() == BluetoothAdapter.STATE_ON); 
    }

    private AdvertiseSettings buildAdvertiseSettings(ReadableMap options) {
        AdvertiseSettings.Builder settingsBuilder = new AdvertiseSettings.Builder();

        if (options != null && options.hasKey("advertiseMode")) {
            settingsBuilder.setAdvertiseMode(options.getInt("advertiseMode"));
        }

        if (options != null && options.hasKey("txPowerLevel")) {
            settingsBuilder.setTxPowerLevel(options.getInt("txPowerLevel"));
        }

        if (options != null && options.hasKey("connectable")) {
            settingsBuilder.setConnectable(options.getBoolean("connectable"));
        }

        return settingsBuilder.build();
    }

    private AdvertiseData buildAdvertiseData(ParcelUuid uuid, byte[] payload, ReadableMap options) {
        AdvertiseData.Builder dataBuilder = new AdvertiseData.Builder();

        if (options != null && options.hasKey("includeDeviceName")) 
            dataBuilder.setIncludeDeviceName(options.getBoolean("includeDeviceName"));
        
         if (options != null && options.hasKey("includeTxPowerLevel")) 
            dataBuilder.setIncludeTxPowerLevel(options.getBoolean("includeTxPowerLevel"));
        
        dataBuilder.addManufacturerData(companyId, payload);
        dataBuilder.addServiceUuid(uuid);
        return dataBuilder.build();
    }

    private class SimpleAdvertiseCallback extends AdvertiseCallback {
        Promise promise;

        public SimpleAdvertiseCallback () {
        }

        public SimpleAdvertiseCallback (Promise promise) {
            this.promise = promise;
        }

        @Override
        public void onStartFailure(int errorCode) {
            super.onStartFailure(errorCode);
            Log.i(TAG, "Advertising failed with code "+ errorCode);

            if (promise == null) return;

            switch (errorCode) {
                case ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                    promise.reject("This feature is not supported on this platform."); break;
                case ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                    promise.reject("Failed to start advertising because no advertising instance is available."); break;
                case ADVERTISE_FAILED_ALREADY_STARTED:
                    promise.reject("Failed to start advertising as the advertising is already started."); break;
                case ADVERTISE_FAILED_DATA_TOO_LARGE:
                    promise.reject("Failed to start advertising as the advertise data to be broadcasted is larger than 31 bytes."); break;
                case ADVERTISE_FAILED_INTERNAL_ERROR:
                    promise.reject("Operation failed due to an internal error."); break;
            }
        }

        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            super.onStartSuccess(settingsInEffect);
            Log.i(TAG, "Advertising successful");

            if (promise == null) return;
            promise.resolve(settingsInEffect.toString());
        }
    }

    private class ExtendedAdvertiseCallback extends AdvertisingSetCallback {
        String uid;
        Promise promise;

        public ExtendedAdvertiseCallback(String uid, Promise promise) {
            this.uid = uid;
            this.promise = promise;
        }

        @Override
        public void onAdvertisingSetStarted(AdvertisingSet advertisingSet, int txPower, int status) {
            super.onAdvertisingSetStarted(advertisingSet, txPower, status);
            
            if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                Log.i(TAG, "Extended advertising successful");
                mAdvertisingSetList.put(uid, advertisingSet);
                
                if (promise != null) {
                    WritableMap result = Arguments.createMap();
                    result.putBoolean("extended", true);
                    result.putInt("txPower", txPower);
                    result.putString("status", "success");
                    promise.resolve(result);
                }
            } else {
                Log.i(TAG, "Extended advertising failed with status " + status);
                
                if (promise != null) {
                    String errorMessage = "Unknown error";
                    switch (status) {
                        case AdvertisingSetCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                            errorMessage = "Extended advertising is not supported on this device."; break;
                        case AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                            errorMessage = "Failed to start advertising because no advertising instance is available."; break;
                        case AdvertisingSetCallback.ADVERTISE_FAILED_ALREADY_STARTED:
                            errorMessage = "Failed to start advertising as the advertising is already started."; break;
                        case AdvertisingSetCallback.ADVERTISE_FAILED_DATA_TOO_LARGE:
                            errorMessage = "Failed to start advertising as the advertise data is too large."; break;
                        case AdvertisingSetCallback.ADVERTISE_FAILED_INTERNAL_ERROR:
                            errorMessage = "Operation failed due to an internal error."; break;
                    }
                    promise.reject(errorMessage);
                }
            }
        }

        @Override
        public void onAdvertisingSetStopped(AdvertisingSet advertisingSet) {
            super.onAdvertisingSetStopped(advertisingSet);
            Log.i(TAG, "Extended advertising stopped");
            mAdvertisingSetList.remove(uid);
        }

        @Override
        public void onAdvertisingEnabled(AdvertisingSet advertisingSet, boolean enable, int status) {
            super.onAdvertisingEnabled(advertisingSet, enable, status);
            Log.i(TAG, "Extended advertising enabled: " + enable + ", status: " + status);
        }

        @Override
        public void onAdvertisingDataSet(AdvertisingSet advertisingSet, int status) {
            super.onAdvertisingDataSet(advertisingSet, status);
            Log.i(TAG, "Extended advertising data set, status: " + status);
        }

        @Override
        public void onAdvertisingParametersUpdated(AdvertisingSet advertisingSet, int txPower, int status) {
            super.onAdvertisingParametersUpdated(advertisingSet, txPower, status);
            Log.i(TAG, "Extended advertising parameters updated, txPower: " + txPower + ", status: " + status);
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                final int prevState = intent.getIntExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, BluetoothAdapter.ERROR);
                
                Log.d(TAG, String.valueOf(state));
                switch (state) {
                case BluetoothAdapter.STATE_OFF:
                    mObservedState = false;
                    break;
                case BluetoothAdapter.STATE_TURNING_OFF:
                    mObservedState = false;
                    break;
                case BluetoothAdapter.STATE_ON:
                    mObservedState = true;
                    break;
                case BluetoothAdapter.STATE_TURNING_ON:
                    mObservedState = true;
                    break;
                }

                // Only send enabled when fully ready. Turning on and Turning OFF are seen as disabled. 
                if (state == BluetoothAdapter.STATE_ON && prevState != BluetoothAdapter.STATE_ON) {
                    WritableMap params = Arguments.createMap();
                    params.putBoolean("enabled", true);
                    sendEvent("onBTStatusChange", params);
                    
                    // Re-test advertising capabilities when Bluetooth is turned on
                    if (mBluetoothAdapter != null) {
                        testAndCacheMaxAdvertisingLength();
                    }
                } else if (state != BluetoothAdapter.STATE_ON && prevState == BluetoothAdapter.STATE_ON ) {
                    WritableMap params = Arguments.createMap();
                    params.putBoolean("enabled", false);
                    sendEvent("onBTStatusChange", params);
                }
            }
        }
    };

    private void sendEvent(String eventName, WritableMap params) {
        getReactApplicationContext()
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
            .emit(eventName, params);
    }

    // @Override
    // public void onCreate() {
    //     super.onCreate();
    //     // Register for broadcasts on BluetoothAdapter state change
    //     IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
    //     registerReceiver(mReceiver, filter);
    // }

    // @Override
    // public void onDestroy() {
    //     super.onDestroy();
    //     unregisterReceiver(mReceiver);
    // }
}