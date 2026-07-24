# RokidGame — Flappy Bird for Rokid Glass

头部控制的 Flappy Bird。无需校准 — 点头即飞。

## 操作方式

| 动作 | 效果 |
|---|---|
| 点头（下巴上扬） | 小鸟扇翅 |
| 触摸板点击 | 开始游戏 / 重试 / 选择菜单项 |
| 触摸板上下滑动 | 菜单导航 |
| 返回键 | 退出（标题页）/ 返回标题（其他页面） |

## 技术实现

- **姿态传感器**: 优先 `TYPE_ROTATION_VECTOR`，回退 `TYPE_GAME_ROTATION_VECTOR`（Rokid Glass 实际暴露的传感器）
- **趋势检测**: 追踪俯仰角的上升/下降沿，波谷之上 3° 触发扇翅 — 无需绝对角度校准
- **纯 Canvas 渲染**: 绿色单色，无外部依赖
- **排行榜**: SharedPreferences 存本地 Top 5

### 游戏参数

| 参数 | 值 |
|---|---|
| 扇翅力度 (`flapV`) | -12 |
| 管道速度 (`pipeSpeed`) | 5.4 |
| 管道间隙 (`pipeGap`) | 300px |
| 重力 (`gravity`) | 0.4 |

## 文件结构

```
RokidGame/
├── app/src/main/java/com/rokid/game/flappy/
│   ├── MainActivity.kt   # 全屏 Activity，生命周期管理
│   └── GameView.kt       # 所有游戏逻辑、渲染、传感器处理（~300 行）
├── dev.sh                 # 构建/安装/日志 一键脚本
├── build.gradle.kts
└── settings.gradle.kts
```

## 构建 & 部署

```bash
./dev.sh build     # 编译 APK
./dev.sh install   # 安装到已连接眼镜
./dev.sh run       # 构建 + 安装 + 启动
./dev.sh log       # 实时崩溃日志
./dev.sh devices   # 列出 ADB 设备
```
