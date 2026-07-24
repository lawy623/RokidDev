# 开放服务

AIUI 允许开发者通过 `createOpenAPI` 调用 Rokid 云平台提供的多种智能服务，以增强智能体的能力。

## 基础接口

### createOpenAPI

创建一个开放服务实例。通过此实例，您可以访问各类云端开放服务。

#### 导入

```javascript
import { createOpenAPI } from 'open';
```

#### 返回值

返回一个 Promise，解析后得到包含各种开放服务接口的对象。

#### 示例代码

```javascript
import { createOpenAPI } from 'open';

createOpenAPI().then(openapi => {
  // 使用 openapi 实例调用相关服务
}).catch(err => {
  console.error('创建开放服务实例失败：', err);
});
```
