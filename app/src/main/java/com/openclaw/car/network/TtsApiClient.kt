package com.openclaw.car.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Base64
import android.util.Log
import com.openclaw.car.audio.FillerAudioPlayer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object TtsApiClient {

    private const val TAG = "TtsApiClient"

    private const val BASE_URL = "http://172.20.10.5:8091"
    private const val STT_URL = "http://172.20.10.5:8090"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // VoxCPM2 generates 6 filler clips sequentially — needs a long timeout.
    private val longClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    fun updateVoice(id: String, fields: Map<String, Any?>): Boolean {
        val json = JSONObject().apply {
            put("id", id)
            for ((key, value) in fields) {
                if (value == null) put(key, JSONObject.NULL)
                else put(key, value)
            }
        }

        val request = Request.Builder()
            .url("$BASE_URL/v1/voices")
            .post(json.toString().toRequestBody(JSON_TYPE))
            .build()

        Log.d(TAG, "updateVoice: POST $BASE_URL/v1/voices body=${json}")
        return try {
            val response = client.newCall(request).execute()
            Log.d(TAG, "updateVoice: response=${response.code}")
            response.isSuccessful.also { response.close() }
        } catch (e: Exception) {
            Log.e(TAG, "updateVoice failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    fun getVoice(id: String): JSONObject? {
        val request = Request.Builder()
            .url("$BASE_URL/v1/voices/$id")
            .get()
            .build()

        return try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                body?.let { JSONObject(it) }
            } else {
                Log.w(TAG, "getVoice: HTTP ${response.code}")
                response.close()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "getVoice failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    fun enableCloneCapture(): Boolean {
        val request = Request.Builder()
            .url("$STT_URL/v1/clone/capture")
            .post("{}".toRequestBody(JSON_TYPE))
            .build()

        return try {
            val response = client.newCall(request).execute()
            Log.d(TAG, "enableCloneCapture: ${response.code}")
            response.isSuccessful.also { response.close() }
        } catch (e: Exception) {
            Log.e(TAG, "enableCloneCapture failed: ${e.message}")
            false
        }
    }

    fun activateCloneVoice(): Boolean {
        val request = Request.Builder()
            .url("$BASE_URL/v1/clone/activate")
            .post("{}".toRequestBody(JSON_TYPE))
            .build()

        return try {
            val response = client.newCall(request).execute()
            Log.d(TAG, "activateCloneVoice: ${response.code}")
            response.isSuccessful.also { response.close() }
        } catch (e: Exception) {
            Log.e(TAG, "activateCloneVoice failed: ${e.message}")
            false
        }
    }

    fun clearCloneAudio(): Boolean {
        val request = Request.Builder()
            .url("$BASE_URL/v1/clone/clear")
            .post("{}".toRequestBody(JSON_TYPE))
            .build()

        return try {
            val response = client.newCall(request).execute()
            Log.d(TAG, "clearCloneAudio: ${response.code}")
            response.isSuccessful.also { response.close() }
        } catch (e: Exception) {
            Log.e(TAG, "clearCloneAudio failed: ${e.message}")
            false
        }
    }

    fun getCloneStatus(): JSONObject? {
        val request = Request.Builder()
            .url("$BASE_URL/v1/clone/status")
            .get()
            .build()

        return try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string()?.let { JSONObject(it) }
            } else {
                Log.w(TAG, "getCloneStatus: HTTP ${response.code}")
                response.close()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "getCloneStatus failed: ${e.message}")
            null
        }
    }

    /**
     * Regenerate filler clips for the current `default` voice and overwrite the device
     * filler pool. Called by PersonaFragment after applyVoiceToAdapter() succeeds so the
     * filler pool stays in sync with whatever ref_audio the adapter is now using.
     *
     * Phrases are sent explicitly so we don't depend on adapter's DEFAULT_FILLER_PHRASES
     * (which requires an adapter restart to change).
     *
     * Layout written: /data/local/tmp/openclaw-home/.openclaw/media/filler/default/NN.mp3
     */
    fun regenerateFillers(): Boolean {
        // 只保留两句万能 filler："请稍等"（通用等待）和"让我查查"（涉及查询/操作）。
        // 其它话术在车机场景里语境太窄（如"让我想想"对工具型指令突兀），反而显得啰嗦。
        val phrases = JSONArray().apply {
            put("好的，请稍等一下哈")
            put("好嘞，马上给您查一下")
        }
        val body = JSONObject().apply {
            put("voice", "default")
            put("response_format", "mp3")
            put("phrases", phrases)
        }
        val request = Request.Builder()
            .url("$BASE_URL/v1/filler/generate")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()

        return try {
            val response = longClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "regenerateFillers: HTTP ${response.code}")
                response.close()
                return false
            }
            val body = response.body?.string() ?: return false
            val payload = JSONObject(body)
            val fillers = payload.optJSONArray("fillers") ?: return false
            val outDir = File(FillerAudioPlayer.FILLER_DIR, "default")
            outDir.mkdirs()
            // Write to .tmp then rename so an in-flight playRandom() never reads a half-written file.
            for (i in 0 until fillers.length()) {
                val f = fillers.getJSONObject(i)
                val filename = f.getString("filename")
                val base64 = f.getString("audio_base64")
                val bytes = Base64.decode(base64, Base64.NO_WRAP)
                val tmp = File(outDir, "$filename.tmp")
                val final = File(outDir, filename)
                tmp.writeBytes(bytes)
                tmp.renameTo(final)
            }
            Log.i(TAG, "regenerateFillers: wrote ${fillers.length()} clips to ${outDir.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "regenerateFillers failed: ${e.message}")
            false
        }
    }
}
