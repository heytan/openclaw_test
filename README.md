# agent_front_app (com.openclaw.car)

车载语音助手前端 App，整合了原 `caragent-app` (com.caragent.bootstrap) 的全部功能。

## 架构概览

单 App 架构，包含三个职责层：

- **UI 层** -- MainActivity + 三个 Fragment（人设 / 技能 / 记忆），负责音色选择、方言设置、技能管理、记忆编辑。
- **服务层** -- `com.openclaw.car.service` 包，提供 UI 自动化、Node.js 进程管理、开机自启、广播指令接收。
- **网络层** -- 通过 OkHttp 调用 TTS Adapter API (`/v1/voices`) 控制音色，StatusChecker 检测各服务状态。

## 组件列表

### Activity / Fragment

| 组件 | 说明 |
|------|------|
| `MainActivity` | 横屏主界面，ViewPager2 + TabLayout 管理 Fragment 页，Debug 模式底部状态栏显示 Gateway / Accessibility / TTS 状态 |
| `PersonaFragment` | 音色预设选择（7 个预设，RecyclerView 可滚动列表）+ 方言选择 + 自定义音色输入 |
| `SkillFragment` | 技能（SKILL.md）查看与编辑 |
| `MemoryFragment` | 记忆（记忆文件）查看与编辑 |

### Services (`com.openclaw.car.service`)

| 组件 | 说明 |
|------|------|
| `UiAutomationService` | AccessibilityService，单例模式；通过根节点遍历执行 setText / click / scroll / findAndClick |
| `UiCommandReceiver` | 接收 `com.caragent.UI_CMD` 广播，解析 action/text/target，委托给 UiAutomationService |
| `NodeProcessService` | 前台 Service，启动 car-router.js 等 Node.js 进程，30s 看门狗自动重启 |
| `NodeProcessManager` | Runtime.exec() 启动/停止/监控 Node.js 进程，管理环境变量 |
| `BootReceiver` | 接收 BOOT_COMPLETED 广播，自动启动 NodeProcessService |
| `CommandReceiver` | 接收 `com.caragent.START` / `com.caragent.STOP` 广播控制进程启停 |
| `UiResultProvider` | ContentProvider（authority: `com.caragent.bootstrap.uiresult`），供外部查询 UI 指令执行结果 |

### 其他

| 组件 | 说明 |
|------|------|
| `OpenClawApp` | Application 类，Production 模式下自动启动 NodeProcessService |
| `TtsApiClient` | OkHttp 封装，调用 TTS Adapter API 切换音色/方言 |
| `StatusChecker` | 周期检测 Gateway / Accessibility / TTS 服务状态 |
| `AudioPreviewPlayer` | 本地播放 assets 中的音色预览 WAV |
| `VoicePresetAdapter` | RecyclerView Adapter，7 个音色预设的列表展示与选择 |
| `PreferenceHelper` | SharedPreferences 封装，持久化用户设置 |
| `FileHelper` | 文件读写工具类 |

## 音色预设

7 个预设音色（原 4 个 + 新增 3 个）：

| # | 音色 | 说明 |
|---|------|------|
| 1 | 温柔女声 | sample_1.wav |
| 2 | 活泼女声 | sample_2.wav |
| 3 | 沉稳男声 | sample_3.wav |
| 4 | 知性女声 | sample_4.wav |
| 5 | 特朗普 | sample_5.wav |
| 6 | 林志玲 | sample_6.wav |
| 7 | 雷军 | sample_7.wav |

UI 从 2x2 按钮网格改为可滚动 RecyclerView 列表，以容纳更多预设。

## 编译

```bash
# 前置：JDK 11+, Android SDK (platform 34, build-tools 34)
export ANDROID_HOME=~/android-sdk

./gradlew assembleDebug
# 输出：app/build/outputs/apk/debug/app-debug.apk
```

## 部署

```bash
# 安装
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 启用无障碍服务
adb shell settings put secure enabled_accessibility_services \
  com.openclaw.car/com.openclaw.car.service.UiAutomationService

# 启动 Node.js 进程
adb shell am broadcast -a com.caragent.START

# UI 自动化测试
adb shell am broadcast -a com.caragent.UI_CMD \
  --es action setText --es text "平安金融中心" --es target "搜索"
```

## 车机开机自动化（Magisk）

车机已安装 Magisk v30.7，开机后全自动启动，无需手动 adb：

```
车机上电 → Magisk init → setenforce 0 → Android 启动 → App 自动启动 → Gateway + car-router
```

- `post-fs-data.d/setenforce.sh` — 关闭 SELinux
- `service.d/openclaw-boot.sh` — 启动 com.openclaw.car/.MainActivity

如需恢复原厂 init_boot：`dd if=/data/adb/init_boot_a.orig.img of=/dev/block/by-name/init_boot_a bs=4096`

## 开发机服务启动

车机端服务开机自启，开发机 TTS/STT 需手动启动：

```bash
conda activate voxcpm

# 1. VoxCPM2 TTS 推理（约 30s 加载模型）
python3 tts-adapter/voxcpm_server.py \
  --model-path ~/work/models/VoxCPM2 \
  --port 8000 --host 0.0.0.0 --gpu-memory 0.9 &

# 2. TTS 适配层（等 VoxCPM2 就绪后）
sleep 15 && python3 tts-adapter/adapter.py \
  --voxcpm-url http://localhost:8000 \
  --port 8091 --host 0.0.0.0 &

# 3. STT 服务（独立启动）
python3 stt-server.py --host 0.0.0.0 --port 8090 &
```

**注意：** `flash-attn` 与 PyTorch 2.12+ 不兼容，需使用 `torch==2.6.0+cu124`。

## 重要：Shell 脚本广播目标变更

合并后广播接收器的 ComponentName 已变更，所有通过 `am broadcast` 控制 UI 自动化的脚本需要更新目标：

```bash
# 旧（caragent-app，已废弃）
am broadcast -n com.caragent.bootstrap/.UiCommandReceiver -a com.caragent.UI_CMD ...

# 新（agent_front_app）
am broadcast -n com.openclaw.car/.service.UiCommandReceiver -a com.caragent.UI_CMD ...
```

注意：广播 action `com.caragent.UI_CMD` 和 ContentProvider authority `com.caragent.bootstrap.uiresult` 保持不变，无需修改。
