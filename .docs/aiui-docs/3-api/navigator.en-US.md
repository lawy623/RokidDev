# Navigator

Provides `navigator`-related capabilities in the runtime environment for obtaining device identifiers and version information.

## Properties

- `navigator.userAgent`: Returns information about the current device and runtime version.

## Methods

- `navigator.getDeviceSerialNumber()`: Returns the SN of the current device.

## Example

```javascript
const serialNumber = navigator.getDeviceSerialNumber();
const userAgent = navigator.userAgent;

console.log('SN:', serialNumber);
console.log('UA:', userAgent);
```

## Use Cases

- Report device identifiers to support device management and troubleshooting.
- Read runtime version information for logging or compatibility checks.

## Notes

- `navigator.getDeviceSerialNumber()` returns the unique serial number of the current device. Be mindful of privacy and data security when using it.
- `navigator.userAgent` is suitable for identifying device and version information. Avoid building tightly coupled logic that depends on string parsing.
