package com.statbot.ai

import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class OpenAiService(private val apiKey: String) {
    private val client = OkHttpClient()
    private val gson = Gson()

    fun extractHubstaffHours(imageUrl: String): String {
        val json = """
            {
              "model": "gpt-4o",
              "messages": [
                {
                  "role": "user",
                  "content": [
                    {"type": "text", "text": "Find total tracked time (Hubstaff) on this screenshot and return ONLY the digits/time format (e.g. 05:32 or 6h 12m), with no other text."},
                    {"type": "image_url", "image_url": {"url": "$imageUrl"}}
                  ]
                }
              ],
              "max_tokens": 50
            }
        """.trimIndent()

        return makeApiCall(json)
    }

    fun analyzeDailyReport(fullName: String, project: String, reportData: Map<String, String>): Pair<Boolean, String> {
        val prompt = """
            Выступай в роли HR-аналитика. Проанализируй ответы сотрудника. 
            Имя сотрудника: $fullName
            Проект: $project

            Если у сотрудника замечены признаки сильного стресса, выгорания, низких оценок (1-3 на общих ощущениях), критических падений скорости или если в поле 'Q1_PROBLEM' описана серьезная блокирующая проблема, верни JSON:
            { "isFlagged": true, "summary": "Краткое описание ситуации НА РУССКОМ ЯЗЫКЕ с упоминанием имени сотрудника и проекта" }
            
            В противном случае верни:
            { "isFlagged": false, "summary": "" }

            КРИТИЧЕСКОЕ ТРЕБОВАНИЕ: Поле 'summary' должно быть полностью на РУССКОМ ЯЗЫКЕ. Не используй английский язык.
            
            Ответы сотрудника:
            $reportData
        """.trimIndent()

        val json = """
            {
              "model": "gpt-4o",
              "response_format": { "type": "json_object" },
              "messages": [{"role": "user", "content": ${gson.toJson(prompt)}}]
            }
        """.trimIndent()

        val response = makeApiCall(json)
        val resultObj = gson.fromJson(response, JsonObject::class.java)
        val isFlagged = resultObj.get("isFlagged")?.asBoolean ?: false
        val summary = resultObj.get("summary")?.asString ?: ""
        return Pair(isFlagged, summary)
    }

    private fun makeApiCall(jsonBody: String): String {
        try {
            val mediaTypeClass = Class.forName("okhttp3.MediaType")
            val parseMethod = mediaTypeClass.getMethod("parse", String::class.java)
            val mediaType = parseMethod.invoke(null, "application/json")

            val requestBodyClass = Class.forName("okhttp3.RequestBody")
            val createMethod = requestBodyClass.getMethod("create", mediaTypeClass, String::class.java)
            val body = createMethod.invoke(null, mediaType, jsonBody) as okhttp3.RequestBody

            val request = Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Unexpected code $response")

                val bodyMethod = response.javaClass.getMethod("body")
                val responseBody = bodyMethod.invoke(response) as? okhttp3.ResponseBody
                val bodyString = responseBody?.string() ?: throw IOException("Response body is null")

                val root = gson.fromJson(bodyString, JsonObject::class.java)
                return root.getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message").get("content").asString
            }
        } catch (e: Exception) {
            throw IOException("Ошибка совместимости OkHttp версий: ${e.message}", e)
        }
    }
}