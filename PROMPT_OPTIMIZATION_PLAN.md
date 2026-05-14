# Agent 提示词优化方案

**日期**：2026-05-13  
**车机**：比亚迪仰望 DiLink 300  
**状态**：✅ 已执行

---

## 问题诊断

| # | 问题 |
|---|------|
| 1 | IDENTITY.md 错位：塞了命令定义和交互规则 |
| 2 | 行为规则三重重复：SOUL.md / IDENTITY.md / car-control SKILL.md |
| 3 | AGENTS.md 空置：安全操作手册完全没写 |
| 4 | TOOLS.md 空置：车机环境信息缺失 |
| 5 | workspace SKILL.md 手工冗余 |
| 6 | car-control SKILL.md 混入人设风格约束 |
| 7 | DiLink 硬件能力内嵌在 IDENTITY.md，不规范 |

---

## 原方案 vs 执行结果

> 原计划在 `openclaw.json` 添加 `agents.defaults.systemPrompt` + `rules`，但当前车载 OpenClaw 版本不支持这两个字段（启动时直接报错拒绝了）。该部分内容已合并进 `AGENTS.md`。

---

## 实际执行内容

### 1. AGENTS.md（重写 — 1.8KB）

合并了原计划的 systemPrompt + rules + AGENTS 三部分：

```
# 车机环境
你是比亚迪仰望 DiLink 300 智能车机助手。运行环境：
- 屏幕：1728×1888 横屏，320dpi，触控操作
- 音频：Dynaudio 丹拿音响，支持 media_session 控制
- 语音：BYD AutoVoice TTS 引擎，讯飞输入法
- 车辆功能：云辇悬挂、DiPilot 智驾、场景模式、360°环视
- 系统：Android 14，MediaTek 8核，10GB 内存

# 硬性规则 (5条)
1. 禁止高德地图 / Google Maps
2. 禁止编造 bash 命令
3. 行驶中禁止执行座椅/空调/车窗操作
4. 关键操作必须先语音确认
5. 控制指令必须 bash 执行

# 启动检查清单
# 行驶中禁止操作 (7条)
# 驻车/怠速模式
# 多 Agent 协作
```

### 2. IDENTITY.md（重写 — 138B）

```
# 仰望车载助手
- 名称：仰望车载助手
- emoji：🚗
- 定位：比亚迪仰望智能副驾，懂车、懂出行、懂生活
```

### 3. TOOLS.md（重写 — 1.8KB）

车机环境参数 + nav-search.sh / car-router.js / media_session / input tap 等工具的限制和注意事项。

### 4. workspace SKILL.md → 已删除

与 car-control 插件内容完全重复。

### 5. car-control/SKILL.md（plugin）→ 已精简

删除了末尾「交互风格约束」整段，从 2.4KB 缩减到 1.8KB。

### 6. SOUL.md + DiDiClaw App assets → 已精简

删除了三份人设文件中的「通用底层约束」（6 条安全红线），只保留人设专属内容（性格定位 + 说话风格 + 行为特点 + 禁令）。

### 7. openclaw.json → 未改动

当前版本不支持 systemPrompt / rules 字段，回退到原始配置。

---

## 文件对比

| 文件 | 之前 | 之后 |
|------|------|------|
| AGENTS.md | 空 | 1.8KB — 车机环境+规则+检查清单 |
| IDENTITY.md | 1.3KB（含命令/规则） | 138B — 仅名称+emoji+定位 |
| TOOLS.md | 空 | 1.8KB — 环境参数+工具限制 |
| SOUL.md | 含通用约束 | 纯人格，通用约束已删除 |
| workspace/SKILL.md | 2.4KB 手工文件 | 已删除 |
| car-control/SKILL.md | 2.4KB 含交互风格 | 1.8KB 纯命令定义 |
| openclaw.json | 无改动 | 无改动 |

---

## 加载顺序（实际）

```
OpenClaw Gateway 系统提示词
  ↓
AGENTS.md    — 车机环境 + 硬性规则 + 安全检查
SOUL.md      — 纯人格（性格 + 风格 + 行为）
TOOLS.md     — 环境特定工具说明
IDENTITY.md  — 名称 + emoji
USER.md      — 用户记忆（占位）
  ↓
SKILL.md     — 按需激活（car-control 等）
```
