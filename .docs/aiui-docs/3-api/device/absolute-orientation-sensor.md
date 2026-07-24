# 绝对方向传感器

绝对方向传感器用于获取设备当前的空间朝向与姿态，适合头部朝向感知、姿态驱动交互、空间 UI 对齐等场景。

在 AIUI 中，`AbsoluteOrientationSensor` 会输出四元数形式的姿态数据，适合在 3D 或空间场景中继续做方向计算。

## 适用场景

- 头部朝向感知
- 空间姿态同步
- 3D 视角对齐
- 沉浸式界面方向控制

## 入口

```javascript
const sensor = new AbsoluteOrientationSensor({ frequency: 60 });
```

## 基本示例

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

## 常用属性

### `quaternion`
- **类型**：`[number, number, number, number] | null`
- **说明**：当前姿态四元数，顺序为 `[x, y, z, w]`。

### `timestamp`
- **类型**：`number | null`
- **说明**：最近一次姿态读数的时间戳。

### `activated`
- **类型**：`boolean`
- **说明**：当前传感器是否已激活。

### `hasReading`
- **类型**：`boolean`
- **说明**：是否已经收到过有效姿态数据。

## 常用方法

### `start()`
- 开始姿态采样。

### `stop()`
- 停止姿态采样。

## 使用建议

- 如果你的业务需要空间姿态而不是简单运动变化，优先使用 `AbsoluteOrientationSensor`。
- 使用四元数做后续计算时，不要直接假设可得到欧拉角或真北朝向。
- 在 `reading` 事件里消费 `quaternion`，并根据业务自行做姿态转换。
- 和 UI 绑定时，先做好更新频率和抖动控制，避免视图抖动。

## 继续阅读

- **[加速度计](/AIUI/api/device-accelerometer)**：查看线性运动读数。
- **[陀螺仪](/AIUI/api/device-gyroscope)**：查看旋转速度读数。
