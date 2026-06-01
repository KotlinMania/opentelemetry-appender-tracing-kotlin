// port-lint: tests layer.rs
package io.github.kotlinmania.opentelemetryappendertracing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LayerTest {
    @Test
    fun tracing_appender_standalone() {
        val provider = InMemoryLoggerProvider()
        val bridge = OpenTelemetryTracingBridge(provider)

        bridge.onEvent(
            TracingEvent(
                metadata =
                    TracingMetadata(
                        level = TracingLevel.ERROR,
                        target = "my-system",
                        name = "my-event-name",
                    ),
            ) {
                recordLong("event_id", 20)
                recordBytes("bytes", byteArrayOf(97, 98, 99))
                recordError(IllegalStateException("already shutdown"))
                recordUnsignedLong("small_u64value", 42uL)
                recordUnsignedLong("big_u64value", ULong.MAX_VALUE)
                recordString("user_name", "otel")
                recordString("user_email", "otel@opentelemetry.io")
                recordString("message", "This is an example message")
            },
        )

        val log = provider.exported.single()
        assertEquals("", provider.scopeNames.single())
        assertEquals(Severity.Error, log.severityNumber)
        assertEquals("ERROR", log.severityText)
        assertEquals("my-system", log.target)
        assertEquals("my-event-name", log.eventName)
        assertNull(log.traceContext)
        assertEquals(AnyValue.Text("This is an example message"), log.body)
        assertEquals(AnyValue.Integer(20), log.attributes[Key("event_id")])
        assertEquals(AnyValue.Bytes(listOf(97, 98, 99)), log.attributes[Key("bytes")])
        assertEquals(AnyValue.Text("already shutdown"), log.attributes[Key("exception.message")])
        assertEquals(AnyValue.Integer(42), log.attributes[Key("small_u64value")])
        assertEquals(AnyValue.Text(ULong.MAX_VALUE.toString()), log.attributes[Key("big_u64value")])
        assertEquals(AnyValue.Text("otel"), log.attributes[Key("user_name")])
        assertEquals(AnyValue.Text("otel@opentelemetry.io"), log.attributes[Key("user_email")])
    }

    @Test
    fun disabled_events_are_not_emitted() {
        val provider = InMemoryLoggerProvider(enabled = false)
        val bridge = OpenTelemetryTracingBridge(provider)

        bridge.onEvent(
            TracingEvent(TracingMetadata(TracingLevel.INFO, "target", "name")) {
                recordString("message", "ignored")
            },
        )

        assertEquals(emptyList(), provider.exported)
    }
}

private class InMemoryLoggerProvider(
    enabled: Boolean = true,
) : LoggerProvider {
    val exported = mutableListOf<InMemoryLogRecord>()
    val scopeNames = mutableListOf<String>()
    private val logger = InMemoryLogger(exported, enabled)

    override fun logger(scopeName: String): Logger {
        scopeNames += scopeName
        return logger
    }
}

private class InMemoryLogger(
    private val exported: MutableList<InMemoryLogRecord>,
    private val enabled: Boolean,
) : Logger {
    override fun createLogRecord(): LogRecord = InMemoryLogRecord()

    override fun eventEnabled(
        severity: Severity,
        target: String,
        name: String?,
    ): Boolean = enabled

    override fun emit(record: LogRecord) {
        exported += record as InMemoryLogRecord
    }
}

private class InMemoryLogRecord : LogRecord {
    var body: AnyValue? = null
        private set
    val attributes = linkedMapOf<Key, AnyValue>()
    var target: String? = null
        private set
    var eventName: String? = null
        private set
    var severityNumber: Severity? = null
        private set
    var severityText: String? = null
        private set
    var traceContext: TraceContext? = null
        private set

    override fun setBody(value: AnyValue) {
        body = value
    }

    override fun addAttribute(
        key: Key,
        value: AnyValue,
    ) {
        attributes[key] = value
    }

    override fun setTarget(target: String) {
        this.target = target
    }

    override fun setEventName(name: String) {
        eventName = name
    }

    override fun setSeverityNumber(severity: Severity) {
        severityNumber = severity
    }

    override fun setSeverityText(text: String) {
        severityText = text
    }

    override fun setTraceContext(
        traceId: String,
        spanId: String,
        traceFlags: String?,
    ) {
        traceContext = TraceContext(traceId, spanId, traceFlags)
    }
}
