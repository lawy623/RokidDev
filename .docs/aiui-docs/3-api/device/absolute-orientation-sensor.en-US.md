# Absolute Orientation Sensor

The absolute orientation sensor is used to obtain the device's current spatial heading and pose. It is suitable for scenarios such as head direction awareness, pose-driven interaction, and spatial UI alignment.

In AIUI, `AbsoluteOrientationSensor` outputs quaternion-based pose data, which is suitable for further direction calculations in 3D or spatial scenes.

## Use Cases

- Head direction awareness
- Spatial pose synchronization
- 3D viewpoint alignment
- Direction control for immersive interfaces

## Entry

```javascript
const sensor = new AbsoluteOrientationSensor({ frequency: 60 });
```

## Basic Example

```javascript
const sensor = new AbsoluteOrientationSensor({ frequency: 60 });

sensor.addEventListener('activate', (event) => {
  console.log('absolute orientation active', event.sessionId);
});

sensor.addEventListener('reading', () => {
  console.log(sensor.quaternion, sensor.timestamp);
});

sensor.addEventListener('error', (event) => {
  console.error(event.error, event.message);
});

sensor.start();
```

## Common Properties

### `quaternion`
- **Type**: `[number, number, number, number] | null`
- **Description**: The current pose quaternion, in the order `[x, y, z, w]`.

### `timestamp`
- **Type**: `number | null`
- **Description**: The timestamp of the most recent pose reading.

### `activated`
- **Type**: `boolean`
- **Description**: Whether the sensor is currently activated.

### `hasReading`
- **Type**: `boolean`
- **Description**: Whether valid pose data has been received.

## Common Methods

### `start()`
- Starts pose sampling.

### `stop()`
- Stops pose sampling.

## Recommendations

- If your feature needs spatial pose data rather than simple motion changes, prefer `AbsoluteOrientationSensor`.
- When using quaternions for follow-up calculations, do not assume you can directly derive Euler angles or true north heading.
- Consume `quaternion` in the `reading` event and perform pose conversion based on your business logic.
- When binding sensor data to UI, control update frequency and jitter first to avoid view shaking.

## Continue Reading

- **[Accelerometer](/AIUI/api/device-accelerometer)**: Learn about linear motion readings.
- **[Gyroscope](/AIUI/api/device-gyroscope)**: Learn about rotational velocity readings.
