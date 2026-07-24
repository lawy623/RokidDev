# IMU

IMU（惯性测量单元）能力让你的智能体可以感知设备的运动状态和空间姿态，包括加速度、旋转速度和绝对方向。

在 AR 眼镜等穿戴式设备上，这些能力是实现头部追踪、空间交互和运动检测的基础。

## 适用场景

- 检测设备晃动、倾斜或移动
- 感知头部朝向与转动
- 实现基于姿态的交互（点头、转头等）
- 运动类应用的步数或活动检测

## 核心传感器

AIUI 提供三种主要传感器：

| 传感器 | 用途 | 典型场景 |
|:--|:--|:--|
| **加速度计 (Accelerometer)** | 感知设备的加速度变化 | 晃动检测、移动判断 |
| **陀螺仪 (Gyroscope)** | 感知设备的旋转角速度 | 头动交互、转动识别 |
| **绝对方向传感器 (AbsoluteOrientationSensor)** | 感知设备在空间中的绝对朝向 | 姿态感知、3D 对齐 |

## 基本用法

以加速度计为例，创建一个传感器实例并开始读取数据：

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

## 使用陀螺仪

检测设备旋转速度：

```javascript
const gyro = new Gyroscope({ frequency: 60 });

gyro.addEventListener('reading', () => {
  console.log('角速度:', gyro.x, gyro.y, gyro.z);
});

gyro.start();
```

## 使用绝对方向传感器

获取设备在空间中的朝向（以四元数表示）：

```javascript
const sensor = new AbsoluteOrientationSensor({ frequency: 60 });

sensor.addEventListener('reading', () => {
  const [x, y, z, w] = sensor.quaternion;
  console.log('方向四元数:', x, y, z, w);
});

sensor.start();
```

## 使用建议

- 从较低频率（如 30-60Hz）开始，按需调高，避免无谓的性能开销。
- 在 `reading` 事件内读取传感器值，不要通过定时器轮询。
- 不需要传感器数据时及时调用 `stop()`，减少功耗。
- 可组合多种传感器数据做更准确的姿态或运动估计。

## 注意事项

- 在 DevTools 模拟器中，传感器数据通常返回模拟值，建议在真机上验证实际表现。
- 不同设备的采样频率和精度可能存在差异。
- 绝对方向传感器的可用性取决于设备硬件支持。

## 继续阅读

- **[蓝牙](/AIUI/guide/basic-device-bluetooth)**：查看如何连接 BLE 外设。
- **[加速度计 (API 参考)](/AIUI/api/device-accelerometer)**：查看加速度计 API 完整参考。
- **[陀螺仪 (API 参考)](/AIUI/api/device-gyroscope)**：查看陀螺仪 API 完整参考。
- **[绝对方向传感器 (API 参考)](/AIUI/api/device-absolute-orientation-sensor)**：查看方向传感器 API 完整参考。
