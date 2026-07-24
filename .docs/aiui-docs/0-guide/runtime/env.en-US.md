# Runtime Environment

AIUI agents run on multiple platforms: Rokid wearable devices (such as Rokid Glasses), desktop debuggers, and more AI + AR hardware that will be supported in the future.

Although the script execution environment and the environment used for component rendering are designed to stay consistent across different runtimes, there are still differences in their underlying implementation and performance characteristics:

| Runtime Environment | Underlying Implementation and Characteristics |
|:--|:--|
| **Rokid Glasses** | The logic layer and view layer run inside Ink, a high-performance in-house container built on **QuickJS** and **Skia**, with deep optimization for the extremely low power, memory, and CPU constraints of wearable devices. |
| **Craft: a Web IDE built on AIUI** | A web-based AIUI development environment that supports code editing, project management, agent binding, and real-time preview, making it easier for developers to write and debug agents online. |
| **Rokid AR Lite / AR Studio** (planned) | In the future, consistency across different AI + AR devices will be achieved by adapting the underlying runtime environment. |

## Platform Differences

Although AIUI strives to provide a consistent development experience across runtime environments, there are still some differences in actual operation:

1. **Hardware and sensor differences**: In the DevTools simulator, some APIs that depend on the real physical environment, such as 6DoF spatial tracking, high-precision gesture recognition, spatial audio, and specific sensor data, can usually return only simulated data. Developers must validate the real interaction behavior on actual Rokid devices.
2. **Performance differences**: Desktop environments (DevTools) have abundant computing resources, while wearable devices are under extremely strict power and thermal constraints. As a result, complex animations or high-frequency data updates (such as frequent `setData`) that run smoothly on the desktop may trigger performance bottlenecks or system throttling on real devices.
3. **Rendering and layout differences**: Although the view layer is described through a consistent responsive pipeline underneath, the actual spatial visual proportions, depth perception, and layout presentation may still vary slightly across physical devices with different resolutions and FOVs (fields of view).

> **Note**: The developer tools (AIUI DevTools) are for development and debugging only. For the final UI presentation, interaction experience, and performance metrics of an agent, always use a real Rokid client device as the reference.

