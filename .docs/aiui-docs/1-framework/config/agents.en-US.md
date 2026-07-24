# AGENTS.md Specification

In the AIUI agent framework, `AGENTS.md` is a standardized description file used to declare the agent configuration, capabilities, and system instructions in the current codebase. It plays a role similar to `package.json` or `manifest.json`, but focuses more on AI and agent-related characteristics.

## Basic Structure

A standard `AGENTS.md` file usually contains the following core sections:

### 1. Meta Information
Describes the basic properties of the agent, including its name, version, description, and author.

```markdown
# Agent: 智能体名称
- **Version**: 1.0.0
- **Description**: 简短描述该智能体的主要功能和使用场景
- **Author**: 作者或组织名称
```

### 2. System Prompts
Defines the agent's core behavioral guidelines, persona, and base instructions. This is the most important context at LLM runtime.

```markdown
## System Prompts
你是一个专业的开发助手。
- 总是使用中文回答。
- 在给出代码前，先解释你的思路。
- 遵循 AIUI 最佳实践。
```

### 3. Capabilities
Declares which specific capabilities or permissions the agent has, such as file access and network requests.

```markdown
## Capabilities
- `fs.read`: 允许读取项目文件
- `fs.write`: 允许修改项目文件
- `network.http`: 允许发起外部 HTTP 请求
```

### 4. Configuration
Declares the required configuration items or environment variables needed to run the agent.

```markdown
## Configuration
- `API_ENDPOINT`: 后端服务地址
- `THEME`: 默认 UI 主题，可选值：`light`, `dark`
```

### 5. Dependencies
Lists the other services, models, or sub-agents this agent depends on.

```markdown
## Dependencies
- Model: `gpt-4` 或等效模型
- Services: `weather-api`, `database-service`
```

## Writing Recommendations

1. **Be clear and specific**: System Prompts should be as concrete and explicit as possible to avoid ambiguous instructions.
2. **Security first**: Declare only the minimum required permissions in Capabilities, following the principle of least privilege.
3. **Version control**: Keep the Version up to date so the evolution of the agent's capabilities can be tracked.
4. **Consistent formatting**: Strictly follow Markdown formatting for easier machine parsing and human readability.

By writing a well-structured `AGENTS.md`, the platform can better understand and orchestrate your agent, and other developers can get started with it more quickly.
