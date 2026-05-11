# DiDiClaw 车机安卓应用 — 开发日志

**日期**：2026-05-11  
**仓库**：https://gitee.com/zardhey/agent_front_app  
**车机型号**：比亚迪 DiLink300 (MediaTek MTK)

---

## 一、项目初始化

从《车机安卓应用开发说明书》出发，从零构建完整 Android 项目。

| 项 | 内容 |
|----|------|
| 开发语言 | Kotlin |
| 最低 SDK | 26 (Android 8.0) |
| 目标 SDK | 33 (Android 13) |
| UI 框架 | Material 3 (强制浅色) |
| 包名 | `com.openclaw.car` |
| 应用名 | **DiDiClaw** |

**项目结构**：
```
agent_front_app/
├── build.gradle.kts              # AGP 7.4.2 + Kotlin 1.8.22
├── settings.gradle.kts
├── gradle/wrapper/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml   # 横屏锁定 + 文件权限声明
│       ├── java/com/openclaw/car/
│       │   ├── MainActivity.kt
│       │   ├── adapter/
│       │   │   ├── ViewPagerAdapter.kt
│       │   │   └── ItemListAdapter.kt
│       │   ├── fragment/
│       │   │   ├── PersonaFragment.kt
│       │   │   ├── SkillFragment.kt
│       │   │   └── MemoryFragment.kt
│       │   └── util/
│       │       ├── FileHelper.kt
│       │       └── PreferenceHelper.kt
│       └── res/  (layouts, drawables, values, mipmap)
```

---

## 二、核心功能实现

### 2.1 三标签页架构
- **ViewPager2** + **TabLayout** + **TabLayoutMediator**
- 人设 / 技能 / 记忆 三个标签页，左右滑动切换

### 2.2 人设配置页
- 3 张圆角卡片：实干派、贴心友人、全能派
- 点击卡片 → 选中高亮（蓝色边框 + 浮起）→ 写入 `persona.txt`
- 4 种音色按钮（2×2 网格）：标准男声、标准女声、温柔女声、沉稳男声
- 选中按钮 → 蓝色填充 + 白字，未选中 → 灰底 + 深色字
- **TTS 音色预览**：点击按钮播放不同音调/语速的语音样本
- SharedPreferences 持久化上次选择

### 2.3 技能展示页
- RecyclerView 只读列表
- 从 `skills.txt` 读取，每行一条，按冒号分列展示标题+描述
- 文件不存在时显示空状态提示

### 2.4 记忆展示页
- RecyclerView 只读列表
- 从 `memory.txt` 读取，按行展示
- 文件不存在时显示空状态提示

### 2.5 调试/生产模式切换
- 顶部标签栏左侧 **MaterialSwitch**
- **调试模式**（蓝色）：使用应用内部存储 `filesDir`，预置模拟数据
- **生产模式**（灰色）：使用 `getExternalFilesDir()` 外部存储，与 OpenClaw Agent 共享文件
- 切换后自动重启并重新加载数据
- 生产模式不弹权限框（Android 10+ 无需额外权限访问自有目录）

---

## 三、龙虾形象设计

### 3.1 启动图标
- 侧面龙虾轮廓（橘红色，头朝右，弧形身段，尾扇，触须，螯足，步足）
- 浅蓝圆形背景，缩放到 Adaptive Icon 安全区域内

### 3.2 人设卡片图标（侧面造型）
| 人设 | 颜色 | 特征 |
|------|------|------|
| 实干派 | 橘红 | 坚毅眼神 + 扳手 + 浓眉 |
| 贴心友人 | 粉色 | 水灵大眼 + 微笑 + 腮红 + 爱心 |
| 全能派 | 亮橘 | 蓝眼镜 + 眨眼 + 魔法星 + 学位帽 |

---

## 四、技术细节

### 4.1 文件路径

**调试模式**：
- 基础目录：`context.filesDir/openclaw_debug/`
- `persona.txt`, `voice_config.txt`, `skills.txt`, `memory.txt`

**生产模式**：
- 基础目录：`context.getExternalFilesDir(null)/openclaw/`
- `agent/persona.txt`, `tts/voice_config.txt`, `agent/skills.txt`, `agent/memory.txt`

### 4.2 内置数据

**人设提示词**：
- 实干派：`你是一个务实高效的助手，专注解决问题，回答简洁直接，不冗余`
- 贴心友人：`你是一个温暖贴心的朋友，语气亲切柔和，善于倾听和安慰`
- 全能派：`你是一个全能助手，精通各类知识，能解答问题、提供建议、辅助决策`

**音色配置**：`standard_male`, `standard_female`, `gentle_female`, `calm_male`

**模拟技能**（7条）：导航助手、语音控制、日程管理、媒体播放、车辆诊断、天气查询、智能家居

**模拟记忆**（7条）：空调偏好、导航目的地、音乐喜好、座椅位置、加油提醒等

### 4.3 TTS 音色参数

| 音色 | 音调 | 语速 |
|------|------|------|
| 标准男声 | 1.00 | 1.00 |
| 标准女声 | 1.15 | 0.95 |
| 温柔女声 | 1.25 | 0.82 |
| 沉稳男声 | 0.88 | 0.85 |

---

## 五、部署记录

### 5.1 服务器环境搭建
- JDK 11 (系统自带，无需升级)
- Android SDK 组件手动下载（dl.google.com）：
  - platform-tools (含 adb)
  - build-tools 33.0.1
  - platforms android-33
- Gradle 8.2 从腾讯镜像下载

### 5.2 车机连接
- USB 连接比亚迪 DiLink300 (MediaTek 0e8d:201c)
- `pkexec` 写入 udev 规则解决 USB 权限
- ADB 设备 ID：`LZBYDUMNB6RW7X5P`

### 5.3 构建安装
```bash
export ANDROID_HOME=~/android-sdk
~/gradle-local/gradle-8.2/bin/gradle assembleDebug
~/android-sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 六、已修复问题

| 问题 | 原因 | 修复 |
|------|------|------|
| 生产模式切换弹权限框报错 | 车机不支持 ACTION_MANAGE_ALL_FILES 页面 | 改用 getExternalFilesDir，无需权限 |
| 音色选中状态丢失 | MaterialButton checkable 无 RadioGroup 约束 | 代码完全控制颜色样式，不依赖 checkable |
| 启动图标被裁剪 | 龙虾太大超出安全区 | 添加 group scale 0.58 缩放 |
| 构建失败 AAPT circle 不支持 | compileSdk 33 不支持 circle 标签 | 全部改用 path 圆弧 |
| 中心不平 | centerHorizontal 不是有效 gravity | 改为 center_horizontal |
| AGP 8.2 需 Java 17 | JDK 11 不兼容 | 降级到 AGP 7.4.2 + compileSdk 33 |
| CardView.strokeColor 编译失败 | AndroidX CardView 无此 setter | 改用 cardElevation 区分选中态 |
