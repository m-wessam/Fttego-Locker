package com.adowaalshamel.boardguard.backend

import com.adowaalshamel.boardguard.BuildConfig
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONObject
import org.json.JSONTokener

/** Device-only API. The screen never receives a mobile manager credential. */
class LockerDeviceApi(
    baseUrl: String = BuildConfig.LOCKER_BASE_URL,
    client: OkHttpClient = OkHttpClient(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val base = baseUrl.trim().trimEnd('/').toHttpUrl()
    private val http = client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        // A redirect must never forward a device credential, nor replay an unlock attempt.
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .cache(null)
        .build()
    private val backoff = AtomicReference<Backoff?>(null)

    init {
        require(base.isHttps || base.host in setOf("localhost", "127.0.0.1", "::1")) {
            "Locker API requires HTTPS."
        }
        require(base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null) {
            "Invalid Locker base URL."
        }
    }

    suspend fun activate(activation: LockerActivationRequest): LockerCredentials = withContext(Dispatchers.IO) {
        require(MAC_PATTERN.matches(activation.macAddress)) { "Invalid screen MAC address." }
        require(BASE64_URL_PATTERN.matches(activation.publicKey) && activation.publicKey.length <= 2048) {
            "Invalid screen public key."
        }
        require(NONCE_PATTERN.matches(activation.nonce)) { "Invalid activation nonce." }
        require(activation.issuedAt > 0) { "Invalid activation timestamp." }
        require(BASE64_URL_PATTERN.matches(activation.signature) && activation.signature.length <= 1024) {
            "Invalid activation signature."
        }
        val payload = JSONObject()
            .put("macAddress", activation.macAddress)
            .put("publicKey", activation.publicKey)
            .put("nonce", activation.nonce)
            .put("issuedAt", activation.issuedAt)
            .put("signature", activation.signature)
        val result = request("POST", listOf("device", "activate"), payload = payload)
        LockerCredentials(
            deviceId = result.json.requiredString("deviceId").also { if (!validDeviceId(it)) invalidResponse() },
            deviceToken = result.json.requiredString("deviceToken").also {
                if (it.length > 4096 || !DEVICE_TOKEN_PATTERN.matches(it)) invalidResponse()
            },
        )
    }

    suspend fun getPolicy(credentials: LockerCredentials): LockerPolicy = withContext(Dispatchers.IO) {
        val result = request("GET", listOf("device", "me", "policy"), credentials)
        val policy = LockerPolicy(
            lockEnabled = result.json.requiredBoolean("lockEnabled"),
            hasPassword = result.json.requiredBoolean("hasPassword"),
            revision = result.headers.requiredRevision(),
        )
        if (policy.lockEnabled && !policy.hasPassword) invalidResponse()
        policy
    }

    suspend fun getPassword(credentials: LockerCredentials, revision: String): LockerPassword =
        withContext(Dispatchers.IO) {
            require(REVISION_PATTERN.matches(revision)) { "Invalid policy revision." }
            val result = request(
                "GET", listOf("devices", credentials.deviceId, "password"), credentials,
                revision = revision,
            )
            if (result.json.requiredString("deviceId") != credentials.deviceId || !result.json.has("password")) {
                invalidResponse()
            }
            val receivedRevision = result.headers.requiredRevision()
            if (receivedRevision != revision) invalidResponse("POLICY_REVISION_MISMATCH")
            val password = if (result.json.isNull("password")) null else {
                (result.json.opt("password") as? String)?.takeIf(LOCKER_PIN_PATTERN::matches)
                    ?: invalidResponse()
            }
            LockerPassword(password, receivedRevision)
        }

    suspend fun unlock(credentials: LockerCredentials, password: String): Boolean = withContext(Dispatchers.IO) {
        require(LOCKER_PIN_PATTERN.matches(password)) { "The PIN must contain exactly four digits." }
        request(
            "POST", listOf("device", "me", "unlock"), credentials,
            payload = JSONObject().put("password", password),
        ).json.requiredBoolean("valid")
    }

    private suspend fun request(
        method: String,
        path: List<String>,
        credentials: LockerCredentials? = null,
        payload: JSONObject? = null,
        revision: String? = null,
    ): ApiResponse {
        backoff.get()?.let { waiting ->
            val milliseconds = waiting.untilMillis - nowMillis()
            if (milliseconds > 0) {
                // Preserve 503 versus 429: only the former permits cached offline verification.
                throw LockerDeviceApiException(waiting.statusCode, waiting.errorCode, secondsCeiling(milliseconds))
            }
            backoff.compareAndSet(waiting, null)
        }
        val url = base.newBuilder().addPathSegments("api/locker").apply {
            path.forEach(::addPathSegment)
        }.build()
        val builder = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("Cache-Control", "no-store")
            .method(method, payload?.toString()?.toRequestBody(JSON_MEDIA_TYPE)?.let(::SingleAttemptBody))
        credentials?.let {
            validateDeviceId(it.deviceId)
            require(it.deviceToken.length <= 4096 && DEVICE_TOKEN_PATTERN.matches(it.deviceToken)) { "Invalid screen credential." }
            builder.header("Authorization", "Bearer ${it.deviceToken}")
        }
        revision?.let { builder.header("X-Locker-If-Revision", it) }
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(builder.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            if (response.code != 200) {
                                val retrySeconds = parseRetryAfter(response.header("Retry-After"))
                                    ?: if (response.code == 429) 60L else null
                                val body = try { parseJson(readBody(response)) } catch (_: Exception) { null }
                                val candidate = body?.optJSONObject("error")?.opt("code") as? String
                                val code = candidate?.takeIf(ERROR_CODE_PATTERN::matches) ?: "HTTP_${response.code}"
                                if (retrySeconds != null && retrySeconds > 0) {
                                    rememberBackoff(response.code, code, retrySeconds)
                                }
                                throw LockerDeviceApiException(response.code, code, retrySeconds)
                            }
                            val result = ApiResponse(parseJson(readBody(response)) ?: invalidResponse(), response.headers)
                            if (continuation.isActive) continuation.resume(result)
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        }
                    }
                }
            })
        }
    }

    private fun readBody(response: Response): String {
        val source = response.body?.source() ?: invalidResponse()
        source.request(MAX_RESPONSE_BYTES + 1)
        if (source.buffer.size > MAX_RESPONSE_BYTES) invalidResponse()
        return source.readUtf8()
    }

    private fun parseJson(text: String): JSONObject? = try {
        val tokenizer = JSONTokener(text)
        val value = tokenizer.nextValue() as? JSONObject
        value?.takeIf { tokenizer.nextClean() == '\u0000' }
    } catch (_: Exception) {
        null
    }

    private fun rememberBackoff(statusCode: Int, errorCode: String, seconds: Long) {
        val now = nowMillis()
        val until = if (seconds > (Long.MAX_VALUE - now) / 1000) Long.MAX_VALUE else now + seconds * 1000
        backoff.updateAndGet { previous ->
            if (previous != null && previous.untilMillis > until) previous else Backoff(statusCode, errorCode, until)
        }
    }

    private fun parseRetryAfter(value: String?): Long? {
        val text = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        text.toLongOrNull()?.let { return it.takeIf { seconds -> seconds >= 0 } }
        return try {
            val deadline = ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            secondsCeiling((deadline - nowMillis()).coerceAtLeast(0))
        } catch (_: Exception) {
            null
        }
    }

    private fun Headers.requiredRevision(): String {
        val values = values("X-Locker-Policy-Revision")
        return values.singleOrNull()?.takeIf(REVISION_PATTERN::matches) ?: invalidResponse()
    }

    private fun JSONObject.requiredString(name: String): String =
        (opt(name) as? String)?.takeIf(String::isNotBlank) ?: invalidResponse()

    private fun JSONObject.requiredBoolean(name: String): Boolean = opt(name) as? Boolean ?: invalidResponse()

    private fun validateDeviceId(id: String) {
        require(validDeviceId(id)) { "Invalid screen ID." }
    }

    private fun validDeviceId(id: String): Boolean = id.isNotBlank() && id.length <= 256 &&
        id != "." && id != ".." && id.none { it <= ' ' || it == '\u007f' }

    private fun invalidResponse(code: String = "INVALID_RESPONSE"): Nothing =
        throw LockerDeviceApiException(200, code)

    private data class ApiResponse(val json: JSONObject, val headers: Headers)
    private data class Backoff(val statusCode: Int, val errorCode: String, val untilMillis: Long)

    // OkHttp may otherwise replay a POST on 503 + Retry-After: 0 even when connection
    // retries are disabled. Activation nonces and unlock attempts are single-use requests.
    private class SingleAttemptBody(private val body: RequestBody) : RequestBody() {
        override fun contentType() = body.contentType()
        override fun contentLength() = body.contentLength()
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val MAC_PATTERN = Regex("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}")
        private val BASE64_URL_PATTERN = Regex("[A-Za-z0-9_-]+")
        private val NONCE_PATTERN = Regex("[a-f0-9]{64}")
        private val DEVICE_TOKEN_PATTERN = Regex("lkd_[A-Za-z0-9._~+/-]+=*")
        private val REVISION_PATTERN = Regex("[!-~]{1,256}")
        private val ERROR_CODE_PATTERN = Regex("[A-Z][A-Z0-9_]{0,79}")
        private const val MAX_RESPONSE_BYTES = 64L * 1024

        private fun secondsCeiling(milliseconds: Long): Long =
            milliseconds / 1000 + if (milliseconds % 1000 > 0) 1 else 0
    }
}

/** Read both halves again when a concurrent management change invalidates the revision. */
internal suspend fun LockerDeviceApi.readConsistentPolicy(credentials: LockerCredentials): Pair<LockerPolicy, LockerPassword> {
    repeat(3) { attempt ->
        try {
            val policy = getPolicy(credentials)
            val password = getPassword(credentials, policy.revision)
            if (policy.hasPassword != (password.password != null)) {
                throw LockerDeviceApiException(200, "INVALID_RESPONSE")
            }
            return policy to password
        } catch (error: LockerDeviceApiException) {
            val changed = error.statusCode == 412 || error.errorCode == "POLICY_REVISION_MISMATCH"
            if (!changed || attempt == 2) throw error
        }
    }
    error("Unreachable policy retry.")
}
