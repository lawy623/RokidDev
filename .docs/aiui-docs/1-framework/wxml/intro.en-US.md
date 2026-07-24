# Introduction

WXML (WeiXin Markup Language) is a tag language designed by the framework. Combined with base components and the event system, it can be used to build the structure of a page.

AIUI follows the WXML specification of WeChat Mini Programs. The following simple examples show what WXML can do:

## Data Binding

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

## Conditional Rendering

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

## List Rendering

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

## Templates

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

See the following chapters for the specific capabilities and usage details.
