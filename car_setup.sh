#!/bin/bash
# DiDiClaw 车机环境初始化 — 车机每次重启后执行一次
ADB="/home/p30021253244/android-sdk/platform-tools/adb"
$ADB root 2>/dev/null
$ADB shell "setenforce 0" 2>/dev/null
$ADB shell "getenforce"
