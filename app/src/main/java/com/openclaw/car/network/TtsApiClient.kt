package com.openclaw.car.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object TtsApiClient {

    private const val TAG = "TtsApiClient"

    private const val BASE_URL = "http://172.20.10.5:8091"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
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
}
