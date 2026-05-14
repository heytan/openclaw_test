# 车机内存优化方案

**日期**：2026-05-13  
**现状**：10GB 内存，空闲仅 408MB，2.6GB 已 swap。Gateway(openclaw) 446MB 在 swap 中挣扎。

---

## 进程分类

### 必须保留（音乐+地图+Agent 核心）

| 进程 | 内存 | 原因 |
|------|------|------|
| system_server | 307MB | Android 核心 |
| com.android.systemui | 216+134MB | 系统界面 |
| com.android.launcher3* | 187+175MB | 桌面（保留一个） |
| com.android.phone | 140MB | 通话 |
| com.android.bluetooth | 129MB | 蓝牙（音乐需要） |
| com.android.car.* | — | 车机框架 |
| **com.byd.launchermap** | 584MB | 🗺️ 地图 |
| **com.byd.mediacenter** | 294+193MB | 🎵 音乐 |
| **com.byd.mediacontroller** | 109+98MB | 🎵 媒体控制 |
| **com.byd.widget.mediacenter** | 115MB | 🎵 音乐小组件 |
| **com.byd.autovoice** | 184+162MB | 🎤 语音识别 |
| **com.byd.autovoice.engine** | 193MB | 🎤 语音引擎 |
| **com.byd.autovoice.tts** | 98MB | 🎤 TTS |
| com.iflytek.inputmethod | 157MB | ⌨️ 输入法 |
| **openclaw (Gateway)** | 416MB | 🤖 Agent |
| com.openclaw.car | 155MB | 📱 DiDiClaw App |
| ai.openclaw.app | 117MB | 🤖 Agent 前端 |
| com.termux | 150MB | 终端 |

**必须保留合计：~3.8GB**

### 可安全关闭（非核心娱乐/工具/服务）

| 类别 | 进程 | 内存 | 可释放 |
|------|------|------|--------|
| 🎬 视频 | com.byd.videoplay* ×2 | 156+122MB | 278MB |
| 🎤 K歌 | com.byd.minikaraoke ×2 | 134+104MB | 238MB |
| 🎤 K歌 | com.tencentbyd.karaokecar | 103MB | 103MB |
| 📹 行车记录 | com.byd.dvr | 109MB | 109MB |
| 📷 相机 | com.byd.auto_photo | 89MB | 89MB |
| 📷 环视 | com.byd.sr | 98MB | 98MB |
| 🌤 天气 | com.byd.weatherdata | 135MB | 135MB |
| 📱 投屏 | com.byd.dishare ×2 | 122+119MB | 241MB |
| 📱 投屏 | com.byd.projection.management | 110MB | 110MB |
| 🖼 壁纸 | com.byd.wallpaperhome ×2 | 111+110MB | 221MB |
| 📻 音乐组件 | com.byd.musicwidget | 102MB | 102MB |
| 🗺️ 副地图 | com.byd.deputymap | 117MB | 117MB |
| ✈️ 出行 | com.byd.smarttravel | 105MB | 105MB |
| 👆 手势 | com.byd.gesture.global | 116MB | 116MB |
| 🔗 同步 | com.byd.synclink | 111MB | 111MB |
| 🪞 后视镜 | com.byd.mirror | 84MB | 84MB |
| 🚗 我的车 | com.byd.mycar ×2 | 104+105MB | 209MB |
| 🚗 驾驶模式 | com.byd.drivemode* ×2 | 99+89MB | 188MB |
| 🏔 场景模式 | com.byd.scenemodes* ×2 | 103+96MB | 199MB |
| 🚙 云辇 | com.byd.yunnian.service | 96MB | 96MB |
| 🔒 哨兵 | com.byd.sentrymode | 91MB | 91MB |
| 🛡 ADAS | com.byd.server.adasagent ×3 | 90+89+88MB | 267MB |
| 📡 CAN | com.byd.CanDataCollect | 93MB | 93MB |
| 📊 诊断 | com.byd.spotinspection | 90MB | 90MB |
| 📝 日志 | com.byd.idclogcollect | 92MB | 92MB |
| 📝 日志 | com.byd.bydlogtool ×2 | 119+107MB | 226MB |
| 🔧 开发工具 | com.byd.byddevelopmenttools | 140MB | 140MB |
| 📡 OTA | com.byd.otaupdate + cota | 112+92MB | 204MB |
| 🏪 应用商店 | com.byd.appstore:remote | 106MB | 106MB |
| ☁️ 云服务 | com.byd.cloudserviceapp | 103MB | 103MB |
| ☁️ 推送 | com.byd.pushservice | 115MB | 115MB |
| 📡 账户 | com.byd.diLinkAccount + accountservice + authservice | 124+88+125MB | 337MB |
| 📡 软件激活 | com.byd.softwareactivation | 97MB | 97MB |
| 🔌 跨域控制 | com.byd.crosscontrol ×3 | 96+95+95MB | 286MB |
| 🔌 其他服务 | (gpsinfo/vehicleconfig/vehicledialog/eventcenter/oms/resmgr/trafficmonitor/wlan/...) | ~1.2GB | 1.2GB |

**可关闭合计：~5.5GB**

---

## 执行方案

### 方案 A：保守关闭（只关纯娱乐+工具）— 预估释放 ~1.5GB

关掉视频、K歌、行车记录、天气、壁纸、开发工具、日志工具、OTA、应用商店。

```bash
# 娱乐类
pm disable com.byd.videoplay
pm disable com.byd.videoplay.fse
pm disable com.byd.minikaraoke
pm disable com.tencentbyd.karaokecar

# 工具类
pm disable com.byd.dvr
pm disable com.byd.weatherdata
pm disable com.byd.byddevelopmenttools
pm disable com.byd.bydlogtool
pm disable com.byd.idclogcollect
pm disable com.byd.wallpaperhome
pm disable com.byd.wallpaperhome.fse
pm disable com.byd.otaupdate
pm disable com.byd.cota
pm disable com.byd.appstore
pm disable com.byd.auto_photo
```

### 方案 B：激进关闭（保留最小集）— 预估释放 ~3GB

方案A + 关掉投屏、K歌、哨兵、ADAS、云服务、诊断、跨域控制等。

### ⚠️ 不能关的

- com.byd.acservice — 空调服务（关了没空调）
- com.byd.car.server — 车机核心服务
- com.byd.oemcarservice — OEM 核心服务
- com.byd.dicore.updatable — DiCore 核心

---

## 恢复方法

```bash
pm enable <包名>   # 恢复单个
# 或重启车机恢复全部
```

---

## 推荐

**方案 A**，保守关闭纯娱乐和工具类，风险低、可恢复、预估释放 1.5GB，Gateway 能拿到更多物理内存，减少 swap。
