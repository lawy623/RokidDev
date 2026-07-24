# Page Definition

`Page` is used to register a page in an agent. The logic of each page is exported as a configuration object through `export default`.

The page accepts an `Object` parameter that specifies the page's initial data, lifecycle callbacks, event handlers, and more.

AIUI page structure follows a `Page` definition model similar to WeChat Mini Programs.

## Object object

| Property | Type | Default | Required | Description |
| :--- | :--- | :--- | :--- | :--- |
| `data` | Object | | No | Initial data of the page |
| `options` | Object | | No | Component options of the page |
| `onLoad` | function | | No | Lifecycle callback that listens for page load |
| `onShow` | function | | No | Lifecycle callback that listens for page display |
| `onKeyUp` | function | | No | Key-up event callback for page-level `keyup` events. You can get the key code through `event.code` |
| `onKeyDown` | function | | No | Key-down event callback for page-level `keydown` events. You can get the key code through `event.code` |
| `onVoiceWakeup` | function | | No | Voice wakeup event callback for page-level `voicewakeup` events. You can get the wake word through `event.keyword`. The default value is `leqi` |
| `onReady` | function | | No | Lifecycle callback that listens for the first render completion of the page |
| `onHide` | function | | No | Lifecycle callback that listens for the page being hidden |
| `onUnload` | function | | No | Lifecycle callback that listens for the page being unloaded |
| Others | any | | No | Developers can add any functions or data and access them through `this` in page methods |

## Example Code

```javascript
export default {
  data: {
    text: "This is page data.",
    user: {
      name: 'Rokid'
    }
  },
  onLoad(options) {
    // Page load
  },
  handleUpdate() {
    // Update data
    this.setData({
      text: 'Updated Text',
      'user.name': 'New Name' // Path-based updates are supported
    }, () => {
      console.log('Data updated');
    });
  },
  handleComplete() {
    // Complete the current page task
    this.finish();
  }
}
```

## Lifecycle Callback Details

### `onLoad(Object options)`

Triggered when the page loads, and called only once globally. You can get the parameters passed when opening the current page through `options`. It is usually used to initialize page data or process route parameters.

```javascript
export default {
  onLoad(options) {
    this.setData({
      pageId: options.id || '',
      status: 'page loaded'
    });
  }
}
```

### `onShow()`

Triggered when the page is displayed or brought to the foreground. It is suitable for visibility-related logic such as refreshing display state, resuming polling, or re-fetching lightweight data.

```javascript
export default {
  onShow() {
    this.setData({
      visible: true,
      status: 'page visible'
    });
  }
}
```

### `onReady()`

Triggered when the page completes its first render, and called only once globally. At this point the page is ready, so it is suitable for logic that depends on the first screen being rendered.

```javascript
export default {
  onReady() {
    this.setData({
      status: 'page ready'
    });
  }
}
```

### `onHide()`

Triggered when the page is hidden or moved to the background. It is commonly used to pause timers, animations, polling requests, or clean up temporary state in the page.

```javascript
export default {
  onHide() {
    this.setData({
      visible: false,
      status: 'page hidden'
    });
  }
}
```

### `onUnload()`

Triggered when the page is unloaded. It is a good place to release resources used by the page to avoid keeping invalid references or tasks after the page is destroyed.

```javascript
export default {
  onUnload() {
    console.log('page unloaded');
  }
}
```

## Event Handling

In addition to lifecycle callbacks, pages also support page-level event handlers such as `onKeyDown`, `onKeyUp`, and `onVoiceWakeup`.

For event triggers, parameter descriptions, and example code, see: [Events](/AIUI/guide/open-agent-format-page-events)

## Instance Methods

In page logic, you can access the page instance through `this` and call the following methods:

### `this.setData(Object data, Function? callback)`

Used to send data from the logic layer to the view layer asynchronously while also updating the corresponding value of `this.data`.

- `data`: Key-value pairs of data to update. Path-based updates are supported, for example `'a.b.c': 1`
- `callback`: Optional callback function that runs after the data update is complete

### `this.finish()`

Notifies the system that the current page task has been completed.

- For **Cut** agents, calling this method actively returns focus and exits the current presentation state
- For **Scene** agents, it is typically used to end the current interaction flow

## Page Data Object `data`

`data` is the initial data used for the first render of the page. When the page loads, `data` is passed from the logic layer to the rendering layer as a JSON string, so the data inside `data` must be of types that can be converted to JSON, such as strings, numbers, booleans, objects, and arrays.

The rendering layer can bind to the data through WXML or the `.ink` page structure.

## Recommended Reading

- [Page Overview](/AIUI/guide/open-agent-format-page)
- [Lifecycle](/AIUI/guide/open-agent-format-page-lifecycle)
- [Events](/AIUI/guide/open-agent-format-page-events)
