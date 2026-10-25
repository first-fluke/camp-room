package camp.room.app.observability

import io.sentry.SentryEvent
import io.sentry.protocol.Message
import io.sentry.protocol.SentryException
import io.sentry.protocol.User
import java.nio.file.Files
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TelemetryPrivacyTest {
    @Test fun `vendor automatic and private attributes never cross the allowlist`() {
        val output=TelemetryPolicy.filter(mapOf("screen" to "trip","outcome" to "success","duration_ms" to 35,"runtime" to "demo","order_mode" to "demo",
            "address" to "private home","latitude" to 37.2,"transcript" to "private speech","VIN" to "secret",
            "authorization" to "Bearer secret","\$device_id" to "fingerprint","screen_name" to "user address",
            "api" to "/orders/private-id","status_code" to 200,"error_type" to "secret user text"))
        assertEquals(mapOf("screen" to "trip","outcome" to "success","duration_ms" to "35","status_code" to "200","runtime" to "demo","order_mode" to "demo"),output)
    }
    @Test fun `crash diagnostics preserve type and frames without messages causes or thread name`() {
        val directory=Files.createTempDirectory("camp-crash").toFile()
        try {
            val store=DiagnosticStore(directory)
            val original=IllegalStateException("SECRET_ADDRESS_TOKEN_TRANSCRIPT",IllegalArgumentException("SECRET_CAUSE"))
            var delegated:Throwable?=null
            val handler=RecordingExceptionHandler(store,Thread.UncaughtExceptionHandler { _,value -> delegated=value })
            handler.uncaughtException(Thread("SECRET_THREAD"),original)
            val text=directory.resolve("events.jsonl").readText()
            assertFalse(text.contains("SECRET"));assertSame(original,delegated)
            val record=Json.decodeFromString<DiagnosticRecord>(text.trim())
            assertEquals("camp_crash",record.event);assertEquals("java.lang.IllegalStateException",record.crash?.type)
            assertTrue(record.crash!!.frames.isNotEmpty())
        } finally { directory.deleteRecursively() }
    }
    @Test fun `local diagnostic rotation stays bounded and keeps parseable records`() {
        val directory=Files.createTempDirectory("camp-rotation").toFile()
        try {
            val store=DiagnosticStore(directory,2048)
            repeat(60) { store.append(TelemetryPolicy.record(Event.SCREEN_VIEWED,Dimensions(screen=Screen.CABIN))) }
            val files=directory.listFiles()!!.toList()
            assertEquals(2,files.size)
            files.forEach { file -> assertTrue(file.length()<=2048);file.readLines().forEach { Json.decodeFromString<DiagnosticRecord>(it) } }
        } finally { directory.deleteRecursively() }
    }
    @Test fun `cloud crash payload is rebuilt without user request extras or raw message`() {
        val input=SentryEvent().apply {
            message=Message().apply { formatted="SECRET_TRANSCRIPT" }
            user=User().apply { email="SECRET_EMAIL" }
            setExtra("address","SECRET_ADDRESS")
            exceptions=listOf(SentryException().apply { type="IllegalStateException";value="SECRET_EXCEPTION" })
        }
        val clean=sanitizedSentry(input)
        assertNull(clean.user);assertNull(clean.message);assertTrue(clean.extras.isNullOrEmpty())
        assertNull(clean.exceptions!!.first().value)
        assertEquals("IllegalStateException",clean.exceptions!!.first().type)
    }
}
