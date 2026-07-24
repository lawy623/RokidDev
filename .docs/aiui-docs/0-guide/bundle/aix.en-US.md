# What Is AIX?

**AIX**, short for **AI eXecutable**, is a standard software package format **introduced and defined by AIUI**. It is the dedicated distribution format for AIUI agents and has the following characteristics:

## Core Features

- **Based on the OAF specification**: The internal structure of an AIX package follows the **OAF (Open Agent Format)** standard.
- **Self-contained**: Includes all agent code (WXML/WXSS/JS), configuration (JSON), and static assets (images/audio).
- **Version tracking**: Each AIX package automatically generates a unique UUID `VERSION` file during packaging for version validation and hot updates.
- **Asset optimization**: Supports lossless/lossy compression for PNG/JPEG images and obfuscation and size reduction for JSON files during packaging.
