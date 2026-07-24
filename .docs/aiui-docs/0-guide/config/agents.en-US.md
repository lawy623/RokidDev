# AGENTS.md Specification

In the AIUI agent framework, `AGENTS.md` is a standardized description file used to declare the configuration, capabilities, and system instructions of the agents in the current codebase. It plays a role similar to `package.json` or `manifest.json`, but focuses more on AI- and agent-related characteristics.

## Basic Structure

A standard `AGENTS.md` file usually contains the following core sections:

### 1. Meta Information
Describes the basic properties of the agent, including its name, version, description, and author.

```markdown
# Agent: Agent Name
- **Version**: 1.0.0
- **Description**: A brief description of the agent's main functions and usage scenarios
- **Author**: Author or organization name
```

### 2. System Prompts
Defines the agent's core behavioral rules, persona, and foundational instructions. This is the most important runtime context for the LLM.

```markdown
## System Prompts
You are a professional development assistant.
- Always respond in Chinese.
- Explain your thinking before providing code.
- Follow AIUI best practices.
```

### 3. Capabilities
Declares the specific capabilities or permissions available to the agent, such as file access and network requests.

```markdown
## Capabilities
- `fs.read`: Allows reading project files
- `fs.write`: Allows modifying project files
- `network.http`: Allows sending external HTTP requests
```

### 4. Configuration
Declares the required configuration or environment variables needed to run the agent.

```markdown
## Configuration
- `API_ENDPOINT`: Backend service address
- `THEME`: Default UI theme, optional values: `light`, `dark`
```

### 5. Dependencies
Lists other services, models, or sub-agents that this agent depends on.

```markdown
## Dependencies
- Model: `gpt-4` or an equivalent model
- Services: `weather-api`, `database-service`
```

## Writing Recommendations

1. **Be clear and explicit**: System Prompts should be as specific and unambiguous as possible.
2. **Security first**: Declare only the minimum required permissions in Capabilities, following the principle of least privilege.
3. **Version management**: Keep Version updated so the evolution of agent capabilities can be tracked.
4. **Formatting standards**: Strictly follow Markdown format so the file is easy for machines to parse and humans to read.

By writing a well-structured `AGENTS.md`, the platform can better understand and orchestrate your agent, and other developers can get started more quickly.
