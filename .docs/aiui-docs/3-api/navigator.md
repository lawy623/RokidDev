# Navigator

提供运行环境中的 `navigator` 相关能力，用于获取设备标识与版本信息。

## 属性

- `navigator.userAgent`: 返回当前设备与运行时版本信息。

## 方法

- `navigator.getDeviceSerialNumber()`: 返回当前设备的 SN 号。

## 使用示例

```javascript
const serialNumber = navigator.getDeviceSerialNumber();
const userAgent = navigator.userAgent;

console.log('SN:', serialNumber);
console.log('UA:', userAgent);
```

## 适用场景

- 上报设备标识信息，便于设备管理和问题排查。
- 读取运行环境版本信息，用于日志记录或兼容性判断。

## 注意事项

- `navigator.getDeviceSerialNumber()` 返回的是当前设备的唯一序列号，使用时应注意隐私与数据安全。
- `navigator.userAgent` 适合用于识别设备与版本信息，不建议依赖字符串解析实现强耦合逻辑。
