# Runtime Mechanism

1. When the agent starts, the logic layer is initialized first.
2. The logic layer loads the home page data and sends it to the view layer.
3. The view layer completes the initial rendering.
4. User interaction triggers events, and the view layer passes those events to the logic layer.
5. The logic layer handles the events, updates data, and drives the view layer to re-render.

