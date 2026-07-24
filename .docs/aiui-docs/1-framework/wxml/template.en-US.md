# Templates

WXML provides templates (`template`), which let you define code fragments in a template and call them in different places.

## Defining a Template

Use the `name` attribute as the template name. Then define the code fragment inside `<template/>`, for example:

```html
<!--
  index: int
  msg: string
  time: string
-->
<template name="msgItem">
  <view>
    <text> {{index}}: {{msg}} </text>
    <text> Time: {{time}} </text>
  </view>
</template>
```

## Using a Template

Use the `is` attribute to declare which template to use, then pass in the `data` required by the template, for example:

```html
<template is="msgItem" data="{{...item}}"/>
```

```javascript
export default {
  data: {
    item: {
      index: 0,
      msg: 'this is a template',
      time: '2016-09-15'
    }
  }
}
```

The `is` attribute can use Mustache syntax to dynamically determine which template should be rendered:

```html
<template name="odd">
  <view> odd </view>
</template>
<template name="even">
  <view> even </view>
</template>

<block ink:for="{{[1, 2, 3, 4, 5]}}">
  <template is="{{item % 2 == 0 ? 'even' : 'odd'}}"/>
</block>
```

## Template Scope

A template has its own scope and can only use the data passed in through `data` and the `<wxs />` modules defined in the file where the template is declared.
