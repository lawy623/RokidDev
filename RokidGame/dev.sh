#!/bin/bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"

cd "$(dirname "$0")"

case "${1:-build}" in
    build)
        ./gradlew assembleDebug
        echo ""
        echo "APK: app/build/outputs/apk/debug/app-debug.apk"
        ;;
    install)
        ./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
        ;;
    run)
        ./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk && adb shell am start -n com.rokid.game.flappy/.MainActivity
        ;;
    log)
        adb logcat | grep -E "AndroidRuntime|Flappy|System.err"
        ;;
    devices)
        adb devices
        ;;
    *)
        echo "Usage: $0 {build|install|run|log|devices}"
        ;;
esac
