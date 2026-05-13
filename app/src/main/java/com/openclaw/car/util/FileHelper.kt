package com.openclaw.car.util

import android.content.Context
import java.io.File

object FileHelper {

    var DEBUG_MODE = false

    private var appContext: Context? = null

    // Agent workspace paths (read/write directly, requires SELinux permissive)
    private const val AGENT_WORKSPACE = "/data/local/tmp/openclaw-home/.openclaw/workspace"
    const val AGENT_SOUL_PATH = "$AGENT_WORKSPACE/SOUL.md"
    private const val AGENT_USER_PATH = "$AGENT_WORKSPACE/MEMORY.md"
    private const val PLUGIN_SKILLS_DIR = "/data/local/tmp/openclaw-home/.openclaw/plugin-skills"

    // Asset file names for each persona
    private val PERSONA_ASSETS = arrayOf(
        "persona/0_shixie.md",
        "persona/1_tiexin.md",
        "persona/2_quanneng.md"
    )

    // Base dir for current mode
    private fun baseDir(): File {
        val ctx = appContext ?: throw IllegalStateException("FileHelper.init() must be called first")
        return if (DEBUG_MODE) {
            File(ctx.filesDir, "openclaw_debug")
        } else {
            File(ctx.getExternalFilesDir(null), "openclaw")
        }
    }

    // Path accessors
    val personaFilePath: String
        get() = if (DEBUG_MODE) {
            File(baseDir(), "agent/persona.txt").absolutePath
        } else {
            AGENT_SOUL_PATH
        }

    val voiceConfigFilePath: String
        get() = File(baseDir(), "tts/voice_config.txt").absolutePath

    val memoryFilePath: String
        get() = if (DEBUG_MODE) File(baseDir(), "agent/memory.txt").absolutePath else AGENT_USER_PATH

    // Short prompts for debug mode (kept in-app, no file I/O needed)
    private val DEBUG_PROMPTS = mapOf(
        0 to "你是一个务实高效的助手，专注解决问题，回答简洁直接，不冗余",
        1 to "你是一个温暖贴心的朋友，语气亲切柔和，善于倾听和安慰",
        2 to "你是一个全能助手，精通各类知识，能解答问题、提供建议、辅助决策"
    )

    private val VOICE_CONFIGS = mapOf(
        0 to "standard_male",
        1 to "standard_female",
        2 to "gentle_female",
        3 to "calm_male"
    )

    /**
     * Get persona prompt content.
     * - Debug mode: returns built-in short prompt
     * - Production mode: reads the full persona markdown from assets
     */
    fun getPersonaPrompt(index: Int): String {
        if (DEBUG_MODE) {
            return DEBUG_PROMPTS[index] ?: DEBUG_PROMPTS[0]!!
        }
        val ctx = appContext ?: return DEBUG_PROMPTS[index] ?: DEBUG_PROMPTS[0]!!
        val assetPath = PERSONA_ASSETS[index % PERSONA_ASSETS.size]
        return try {
            ctx.assets.open(assetPath).bufferedReader(Charsets.UTF_8).readText()
        } catch (e: Exception) {
            e.printStackTrace()
            DEBUG_PROMPTS[index] ?: DEBUG_PROMPTS[0]!!
        }
    }

    fun getVoiceConfig(index: Int): String = VOICE_CONFIGS[index] ?: VOICE_CONFIGS[0]!!

    /**
     * Read full SKILL.md content for a given skill name.
     */
    fun getSkillDetail(name: String): String {
        if (DEBUG_MODE) return "调试模式下无详细技能说明"
        val file = File("$PLUGIN_SKILLS_DIR/$name/SKILL.md")
        return try {
            if (!file.exists()) "(技能文件不存在)"
            else file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            "(读取失败)"
        }
    }

    /**
     * Get skill list for display.
     * - Debug mode: reads from built-in sample data
     * - Production mode: enumerates plugin-skills/ directories, parses SKILL.md frontmatter
     * Returns list of "name：description" lines.
     */
    fun getSkills(): List<String> {
        if (DEBUG_MODE) {
            return readLines(File(baseDir(), "agent/skills.txt").absolutePath)
        }
        return enumeratePluginSkills()
    }

    private fun enumeratePluginSkills(): List<String> {
        val result = mutableListOf<String>()
        val pluginsDir = File(PLUGIN_SKILLS_DIR)
        if (!pluginsDir.exists() || !pluginsDir.isDirectory) return result
        val dirs = pluginsDir.listFiles { f -> f.isDirectory } ?: return result
        for (dir in dirs.sortedBy { it.name }) {
            val skillMd = File(dir, "SKILL.md")
            if (!skillMd.exists()) continue
            val frontmatter = parseFrontmatter(skillMd)
            val name = frontmatter["name"] ?: dir.name
            val desc = frontmatter["description"] ?: ""
            result.add("${name}：${desc}")
        }
        return result
    }

    /**
     * Parse YAML frontmatter from a SKILL.md file.
     * Extracts key-value pairs between the first pair of --- markers.
     * Handles both inline values and multi-line block scalars (|).
     */
    private fun parseFrontmatter(file: File): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            val lines = file.readLines(Charsets.UTF_8)
            if (lines.isEmpty() || lines[0].trim() != "---") return result
            var multiLineKey: String? = null
            val multiLineBuf = StringBuilder()
            for (line in lines.drop(1)) {
                if (line.trim() == "---") break

                // If currently collecting a multi-line value
                if (multiLineKey != null) {
                    val trimmed = line.trim()
                    // Check if this is a new key (not indented continuation)
                    if (trimmed.contains(":") && !line.startsWith(" ") && !line.startsWith("\t")) {
                        // Save previous multi-line value
                        result[multiLineKey] = multiLineBuf.toString().trim()
                        multiLineKey = null
                        multiLineBuf.clear()
                        // Fall through to parse this new key
                    } else {
                        if (trimmed.isNotEmpty()) {
                            if (multiLineBuf.isNotEmpty()) multiLineBuf.append(" ")
                            multiLineBuf.append(trimmed)
                        }
                        continue
                    }
                }

                val parts = line.split(":", limit = 2)
                if (parts.size < 2) continue
                val key = parts[0].trim()
                var value = parts[1].trim().trim('"').trim('\'')
                if (value == "|") {
                    // Start of a multi-line block scalar
                    multiLineKey = key
                    multiLineBuf.clear()
                } else if (value.isNotEmpty()) {
                    result[key] = value
                }
            }
            // Don't forget last multi-line value
            if (multiLineKey != null && multiLineBuf.isNotEmpty()) {
                result[multiLineKey] = multiLineBuf.toString().trim()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    /**
     * Initialize storage. In debug mode, pre-populates sample data.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (DEBUG_MODE) {
            baseDir().mkdirs()
            File(baseDir(), "agent").mkdirs()
            File(baseDir(), "tts").mkdirs()
            writeSampleData()
        }
    }

    private fun writeSampleData() {
        val skillFile = File(baseDir(), "agent/skills.txt")
        if (!skillFile.exists()) {
            skillFile.writeText(SAMPLE_SKILLS, Charsets.UTF_8)
        }
        val memoryFile = File(baseDir(), "agent/memory.txt")
        if (!memoryFile.exists()) {
            memoryFile.writeText(SAMPLE_MEMORIES, Charsets.UTF_8)
        }
    }

    fun writeFile(path: String, content: String): Boolean {
        return try {
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeText(content, Charsets.UTF_8)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun readLines(path: String): List<String> {
        return try {
            val file = File(path)
            if (!file.exists()) emptyList()
            else file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    // Sample data for debug verification
    private const val SAMPLE_SKILLS = """
导航助手：提供实时路线规划、路况播报和周边兴趣点搜索
语音控制：支持免唤醒语音指令，可控制空调、车窗、音乐等车载功能
日程管理：与手机日历同步，重要行程语音提醒
媒体播放：支持蓝牙音乐、在线电台、USB音频等多种播放方式
车辆诊断：实时监控车辆状态，包括胎压、电量、保养提醒
天气查询：当前位置天气及未来7天预报
智能家居：远程控制家中智能设备，支持场景联动
"""

    private const val SAMPLE_MEMORIES = """
2026-05-10｜用户偏好空调温度24度，风量中档｜驾驶时偏好经济模式
2026-05-10｜常用导航目的地：公司、家、万达广场｜周末常去公园
2026-05-09｜用户喜欢听轻音乐和播客节目｜不听重摇滚
2026-05-09｜座椅位置记忆：高度中、靠背微倾、腰部支撑开启
2026-05-08｜每周五下午5点提醒加油｜常去中石化加油站
2026-05-08｜用户说开车时不喜欢被打扰｜重要消息才语音播报
2026-05-07｜下午茶时间习惯点一杯拿铁｜经过星巴克时提醒
"""
}
