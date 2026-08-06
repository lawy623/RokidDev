#!/bin/bash
set -e

export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"

cd "$(dirname "$0")"

case "${1:-build}" in
    build)
        ./gradlew assembleDebug
        echo "APK: app/build/outputs/apk/debug/app-debug.apk"
        ;;
    install)
        ./gradlew assembleDebug
        adb install -r app/build/outputs/apk/debug/app-debug.apk
        ;;
    run)
        ./gradlew assembleDebug
        adb install -r app/build/outputs/apk/debug/app-debug.apk
        adb shell am start -n com.rokid.terminal/.MainActivity
        ;;
    public-key)
        profile_id="${2:-cloud}"
        adb shell run-as com.rokid.terminal cat "files/ssh_public_key_${profile_id}.txt"
        ;;
    import-profile)
        profile_file="${2:?Usage: $0 import-profile /absolute/path/to/profile.json}"
        adb push "$profile_file" /data/local/tmp/rokid-terminal-profile.json
        adb shell run-as com.rokid.terminal cp /data/local/tmp/rokid-terminal-profile.json files/pending_profile.json
        adb shell rm /data/local/tmp/rokid-terminal-profile.json
        adb shell am force-stop com.rokid.terminal
        adb shell am start -n com.rokid.terminal/.MainActivity
        ;;
    log)
        adb logcat | grep -E "AndroidRuntime|RokidTerminal|RokidTermInput|SpeechRecognizer|System.err"
        ;;
    log-input)
        adb logcat -s RokidTermInput:I
        ;;
    devices)
        adb devices
        ;;
    *)
        echo "Usage: $0 {build|install|run|public-key [profile-id]|import-profile FILE|log|log-input|devices}"
        exit 1
        ;;
esac
