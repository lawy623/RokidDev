# Introduction

The core of the AIUI agent framework is not simply "rendering a page," but organizing the agent description, page entry, logic layer, and view layer into a complete runtime system. Developers define the identity of the agent, the page structure, and the business logic, and the runtime then connects these parts so that state changes are continuously mapped into interface feedback.

The interactive explanation below helps you understand the AIUI agent framework from two angles:

- What parts it is made of
- How a page update happens at runtime

<framework-runtime-explorer></framework-runtime-explorer>

## Core Concepts

AIUI adopts the classic separation between the logic layer and the view layer, but within the agent framework this separation does not exist in isolation. Instead, it works together with the agent description, application entry, and page structure:

1. **Logic Layer**: Runs in QuickJS and is responsible for business logic, API calls, and data state management.
2. **View Layer**: Built with WXML and WXSS, runs in the Ink rendering engine, and is responsible for page structure and style presentation.
3. **Application Entry**: Defined by `app.json`, which specifies page entries and global window configuration, determining which page the agent enters first after startup.
4. **Agent Description**: Defined by `AGENTS.md`, which specifies the agent's identity, description, capabilities, and system instructions, determining how the platform understands and schedules this agent.

This architecture not only provides smooth interface update capability, but also allows developers to organize "who the agent is," "where the entry starts," "how the logic flows," and "how the interface responds" separately, making it a better fit for AI + AR scenarios with high-frequency interaction and continuous state updates.

