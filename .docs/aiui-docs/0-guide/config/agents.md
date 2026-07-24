# AGENTS.md 规范

在 AIUI 智能体框架中，`AGENTS.md` 是一个标准化的描述文件，用于声明当前代码库中的智能体（Agent）配置、能力和系统指令。它起到类似 `package.json` 或 `manifest.json` 的作用，但更侧重于 AI 和智能体特性的描述。

## 基本结构

一个标准的 `AGENTS.md` 文件通常包含以下几个核心部分：

### 1. 基础信息 (Meta Information)
描述智能体的基本属性，包括名称、版本、描述和作者等。

```markdown
# Agent: 智能体名称
- **Version**: 1.0.0
- **Description**: 简短描述该智能体的主要功能和使用场景
- **Author**: 作者或组织名称
```

### 2. 系统指令 (System Prompts)
定义智能体的核心行为准则、性格设定和基础指令。这是 LLM 运行时最重要的上下文。

```markdown
## System Prompts
你是一个专业的开发助手。
- 总是使用中文回答。
- 在给出代码前，先解释你的思路。
- 遵循 AIUI 最佳实践。
```

### 3. 能力声明 (Capabilities)
声明该智能体具备哪些特定的能力或权限，例如文件访问、网络请求等。

```markdown
## Capabilities
- `fs.read`: 允许读取项目文件
- `fs.write`: 允许修改项目文件
- `network.http`: 允许发起外部 HTTP 请求
```

### 4. 环境变量与配置 (Configuration)
声明运行该智能体所需的必要配置或环境变量。

```markdown
## Configuration
- `API_ENDPOINT`: 后端服务地址
- `THEME`: 默认 UI 主题，可选值：`light`, `dark`
```

### 5. 依赖列表 (Dependencies)
列出该智能体依赖的其他服务、模型或子智能体。

```markdown
## Dependencies
- Model: `gpt-4` 或等效模型
- Services: `weather-api`, `database-service`
```

## 编写建议

1. **清晰明确**：System Prompts 应该尽可能具体和明确，避免模棱两可的指令。
2. **安全优先**：在 Capabilities 中只声明必需的最小权限，遵循最小权限原则。
3. **版本控制**：保持 Version 的更新，以便于追踪智能体能力的演进。
4. **格式规范**：严格遵循 Markdown 格式，便于机器解析 and 人类阅读。

通过编写规范的 `AGENTS.md`，可以让平台更好地理解和调度你的智能体，也能让其他开发者更快地上手使用。
