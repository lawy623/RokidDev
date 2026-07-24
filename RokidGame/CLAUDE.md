# CLAUDE.md

This file provides guidance to Claude Code when working in this project.

## Flappy Bird for Rokid Glass

Head-controlled Flappy Bird clone. Complete and playable.

### Controls

- **Head nod up** = bird flaps (trend-based detection, each nod-up cycle = one flap)
- **TP click** = start game / retry / select menu item
- **TP swipe up/down** = navigate menu (title screen)
- **Back key** = exit to launcher (title screen) / back to title (other screens)

### Key Technical Details

- Pitch control uses **trend-based edge detection**: tracks rise/fall of pitch angle, triggers flap when D rises 3° above the last trough. No absolute angle calibration needed.
- Sensor: tries `TYPE_ROTATION_VECTOR` first, falls back to `TYPE_GAME_ROTATION_VECTOR` (what Rokid Glass actually exposes)
- All rendering is Canvas-based green monochrome, no external dependencies
- Scores stored in SharedPreferences, top 5 leaderboard
- Parameters: `flapV = -12f` (flap strength), `pipeSpeed = 5.4f`, `pipeGap = 300f`, `gravity = 0.4f`

### File Structure

```
app/src/main/java/com/rokid/game/flappy/
├── MainActivity.kt   # Fullscreen Activity, lifecycle management
└── GameView.kt       # All game logic, rendering, sensor handling (~300 lines)
```

### Build & Deploy

```bash
./dev.sh build     # compile APK
./dev.sh install   # install to connected glasses
./dev.sh run       # build + install + launch
./dev.sh log       # watch crash logs
./dev.sh devices   # list ADB devices
```
