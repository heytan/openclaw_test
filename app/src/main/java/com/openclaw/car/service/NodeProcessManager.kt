package com.openclaw.car.service

import android.content.Context
import android.util.Log
import com.openclaw.car.OpenClawApp
import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader

class NodeProcessManager(context: Context) {

    private val stagingDir = "/data/local/tmp"
    private val nodeBin = "$stagingDir/node-termux"
    private val nodeLibDir = "$stagingDir/node-lib"
    private val openclawHome = "/data/local/tmp/openclaw-home"

    private data class ProcessDef(
        val name: String,
        val cmdParts: Array<String>,
        val extraEnv: Array<String>,
        val homeDir: String
    )

    private val openclawEntry = "$stagingDir/openclaw/lib/node_modules/openclaw/openclaw.mjs"

    private val processDefs: Array<ProcessDef> = arrayOf(
        ProcessDef(
            "openclaw-gateway",
            arrayOf(nodeBin, openclawEntry, "gateway", "run"),
            arrayOf(
                "ZAI_API_KEY=b30e917b73334579b34903071f753b89.kgJ12xYP3JFr5hqp",
                "FEISHU_APP_ID=cli_a9658c749eb81cc5",
                "FEISHU_APP_SECRET=lfTd3y2Dw3X083iB5g0nDfHbAxfdaibO",
            ),
            openclawHome
        ),
        ProcessDef(
            "mcp-proxy",
            arrayOf(nodeBin, "$openclawHome/mcp-proxy.js"),
            arrayOf(),
            openclawHome
        ),
    )

    private val procs = arrayOfNulls<Process>(processDefs.size)
    private val logFile = File("/data/local/tmp/openclaw-home/.openclaw/logs/gateway-stdout.log")

    private fun writeLog(line: String) {
        Log.i(OpenClawApp.TAG, line)
        try {
            val fw = FileWriter(logFile, true)
            fw.append(System.currentTimeMillis().toString()).append(" ").append(line).append("\n")
            fw.close()
        } catch (_: Exception) {}
    }

    @Synchronized
    fun startAll() {
        for (i in processDefs.indices) {
            if (isAlive(i)) continue
            startProcess(i)
            if (i == 0) {
                Log.i(OpenClawApp.TAG, "Waiting for gateway to be ready...")
                Thread.sleep(5000)
            }
        }
    }

    @Synchronized
    fun stopAll() {
        for (i in processDefs.indices) {
            procs[i]?.let {
                it.destroy()
                procs[i] = null
                Log.i(OpenClawApp.TAG, "Stopped ${processDefs[i].name}")
            }
        }
    }

    @Synchronized
    fun isRunning(): Boolean {
        return processDefs.isNotEmpty() && processDefs.indices.all { isAlive(it) }
    }

    @Synchronized
    fun getStatus(): String {
        return processDefs.indices.joinToString("\n") { i ->
            "${processDefs[i].name}: ${if (isAlive(i)) "Running" else "Stopped"}"
        }
    }

    @Synchronized
    fun restartDead() {
        for (i in processDefs.indices) {
            if (!isAlive(i) && procs[i] != null) {
                Log.w(OpenClawApp.TAG, "${processDefs[i].name} died, restarting")
                procs[i] = null
                startProcess(i)
            }
        }
    }

    private fun startProcess(index: Int) {
        val def = processDefs[index]
        try {
            val baseEnv = arrayOf(
                "LD_LIBRARY_PATH=$nodeLibDir",
                "OPENSSL_CONF=$nodeLibDir/openssl.cnf",
                "HOME=${def.homeDir}",
                "NODE_PATH=$nodeLibDir/node_modules",
                "OPENCLAW_HOME=$openclawHome",
                "PATH=/system/bin:/system/xbin:$stagingDir",
            )
            val env = baseEnv + def.extraEnv

            val pb = ProcessBuilder(*def.cmdParts)
            pb.redirectErrorStream(true)
            for (e in env) {
                val eq = e.indexOf('=')
                if (eq > 0) {
                    pb.environment()[e.substring(0, eq)] = e.substring(eq + 1)
                }
            }
            val p = pb.start()
            procs[index] = p

            Thread {
                try {
                    val br = BufferedReader(InputStreamReader(p.inputStream))
                    var line: String?
                    while (br.readLine().also { line = it } != null) {
                        writeLog("[${def.name}] $line")
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true }.start()

            Log.i(OpenClawApp.TAG, "Started ${def.name}")
        } catch (e: Exception) {
            Log.e(OpenClawApp.TAG, "Failed to start ${def.name}: ${e.message}")
        }
    }

    private fun isAlive(index: Int): Boolean {
        val p = procs[index] ?: return false
        return try {
            p.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }
}
