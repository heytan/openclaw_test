#!/bin/bash
# Sync SOUL.md from DiDiClaw app sandbox to OpenClaw Agent workspace.
# Run this after switching persona in production mode.

ADB="/home/p30021253244/android-sdk/platform-tools/adb"
APP_FILE="/data/data/com.openclaw.car/files/openclaw/SOUL.md"
AGENT_FILE="/data/local/tmp/openclaw-home/.openclaw/workspace/SOUL.md"

echo "=== Syncing SOUL.md to Agent ==="
$ADB shell "run-as com.openclaw.car cat $APP_FILE 2>/dev/null" > /tmp/soul_sync.tmp

if [ -s /tmp/soul_sync.tmp ]; then
    $ADB push /tmp/soul_sync.tmp "$AGENT_FILE" 2>&1
    echo "OK — $(wc -c < /tmp/soul_sync.tmp) bytes written"
    rm /tmp/soul_sync.tmp
else
    echo "FAILED — app hasn't written SOUL.md yet. Switch persona in production mode first."
    rm /tmp/soul_sync.tmp
    exit 1
fi
