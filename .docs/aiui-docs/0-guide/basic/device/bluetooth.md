# 蓝牙

蓝牙能力让你的智能体可以搜索、连接和读写附近的 BLE（低功耗蓝牙）设备。

如果你的应用需要连接心率带、遥控器、外设传感器或其他 BLE 硬件，可以从这里开始。

## 适用场景

- 搜索附近的 BLE 设备
- 连接外设并读写 GATT 服务与特征值
- 订阅设备通知，持续接收状态更新

## 基本用法

通过 `navigator.bluetooth` 发起设备请求，连接到设备后读取服务：

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

## 扫描设备

如果需要持续发现附近设备，可以使用扫描模式：

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

## 使用建议

- 优先用服务 UUID 或设备名称过滤，减少无关扫描结果。
- 连接后先确认服务和特征值是否存在，再进入读写流程。
- 对持续更新的数据优先使用通知而非轮询。
- 页面离开或不再需要时及时断开连接。

## 注意事项

- 蓝牙操作要求当前界面处于可交互状态。
- `connect()`、`requestDevice()` 等调用失败时，应当区分"不可用""无权限""连接失败"等不同状态。
- 通知事件触发后 `characteristic.value` 会更新为最新缓存值。

## 继续阅读

- **[IMU](/AIUI/guide/basic-device-imu)**：查看设备运动与姿态传感器能力。
- **[蓝牙 (API 参考)](/AIUI/api/device-bluetooth)**：查看蓝牙 API 的完整参考文档。
