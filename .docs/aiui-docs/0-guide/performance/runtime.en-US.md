# Runtime Performance

- **Avoid frequent `setData` calls**: Merge multiple data updates into a single call.
- **Reduce data transfer volume**: Only pass the data required by the view layer, and avoid sending large objects.
- **Optimize lists**: Use long-list components and reuse DOM nodes.
