# 视觉设计

Rokid AIUI 的视觉设计规范旨在为用户提供一致、沉浸、可读且适合设备侧落地的 AR 体验。AIUI 的视觉系统不是单纯的静态配色约定，而是基于宿主驱动的主题机制与 Design Tokens 建立的一套可复用视觉基础设施。

## 核心原则

- **清晰性**：在不同光照环境、透明显示背景和远近视距下保持可读性。
- **层次感**：通过 surface、边框、文字层级和间距建立稳定的信息结构。
- **品牌一致性**：延续 Rokid 在 AR 设备上的绿色主题语言，保证跨页面和跨组件体验一致。
- **简洁性**：避免占用用户主视角，不用过度装饰干扰真实世界观察。
- **可主题化**：视觉规范应通过 Design Tokens 落到主题层，便于宿主和应用侧统一接入与覆盖。

## 主题机制

AIUI 的视觉设计建议基于 CSS 自定义属性，也就是 Design Tokens 组织。一个主题本质上就是一份普通 CSS，用来定义布局、颜色、边框、圆角、间距以及组件默认样式。

- **宿主主题优先注入**：宿主会先注入主题，作为默认 token 层。
- **应用样式可继续覆盖**：应用仍然可以在 `app.wxss`、页面样式和组件局部样式中覆盖变量。
- **主题结构应保持稳定**：不同主题尽量复用一致的 token 结构，这样组件切换主题时不需要改动标记结构。

## 推荐主题

对于 Rokid Glasses 的单绿色显示场景，推荐使用 Ink 内置主题 `yodaos-sprite-greenonly`。它以黑色背景和绿色前景作为核心基调，目标是在单绿色显示硬件上保持足够的对比度、辨识度与舒适性。

## 布局 Tokens

`yodaos-sprite-greenonly` 主题提供了适合当前设备形态的布局建议：

| Token | 用途 | 值 |
| --- | --- | --- |
| `--app-width` | 建议的应用宽度，适合作为浮层和卡片式智能体界面的默认宽度。 | `480px` |
| `--app-height-min` | 建议的最小应用高度，适合紧凑型 surface 或较短信息卡片。 | `120px` |
| `--app-height-max` | 建议的最大应用高度，超过后应优先进入滚动布局。 | `380px` |

这组设置适合作为浮层、卡片式智能体界面的默认尺寸参考。超过最大高度后，建议进入滚动布局，而不是继续无边界扩展。

## 颜色 Tokens

由于 Rokid Glasses 硬件以绿色显示为核心特征，视觉系统应围绕绿色前景与黑色背景构建：

| Token | 用途 | 值 |
| --- | --- | --- |
| `--color-primary` | 主品牌色和核心强调色，用于标题、关键数据和高优先级交互。 | `#40ff5e` |
| `--color-primary-60` | 中强调色，用于次一级文字、边框和弱化强调层。 | `rgba(64, 255, 94, 0.6)` |
| `--color-primary-40` | 低强调色，用于轻量填充、高亮 surface 和背景提示层。 | `rgba(64, 255, 94, 0.4)` |
| `--color-background` | 页面级背景色，适合作为透明显示环境下的基础底色。 | `#000000` |
| `--color-surface` | 基础 surface 背景色，用于卡片、面板和容器。 | `#000000` |
| `--color-surface-highlight` | 比普通 surface 更强调的背景层，适合高亮卡片和 demo block。 | `var(--color-primary-40)` |
| `--color-text-primary` | 主文字色，用于标题、正文和高对比标签。 | `var(--color-primary)` |
| `--color-text-secondary` | 次文字色，用于描述、提示、placeholder 和弱化信息。 | `var(--color-primary-60)` |

```colors
#40FF5E, 1.0, 100%
#40FF5E, 0.6, 60%
#40FF5E, 0.4, 40%
#000000, 1.0, Background
```

这意味着在 AIUI 中应优先使用绿色来表达标题、关键数据、交互边框和高亮状态，使用透明绿色层来承载弱化信息、面板和输入区域。

## 边框、圆角与间距

为了让界面在单绿色显示上仍然具备清晰结构，推荐直接遵循主题里的基础 token：

### 边框宽度

| Token | 用途 | 值 |
| --- | --- | --- |
| `--border-width-thin` | 轻量 outline、divider 和输入框边框。 | `1px` |
| `--border-width-default` | 卡片、普通面板和大多数内容容器。 | `2px` |
| `--border-width-strong` | 更强的轮廓强调或重点状态。 | `4px` |

### 边框颜色

| Token | 用途 | 值 |
| --- | --- | --- |
| `--border-color-default` | 默认中性边框色，适合大多数普通外框。 | `var(--color-primary-60)` |
| `--border-color-muted` | 更柔和的边框色，适合 divider 和弱分隔线。 | `var(--color-primary-40)` |
| `--border-color-accent` | 强调边框色，适合贴近主题主色的高亮容器。 | `var(--color-primary)` |

### 圆角

| Token | 用途 | 值 |
| --- | --- | --- |
| `--radius-sm` | 小圆角，适合输入框和紧凑型元素。 | `12px` |
| `--radius-md` | 标准圆角，适合卡片和大部分容器。 | `12px` |

### 间距

| Token | 用途 | 值 |
| --- | --- | --- |
| `--spacing-sm` | 紧凑 gap、图标间距和小 padding。 | `8px` |
| `--spacing-md` | 标准组件 padding 和常规间距。 | `12px` |
| `--spacing-lg` | 页面 padding、section 间隔和宽松布局。 | `18px` |

在视觉上，这组 token 倾向于“轻填充 + 明确轮廓 + 稳定留白”，比依赖阴影更适合透明 AR 显示环境。

## 组件 Tokens

除了通用视觉 token 外，主题还为常见组件提供了默认值，适合直接作为 AIUI 组件设计的基准：

### Card

| Token | 用途 | 值 |
| --- | --- | --- |
| `--card-padding` | card 主体内容的默认内边距。 | `var(--spacing-md)` |
| `--card-border-width` | card 默认边框宽度。 | `var(--border-width-default)` |
| `--card-border-color` | card 默认边框颜色。 | `var(--border-color-default)` |
| `--card-cover-height` | card cover 或媒体头图区的默认高度。 | `180px` |

### Input

| Token | 用途 | 值 |
| --- | --- | --- |
| `--input-background-color` | 输入框默认背景色。 | `rgba(64, 255, 94, 0.08)` |
| `--input-border-color` | 输入框默认边框颜色。 | `var(--border-color-default)` |
| `--input-placeholder-color` | placeholder 文字颜色。 | `var(--color-text-secondary)` |
| `--input-padding-y` | 输入框纵向内边距。 | `10px` |
| `--input-padding-x` | 输入框横向内边距。 | `14px` |

### Error State

| Token | 用途 | 值 |
| --- | --- | --- |
| `--error-state-background` | 错误提示容器的背景色。 | `rgba(64, 255, 94, 0.08)` |
| `--error-state-border-color` | 错误提示容器的边框颜色。 | `var(--border-color-muted)` |
| `--error-state-text-color` | 错误提示文字颜色。 | `var(--color-text-primary)` |

这类 token 的价值在于：当你设计卡片、输入框、错误提示、图表容器等组件时，不需要每次重新决定视觉参数，而是直接复用统一的主题约束。

## 主题设置示例

下面是来自 `packages/ink/themes/yodaos-sprite-greenonly.theme.css` 的主题设置示例，可直接作为 AIUI 视觉设计的参考基线：

```css
:root {
  --app-width: 480px;
  --app-height-min: 120px;
  --app-height-max: 380px;

  --color-primary: #40ff5e;
  --color-primary-60: rgba(64, 255, 94, 0.6);
  --color-primary-40: rgba(64, 255, 94, 0.4);
  --color-background: #000000;
  --color-surface: #000000;
  --color-surface-highlight: var(--color-primary-40);
  --color-text-primary: var(--color-primary);
  --color-text-secondary: var(--color-primary-60);

  --border-width-thin: 1px;
  --border-width-default: 2px;
  --border-width-strong: 4px;
  --border-color-default: var(--color-primary-60);
  --border-color-muted: var(--color-primary-40);
  --border-color-strong: var(--color-primary);
  --border-color-accent: var(--color-primary);

  --radius-sm: 12px;
  --radius-md: 12px;

  --spacing-sm: 8px;
  --spacing-md: 12px;
  --spacing-lg: 18px;

  --card-padding: var(--spacing-md);
  --card-border-width: var(--border-width-default);
  --card-border-color: var(--border-color-default);

  --input-background-color: rgba(64, 255, 94, 0.08);
  --input-border-width: var(--border-width-thin);
  --input-border-color: var(--border-color-default);
  --input-placeholder-color: var(--color-text-secondary);
  --input-padding-y: 10px;
  --input-padding-x: 14px;
  --input-radius: var(--radius-sm);
}
```

## 设计建议

- 优先从 token 出发定义视觉，而不是直接在页面里写死颜色和间距。
- 在 Rokid Glasses 场景下，默认以 `yodaos-sprite-greenonly` 作为视觉基线。
- 对需要强调的状态，优先使用边框、文字亮度和 surface 层级，而不是复杂阴影和多色装饰。
- 组件设计时优先复用 card、input、error-state 等已有 token，保证整体一致性。
