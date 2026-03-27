package cl.posgo.apiproxyandroid.utils

import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import java.io.BufferedReader
import java.io.InputStreamReader

object Extensions {
    private val gson = Gson()

    fun NanoHTTPD.IHTTPSession.getBodyAsString(): String {
        val map = HashMap<String, String>()
        try {
            this.parseBody(map)
        } catch (e: Exception) {
            return ""
        }

        val contentLength = this.headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength <= 0) {
            return ""
        }

        val postData = map["postData"] ?: ""
        if (postData.isNotEmpty()) {
            return postData
        }

        return try {
            val inputStream = this.inputStream
            val reader = BufferedReader(InputStreamReader(inputStream))
            val stringBuilder = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                stringBuilder.append(line)
            }
            stringBuilder.toString()
        } catch (e: Exception) {
            ""
        }
    }

    fun NanoHTTPD.IHTTPSession.getBodyAsMap(): Map<*, *> {
        val bodyString = getBodyAsString()
        return if (bodyString.isEmpty()) {
            emptyMap<String, Any>()
        } else {
            try {
                gson.fromJson(bodyString, Map::class.java)
            } catch (e: Exception) {
                emptyMap<String, Any>()
            }
        }
    }
}

fun newJsonResponse(status: NanoHTTPD.Response.Status, data: Any): NanoHTTPD.Response {
    val gson = Gson()
    val jsonString = gson.toJson(data)
    return NanoHTTPD.newFixedLengthResponse(
        status,
        "application/json",
        jsonString
    )
}