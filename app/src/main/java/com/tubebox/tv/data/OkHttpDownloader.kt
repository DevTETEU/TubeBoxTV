package com.tubebox.tv.data

import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.util.concurrent.TimeUnit

class OkHttpDownloader private constructor() : Downloader() {

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

        val instance: OkHttpDownloader by lazy { OkHttpDownloader() }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val requestBody: RequestBody? = when {
            dataToSend != null -> dataToSend.toRequestBody(null)
            httpMethod.equals("POST", ignoreCase = true) ||
                httpMethod.equals("PUT", ignoreCase = true) -> ByteArray(0).toRequestBody(null)
            else -> null
        }

        val builder = okhttp3.Request.Builder()
            .method(httpMethod, requestBody)
            .url(url)
            .header("User-Agent", USER_AGENT)

        for ((headerName, headerValues) in headers) {
            if (headerValues.size > 1) {
                builder.removeHeader(headerName)
                for (value in headerValues) {
                    builder.addHeader(headerName, value)
                }
            } else if (headerValues.size == 1) {
                builder.header(headerName, headerValues[0])
            }
        }

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("reCaptcha Challenge requested", url)
            }

            val bodyString = response.body?.string() ?: ""
            val latestUrl = response.request.url.toString()

            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                bodyString,
                latestUrl
            )
        }
    }
}
