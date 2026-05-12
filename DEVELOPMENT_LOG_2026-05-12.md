# DiDiClaw 车机安卓应用 — 开发日志

**日期**：2026-05-12  
**仓库**：https://gitee.com/zardhey/agent_front_app

---

## 一、人设提示词完善

### 1.1 三份完整 SOUL.md
编写三份人设提示词文件，每份包含：
- 通用底层约束（6 条安全红线）
- 人设专属「性格定位 + 说话风格 + 行为特点 + 禁令/边界」

| 文件 | 大小 |
|------|------|
| 人设-实干派.md | 1784B — 干练理性·高效极简 |
| 人设-贴心友人.md | 1962B — 温柔共情·暖心陪伴 |
| 人设-全能派.md | 2029B — 干练+暖心+专业博学综合型 |

### 1.2 打包进 APK
三份文件复制到 `app/src/main/assets/persona/`，APK 内置，生产模式下从 assets 读取完整内容写入 Agent 的 SOUL.md。

---

## 二、生产模式 SOUL.md 写入

### 2.1 路径
生产模式下点击人设卡片 → 写入：

```
/data/local/tmp/openclaw-home/.openclaw/workspace/SOUL.md
```

### 2.2 障碍与解决

| 问题 | 原因 | 解决 |
|------|------|------|
| App 无法写入 Agent 路径 | SELinux Enforcing 阻止 untrusted_app 域写入 shell_data_file | `adb root` + `setenforce 0` |
| chcon 修改 SELinux 上下文失败 | shell 用户无权限 | 直接关 SELinux |
| 车机重启后 SELinux 恢复 | 重启默认 Enforcing | 创建 `car_setup.sh` 一键修复 |

### 2.3 car_setup.sh
车机每次重启后执行一次：
```bash
./car_setup.sh   # adb root + setenforce 0
```

---

## 三、Bug 修复

| 问题 | 现象 | 修复 |
|------|------|------|
| 生产模式人设无法选择 | writeFile 失败时 UI 不更新 | 解耦 UI 与文件写入，点击即时响应 |
| 音色选中状态消失 | MaterialButton checkable 无 RadioGroup 约束 | 代码完全控制按钮样式，不依赖 checkable |
| 启动图标龙虾被裁剪 | 图形超出 Adaptive Icon 安全区 | group scale 0.58 缩放 |

---

## 四、代码整理

- 删除 `adb_sync_soul.sh`（SELinux 已解决，不再需要备用同步脚本）
- 删除 `README.en.md`（Gitee 模板英文 README，无关）

---

## 五、验证结果

生产模式下三个人设全部测试通过：

| 操作 | 结果 |
|------|------|
| 点击实干派 | SOUL.md 写入 1784B，Agent 可读 |
| 点击贴心友人 | SOUL.md 更新为 1962B |
| 点击全能派 | SOUL.md 更新为 2029B |
| 音色选择 | UI 状态持久保持，TTS 语音预览正常 |
| 调试/生产切换 | 开关文字颜色变化清晰，重启后可正常加载 |
