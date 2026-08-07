package org.nostr.nostrord.auth.pomegranate

import org.nostr.nostrord.utils.epochSeconds
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@OptIn(ExperimentalEncodingApi::class)
class PomegranateServiceTest {
    private val service = PomegranateService()

    private fun tokenOf(
        createdAt: Long,
        tags: String = """[["email","a@b.c"]]""",
    ): String = Base64.encode("""{"created_at":$createdAt,"tags":$tags}""".encodeToByteArray())

    @Test
    fun defaultThresholdIsALittleOverHalf() {
        assertEquals(2, PomegranateConfig.defaultThreshold(2))
        assertEquals(2, PomegranateConfig.defaultThreshold(3))
        assertEquals(3, PomegranateConfig.defaultThreshold(5))
        assertEquals(7, PomegranateConfig.defaultThreshold(12))
    }

    @Test
    fun normalizeOriginDropsPathAndTrailingSlash() {
        assertEquals("https://auth.njump.me", normalizePomegranateOrigin("https://auth.njump.me/"))
        assertEquals("https://po.njump.me", normalizePomegranateOrigin("po.njump.me"))
        assertEquals("https://x.example:8443", normalizePomegranateOrigin("https://x.example:8443/path/"))
        assertEquals("http://localhost:8080", normalizePomegranateOrigin("localhost:8080"))
        assertEquals("https://auth.njump.me", normalizePomegranateOrigin("  https://auth.njump.me  "))
    }

    @Test
    fun decodeGoogleTokenParsesEmailAndCreatedAt() {
        val createdAt = epochSeconds() - 60
        val token = service.decodeGoogleToken(tokenOf(createdAt))
        assertEquals("a@b.c", token.email)
        assertEquals(createdAt * 1000, token.createdAtMillis)
    }

    @Test
    fun decodeGoogleTokenToleratesMissingEmailTag() {
        val token = service.decodeGoogleToken(tokenOf(epochSeconds(), tags = "[]"))
        assertEquals("", token.email)
    }

    @Test
    fun decodeGoogleTokenRejectsExpired() {
        assertFailsWith<Exception> {
            service.decodeGoogleToken(tokenOf(epochSeconds() - 25 * 60 * 60))
        }
    }

    @Test
    fun decodeGoogleTokenRejectsGarbage() {
        assertFailsWith<Exception> { service.decodeGoogleToken("not-base64!!") }
        assertFailsWith<Exception> { service.decodeGoogleToken(Base64.encode("[1,2]".encodeToByteArray())) }
    }

    @Test
    fun parseAndroidLoginTokenReadsTheTokenField() {
        assertEquals("abc123", service.parseAndroidLoginToken("""{"token":"abc123"}"""))
    }

    @Test
    fun parseAndroidLoginTokenReturnsNullOnUnusableReplies() {
        // An error page or an empty token must fall back to the browser flow, not log in blank.
        assertNull(service.parseAndroidLoginToken("invalid google token audience"))
        assertNull(service.parseAndroidLoginToken("""{"error":"nope"}"""))
        assertNull(service.parseAndroidLoginToken("""{"token":""}"""))
    }
}
