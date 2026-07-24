# Open Agent Format

Open Agent Format, or OAF for short, is an open specification for describing how agents are organized. It emphasizes using directories and files to represent an agent, so the agent's identity, description, capability boundaries, and composable resources can be organized, read, and migrated clearly.

AIUI adopts and extends this idea. In addition to supporting `AGENTS.md` as the core description file, it further defines the UI and runtime organization model, so an agent can be not only described, but also run and presented.

## What Problem Does It Solve

Without a unified project structure, different platforms often describe agents in their own ways:

- Who the agent is
- What capabilities it has
- What resources it depends on
- How it is invoked
- How it interacts with users through a UI

OAF aims to solve the organization and portability of this information. It tries to bring the definition of "what an agent is" back to the directories and files themselves, instead of scattering it across platform-specific configuration.

## How To Understand It In AIUI

In AIUI, you can think of Open Agent Format as the "description layer" of an agent, while AIUI adds the "application and UI layer" on top of it.

A typical AIUI agent usually contains three kinds of key content:

- `AGENTS.md`: Defines who the agent is, what it does, and which instructions it follows
- `app.json`: Defines the application entry and global configuration
- `pages/`: Defines page organization and the user interaction UI

You can also understand it more simply as:

- **OAF describes the agent**
- **AIUI turns the agent into an interactive AI-Native User Interface**

## Why It Matters

For developers, the value of OAF is not about introducing a new concept, but about making agent projects easier to understand, maintain, and migrate:

- Clearer directory structures
- More centralized description files
- Easier mapping across different platforms
- Clearer boundaries between the UI layer and the agent description layer

## Recommended Reading

- [AGENTS.md](/AIUI/guide/config-agents)
- [Directory Structure](/AIUI/guide/structure)
