// port-lint: source layer.rs
@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package io.github.kotlinmania.opentelemetryappendertracing

import kotlin.native.HiddenFromObjC

public enum class Severity {
    Trace,
    Debug,
    Info,
    Warn,
    Error,
}

public enum class TracingLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

public sealed interface AnyValue {
    public data class Text(
        public val value: String,
    ) : AnyValue

    public data class Integer(
        public val value: Long,
    ) : AnyValue

    public data class FloatingPoint(
        public val value: Double,
    ) : AnyValue

    public data class BooleanValue(
        public val value: Boolean,
    ) : AnyValue

    public data class Bytes(
        public val value: List<Byte>,
    ) : AnyValue
}

public data class Key(
    public val name: String,
)

public data class TraceContext(
    public val traceId: String,
    public val spanId: String,
    public val traceFlags: String? = null,
)

public interface LogRecord {
    public fun setBody(value: AnyValue)

    public fun addAttribute(
        key: Key,
        value: AnyValue,
    )

    public fun setTarget(target: String)

    public fun setEventName(name: String)

    public fun setSeverityNumber(severity: Severity)

    public fun setSeverityText(text: String)

    public fun setTraceContext(
        traceId: String,
        spanId: String,
        traceFlags: String? = null,
    )
}

public interface Logger {
    public fun createLogRecord(): LogRecord

    public fun eventEnabled(
        severity: Severity,
        target: String,
        name: String?,
    ): Boolean = true

    public fun emit(record: LogRecord)
}

public interface LoggerProvider {
    public fun logger(scopeName: String): Logger
}

public data class TracingMetadata(
    public val level: TracingLevel,
    public val target: String,
    public val name: String,
)

@HiddenFromObjC
public class TracingEvent(
    public val metadata: TracingMetadata,
    private val recordFields: EventVisitor.() -> Unit,
) {
    internal fun record(visitor: EventVisitor) {
        visitor.recordFields()
    }
}

public class EventVisitor internal constructor(
    private val logRecord: LogRecord,
) {
    public fun recordDebug(
        fieldName: String,
        value: Any?,
    ) {
        if (fieldName == "message") {
            logRecord.setBody(AnyValue.Text(value.toString()))
        } else {
            logRecord.addAttribute(Key(fieldName), AnyValue.Text(value.toString()))
        }
    }

    @HiddenFromObjC
    public fun recordError(value: Throwable) {
        logRecord.addAttribute(Key("exception.message"), AnyValue.Text(value.message ?: value.toString()))
    }

    public fun recordBytes(
        fieldName: String,
        value: ByteArray,
    ) {
        logRecord.addAttribute(Key(fieldName), AnyValue.Bytes(value.asList()))
    }

    public fun recordString(
        fieldName: String,
        value: String,
    ) {
        if (fieldName == "message") {
            logRecord.setBody(AnyValue.Text(value))
        } else {
            logRecord.addAttribute(Key(fieldName), AnyValue.Text(value))
        }
    }

    public fun recordBoolean(
        fieldName: String,
        value: Boolean,
    ) {
        logRecord.addAttribute(Key(fieldName), AnyValue.BooleanValue(value))
    }

    public fun recordDouble(
        fieldName: String,
        value: Double,
    ) {
        logRecord.addAttribute(Key(fieldName), AnyValue.FloatingPoint(value))
    }

    public fun recordLong(
        fieldName: String,
        value: Long,
    ) {
        logRecord.addAttribute(Key(fieldName), AnyValue.Integer(value))
    }

    public fun recordUnsignedLong(
        fieldName: String,
        value: ULong,
    ) {
        if (value <= Long.MAX_VALUE.toULong()) {
            logRecord.addAttribute(Key(fieldName), AnyValue.Integer(value.toLong()))
        } else {
            logRecord.addAttribute(Key(fieldName), AnyValue.Text(value.toString()))
        }
    }
}

public class OpenTelemetryTracingBridge(
    provider: LoggerProvider,
) {
    private val logger: Logger = provider.logger("")

    @HiddenFromObjC
    public fun onEvent(event: TracingEvent) {
        val metadata = event.metadata
        val severity = severityOfLevel(metadata.level)
        if (!logger.eventEnabled(severity, metadata.target, metadata.name)) {
            return
        }

        val logRecord = logger.createLogRecord()
        logRecord.setTarget(metadata.target)
        logRecord.setEventName(metadata.name)
        logRecord.setSeverityNumber(severity)
        logRecord.setSeverityText(metadata.level.name)
        event.record(EventVisitor(logRecord))
        logger.emit(logRecord)
    }

    public companion object {
        public fun severityOfLevel(level: TracingLevel): Severity =
            when (level) {
                TracingLevel.TRACE -> Severity.Trace
                TracingLevel.DEBUG -> Severity.Debug
                TracingLevel.INFO -> Severity.Info
                TracingLevel.WARN -> Severity.Warn
                TracingLevel.ERROR -> Severity.Error
            }
    }
}
