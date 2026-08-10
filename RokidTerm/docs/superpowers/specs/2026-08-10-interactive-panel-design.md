# AskUserQuestion 交互面板设计

> 状态：已批准（用户 2026-08-10）
> 基于真实设备案例设计（2026-08-10，frame dump + 字节流捕获，chunk 271）

## 目标

当远程 Claude Code 在对话中弹出 **AskUserQuestion 工具面板**（选项列表 + 自由输入入口）时，App 自动识别并把选项组装成一个可交互的底部 overlay 面板，交互方式沿用现有 command panel（/effort、/model）的成熟模式：滑动导航、确认、取消，外加 `Type something.` / `Chat about this` 项确认后唤出 composer 自由输入。

现状：交互面板出现时 App **不会进入 panelMode**（只在发送 `/` 命令后进入），方向键被历史浏览消费，面板完全无法操作（用户 2026-08-10："确实不行"）。

## 真实案例（决定性证据）

AskUserQuestion 面板在终端中的渲染（54×36 网格，输入行回显选中项）：

```
[19] ───────────────────────────────────────
[20]  ☐ 测试场景                    ← 选中项：48;5;153 高亮背景 + ☐
[22] 你想用这个交互选择面板来做什么测试？   ← 问题文本（粗体白）
[24] ❯ 1. 测试选项点击              ← 输入行回显选中项（❯ 高亮 + 编号灰 + 标题）
[25]    只想测试直接点击选项时的响应效果    ← 副标题（38;5;246 灰，缩进 3）
[26] 2. 测试文字输入 / [27] 副标题
[28] 3. 两者都测试 / [29-30] 副标题（跨行）
[31] 4. Type something.            ← 自由输入入口（固定项）
[32] ───────────────────────────────────────
[33] 5. Chat about this             ← 固定项
[34] Enter to select · ↑/↓ to navigate · Esc to cancel  ← 帮助行
[35] [tmux 状态行]
```

协议（来自帮助行，与 /effort 面板一致）：
- **↑/↓（方向键）**：切换选中项——选中项回显在输入行（`❯ N. 标题`）
- **Enter**：确认选中项
- **ESC**：取消

## 检测规则（TerminalView 新增 `detectAskPanel()`）

每帧扫描，命中任一特征判定为 AskUserQuestion 面板：

1. **强特征**：任意行文本包含 `Enter to select`（帮助行；中文面板 Claude 仍输出英文帮助行——字节流确认）
2. **次级特征**：输入行文本匹配 `❯ \d+\. `（选中项回显）且屏幕上有 `Type something.` 行

检测到的瞬间（帧内容稳定后，防半帧误判：同一检测连续 2 帧成立才触发）→ 进入交互面板模式。

## 模式与状态流

交互面板模式 = 现有 panelMode 的**变体** `askPanelMode`（复用 panel 的按键矩阵、严格隔离、回复信号自动退出），新增：overlay 渲染 + Type-something 子状态。

```
[终端] detectAskPanel 连续 2 帧命中 → askPanelMode（panelMode=true + askPanel=true）
   │
   ├─ 滑动/方向键导航 ──→ 发 PTY 方向键 + 本地高亮镜像
   │
   ├─ 确认普通选项 ──→ 发 Enter ──→ Claude 处理 ──→ 回复信号自动退出
   │
   ├─ 确认 Type something. / Chat about this ──→ 发 Enter + 开 composer（面板子状态）
   │        │
   │        ├─ 长按 TP / 左旋钮双击 ──→ sendTextWithEnter（paste-burst 修复覆盖长文本）──→ 等回复自动退出
   │        │
   │        └─ 取消（右旋钮单击 / 双击 / Back）──→ 关 composer + 发 ESC ──→ 回面板继续选
   │
   └─ 取消面板（TP 双击 / 右旋钮 / GO 双击）──→ 发 ESC ──→ 退出 askPanelMode
```

- composer 是面板的**子状态**：composer 打开时 panelMode 保持 true（现有互斥需放开：仅 askPanelMode 允许 composer 在面板下打开）
- composer 取消时**必须发 ESC**：Claude 面板从输入态退回选项选择（真机验证点；若面板已关，ESC 落在输入行无害）
- 面板消失（帮助行不再出现，输入行恢复裸 `❯ `）→ overlay 自动收起

## Overlay 渲染（TerminalView）

- 位置：屏幕底部（composer 位置），解析选项块渲染为列表
- 解析：输入行**下方**区域——`\d+\. ` 行 = 选项标题，后续缩进行 = 副标题（跨行合并，标题行截断到格子宽）；`Type something.`、`Chat about this` 作为普通列表项
- 选中项高亮：复用命令面板列表视觉（`drawCommandPaletteList` 的选中样式）；**高亮每帧从输入行文本（`❯ N. 标题`）镜像校正**——本地与 Claude 永远一致
- 底部提示（英文，UI 语言约定）：`SELECT CONFIRM · TYPE → COMPOSER · ESC CANCEL`
- 面板状态变化（帮助行消失）→ 收起 overlay

## 按键矩阵（复用 command panel，追加两行）

| 动作 | Rokid TP | COIDEA | Ring4 |
|---|---|---|---|
| 上下导航 | 上下滑动（发 PTY 方向键） | 键 2/5 | 触控板左右滑（右=下） |
| 确认 | 长按 | 左旋钮单击 | 触控板长按 |
| 取消面板（ESC+退出） | TP 双击 / Back | 右旋钮单击 | GO 双击 |
| **Type-something 确认** | 长按（同确认） | 左旋钮单击 | 触控板长按 |
| composer 发送 | TP 长按 | 左旋钮双击 | 触控板长按 |
| composer 取消→回面板 | TP 双击 / Back | 右旋钮单击 | GO 双击 |

## 边界与异常

- **半帧误判**：连续 2 帧命中才触发；触发后确认模式已进入（overlay 出现）才发键
- **面板中途渲染变化**：选项块以帮助行为锚重新解析；帮助行消失 = 面板结束
- **composer 取消后 ESC 的副作用**：真机验证（面板输入态退回 vs 已关闭）；ESC 在两种状态均无害
- **切换会话/断连**：askPanelMode 随连接状态重置（连接断开即退出）
- **普通 panelMode 不变**：/effort、/model 路径零改动，检测只识别 AskUserQuestion 特征

## 测试

- JVM 单测：`detectAskPanel`（真机帧样本：正例 3 种布局变体、负例 /effort 面板、普通对话）、选项解析（含跨行副标题、Type something. 位置）
- 真机验证清单：
  1. Claude 弹出 AskUserQuestion → overlay 自动出现，选项齐全
  2. 滑动切换 → 高亮跟随（镜像输入行）
  3. 确认普通选项 → Claude 收到选择并回复 → overlay 自动退出
  4. 确认 Type something. → composer 打开 → 输入发送 → Claude 收到文本
  5. composer 取消 → 回面板 → 可继续选其他选项
  6. 长文本输入（paste-burst 回归）
  7. 取消面板（ESC）→ 回终端正常

## 不在范围

- 不解析除 AskUserQuestion 外的其他交互面板（遇新形态再扩展，同 pickerAxis 启发式策略）
- 不做本地选项过滤/搜索
- 不改变 command panel（/effort 等）现有行为
