# Visual Design

The visual design guidelines for Rokid AIUI aim to provide an AR experience that is consistent, immersive, readable, and suitable for deployment on actual devices. AIUI's visual system is not just a static color convention. It is a reusable visual infrastructure built on a host-driven theming mechanism and Design Tokens.

## Core Principles

- **Clarity**: Maintain readability across different lighting conditions, transparent display backgrounds, and viewing distances.
- **Hierarchy**: Build a stable information structure through surfaces, borders, typography levels, and spacing.
- **Brand consistency**: Continue Rokid's green visual language on AR devices to keep the experience consistent across pages and components.
- **Simplicity**: Avoid occupying the user's primary field of view and avoid excessive decoration that distracts from observing the real world.
- **Themeability**: Visual guidelines should be expressed through Design Tokens at the theme layer so hosts and applications can integrate and override them consistently.

## Theming Mechanism

AIUI visual design is recommended to be organized around CSS custom properties, that is, Design Tokens. A theme is essentially a normal CSS file used to define layout, colors, borders, border radius, spacing, and default component styles.

- **Host themes are injected first**: The host injects the theme first as the default token layer.
- **Application styles can still override**: Applications can still override variables in `app.wxss`, page styles, and component-local styles.
- **Theme structure should remain stable**: Different themes should reuse a consistent token structure whenever possible, so components do not need markup changes when switching themes.

## Recommended Theme

For the single-green display scenario on Rokid Glasses, the recommended built-in Ink theme is `yodaos-sprite-greenonly`. It uses a black background and green foreground as its core tone, with the goal of maintaining sufficient contrast, legibility, and comfort on single-green display hardware.

## Layout Tokens

The `yodaos-sprite-greenonly` theme provides layout recommendations suited to the current device form factor:

| Token | Purpose | Value |
| --- | --- | --- |
| `--app-width` | Recommended app width, suitable as the default width for floating panels and card-based agent interfaces. | `480px` |
| `--app-height-min` | Recommended minimum app height, suitable for compact surfaces or short information cards. | `120px` |
| `--app-height-max` | Recommended maximum app height. Beyond this value, a scrolling layout should be preferred. | `380px` |

This set of values works well as a default size reference for floating panels and card-based agent interfaces. Once the maximum height is exceeded, a scrolling layout is recommended instead of continuing to expand without bounds.

## Color Tokens

Because Rokid Glasses hardware is centered around green display output, the visual system should be built around green foregrounds and black backgrounds:

| Token | Purpose | Value |
| --- | --- | --- |
| `--color-primary` | Primary brand color and core accent color, used for titles, key data, and high-priority interactions. | `#40ff5e` |
| `--color-primary-60` | Medium emphasis color, used for secondary text, borders, and lower-emphasis accent layers. | `rgba(64, 255, 94, 0.6)` |
| `--color-primary-40` | Low emphasis color, used for light fills, highlighted surfaces, and background hint layers. | `rgba(64, 255, 94, 0.4)` |
| `--color-background` | Page-level background color, suitable as the base tone in transparent display environments. | `#000000` |
| `--color-surface` | Base surface background color for cards, panels, and containers. | `#000000` |
| `--color-surface-highlight` | A more emphasized background layer than normal surfaces, suitable for highlighted cards and demo blocks. | `var(--color-primary-40)` |
| `--color-text-primary` | Primary text color for titles, body text, and high-contrast labels. | `var(--color-primary)` |
| `--color-text-secondary` | Secondary text color for descriptions, hints, placeholders, and de-emphasized information. | `var(--color-primary-60)` |

```colors
#40FF5E, 1.0, 100%
#40FF5E, 0.6, 60%
#40FF5E, 0.4, 40%
#000000, 1.0, Background
```

This means that in AIUI, green should be the preferred color for titles, key data, interaction borders, and highlighted states, while translucent green layers should carry lower-emphasis information, panels, and input areas.

## Borders, Radius, And Spacing

To keep the interface structurally clear on a single-green display, it is recommended to follow the base tokens defined in the theme:

### Border Width

| Token | Purpose | Value |
| --- | --- | --- |
| `--border-width-thin` | Light outlines, dividers, and input borders. | `1px` |
| `--border-width-default` | Cards, regular panels, and most content containers. | `2px` |
| `--border-width-strong` | Stronger outline emphasis or key states. | `4px` |

### Border Color

| Token | Purpose | Value |
| --- | --- | --- |
| `--border-color-default` | Default neutral border color, suitable for most normal outlines. | `var(--color-primary-60)` |
| `--border-color-muted` | Softer border color, suitable for dividers and weak separators. | `var(--color-primary-40)` |
| `--border-color-accent` | Accent border color, suitable for highlighted containers close to the theme primary color. | `var(--color-primary)` |

### Radius

| Token | Purpose | Value |
| --- | --- | --- |
| `--radius-sm` | Small radius, suitable for inputs and compact elements. | `12px` |
| `--radius-md` | Standard radius, suitable for cards and most containers. | `12px` |

### Spacing

| Token | Purpose | Value |
| --- | --- | --- |
| `--spacing-sm` | Compact gaps, icon spacing, and small padding. | `8px` |
| `--spacing-md` | Standard component padding and regular spacing. | `12px` |
| `--spacing-lg` | Page padding, section spacing, and looser layouts. | `18px` |

Visually, this token set favors light fills, clear outlines, and stable whitespace, which suits transparent AR display environments better than relying on shadows.

## Component Tokens

In addition to general visual tokens, the theme also provides defaults for common components, making it suitable as a direct baseline for AIUI component design:

### Card

| Token | Purpose | Value |
| --- | --- | --- |
| `--card-padding` | Default inner padding for card body content. | `var(--spacing-md)` |
| `--card-border-width` | Default border width for cards. | `var(--border-width-default)` |
| `--card-border-color` | Default border color for cards. | `var(--border-color-default)` |
| `--card-cover-height` | Default height for card cover or media header area. | `180px` |

### Input

| Token | Purpose | Value |
| --- | --- | --- |
| `--input-background-color` | Default input background color. | `rgba(64, 255, 94, 0.08)` |
| `--input-border-color` | Default input border color. | `var(--border-color-default)` |
| `--input-placeholder-color` | Placeholder text color. | `var(--color-text-secondary)` |
| `--input-padding-y` | Vertical input padding. | `10px` |
| `--input-padding-x` | Horizontal input padding. | `14px` |

### Error State

| Token | Purpose | Value |
| --- | --- | --- |
| `--error-state-background` | Background color for error hint containers. | `rgba(64, 255, 94, 0.08)` |
| `--error-state-border-color` | Border color for error hint containers. | `var(--border-color-muted)` |
| `--error-state-text-color` | Text color for error hints. | `var(--color-text-primary)` |

The value of these tokens is that when you design cards, inputs, error hints, chart containers, and similar components, you do not need to decide visual parameters from scratch every time. You can directly reuse a unified theme constraint.

## Theme Example

Below is an example theme configuration from `packages/ink/themes/yodaos-sprite-greenonly.theme.css`, which can be used directly as a visual design baseline for AIUI:

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

## Design Recommendations

- Define visuals from tokens first instead of hardcoding colors and spacing directly in pages.
- In Rokid Glasses scenarios, use `yodaos-sprite-greenonly` as the default visual baseline.
- For emphasized states, prefer borders, text brightness, and surface hierarchy rather than complex shadows or multicolor decoration.
- Reuse existing tokens such as card, input, and error-state when designing components to keep the overall system consistent.
