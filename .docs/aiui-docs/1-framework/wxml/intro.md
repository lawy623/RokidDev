# 介绍

WXML（WeiXin Markup Language）是框架设计的一套标签语言，结合基础组件、事件系统，可以构建出页面的结构。

AIUI 沿用了微信小程序的 WXML 规范。用以下一些简单的例子来看看 WXML 具有什么能力：

## 数据绑定

```html
<!--wxml-->
<view> {{message}} </view>
```

```javascript
// page.js
export default {
  data: {
    message: 'Hello MINA!'
  }
}
```

## 条件渲染

```html
<!--wxml-->
<view ink:if="{{view == 'WEBVIEW'}}"> WEBVIEW </view>
<view ink:elif="{{view == 'APP'}}"> APP </view>
<view ink:else="{{view == 'MINA'}}"> MINA </view>
```

```javascript
// page.js
export default {
  data: {
    view: 'MINA'
  }
}
```

## 列表渲染

```html
<!--wxml-->
<view ink:for="{{array}}"> {{item}} </view>
```

```javascript
// page.js
export default {
  data: {
    array: [1, 2, 3, 4, 5]
  }
}
```

## 模板

```html
<!--wxml-->
<template name="staffName">
  <view>
    FirstName: {{firstName}}, LastName: {{lastName}}
  </view>
</template>

<template is="staffName" data="{{...staffA}}"></template>
<template is="staffName" data="{{...staffB}}"></template>
<template is="staffName" data="{{...staffC}}"></template>
```

```javascript
// page.js
export default {
  data: {
    staffA: {firstName: 'Hulk', lastName: 'Hu'},
    staffB: {firstName: 'Shang', lastName: 'You'},
    staffC: {firstName: 'Gideon', lastName: 'Lin'}
  }
}
```

具体的能力以及使用方式在后续章节查看。