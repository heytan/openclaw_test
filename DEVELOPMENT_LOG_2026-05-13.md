# DiDiClaw 车机安卓应用 — 开发日志

**日期**：2026-05-13  
**仓库**：https://gitee.com/zardhey/agent_front_app

---

## 一、Agent 提示词优化

### 1.1 问题诊断
- IDENTITY.md 内容错位（塞了命令和规则）
- 行为规则在 SOUL.md / IDENTITY.md / car-control SKILL.md 三处重复
- AGENTS.md / TOOLS.md 空置
- workspace SKILL.md 手工文件与 car-control 插件内容重复
- car-control SKILL.md 混入交互风格约束
- DiLink 硬件能力没有规范位置

### 1.2 执行内容

| 文件 | 操作 | 结果 |
|------|------|------|
| AGENTS.md | 重写 | 1.8KB — 车机环境+5条硬性规则+启动检查清单+行驶禁令+驻车模式+多Agent协作 |
| IDENTITY.md | 重写 | 138B — 仅名称+emoji+定位 |
| TOOLS.md | 重写 | 1.8KB — 车机参数+工具限制 |
| workspace/SKILL.md | 删除 | 已删除 |
| car-control/SKILL.md | 删除交互风格约束段 | 2.4KB→1.8KB |
| SOUL.md + App assets | 删除通用底层约束（6条） | 纯人格 |
| openclaw.json | 未改动 | systemPrompt/rules 字段当前版本不支持，合并进 AGENTS.md |

### 1.3 App 侧更新
- 技能页改为读取 `plugin-skills/` 目录，解析 SKILL.md frontmatter
- 卡片样式：2列网格 + 分类图标 + 中文翻译
- 点击卡片弹窗展示完整技能内容
- 默认启动模式改为生产模式

---

## 二、Gateway 对话延迟优化

### 2.1 问题
对话响应极慢（97秒），定位到 TLS 证书验证是瓶颈。

### 2.2 分析过程

```
TCP连接:  ~100ms  ✓ 快
TLS握手:  ~644ms  ✓ 正常
证书验证: +3000ms  ✗ 车机CPU上OpenSSL校验链反复超时
API推理:    2ms   ✓ 快
```

### 2.3 解决
- 设置 `NODE_TLS_REJECT_UNAUTHORIZED=0` 跳过证书验证
- zai 插件 models.json 添加 `rejectUnauthorized: false`
- 系统 CA 证书包推送到车机备用

### 2.4 效果

| 指标 | 优化前 | 优化后 |
|------|--------|--------|
| 单次对话 | 97秒 | ~4.5秒 |
| TLS连接 | ~4秒 | ~0.5秒 |
| 提升 | — | **20x** |

### 2.5 对话工具
- 启用 Gateway `/v1/chat/completions` HTTP API
- 创建 `chat.sh` 交互式对话脚本
- ADB 端口转发：`adb forward tcp:18801 tcp:18801`

---

## 三、App 技能页重构

### 3.1 数据源修正
- 生产模式：从 `plugin-skills/` 目录枚举，解析每个 SKILL.md 的 frontmatter
- 调试模式：保持内置模拟数据

### 3.2 UI 优化
- 2 列 GridLayout
- 分类图标（车辆/文档/聊天/工具）
- 中文名+描述映射
- 卡片点击弹窗展示完整 SKILL.md
- 记忆页同步改为卡片样式

---

## 四、其他修复

| 问题 | 修复 |
|------|------|
| feishu-doc 等技能无描述 | frontmatter 解析器支持 `|` 多行块标量 |
| 生产模式人设写入失败 | SELinux permissive + 直接 Agent 路径 |
| 音色 TTS 日志 | 确认 BydTtsEngineImpl 正常调用 |
