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
            Выступай в роли опытного HR-аналитика и психолога. Проанализируй ответы сотрудника по новому 30-дневному регламенту опросов. 
            Имя сотрудника: $fullName
            Проект: $project

            КРИТЕРИИ ДЛЯ ВЫСТАВЛЕНИЯ ФЛАГА ТРЕВОГИ (isFlagged = true):
            1. Низкие оценки (1-3 или 4-6) по параметрам:
               - Энергичность / Эмоциональный настрой (DAY2_ENERGY, DAY16_EMOTIONAL)
               - Скорость работы (DAY2_SPEED, DAY16_SPEED)
               - Вовлеченность в проекты (DAY4_ENGAGEMENT)
               - Включенность на созвонах или комфорт нагрузки (DAY11_CALL_ENGAGEMENT, DAY11_WORKLOAD)
               - Счастье в компании (DAY25_HAPPINESS)
               - Общая оценка недели (FRI_WEEK_SCORE)
            2. Низкий процент выполнения задач за неделю: FRI_TASK_PCT = '<30%' или '50%'.
            3. Сильные блокираторы, завалы или проблемы в открытых ответах:
               - DAY2_SPEED_WHY (что помешало работать на максимальной скорости)
               - DAY4_ENGAGEMENT_WHY (что снижает вовлеченность)
               - FRI_MISSED_REASON (причины невыполнения запланированных задач)
               - MON_TASKS_CHANGES (критические срывы планов)

            Если ХОТЯ БЫ ОДИН из этих факторов указывает на критический завал, стресс, выгорание или конфликт, верни JSON:
            { "isFlagged": true, "summary": "Кратко (2-3 предложения) на РУССКОМ ЯЗЫКЕ опиши проблему, указав имя сотрудника и проект" }
            
            В противном случае (если показатели нормальные или выше средних) верни:
            { "isFlagged": false, "summary": "" }

            СТРОГОЕ ТРЕБОВАНИЕ: Текст в поле 'summary' должен быть ИСКЛЮЧИТЕЛЬНО на РУССКОМ ЯЗЫКЕ.
            
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

    fun transcribeVoice(fileUrl: String): String {
        try {
            val downloadResponse = client.newCall(Request.Builder().url(fileUrl).build()).execute()
            val downloadBodyMethod = downloadResponse.javaClass.getMethod("body")
            val downloadResponseBody = downloadBodyMethod.invoke(downloadResponse) as? okhttp3.ResponseBody
            val audioBytes = downloadResponseBody?.bytes() ?: throw IOException("Не удалось скачать аудиофайл")

            val tempFile = java.io.File.createTempFile("voice_", ".ogg").apply {
                writeBytes(audioBytes)
                deleteOnExit()
            }

            val requestBodyBuilderClass = Class.forName("okhttp3.MultipartBody${'$'}Builder")
            val builderInstance = requestBodyBuilderClass.getConstructor().newInstance()

            val setTypeMethod = requestBodyBuilderClass.getMethod("setType", Class.forName("okhttp3.MediaType"))
            val mediaTypeClass = Class.forName("okhttp3.MediaType")
            val parseMethod = mediaTypeClass.getMethod("parse", String::class.java)
            val formDataMediaType = parseMethod.invoke(null, "multipart/form-data")
            setTypeMethod.invoke(builderInstance, formDataMediaType)

            val audioMediaType = parseMethod.invoke(null, "audio/ogg")
            val requestBodyClass = Class.forName("okhttp3.RequestBody")
            val createFromFileMethod = requestBodyClass.getMethod("create", mediaTypeClass, java.io.File::class.java)
            val fileBody = createFromFileMethod.invoke(null, audioMediaType, tempFile)

            val addFormDataPartMethod = requestBodyBuilderClass.getMethod("addFormDataPart", String::class.java, String::class.java, requestBodyClass)
            addFormDataPartMethod.invoke(builderInstance, "file", tempFile.name, fileBody)

            val addTextPartMethod = requestBodyBuilderClass.getMethod("addFormDataPart", String::class.java, String::class.java)
            addTextPartMethod.invoke(builderInstance, "model", "whisper-1")

            val buildMethod = requestBodyBuilderClass.getMethod("build")
            val body = buildMethod.invoke(builderInstance) as okhttp3.RequestBody

            val request = Request.Builder()
                .url("https://api.openai.com/v1/audio/transcriptions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Ошибка Whisper API: $response")
                val bodyMethod = response.javaClass.getMethod("body")
                val responseBody = bodyMethod.invoke(response) as? okhttp3.ResponseBody
                val jsonString = responseBody?.string() ?: ""
                val jsonObj = gson.fromJson(jsonString, JsonObject::class.java)
                return jsonObj.get("text")?.asString ?: ""
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return "[Не удалось распознать голосовое сообщение]"
        }
    }
}