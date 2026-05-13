#!/bin/bash
ADB="/home/p30021253244/android-sdk/platform-tools/adb"
$ADB root 2>/dev/null
$ADB shell "setenforce 0" 2>/dev/null
$ADB shell "chmod 755 /data/local/tmp/openclaw-home/.openclaw" 2>/dev/null
$ADB shell "getenforce"
