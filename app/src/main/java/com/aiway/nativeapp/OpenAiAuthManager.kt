package com.aiway.nativeapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Official Sign in with ChatGPT flow for open-source/local clients:
 * dynamic client registration + Authorization Code + PKCE + loopback callback.
 * No client secret and no external backend are required.
 */
class OpenAiAuthManager(
    private val context: Context,
    private val secure: SecureStore,
    private val http: OkHttpClient
) {
    data class Session(
        val clientId: String,
        val accessToken: String,
        val refreshToken: String,
        val idToken: String,
        val email: String,
        val subject: String,
        val scopes: Set<String>,
        val expiresAtMs: Long
    )

    private val authEndpoint = "https://auth.openai.com/api/accounts/authorize"
    private val tokenEndpoint = "https://auth.openai.com/api/accounts/oauth/token"
    private val jwksEndpoint = "https://auth.openai.com/.well-known/jwks.json"
    private val resource = "https://api.openai.com/v1"
    private val issuer = "https://auth.openai.com"
    private val random = SecureRandom()

    private val hostId: String
        get() {
            secure.get("openai_host_id").takeIf { it.isNotBlank() }?.let { return it }
            val id = "urn:uuid:${UUID.randomUUID()}"
            secure.put("openai_host_id", id)
            return id
        }

    fun currentSession(): Session? = loadSession()

    suspend fun signIn(openBrowser: (String) -> Unit): Session = withContext(Dispatchers.IO) {
        val existing = loadSession()
        val state = randomUrlSafe(32)
        val nonce = randomUrlSafe(32)
        val verifier = randomUrlSafe(64)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val listener = LoopbackListener()
        val redirectUri = "http://127.0.0.1:${listener.port}/auth/callback"
        val initial = existing?.clientId.isNullOrBlank()
        val requestClientId = if (initial) "dynamic_agent_client" else existing!!.clientId

        val uri = Uri.parse(authEndpoint).buildUpon()
            .appendQueryParameter("client_id", requestClientId)
            .apply {
                if (initial) appendQueryParameter("agent_name_hint", "AiWay")
                appendQueryParameter("ext_agent_host_id", hostId)
                if (!initial && !existing!!.idToken.isBlank()) appendQueryParameter("id_token_hint", existing.idToken)
                if (!initial && existing!!.email.isNotBlank()) appendQueryParameter("login_hint", existing.email)
            }
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("scope", "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct")
            .appendQueryParameter("resource", resource)
            .appendQueryParameter("state", state)
            .appendQueryParameter("nonce", nonce)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .build().toString()

        withContext(Dispatchers.Main) { openBrowser(uri) }
        val callback = try {
            withTimeout(TimeUnit.MINUTES.toMillis(10)) { listener.await() }
        } finally {
            listener.close()
        }
        if (callback["state"] != state) error("فشل التحقق من حالة تسجيل الدخول (state mismatch)")
        callback["error"]?.let { error("OpenAI login: $it") }
        val code = callback["code"].orEmpty()
        require(code.isNotBlank()) { "لم يصل authorization code من OpenAI" }
        val issuedClientId = if (initial) callback["client_id"].orEmpty() else existing!!.clientId
        require(issuedClientId.isNotBlank() && issuedClientId != "dynamic_agent_client") {
            "لم يصل client_id المسجل من OpenAI"
        }
        if (!initial && callback["client_id"].orEmpty().isNotBlank() && callback["client_id"] != issuedClientId) {
            error("OpenAI returned a different client_id")
        }

        val tokenJson = postToken(
            FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("client_id", issuedClientId)
                .add("code", code)
                .add("code_verifier", verifier)
                .add("redirect_uri", redirectUri)
                .add("resource", resource)
                .build()
        )
        val idToken = tokenJson.optString("id_token")
        require(idToken.isNotBlank()) { "Token response did not include id_token" }
        val claims = validateIdToken(idToken, issuedClientId, nonce)
        val scopes = tokenJson.optString("scope").split(' ').filter { it.isNotBlank() }.toSet()
        require("chatgpt.tokens.use.direct" in scopes) {
            "لم يتم منح صلاحية استخدام خطة ChatGPT للتطبيق"
        }
        val session = Session(
            clientId = issuedClientId,
            accessToken = tokenJson.optString("access_token"),
            refreshToken = tokenJson.optString("refresh_token"),
            idToken = idToken,
            email = claims.optString("email"),
            subject = claims.optString("sub"),
            scopes = scopes,
            expiresAtMs = System.currentTimeMillis() + tokenJson.optLong("expires_in", 3600) * 1000L
        )
        require(session.accessToken.isNotBlank()) { "Token response did not include access_token" }
        saveSession(session)
        session
    }

    suspend fun validAccessToken(): String = withContext(Dispatchers.IO) {
        var session = loadSession() ?: error("سجل الدخول إلى ChatGPT أولاً")
        if (session.expiresAtMs - System.currentTimeMillis() > TimeUnit.MINUTES.toMillis(5)) return@withContext session.accessToken
        if (session.refreshToken.isBlank()) error("انتهت جلسة ChatGPT. سجّل الدخول مرة أخرى")

        val tokenJson = postToken(
            FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", session.clientId)
                .add("refresh_token", session.refreshToken)
                .add("resource", resource)
                .build()
        )
        val nextIdToken = tokenJson.optString("id_token").ifBlank { session.idToken }
        // Refresh responses may omit an id_token. When present, signature/issuer/audience/expiry are still validated.
        if (tokenJson.optString("id_token").isNotBlank()) validateIdToken(nextIdToken, session.clientId, expectedNonce = null)
        val scopes = tokenJson.optString("scope")
            .takeIf { it.isNotBlank() }
            ?.split(' ')?.filter { it.isNotBlank() }?.toSet()
            ?: session.scopes
        require("chatgpt.tokens.use.direct" in scopes) { "ChatGPT plan permission is no longer available" }
        session = session.copy(
            accessToken = tokenJson.optString("access_token").ifBlank { error("Refresh response missing access_token") },
            refreshToken = tokenJson.optString("refresh_token").ifBlank { session.refreshToken },
            idToken = nextIdToken,
            scopes = scopes,
            expiresAtMs = System.currentTimeMillis() + tokenJson.optLong("expires_in", 3600) * 1000L
        )
        saveSession(session)
        session.accessToken
    }

    fun signOutLocal() {
        secure.remove("openai_session")
    }

    private fun postToken(body: FormBody): JSONObject {
        val req = Request.Builder().url(tokenEndpoint).post(body).header("Accept", "application/json").build()
        http.newCall(req).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val obj = runCatching { JSONObject(text) }.getOrNull()
                val msg = obj?.optString("error_description").takeUnless { it.isNullOrBlank() }
                    ?: obj?.optString("error").takeUnless { it.isNullOrBlank() }
                    ?: "HTTP ${response.code}"
                error("OpenAI OAuth: $msg")
            }
            return JSONObject(text)
        }
    }

    private fun validateIdToken(jwt: String, expectedAudience: String, expectedNonce: String?): JSONObject {
        val parts = jwt.split('.')
        require(parts.size == 3) { "Invalid id_token" }
        val header = JSONObject(String(Base64.decode(parts[0], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)))
        val claims = JSONObject(String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)))
        require(header.optString("alg") == "RS256") { "Unsupported id_token algorithm" }
        val kid = header.optString("kid")
        require(kid.isNotBlank()) { "id_token missing kid" }

        val jwksReq = Request.Builder().url(jwksEndpoint).get().build()
        val jwks = http.newCall(jwksReq).execute().use { r ->
            if (!r.isSuccessful) error("تعذر تحميل OpenAI JWKS: HTTP ${r.code}")
            JSONObject(r.body?.string().orEmpty())
        }
        val keys = jwks.optJSONArray("keys") ?: JSONArray()
        var keyObj: JSONObject? = null
        for (i in 0 until keys.length()) {
            val k = keys.optJSONObject(i) ?: continue
            if (k.optString("kid") == kid) { keyObj = k; break }
        }
        require(keyObj != null) { "لم يتم العثور على مفتاح توقيع OpenAI" }
        val n = BigInteger(1, Base64.decode(keyObj!!.getString("n"), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        val e = BigInteger(1, Base64.decode(keyObj!!.getString("e"), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e))
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
        require(verifier.verify(Base64.decode(parts[2], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))) { "توقيع id_token غير صالح" }

        require(claims.optString("iss") == issuer) { "id_token issuer غير صحيح" }
        val audOk = when (val aud = claims.opt("aud")) {
            is String -> aud == expectedAudience
            is JSONArray -> (0 until aud.length()).any { aud.optString(it) == expectedAudience }
            else -> false
        }
        require(audOk) { "id_token audience غير صحيح" }
        require(claims.optLong("exp", 0) > System.currentTimeMillis() / 1000L) { "id_token منتهي" }
        if (expectedNonce != null) require(claims.optString("nonce") == expectedNonce) { "id_token nonce غير صحيح" }
        require(claims.optString("sub").isNotBlank()) { "id_token missing subject" }
        return claims
    }

    private fun saveSession(s: Session) {
        secure.put("openai_session", JSONObject()
            .put("client_id", s.clientId)
            .put("access_token", s.accessToken)
            .put("refresh_token", s.refreshToken)
            .put("id_token", s.idToken)
            .put("email", s.email)
            .put("subject", s.subject)
            .put("scopes", JSONArray(s.scopes.toList()))
            .put("expires_at", s.expiresAtMs)
            .toString())
    }

    private fun loadSession(): Session? {
        val raw = secure.get("openai_session")
        if (raw.isBlank()) return null
        return runCatching {
            val o = JSONObject(raw)
            val scopesArr = o.optJSONArray("scopes") ?: JSONArray()
            val scopes = (0 until scopesArr.length()).map { scopesArr.optString(it) }.filter { it.isNotBlank() }.toSet()
            Session(
                clientId = o.getString("client_id"),
                accessToken = o.getString("access_token"),
                refreshToken = o.optString("refresh_token"),
                idToken = o.getString("id_token"),
                email = o.optString("email"),
                subject = o.getString("subject"),
                scopes = scopes,
                expiresAtMs = o.optLong("expires_at", 0L)
            )
        }.getOrNull()
    }

    private fun randomUrlSafe(bytes: Int): String {
        val data = ByteArray(bytes).also(random::nextBytes)
        return base64Url(data)
    }

    private fun base64Url(data: ByteArray): String = Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private class LoopbackListener {
        private val server: ServerSocket = openServer()
        val port: Int get() = server.localPort
        private val result = CompletableDeferred<Map<String, String>>()

        private val worker = Thread {
            try {
                while (!result.isCompleted && !server.isClosed) {
                    val socket = server.accept()
                    socket.soTimeout = 5_000
                    socket.use {
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                        val first = reader.readLine().orEmpty()
                        val target = first.split(' ').getOrNull(1).orEmpty()
                        // Drain request headers so Chromium does not keep the request pending.
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isBlank()) break
                        }

                        val uri = runCatching { Uri.parse("http://127.0.0.1$target") }.getOrNull()
                        val isCallback = uri?.path == "/auth/callback"
                        val params = linkedMapOf<String, String>()
                        if (isCallback && uri != null) {
                            uri.queryParameterNames.forEach { name ->
                                params[name] = uri.getQueryParameter(name).orEmpty()
                            }
                        }

                        val body = if (isCallback) {
                            """<!doctype html><html dir='rtl'><head><meta name='viewport' content='width=device-width,initial-scale=1'><meta charset='utf-8'><title>AiWay</title></head><body style='margin:0;background:#f7f9ff;font-family:Arial,sans-serif;color:#172033;display:flex;min-height:100vh;align-items:center;justify-content:center'><div style='max-width:420px;padding:28px;text-align:center'><div style='width:72px;height:72px;border-radius:22px;background:#2f6fed;color:white;margin:0 auto 18px;display:flex;align-items:center;justify-content:center;font-size:28px;font-weight:700'>A</div><h2>تم ربط ChatGPT بـ AiWay</h2><p style='color:#667085;line-height:1.7'>تم استلام تسجيل الدخول بنجاح. سيتم الرجوع إلى تطبيق AiWay تلقائياً.</p><p><a href='aiway://oauth-complete' style='display:inline-block;background:#2f6fed;color:white;padding:12px 22px;border-radius:14px;text-decoration:none'>العودة إلى AiWay</a></p><script>setTimeout(function(){location.href='aiway://oauth-complete'},700)</script></div></body></html>"""
                        } else {
                            """<!doctype html><html><body>AiWay OAuth listener is running.</body></html>"""
                        }
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        val out = socket.getOutputStream()
                        val status = if (isCallback) "200 OK" else "204 No Content"
                        out.write(("HTTP/1.1 $status\r\n" +
                            "Content-Type: text/html; charset=utf-8\r\n" +
                            "Cache-Control: no-store\r\n" +
                            "Content-Length: ${bytes.size}\r\n" +
                            "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                        if (isCallback) out.write(bytes)
                        out.flush()

                        if (isCallback) result.complete(params)
                    }
                }
            } catch (t: Throwable) {
                if (!result.isCompleted && !server.isClosed) result.completeExceptionally(t)
            }
        }.apply {
            isDaemon = true
            name = "aiway-oauth-loopback"
            start()
        }

        suspend fun await(): Map<String, String> = result.await()
        fun close() { runCatching { server.close() } }

        companion object {
            private fun openServer(): ServerSocket {
                // Prefer the documented example port on mobile; fall back to an ephemeral port if occupied.
                return runCatching { ServerSocket(1455, 16, InetAddress.getByName("127.0.0.1")) }
                    .getOrElse { ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")) }
            }
        }
    }
}