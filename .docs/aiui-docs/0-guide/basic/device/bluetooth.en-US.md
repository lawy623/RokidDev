# Bluetooth

Bluetooth capabilities allow your agent to discover, connect to, and read from or write to nearby BLE (Bluetooth Low Energy) devices.

If your app needs to connect to heart-rate straps, controllers, peripheral sensors, or other BLE hardware, this is a good place to start.

## Use Cases

- Discover nearby BLE devices
- Connect to peripherals and read from or write to GATT services and characteristics
- Subscribe to device notifications and continuously receive status updates

## Basic Usage

Use `navigator.bluetooth` to request a device, then connect to the device and read its services:

```javascript
const device = await navigator.bluetooth.requestDevice({
  filters: [{ services: ['0000180d-0000-1000-8000-00805f9b34fb'] }],
  optionalServices: ['0000180f-0000-1000-8000-00805f9b34fb'],
});

const server = await device.gatt.connect();
const service = await server.getPrimaryService('0000180d-0000-1000-8000-00805f9b34fb');
const characteristic = await service.getCharacteristic('00002a37-0000-1000-8000-00805f9b34fb');

await characteristic.startNotifications();
characteristic.addEventListener('characteristicvaluechanged', () => {
  console.log(characteristic.value);
});
```

## Scan for Devices

If you need to keep discovering nearby devices, you can use scan mode:

```javascript
const scan = await navigator.bluetooth.scanDevices({
  filters: [{ services: ['heart_rate'] }],
});

scan.onDeviceFound((event) => {
  console.log('发现设备:', event.device.id, event.device.name);
});

// 不再需要时停止扫描
scan.stop();
```

## Best Practices

- Prefer filtering by service UUID or device name to reduce irrelevant scan results.
- After connecting, confirm that the required services and characteristics exist before starting the read/write flow.
- Prefer notifications over polling for continuously updated data.
- Disconnect promptly when leaving the page or when the connection is no longer needed.

## Notes

- Bluetooth operations require the current UI to be in an interactive state.
- When calls such as `connect()` or `requestDevice()` fail, distinguish between states such as "unavailable", "no permission", and "connection failed".
- After a notification event fires, `characteristic.value` is updated to the latest cached value.

## Continue Reading

- **[IMU](/AIUI/guide/basic-device-imu)**: See motion and posture sensor capabilities on the device.
- **[Bluetooth (API Reference)](/AIUI/api/device-bluetooth)**: See the complete Bluetooth API reference.
