# Page Overview

In AIUI's extension of Open Agent Format, the `pages/` directory is used to define the concrete UI and interactive pages of an agent. A page not only carries the UI structure, but also page-level data, lifecycle callbacks, and event handling logic.

If:

- `AGENTS.md` defines who the agent is
- `app.json` defines how the application starts

Then a "page" defines what the user actually sees and how the user interacts with the agent.

## Page Organization

In AIUI, pages usually have two organization models:

- **Multi-file pages**: A page is made up of files such as `index.js`, `index.wxml`, `index.wxss`, and `page.json`, and the exported page object is usually written in `index.js`
- **Single-file pages**: A page uses a single `.ink` file to declare configuration, logic, structure, and styles together. The page logic is usually written in `<script setup>`

You can understand them as:

- Multi-file pages: Suitable for maintaining configuration / logic / structure / styles separately
- Single-file pages: Suitable for reading and editing everything related to the same page in one place

No matter which organization model you use, the page lifecycle, `data` object, and event handling model remain the same.

## Launch Context

In addition to how page files are organized, pages can also be used in different launch contexts. In AIUI, there are currently two common hosting models for pages:

- **Used inside a chat window**: The page is embedded in the chat window, which is suitable for presenting richer and more structured information within the conversation flow
- **Used in a standalone window / modal**: The page opens in a new window or `modal`, which is suitable for more complete and immersive interaction flows

One thing to note is that `target` is not a field that developers declare manually inside a page. Instead, AIUI determines how a page is ultimately launched based on the configuration and intended usage provided for each page.

You can understand it as:

- `_current`: Stay in the current chat context and complete presentation and interaction inside the message flow
- `_blank`: Use the page as an independent hosting space, emphasizing an immersive experience

In general, when a page is first used inside a chat window, users can still click it later to switch it into `_blank` mode for more display space and a more complete interactive experience.

If your page is mainly used for result presentation, card supplements, or status updates, it is usually first hosted inside the chat window.  
If your page carries a complete flow, a form, multi-step operations, or stronger interaction, it is more suitable for a standalone window or `modal`.

## Its Place In Open Agent Format

From the perspective of Open Agent Format, pages are a key layer where AIUI turns agent capabilities into an interactive UI:

- `AGENTS.md`: Defines the agent identity and system instructions
- `app.json`: Defines the application entry and global configuration
- `pages/`: Defines concrete pages and UI interaction

Therefore, one of the key extensions AIUI makes to OAF is to expand an agent that originally stayed only at the description layer into a truly runnable and interactive AI-Native User Interface.

## Continue Reading

- [Page Definition](/AIUI/guide/open-agent-format-page-definition): View the `Page` object, parameter table, example code, and instance methods
- [Lifecycle](/AIUI/guide/open-agent-format-page-lifecycle): View the lifecycle order, callback table, and interactive demo
- [Events](/AIUI/guide/open-agent-format-page-events): View page-level event callbacks, voice wakeup details, and example code

## Recommended Reading

- [Open Agent Format](/AIUI/guide/open-agent-format)
- [app.json](/AIUI/guide/open-agent-format-app-json)
