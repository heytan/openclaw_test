# DiDiClaw 车机安卓应用 — 开发日志

**仓库**：https://gitee.com/zardhey/agent_front_app  
**车机**：比亚迪仰望 DiLink300 (MediaTek MT6991)  
**开发周期**：2026-05-11 ~ 2026-05-13

---

## 一、项目初始化 (05-11)

从《车机安卓应用开发说明书》出发，从零构建完整 Android 项目。

| 项 | 内容 |
|----|------|
| 开发语言 | Kotlin |
| 最低 SDK | 26 (Android 8.0) |
| 目标 SDK | 33 (Android 13) |
| UI 框架 | Material 3 (强制浅色) |
| 包名 | `com.openclaw.car` |
| 应用名 | **DiDiClaw** |

### 核心功能
- **ViewPager2 + TabLayout** 三标签页（人设/技能/记忆）
- 人设页：3 张龙虾形象卡片 + 4 种 TTS 音色预览
- 技能页：只读列表展示
- 记忆页：只读列表展示
- 调试/生产模式切换开关
- SharedPreferences 持久化选中状态

### 部署环境
- JDK 11 + Android SDK (platform-tools/build-tools 33.0.1/platform android-33)
- Gradle 8.2 从腾讯镜像下载
- ADB 连接比亚迪 DiLink300，pkexec 写入 udev 规则
- 构建安装：`gradle assembleDebug` → `adb install`

### 早期 Bug 修复
- AGP 8.2 需 Java 17 → 降级 AGP 7.4.2 + compileSdk 33
- AAPT 不支持 circle 标签 → 改用 path 圆弧
- 生产模式权限弹窗触发车机报错 → 改用 getExternalFilesDir

---

## 二、人设完善 & 生产模式接入 (05-12)

### 三份完整 SOUL.md
编写三份人设提示词（实干派/贴心友人/全能派），每份含通用底层约束 + 性格定位 + 说话风格 + 行为特点 + 禁令。打包进 APK assets，生产模式下点击人设卡片写入 Agent 的 SOUL.md。

### 生产模式路径
- SOUL.md → `/data/local/tmp/openclaw-home/.openclaw/workspace/SOUL.md`
- SKILL.md → `plugin-skills/*/SKILL.md`
- MEMORY.md → `/data/local/tmp/openclaw-home/.openclaw/workspace/MEMORY.md`

### SELinux 问题
车机 SELinux Enforcing 阻止 App 写入 `/data/local/tmp/`。通过 `adb root` + `setenforce 0` 解决。重启后需重新执行，创建 `car_setup.sh` 一键恢复。

---

## 三、Agent 提示词优化 (05-13)

### 问题诊断
- IDENTITY.md 内容错位（塞了命令和规则）
- 行为规则在 SOUL.md / IDENTITY.md / car-control SKILL.md 三处重复
- AGENTS.md / TOOLS.md 空置
- workspace SKILL.md 手工冗余

### 执行内容

| 文件 | 操作 |
|------|------|
| AGENTS.md | 重写：车机环境 + 5条硬性规则 + 安全检查清单 + 行驶禁令 + 驻车模式 + 记忆持久化 |
| IDENTITY.md | 精简为 3 行：名称 + emoji + 定位 |
| TOOLS.md | 重写：车机参数 + 工具限制（nav-search/media_session/input tap） |
| workspace/SKILL.md | 删除 |
| car-control/SKILL.md | 删除末尾「交互风格约束」段 |
| SOUL.md | 删除「通用底层约束」，纯人格 |
| openclaw.json | 未改动（systemPrompt/rules 当前版本不支持） |

---

## 四、Gateway 对话延迟优化 (05-13)

### 问题
首次对话延迟 97 秒。

### 链路分析
```
ADB转发:   33ms  ▏
DNS解析:   53ms  ▏
TLS握手: 3960ms  ████████████████████████  ← 瓶颈
API推理:    2ms  ▏
```

### 修复
- 车机 node-termux TLS 证书验证在 ARM CPU 上反复超时
- 设置 `NODE_TLS_REJECT_UNAUTHORIZED=0` + models.json `rejectUnauthorized: false`
- 延迟从 97s → ~4.5s（20x 提升）

### 社区 Bug
- [#75513](https://github.com/openclaw/openclaw/issues/75513) ARM64 重复加载插件（已修复于 2026.4.29）
- [#78461](https://github.com/openclaw/openclaw/issues/78461) 模型规范化时重复扫描元数据（已修复于 2026.5.7）
- 车机当前版本 2026.5.7 已包含两个修复

### 剩余开销
3-4 秒是 OpenClaw Gateway 在 ARM 设备上的正常基线，社区公认痛点。同版本在树莓派上要 66-120 秒。

---

## 五、App UI 优化

### 技能页重构
- 生产模式改为读取 `plugin-skills/` 目录，解析 SKILL.md frontmatter
- 2 列 GridLayout 卡片网格
- 分类图标（车辆/文档/聊天/工具）+ 中文翻译
- 点击卡片弹窗展示完整 SKILL.md 内容
- 修复 frontmatter 解析器支持 YAML `|` 多行块标量

### 记忆页
- 生产模式改为读取 `MEMORY.md`（OpenClaw 标准）
- 卡片样式

### 其他
- 默认启动模式改为生产模式
- `.openclaw` 目录权限修复（700→755，否则 App 无法读取）
- 修复写入 start-gw.sh 和 car_setup.sh 永久生效

---

## 六、启用 Gateway HTTP API

- 配置 `openclaw.json` 启用 `/v1/chat/completions` 端点
- 创建 `chat.sh` 交互式对话脚本
- ADB 端口转发：`adb forward tcp:18801 tcp:18801`

---

## 七、构建安装命令

```bash
# 构建
export ANDROID_HOME=~/android-sdk
~/gradle-local/gradle-8.2/bin/gradle assembleDebug

# 安装
~/android-sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk

# 车机重启后
./car_setup.sh   # adb root + setenforce 0 + chmod 755
adb forward tcp:18801 tcp:18801
./chat.sh        # 对话
```
