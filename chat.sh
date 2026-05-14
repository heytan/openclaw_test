#!/bin/bash
# 与车机 Agent 对话
# 使用前：~/android-sdk/platform-tools/adb forward tcp:18801 tcp:18801

GATEWAY="http://localhost:18801"
TOKEN="fe3936a8d8dafeec8efb6d801863eb00c4c08298555a4817"

echo "🚗 DiDiClaw Agent 对话"
echo "___________________________________"

while true; do
    printf "\n👤 你: "
    read -r input
    [ "$input" = "/exit" ] && { echo "再见！"; break; }
    [ -z "$input" ] && continue

    printf "🚗 Agent: "
    export CHAT_INPUT="$input"
    python3 -c "
import os, urllib.request, json
msg = os.environ['CHAT_INPUT']
data = json.dumps({'messages': [{'role': 'user', 'content': msg}], 'max_tokens': 500}).encode()
req = urllib.request.Request(
    '${GATEWAY}/v1/chat/completions',
    data=data,
    headers={'Authorization': 'Bearer ${TOKEN}', 'Content-Type': 'application/json'}
)
try:
    with urllib.request.urlopen(req, timeout=120) as resp:
        body = json.loads(resp.read())
        print(body['choices'][0]['message']['content'])
except Exception as e:
    print(f'(请求失败: {e})')
"
done
