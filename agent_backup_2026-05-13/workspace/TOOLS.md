# 车机环境
- 型号：比亚迪仰望 DiLink 300（MediaTek MTK）
- 系统：Android 14（SDK 34），SELinux permissive
- 屏幕：1728×1888 横屏，320dpi
- CPU：8 核，内存：10GB
- TTS：com.byd.autovoice.tts（BYD AutoVoice）
- 语音识别：com.iflytek.inputmethod（讯飞输入法）
- 音频：Dynaudio 丹拿音响，audio_flinger 含 AGC/NS 预处理

# 关键工具与限制

## nav-search.sh / nav-select.sh
- nav-search.sh：打开地图 → 点击搜索框 → 输入目的地 → 提交搜索 → dump UI 布局
- nav-select.sh：通过 AccessibilityService (com.caragent.bootstrap) 点击结果 → 点击"去这里"
- 依赖 car-router.js 在 18800 端口运行
- 坐标基于 1728×1888 分辨率，切换分辨率后失效
- 搜索超时 8 秒，选择超时 5 秒

## car-router.js (port 18800)
- 接收 HTTP POST /command，JSON body: {"action":"...", "key":"value"}
- 内部调用 execSync 执行 shell 命令
- 日志写入 /data/local/tmp/car-agent.log

## car-cmd.sh
- 封装 car-router HTTP 调用的命令行工具
- 例：sh /data/local/tmp/car-cmd.sh nav_search dest=大梅沙
- 依赖 node-termux 和 LD_LIBRARY_PATH

## media_session
- 仅音乐 app（com.byd.mediacenter）在前台时有效
- play/pause/next/previous 四指令
- 播放前必须先 am start 打开 app，等待 1-2 秒

## input tap
- 坐标硬编码（回家 241 488，去公司 543 488）
- 屏幕分辨率改变后必须重新获取坐标

## Shell 可用
- am, pm, cmd, input, settings, dumpsys
- grep, sed, awk 可用
- 无 curl，HTTP 调用用 node-termux
- 无 jq，JSON 解析用 node 或 grep/sed

# 权限边界
- /data/local/tmp/ 可读写
- /sdcard/ 普通 app 不可写
- /data/data/ 仅本 app 可访问
- 应用包名：com.openclaw.car（DiDiClaw 配置 App）
