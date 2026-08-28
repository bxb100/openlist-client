package org.openlist.mobile.data.logging

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LogSanitizerTest {
    @Test
    fun redactsHeadersJsonQueriesRawUrlsJwtAndUserInfo() {
        val raw = """
            Authorization: Bearer header-secret
            {"password":"hunter2","access_token":"json-token"}
            GET https://example.test/file?safe=visible&sign=query-sign&token=query-token
            raw_url=https://cdn.test/file?opaque=raw-secret&another=value
            jwt=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signaturevalue
            https://alice:user-password@example.test/private
        """.trimIndent()

        val sanitized = LogSanitizer.redact(raw)

        assertThat(sanitized).contains("safe=visible")
        assertThat(sanitized).contains(LogSanitizer.REDACTED)
        assertThat(sanitized).doesNotContain("header-secret")
        assertThat(sanitized).doesNotContain("hunter2")
        assertThat(sanitized).doesNotContain("json-token")
        assertThat(sanitized).doesNotContain("query-sign")
        assertThat(sanitized).doesNotContain("query-token")
        assertThat(sanitized).doesNotContain("raw-secret")
        assertThat(sanitized).doesNotContain("signaturevalue")
        assertThat(sanitized).doesNotContain("user-password")
    }

    @Test
    fun redactsCommonAssignmentShapesAndIsIdempotent() {
        val raw = "token=one password is two pwd three api_key:four cookie=five"

        val once = LogSanitizer.redact(raw)
        val twice = LogSanitizer.redact(once)

        assertThat(once).doesNotContain("one")
        assertThat(once).doesNotContain("two")
        assertThat(once).doesNotContain("three")
        assertThat(once).doesNotContain("four")
        assertThat(once).doesNotContain("five")
        assertThat(twice).isEqualTo(once)
    }

    @Test
    fun doesNotRedactUnrelatedWordsOrSafeQueryValues() {
        val raw = "monkey business https://example.test/file?name=photo&size=42"

        assertThat(LogSanitizer.redact(raw)).isEqualTo(raw)
    }
}
