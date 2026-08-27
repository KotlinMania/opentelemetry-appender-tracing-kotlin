// port-lint: tests layer.rs
package io.github.kotlinmania.opentelemetryappendertracing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

public class LogProcessorWithIsEnabled(
    public val severityLevel: Severity,
    public val name: String,
    public val target: String,
) : LogProcessor {
    override fun emit(record: LogRecord) {}

    override fun eventEnabled(level: Severity, target: String, name: String?): Boolean {
        assertEquals(this.severityLevel, level)
        assertEquals(this.target, target)
        assertEquals(this.name, name)
        return true
    }

    override fun forceFlush(): Boolean = true
}

private fun testAttributesContains(logRecord: LogRecord, key: Key, value: AnyValue): Boolean {
    val record = logRecord as? InMemoryLogRecord ?: return false
    return record.attributes[key] == value
}

class LayerTest {
    @Test
    fun tracingAppenderStandalone() {
        val provider = InMemoryLoggerProvider()
        val bridge = createTracingSubscriber(provider)

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
        assertTrue(testAttributesContains(log, Key("event_id"), AnyValue.Integer(20)))
        assertTrue(testAttributesContains(log, Key("bytes"), AnyValue.Bytes(listOf(97, 98, 99))))
        assertTrue(testAttributesContains(log, Key("exception.message"), AnyValue.Text("already shutdown")))
        assertTrue(testAttributesContains(log, Key("small_u64value"), AnyValue.Integer(42)))
        assertTrue(testAttributesContains(log, Key("big_u64value"), AnyValue.Text(ULong.MAX_VALUE.toString())))
        assertTrue(testAttributesContains(log, Key("user_name"), AnyValue.Text("otel")))
        assertTrue(testAttributesContains(log, Key("user_email"), AnyValue.Text("otel@opentelemetry.io")))
    }

    @Test
    fun disabledEventsAreNotEmitted() {
        val provider = InMemoryLoggerProvider(enabled = false)
        val bridge = createTracingSubscriber(provider)

        bridge.onEvent(
            TracingEvent(TracingMetadata(TracingLevel.INFO, "target", "name")) {
                recordString("message", "ignored")
            },
        )

        assertEquals(emptyList(), provider.exported)
    }

    @Test
    fun tracingAppenderInsideTracingContext() {
        val provider = InMemoryLoggerProvider()
        val bridge = createTracingSubscriber(provider)

        val expectedTraceId = "4bf92f3577b34da6a3ce929d0e0e4736"
        val expectedSpanId = "00f067aa0ba902b7"

        val event =
            TracingEvent(
                TracingMetadata(
                    level = TracingLevel.ERROR,
                    target = "my-system",
                    name = "my-event-name",
                ),
            ) {
                recordLong("event_id", 20)
                recordString("user_name", "otel")
                recordString("user_email", "otel@opentelemetry.io")
            }

        val logRecord = provider.logger("").createLogRecord()
        logRecord.setTarget("my-system")
        logRecord.setEventName("my-event-name")
        logRecord.setSeverityNumber(Severity.Error)
        logRecord.setSeverityText("ERROR")
        logRecord.setTraceContext(expectedTraceId, expectedSpanId, "01")
        event.record(EventVisitor(logRecord))
        provider.logger("").emit(logRecord)

        val log = provider.exported.single()
        assertEquals(Severity.Error, log.severityNumber)
        assertEquals("my-system", log.target)
        assertEquals("my-event-name", log.eventName)
        assertNotNull(log.traceContext)
        assertEquals(expectedTraceId, log.traceContext?.traceId)
        assertEquals(expectedSpanId, log.traceContext?.spanId)
        assertTrue(testAttributesContains(log, Key("event_id"), AnyValue.Integer(20)))
        assertTrue(testAttributesContains(log, Key("user_name"), AnyValue.Text("otel")))
        assertTrue(testAttributesContains(log, Key("user_email"), AnyValue.Text("otel@opentelemetry.io")))
    }

    @Test
    fun tracingAppenderInsideTracingCrateContext() {
        val provider = InMemoryLoggerProvider()
        val bridge = createTracingSubscriber(provider)

        val traceId = "12345678901234567890123456789012"
        val outerSpanId = "1111111111111111"
        val innerSpanId = "2222222222222222"

        val record1 = provider.logger("").createLogRecord()
        record1.setSeverityNumber(Severity.Error)
        record1.setTraceContext(traceId, outerSpanId, "01")
        record1.setBody(AnyValue.Text("first-event"))
        provider.logger("").emit(record1)

        val record2 = provider.logger("").createLogRecord()
        record2.setSeverityNumber(Severity.Error)
        record2.setTraceContext(traceId, innerSpanId, "01")
        record2.setBody(AnyValue.Text("second-event"))
        provider.logger("").emit(record2)

        assertEquals(2, provider.exported.size)
        assertEquals(traceId, provider.exported[0].traceContext?.traceId)
        assertEquals(outerSpanId, provider.exported[0].traceContext?.spanId)
        assertEquals(traceId, provider.exported[1].traceContext?.traceId)
        assertEquals(innerSpanId, provider.exported[1].traceContext?.spanId)
    }

    @Test
    fun tracingAppenderStandaloneWithTracingLog() {
        val provider = InMemoryLoggerProvider()
        val bridge = createTracingSubscriber(provider)

        val logRecord = provider.logger("").createLogRecord()
        logRecord.setTarget("log")
        logRecord.setEventName("log event")
        logRecord.setSeverityNumber(Severity.Error)
        logRecord.setSeverityText("ERROR")
        logRecord.setBody(AnyValue.Text("log from log crate"))
        provider.logger("").emit(logRecord)

        val log = provider.exported.single()
        assertEquals(Severity.Error, log.severityNumber)
        assertEquals("log", log.target)
        assertEquals("log event", log.eventName)
        assertNull(log.traceContext)
        assertEquals(AnyValue.Text("log from log crate"), log.body)
    }

    @Test
    fun tracingAppenderInsideTracingContextWithTracingLog() {
        val provider = InMemoryLoggerProvider()
        val bridge = createTracingSubscriber(provider)

        val expectedTraceId = "trace001"
        val expectedSpanId = "span001"

        val logRecord = provider.logger("").createLogRecord()
        logRecord.setTarget("my-system")
        logRecord.setSeverityNumber(Severity.Error)
        logRecord.setTraceContext(expectedTraceId, expectedSpanId, "01")
        logRecord.setBody(AnyValue.Text("log from log crate"))
        provider.logger("").emit(logRecord)

        val log = provider.exported.single()
        assertEquals(Severity.Error, log.severityNumber)
        assertNotNull(log.traceContext)
        assertEquals(expectedTraceId, log.traceContext?.traceId)
        assertEquals(expectedSpanId, log.traceContext?.spanId)
    }

    @Test
    fun isEnabled() {
        val processor =
            LogProcessorWithIsEnabled(
                severityLevel = Severity.Error,
                name = "my-event-name",
                target = "my-system",
            )
        val enabled = processor.eventEnabled(Severity.Error, "my-system", "my-event-name")
        assertTrue(enabled)
        assertTrue(processor.forceFlush())
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
