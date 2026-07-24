# Open Services

AIUI allows developers to use `createOpenAPI` to call a variety of intelligent services provided by the Rokid cloud platform, extending the capabilities of an agent.

## Basic API

### createOpenAPI

Creates an open service instance. Through this instance, you can access different cloud open services.

#### Import

```javascript
import { createOpenAPI } from 'open';
```

#### Return Value

Returns a Promise that resolves to an object containing various open service APIs.

#### Example

```javascript
import { createOpenAPI } from 'open';

createOpenAPI().then(openapi => {
  // Use the openapi instance to call related services
}).catch(err => {
  console.error('创建开放服务实例失败：', err);
});
```
