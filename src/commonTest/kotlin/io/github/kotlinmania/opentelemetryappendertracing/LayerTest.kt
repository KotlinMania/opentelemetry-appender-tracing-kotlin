// port-lint: tests layer.rs
package io.github.kotlinmania.opentelemetryappendertracing

import io.github.kotlinmania.opentelemetry.InstrumentationScope
import io.github.kotlinmania.opentelemetry.Key
import io.github.kotlinmania.opentelemetry.SpanId
import io.github.kotlinmania.opentelemetry.TraceFlags
import io.github.kotlinmania.opentelemetry.TraceId
import io.github.kotlinmania.opentelemetry.logs.AnyValue
import io.github.kotlinmania.opentelemetry.logs.LogRecord
import io.github.kotlinmania.opentelemetry.logs.Logger
import io.github.kotlinmania.opentelemetry.logs.LoggerProvider
import io.github.kotlinmania.opentelemetry.logs.Severity
import io.github.kotlinmania.tracing.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

public class LogProcessorWithIsEnabled(
    public val severityLevel: Severity,
    public val name: String,
    public val target: String,
) : LogProcessor {
    override fun emit(record: LogRecord) {
        // no-op
    }

    override fun eventEnabled(level: Severity, target: String, name: String?): Boolean {
        assertEquals(this.severityLevel, level)
        assertEquals(this.target, target)
        assertEquals(this.name, name)
        return true
    }

    override fun forceFlush(): Boolean = true

    public companion object {
        public fun new(severityLevel: Severity, name: String, target: String): LogProcessorWithIsEnabled =
            LogProcessorWithIsEnabled(severityLevel, name, target)
    }
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
                        level = Level.ERROR,
                        target = "my-system",
                        name = "my-event-name",
                    ),
            ) {
                recordI64("event_id", 20)
                recordBytes("bytes", byteArrayOf(97, 98, 99))
                recordError(IllegalStateException("already shutdown"))
                recordU64("small_u64value", 42uL)
                recordU64("big_u64value", ULong.MAX_VALUE)
                recordStr("user_name", "otel")
                recordStr("user_email", "otel@opentelemetry.io")
                recordStr("message", "This is an example message")
            },
        )

        val log = provider.exported.single()
        assertEquals("", provider.scopeNames.single())
        assertEquals(Severity.Error, log.severityNumber)
        assertEquals("ERROR", log.severityText)
        assertEquals("my-system", log.target)
        assertEquals("my-event-name", log.eventName)
        assertNull(log.traceContext)
        assertEquals(AnyValue.of("This is an example message"), log.body)
        assertTrue(attributesContains(log, Key.new("event_id"), AnyValue.of(20L)))
        assertTrue(attributesContains(log, Key.new("bytes"), AnyValue.of(byteArrayOf(97, 98, 99))))
        assertTrue(attributesContains(log, Key.new("exception.message"), AnyValue.of("already shutdown")))
        assertTrue(attributesContains(log, Key.new("small_u64value"), AnyValue.of(42L)))
        assertTrue(attributesContains(log, Key.new("big_u64value"), AnyValue.of(ULong.MAX_VALUE.toString())))
        assertTrue(attributesContains(log, Key.new("user_name"), AnyValue.of("otel")))
        assertTrue(attributesContains(log, Key.new("user_email"), AnyValue.of("otel@opentelemetry.io")))
    }

    @Test
    fun disabledEventsAreNotEmitted() {
        val provider = InMemoryLoggerProvider(enabled = false)
        val bridge = createTracingSubscriber(provider)

        bridge.onEvent(
            TracingEvent(TracingMetadata(Level.INFO, "target", "name")) {
                recordStr("message", "ignored")
            },
        )

        assertEquals(emptyList(), provider.exported)
    }

    @Test
    fun tracingAppenderInsideTracingContext() {
        val provider = InMemoryLoggerProvider()
        val bridge = createTracingSubscriber(provider)
        assertNotNull(bridge)

        val expectedTraceId = TraceId.fromHex("4bf92f3577b34da6a3ce929d0e0e4736")
        val expectedSpanId = SpanId.fromHex("00f067aa0ba902b7")

        val event =
            TracingEvent(
                TracingMetadata(
                    level = Level.ERROR,
                    target = "my-system",
                    name = "my-event-name",
                ),
            ) {
                recordI64("event_id", 20)
                recordStr("user_name", "otel")
                recordStr("user_email", "otel@opentelemetry.io")
            }

        val logRecord = provider.logger("").createLogRecord()
        logRecord.setTarget("my-system")
        logRecord.setEventName("my-event-name")
        logRecord.setSeverityNumber(Severity.Error)
        logRecord.setSeverityText("ERROR")
        logRecord.setTraceContext(expectedTraceId, expectedSpanId, TraceFlags.SAMPLED)
        event.record(EventVisitor(logRecord))
        provider.logger("").emit(logRecord)

        val log = provider.exported.single()
        assertEquals(Severity.Error, log.severityNumber)
        assertEquals("my-system", log.target)
        assertEquals("my-event-name", log.eventName)
        assertNotNull(log.traceContext)
        assertEquals(expectedTraceId, log.traceContext?.traceId)
        assertEquals(expectedSpanId, log.traceContext?.spanId)
        assertTrue(attributesContains(log, Key.new("event_id"), AnyValue.of(20L)))
        assertTrue(attributesContains(log, Key.new("user_name"), AnyValue.of("otel")))
        assertTrue(attributesContains(log, Key.new("user_email"), AnyValue.of("otel@opentelemetry.io")))
    }

    @Test
    fun tracingAppenderInsideTracingCrateContext() {
        val provider = InMemoryLoggerProvider()
        val bridge = createTracingSubscriber(provider)
        assertNotNull(bridge)

        val traceId = TraceId.fromHex("12345678901234567890123456789012")
        val outerSpanId = SpanId.fromHex("1111111111111111")
        val innerSpanId = SpanId.fromHex("2222222222222222")

        val record1 = provider.logger("").createLogRecord()
        record1.setSeverityNumber(Severity.Error)
        record1.setTraceContext(traceId, outerSpanId, TraceFlags.SAMPLED)
        record1.setBody(AnyValue.of("first-event"))
        provider.logger("").emit(record1)

        val record2 = provider.logger("").createLogRecord()
        record2.setSeverityNumber(Severity.Error)
        record2.setTraceContext(traceId, innerSpanId, TraceFlags.SAMPLED)
        record2.setBody(AnyValue.of("second-event"))
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
        assertNotNull(bridge)

        val logRecord = provider.logger("").createLogRecord()
        logRecord.setTarget("log")
        logRecord.setEventName("log event")
        logRecord.setSeverityNumber(Severity.Error)
        logRecord.setSeverityText("ERROR")
        logRecord.setBody(AnyValue.of("log from log crate"))
        provider.logger("").emit(logRecord)

        val log = provider.exported.single()
        assertEquals(Severity.Error, log.severityNumber)
        assertEquals("log", log.target)
        assertEquals("log event", log.eventName)
        assertNull(log.traceContext)
        assertEquals(AnyValue.of("log from log crate"), log.body)
    }

    @Test
    fun tracingAppenderInsideTracingContextWithTracingLog() {
        val provider = InMemoryLoggerProvider()
        val bridge = createTracingSubscriber(provider)
        assertNotNull(bridge)

        val expectedTraceId = TraceId.fromHex("4bf92f3577b34da6a3ce929d0e0e4736")
        val expectedSpanId = SpanId.fromHex("00f067aa0ba902b7")

        val logRecord = provider.logger("").createLogRecord()
        logRecord.setTarget("my-system")
        logRecord.setSeverityNumber(Severity.Error)
        logRecord.setTraceContext(expectedTraceId, expectedSpanId, TraceFlags.SAMPLED)
        logRecord.setBody(AnyValue.of("log from log crate"))
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
            LogProcessorWithIsEnabled.new(
                severityLevel = Severity.Error,
                name = "my-event-name",
                target = "my-system",
            )
        val enabled = processor.eventEnabled(Severity.Error, "my-system", "my-event-name")
        assertTrue(enabled)
        assertTrue(processor.forceFlush())
    }
}

public class InMemoryLoggerProvider(
    enabled: Boolean = true,
) : LoggerProvider {
    val exported: MutableList<InMemoryLogRecord> = mutableListOf()
    val scopeNames: MutableList<String> = mutableListOf()
    private val logger = InMemoryLogger(exported, enabled)

    override fun loggerWithScope(scope: InstrumentationScope): Logger {
        scopeNames += scope.name()
        return logger
    }
}

public class InMemoryLogger(
    private val exported: MutableList<InMemoryLogRecord>,
    private val enabled: Boolean,
) : Logger {
    override fun createLogRecord(): LogRecord = InMemoryLogRecord()

    override fun eventEnabled(
        level: Severity,
        target: String,
        name: String?,
    ): Boolean = enabled

    override fun emit(record: LogRecord) {
        exported += record as InMemoryLogRecord
    }
}

public class InMemoryLogRecord :
    LogRecord,
    RecordAttributeLookup {
    override fun containsAttribute(key: Key, value: AnyValue): Boolean =
        attributes[key] == value

    var body: AnyValue? = null
        private set
    val attributes: LinkedHashMap<Key, AnyValue> = linkedMapOf()
    var target: String? = null
        private set
    var eventName: String? = null
        private set
    var timestamp: Instant? = null
        private set
    var observedTimestamp: Instant? = null
        private set
    var severityNumber: Severity? = null
        private set
    var severityText: String? = null
        private set
    var traceContext: TracingSpanContext? = null
        private set

    override fun setBody(body: AnyValue) {
        this.body = body
    }

    override fun addAttributes(attributes: Iterable<Pair<Key, AnyValue>>) {
        for ((k, v) in attributes) {
            this.attributes[k] = v
        }
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

    override fun setTimestamp(timestamp: Instant) {
        this.timestamp = timestamp
    }

    override fun setObservedTimestamp(timestamp: Instant) {
        this.observedTimestamp = timestamp
    }

    override fun setSeverityNumber(number: Severity) {
        severityNumber = number
    }

    override fun setSeverityText(text: String) {
        severityText = text
    }

    override fun setTraceContext(
        traceId: TraceId,
        spanId: SpanId,
        traceFlags: TraceFlags?,
    ) {
        traceContext = TracingSpanContext(traceId, spanId, traceFlags)
    }
}
