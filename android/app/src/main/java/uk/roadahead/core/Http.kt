package uk.roadahead.core

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object Http {
    const val UA = "RoadAheadUK/0.1 (Android; personal use)"
    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 15000): String = request("GET", url, null, headers, timeoutMs)

    fun postForm(url: String, body: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 20000): String =
        request("POST", url, body, headers + ("Content-Type" to "application/x-www-form-urlencoded"), timeoutMs)

    fun request(method: String, url: String, body: String?, headers: Map<String, String>, timeoutMs: Int): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept-Language", "en-GB")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpException(code, "HTTP $code from ${URL(url).host}")
            return text
        } finally { c.disconnect() }
    }
}

class HttpException(val code: Int, msg: String) : Exception(msg)
