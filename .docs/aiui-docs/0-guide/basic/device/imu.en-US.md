# IMU

IMU (Inertial Measurement Unit) capabilities allow your agent to perceive device motion and spatial posture, including acceleration, rotational velocity, and absolute orientation.

On wearable devices such as AR glasses, these capabilities are the foundation for head tracking, spatial interaction, and motion detection.

## Use Cases

- Detect device shaking, tilting, or movement
- Sense head direction and rotation
- Implement posture-based interactions such as nodding or turning the head
- Step counting or activity detection in fitness applications

## Core Sensors

AIUI provides three main sensors:

| Sensor | Purpose | Typical Scenario |
|:--|:--|:--|
| **Accelerometer** | Detects changes in device acceleration | Shake detection, movement detection |
| **Gyroscope** | Detects rotational angular velocity of the device | Head-motion interaction, rotation detection |
| **AbsoluteOrientationSensor** | Detects the device's absolute orientation in space | Posture awareness, 3D alignment |

## Basic Usage

Take the accelerometer as an example. Create a sensor instance and start reading data:

```javascript
const sensor = new Accelerometer({ frequency: 60 });

sensor.addEventListener('reading', () => {
  console.log('加速度:', sensor.x, sensor.y, sensor.z);
});

sensor.addEventListener('error', (event) => {
  console.error('传感器错误:', event.error);
});

sensor.start();
```

## Using the Gyroscope

Detect device rotation speed:

```javascript
const gyro = new Gyroscope({ frequency: 60 });

gyro.addEventListener('reading', () => {
  console.log('角速度:', gyro.x, gyro.y, gyro.z);
});

gyro.start();
```

## Using the Absolute Orientation Sensor

Get the device orientation in space as a quaternion:

```javascript
const sensor = new AbsoluteOrientationSensor({ frequency: 60 });

sensor.addEventListener('reading', () => {
  const [x, y, z, w] = sensor.quaternion;
  console.log('方向四元数:', x, y, z, w);
});

sensor.start();
```

## Best Practices

- Start with a lower frequency such as 30-60Hz and increase it only when needed to avoid unnecessary performance overhead.
- Read sensor values inside the `reading` event instead of polling them with a timer.
- Call `stop()` promptly when sensor data is no longer needed to reduce power consumption.
- You can combine multiple sensor data sources for more accurate posture or motion estimation.

## Notes

- In the DevTools simulator, sensor data usually returns simulated values. It is recommended to verify actual behavior on a real device.
- Sampling frequency and precision may vary across devices.
- The availability of the absolute orientation sensor depends on hardware support on the device.

## Continue Reading

- **[Bluetooth](/AIUI/guide/basic-device-bluetooth)**: See how to connect BLE peripherals.
- **[Accelerometer (API Reference)](/AIUI/api/device-accelerometer)**: See the complete accelerometer API reference.
- **[Gyroscope (API Reference)](/AIUI/api/device-gyroscope)**: See the complete gyroscope API reference.
- **[Absolute Orientation Sensor (API Reference)](/AIUI/api/device-absolute-orientation-sensor)**: See the complete orientation sensor API reference.
