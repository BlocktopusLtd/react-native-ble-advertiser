import React, {Component} from 'react';

import {
  Alert,
  SafeAreaView,
  StyleSheet,
  View,
  Text,
  TouchableOpacity,
  FlatList,
  Platform,
  TextInput,
} from 'react-native';

import {NativeEventEmitter, NativeModules} from 'react-native';

import update from 'immutability-helper';
import BLEAdvertiser from 'react-native-ble-advertiser';
import UUIDGenerator from 'react-native-uuid-generator';
import {PermissionsAndroid} from 'react-native';

// Uses the Apple code to pick up iPhones
const APPLE_ID = 0x4c;

BLEAdvertiser.setCompanyId(APPLE_ID);

export async function requestLocationPermission() {
  try {
    if (Platform.OS === 'android') {
      const granted = await PermissionsAndroid.request(
        PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION,
        {
          title: 'BLE Multi-Packet Demo',
          message: 'Demo App access to your location ',
        },
      );
      if (granted === PermissionsAndroid.RESULTS.GRANTED) {
        console.log('[Permissions]', 'Location Permission granted');
      } else {
        console.log('[Permissions]', 'Location Permission denied');
      }
    }

    const blueoothActive = await BLEAdvertiser.getAdapterState()
      .then((result) => {
        console.log('[Bluetooth]', 'Bluetooth Status', result);
        return result === 'STATE_ON';
      })
      .catch((error) => {
        console.log('[Bluetooth]', 'Bluetooth Not Enabled');
        return false;
      });

    if (!blueoothActive) {
      await Alert.alert(
        'Demo requires bluetooth to be enabled',
        'Would you like to enable Bluetooth?',
        [
          {
            text: 'Yes',
            onPress: () => BLEAdvertiser.enableAdapter(),
          },
          {
            text: 'No',
            onPress: () => console.log('Do Not Enable Bluetooth Pressed'),
            style: 'cancel',
          },
        ],
      );
    }
  } catch (err) {
    console.warn(err);
  }
}

class MultiPacketDemo extends Component {
  constructor(props) {
    super(props);
    this.state = {
      uuid: '',
      isLogging: false,
      devicesFound: [],
      maxAdvertisingLength: 31,
      customMessage: 'Hello World! This is a test message that will be split into multiple packets when it exceeds the maximum advertising data length.',
      broadcastStatus: '',
    };
  }

  addDevice(_uuid, _name, _mac, _rssi, _date, _manufData, _isReassembled, _originalPackets) {
    const index = this.state.devicesFound.findIndex(({uuid}) => uuid === _uuid);
    const deviceInfo = {
      uuid: _uuid,
      name: _name,
      mac: _mac,
      rssi: _rssi,
      start: index < 0 ? _date : this.state.devicesFound[index].start,
      end: _date,
      manufData: _manufData,
      isReassembled: _isReassembled,
      originalPackets: _originalPackets,
      dataLength: _manufData ? _manufData.length : 0,
    };

    if (index < 0) {
      this.setState({
        devicesFound: update(this.state.devicesFound, {
          $push: [deviceInfo],
        }),
      });
    } else {
      this.setState({
        devicesFound: update(this.state.devicesFound, {
          [index]: {$set: deviceInfo},
        }),
      });
    }
  }

  async componentDidMount() {
    await requestLocationPermission();
    
    // Get max advertising length
    try {
      const maxLength = await BLEAdvertiser.getMaxAdvertisingDataLength();
      console.log('Max advertising length:', maxLength);
      this.setState({maxAdvertisingLength: maxLength});
    } catch (error) {
      console.log('Error getting max advertising length:', error);
    }

    UUIDGenerator.getRandomUUID((newUid) => {
      this.setState({
        uuid: newUid.slice(0, -2) + '00',
      });
    });
  }

  componentWillUnmount() {
    if (this.state.isLogging) {
      this.stop();
    }
  }

  start() {
    console.log(this.state.uuid, 'Registering Listener');
    const eventEmitter = new NativeEventEmitter(NativeModules.BLEAdvertiser);

    this.onDeviceFound = eventEmitter.addListener('onDeviceFound', (event) => {
      console.log('onDeviceFound', event);
      if (event.serviceUuids) {
        for (let i = 0; i < event.serviceUuids.length; i++) {
          if (event.serviceUuids[i] && event.serviceUuids[i].endsWith('00')) {
            this.addDevice(
              event.serviceUuids[i],
              event.deviceName,
              event.deviceAddress,
              event.rssi,
              new Date(),
              event.manufData,
              event.isReassembled,
              event.originalPackets,
            );
          }
        }
      }
    });

    // Convert custom message to byte array
    const messageBytes = [];
    for (let i = 0; i < this.state.customMessage.length; i++) {
      messageBytes.push(this.state.customMessage.charCodeAt(i));
    }

    console.log(this.state.uuid, 'Starting Advertising with message length:', messageBytes.length);
    this.setState({broadcastStatus: 'Starting broadcast...'});
    
    BLEAdvertiser.broadcast(this.state.uuid, messageBytes, {
      advertiseMode: BLEAdvertiser.ADVERTISE_MODE_BALANCED,
      txPowerLevel: BLEAdvertiser.ADVERTISE_TX_POWER_MEDIUM,
      connectable: false,
      includeDeviceName: false,
      includeTxPowerLevel: false,
    })
      .then((result) => {
        console.log(this.state.uuid, 'Adv Successful', result);
        if (typeof result === 'object' && result.status === 'multi_packet_broadcast_started') {
          this.setState({
            broadcastStatus: `Multi-packet broadcast started: ${result.totalPackets} packets, ID: ${result.packetId}`,
          });
        } else {
          this.setState({broadcastStatus: 'Single packet broadcast started'});
        }
      })
      .catch((error) => {
        console.log(this.state.uuid, 'Adv Error', error);
        this.setState({broadcastStatus: `Broadcast error: ${error}`});
      });

    console.log(this.state.uuid, 'Starting Scanner');
    BLEAdvertiser.scan(null, {
      scanMode: BLEAdvertiser.SCAN_MODE_LOW_LATENCY,
    })
      .then((sucess) => console.log(this.state.uuid, 'Scan Successful', sucess))
      .catch((error) => console.log(this.state.uuid, 'Scan Error', error));

    this.setState({
      isLogging: true,
    });
  }

  stop() {
    console.log(this.state.uuid, 'Removing Listener');
    this.onDeviceFound.remove();
    delete this.onDeviceFound;

    console.log(this.state.uuid, 'Stopping Broadcast');
    BLEAdvertiser.stopBroadcast()
      .then((result) => {
        console.log(this.state.uuid, 'Stop Broadcast Successful', result);
        this.setState({broadcastStatus: 'Broadcast stopped'});
      })
      .catch((error) => {
        console.log(this.state.uuid, 'Stop Broadcast Error', error);
        this.setState({broadcastStatus: `Stop error: ${error}`});
      });

    console.log(this.state.uuid, 'Stopping Scanning');
    BLEAdvertiser.stopScan()
      .then((sucess) => console.log(this.state.uuid, 'Stop Scan Successful', sucess))
      .catch((error) => console.log(this.state.uuid, 'Stop Scan Error', error));

    this.setState({
      isLogging: false,
    });
  }

  short(str) {
    return (
      str.substring(0, 4) +
      ' ... ' +
      str.substring(str.length - 4, str.length)
    ).toUpperCase();
  }

  bytesToString(bytes) {
    if (!bytes) return '';
    return bytes.map(b => String.fromCharCode(b)).join('');
  }

  render() {
    const messageLength = this.state.customMessage.length;
    const willSplit = messageLength > (this.state.maxAdvertisingLength - 24); // Approximate overhead

    return (
      <SafeAreaView>
        <View style={styles.body}>
          <View style={styles.sectionContainer}>
            <Text style={styles.sectionTitle}>BLE Multi-Packet Demo</Text>
            <Text style={styles.sectionDescription}>
              Broadcasting:{' '}
              <Text style={styles.highlight}>
                {this.short(this.state.uuid)}
              </Text>
            </Text>
            <Text style={styles.sectionDescription}>
              Max Advertising Length: {this.state.maxAdvertisingLength} bytes
            </Text>
          </View>

          <View style={styles.sectionContainer}>
            <Text style={styles.inputLabel}>Custom Message ({messageLength} bytes):</Text>
            <TextInput
              style={styles.textInput}
              multiline
              value={this.state.customMessage}
              onChangeText={(text) => this.setState({customMessage: text})}
              placeholder="Enter your message here..."
            />
            <Text style={[styles.splitInfo, {color: willSplit ? '#fd4a4a' : '#665eff'}]}>
              {willSplit ? 'Will be split into multiple packets' : 'Fits in single packet'}
            </Text>
          </View>

          <View style={styles.sectionContainer}>
            {this.state.isLogging ? (
              <TouchableOpacity
                onPress={() => this.stop()}
                style={styles.stopLoggingButtonTouchable}>
                <Text style={styles.stopLoggingButtonText}>Stop</Text>
              </TouchableOpacity>
            ) : (
              <TouchableOpacity
                onPress={() => this.start()}
                style={styles.startLoggingButtonTouchable}>
                <Text style={styles.startLoggingButtonText}>Start</Text>
              </TouchableOpacity>
            )}
            {this.state.broadcastStatus ? (
              <Text style={styles.statusText}>{this.state.broadcastStatus}</Text>
            ) : null}
          </View>

          <View style={styles.sectionContainerFlex}>
            <Text style={styles.sectionTitle}>Devices Found</Text>
            <FlatList
              data={this.state.devicesFound}
              renderItem={({item}) => (
                <View style={styles.deviceItem}>
                  <Text style={styles.deviceHeader}>
                    {this.short(item.uuid)} {item.mac} {item.rssi}dBm
                  </Text>
                  <Text style={styles.deviceInfo}>
                    Data: {item.dataLength} bytes
                    {item.isReassembled ? ` (Reassembled from ${item.originalPackets} packets)` : ' (Single packet)'}
                  </Text>
                  <Text style={styles.deviceMessage}>
                    Message: "{this.bytesToString(item.manufData)}"
                  </Text>
                </View>
              )}
              keyExtractor={(item) => item.uuid + item.end.getTime()}
            />
          </View>

          <View style={styles.sectionContainer}>
            <TouchableOpacity
              onPress={() => this.setState({devicesFound: []})}
              style={styles.startLoggingButtonTouchable}>
              <Text style={styles.startLoggingButtonText}>Clear Devices</Text>
            </TouchableOpacity>
          </View>
        </View>
      </SafeAreaView>
    );
  }
}

const styles = StyleSheet.create({
  body: {
    height: '100%',
  },
  sectionContainerFlex: {
    flex: 1,
    marginTop: 12,
    marginBottom: 12,
    paddingHorizontal: 24,
  },
  sectionContainer: {
    flex: 0,
    marginTop: 12,
    marginBottom: 12,
    paddingHorizontal: 24,
  },
  sectionTitle: {
    fontSize: 24,
    marginBottom: 8,
    fontWeight: '600',
    textAlign: 'center',
  },
  sectionDescription: {
    fontSize: 16,
    fontWeight: '400',
    textAlign: 'center',
    marginBottom: 4,
  },
  highlight: {
    fontWeight: '700',
  },
  inputLabel: {
    fontSize: 16,
    fontWeight: '600',
    marginBottom: 8,
  },
  textInput: {
    borderWidth: 1,
    borderColor: '#ccc',
    borderRadius: 8,
    padding: 12,
    fontSize: 14,
    minHeight: 80,
    textAlignVertical: 'top',
  },
  splitInfo: {
    fontSize: 14,
    fontWeight: '600',
    textAlign: 'center',
    marginTop: 8,
  },
  statusText: {
    fontSize: 12,
    textAlign: 'center',
    marginTop: 8,
    color: '#666',
  },
  startLoggingButtonTouchable: {
    borderRadius: 12,
    backgroundColor: '#665eff',
    height: 52,
    alignSelf: 'center',
    width: 300,
    justifyContent: 'center',
  },
  startLoggingButtonText: {
    fontSize: 14,
    lineHeight: 19,
    letterSpacing: 0,
    textAlign: 'center',
    color: '#ffffff',
  },
  stopLoggingButtonTouchable: {
    borderRadius: 12,
    backgroundColor: '#fd4a4a',
    height: 52,
    alignSelf: 'center',
    width: 300,
    justifyContent: 'center',
  },
  stopLoggingButtonText: {
    fontSize: 14,
    lineHeight: 19,
    letterSpacing: 0,
    textAlign: 'center',
    color: '#ffffff',
  },
  deviceItem: {
    padding: 12,
    borderBottomWidth: 1,
    borderBottomColor: '#eee',
  },
  deviceHeader: {
    fontSize: 16,
    fontWeight: '600',
    marginBottom: 4,
  },
  deviceInfo: {
    fontSize: 14,
    color: '#666',
    marginBottom: 4,
  },
  deviceMessage: {
    fontSize: 12,
    color: '#333',
    fontStyle: 'italic',
  },
});

export default MultiPacketDemo;
