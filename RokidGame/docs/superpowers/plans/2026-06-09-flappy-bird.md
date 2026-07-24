# Flappy Bird for Rokid Glass — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a head-controlled Flappy Bird clone that runs on Rokid Glass and can be tested on-device.

**Architecture:** Single-activity Android app (API 26+, Kotlin). Custom View with game loop (Handler.postDelayed @ 60fps). `SensorManager.TYPE_ROTATION_VECTOR` for pitch detection. Canvas rendering in green monochrome. No external dependencies beyond Android SDK.

**Tech Stack:** Kotlin, Android SDK, Gradle Kotlin DSL

---

### Task 1: Project Scaffolding

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/values/styles.xml`

- [ ] **Step 1: Create settings.gradle.kts**

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "RokidFlappy"
include(":app")
```

- [ ] **Step 2: Create root build.gradle.kts**

```kotlin
plugins {
    id("com.android.application") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.20" apply false
}
```

- [ ] **Step 3: Create gradle.properties**

```properties
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

- [ ] **Step 4: Create app/build.gradle.kts**

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rokid.game.flappy"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rokid.game.flappy"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
}
```

- [ ] **Step 5: Create AndroidManifest.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-feature android:name="android.hardware.sensor.gyroscope" android:required="false" />

    <application
        android:allowBackup="true"
        android:label="Flappy">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/GameTheme"
            android:configChanges="orientation|keyboardHidden">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 6: Create styles.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="GameTheme" parent="@android:style/Theme.NoTitleBar.Fullscreen">
        <item name="android:windowBackground">@android:color/black</item>
        <item name="android:windowFullscreen">true</item>
    </style>
</resources>
```

- [ ] **Step 7: Build to verify scaffolding**

```bash
cd /Users/lawy623/Desktop/VibeCode/RokidDev/RokidGame
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL (compiles but has no source yet — no-op build)

If `gradlew` doesn't exist, generate it first from any Android project or install via `brew install gradle` and run `gradle wrapper`.

---

### Task 2: MainActivity — Fullscreen Entry Point

**Files:**
- Create: `app/src/main/java/com/rokid/game/flappy/MainActivity.kt`

- [ ] **Step 1: Create MainActivity.kt**

```kotlin
package com.rokid.game.flappy

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager

class MainActivity : Activity() {

    private lateinit var gameView: GameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        gameView = GameView(this)
        setContentView(gameView)
    }

    override fun onResume() {
        super.onResume()
        gameView.start()
    }

    override fun onPause() {
        super.onPause()
        gameView.stop()
    }
}
```

- [ ] **Step 2: Build to verify**

```bash
./gradlew assembleDebug
```

Expected: BUILD FAILED — GameView doesn't exist yet (expected, created next task)

---

### Task 3: GameView — Game Loop, Rendering, Physics, Input

**Files:**
- Create: `app/src/main/java/com/rokid/game/flappy/GameView.kt`

This is the entire game in one file. Create it with all game mechanics included.

- [ ] **Step 1: Create GameView.kt**

```kotlin
package com.rokid.game.flappy

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import kotlin.math.abs

class GameView(context: Context) : View(context), SensorEventListener {

    // === Drawing ===
    private val strokePaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }
    private val fillPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        color = Color.GREEN
        textSize = 48f
        isAntiAlias = true
    }
    private val centerTextPaint = Paint().apply {
        color = Color.GREEN
        textSize = 56f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    // === Game Loop ===
    private val handler = Handler(Looper.getMainLooper())
    private val gameLoop = object : Runnable {
        override fun run() {
            if (state == GameState.PLAYING) update()
            invalidate()
            handler.postDelayed(this, 16)
        }
    }

    // === Sensor ===
    private var sensorManager: SensorManager? = null
    private var pitch = 0f // radians, 0=level, positive=looking down
    private var hasSensor = false

    // === Game State ===
    private enum class GameState { READY, PLAYING, DEAD }
    private var state = GameState.READY

    // === Bird ===
    private var birdY = 0f
    private var birdVelocity = 0f
    private val birdX = 160f
    private val birdSize = 36f

    // === Pipes ===
    private data class PipePair(var x: Float, val gapCenterY: Float, var scored: Boolean = false)
    private val pipes = mutableListOf<PipePair>()
    private val pipeWidth = 90f
    private val pipeGapHeight = 320f
    private val pipeSpeed = 7f
    private val pipeSpawnInterval = 100
    private var pipeFrameCount = 0

    // === Score ===
    private var score = 0

    // === Screen ===
    private var screenW = 0
    private var screenH = 0
    private val groundHeight = 4f

    // === Physics ===
    private val gravity = 0.55f
    private val pitchSensitivity = 4.0f
    private val pitchDeadZone = Math.toRadians(5.0).toFloat()
    private val maxVelocity = 14f
    private val minVelocity = -14f

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun start() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        hasSensor = rotationSensor != null
        sensorManager?.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
        handler.post(gameLoop)
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        handler.removeCallbacks(gameLoop)
    }

    // === SensorEventListener ===
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientation)
            pitch = orientation[1] // pitch in radians
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // === Key Input ===
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            when (state) {
                GameState.READY -> state = GameState.PLAYING
                GameState.DEAD -> resetGame()
                GameState.PLAYING -> {} // ignore during play; game is head-controlled
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // === Update ===
    private fun update() {
        // Bird physics: pitch drives vertical velocity
        // Negative pitch = head up → bird goes up
        // Positive pitch = head down → bird goes down
        val pitchInput = if (abs(pitch) < pitchDeadZone) 0f else pitch * pitchSensitivity
        birdVelocity += gravity + pitchInput
        birdVelocity = birdVelocity.coerceIn(minVelocity, maxVelocity)
        birdY += birdVelocity

        // Spawn pipes
        pipeFrameCount++
        if (pipeFrameCount >= pipeSpawnInterval) {
            pipeFrameCount = 0
            val margin = pipeGapHeight / 2 + 40f
            val gapCenter = margin + (Math.random() * (screenH - 2 * margin)).toFloat()
            pipes.add(PipePair(x = screenW.toFloat(), gapCenterY = gapCenter))
        }

        // Move pipes and score
        val iterator = pipes.iterator()
        while (iterator.hasNext()) {
            val pipe = iterator.next()
            pipe.x -= pipeSpeed
            if (!pipe.scored && pipe.x + pipeWidth < birdX) {
                pipe.scored = true
                score++
            }
            if (pipe.x + pipeWidth < 0) iterator.remove()
        }

        // Collision: screen bounds
        if (birdY <= 0 || birdY + birdSize >= screenH - groundHeight) {
            state = GameState.DEAD
            return
        }

        // Collision: pipes
        for (pipe in pipes) {
            if (birdX + birdSize > pipe.x && birdX < pipe.x + pipeWidth) {
                val gapTop = pipe.gapCenterY - pipeGapHeight / 2
                val gapBottom = pipe.gapCenterY + pipeGapHeight / 2
                if (birdY < gapTop || birdY + birdSize > gapBottom) {
                    state = GameState.DEAD
                    return
                }
            }
        }
    }

    // === Drawing ===
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        screenW = w
        screenH = h
        if (birdY == 0f) resetGame()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)

        // Ground line
        canvas.drawRect(0f, screenH - groundHeight, screenW.toFloat(), screenH.toFloat(), fillPaint)

        // Pipes
        for (pipe in pipes) {
            val gapTop = pipe.gapCenterY - pipeGapHeight / 2
            val gapBottom = pipe.gapCenterY + pipeGapHeight / 2
            canvas.drawRect(pipe.x, 0f, pipe.x + pipeWidth, gapTop, strokePaint)
            canvas.drawRect(pipe.x, gapBottom, pipe.x + pipeWidth, screenH - groundHeight, strokePaint)
        }

        // Bird
        canvas.drawRect(birdX, birdY, birdX + birdSize, birdY + birdSize, fillPaint)

        // Score
        canvas.drawText("$score", 20f, 55f, textPaint)

        // Overlay text
        when (state) {
            GameState.READY -> {
                canvas.drawText("Click to start", screenW / 2f, screenH / 2f, centerTextPaint)
            }
            GameState.DEAD -> {
                canvas.drawText("Score: $score", screenW / 2f, screenH / 2f - 40f, centerTextPaint)
                canvas.drawText("Click to retry", screenW / 2f, screenH / 2f + 30f, centerTextPaint)
            }
            GameState.PLAYING -> {} // no overlay during play
        }
    }

    private fun resetGame() {
        birdY = screenH / 2f
        birdVelocity = 0f
        pipes.clear()
        pipeFrameCount = 0
        score = 0
        state = GameState.READY
    }
}
```

- [ ] **Step 2: Build**

```bash
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL

---

### Task 4: Deploy & Test on Rokid Glass

- [ ] **Step 1: Verify ADB connection**

```bash
adb devices
```

Expected output: list with a device ID and "device" status. If empty, check USB connection and enable USB debugging on glasses (Settings → Developer options).

- [ ] **Step 2: Install APK**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: "Success"

- [ ] **Step 3: Launch the game**

```bash
adb shell am start -n com.rokid.game.flappy/.MainActivity
```

Expected: App launches on glasses. See green Flappy Bird screen.

- [ ] **Step 4: Play a test round**

Wear the glasses and:
1. See "Click to start" — press TouchPad center
2. Bird starts falling — tilt head up to fly, level to fall
3. Navigate through pipe gaps
4. On collision → "Score: N / Click to retry"
5. Click to restart

- [ ] **Step 5: Tune physics if needed**

If the game feels wrong, adjust these constants in `GameView.kt` and rebuild:
- `gravity` (line ~78): lower = bird falls slower
- `pitchSensitivity` (line ~79): higher = head tilt more responsive
- `pipeGapHeight` (line ~60): larger = easier
- `pipeSpawnInterval` (line ~62): larger = more time between pipes

---

### Post-Development: Development Loop

After initial testing, the iterative cycle is:

```bash
# 1. Edit code in GameView.kt
# 2. Rebuild
./gradlew assembleDebug
# 3. Reinstall
adb install -r app/build/outputs/apk/debug/app-debug.apk
# 4. Test on glasses
# 5. If crash, check logs
adb logcat | grep -E "AndroidRuntime|Flappy|System.err"
```
