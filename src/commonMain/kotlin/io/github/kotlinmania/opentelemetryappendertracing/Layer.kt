// port-lint: source layer.rs
package io.github.kotlinmania.opentelemetryappendertracing

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

public interface RecordAttributeLookup {
    public fun containsAttribute(key: Key, value: AnyValue): Boolean
}

public fun attributesContains(logRecord: LogRecord, key: Key, value: AnyValue): Boolean {
    val lookup = logRecord as? RecordAttributeLookup ?: return false
    return lookup.containsAttribute(key, value)
}

public fun createTracingSubscriber(loggerProvider: LoggerProvider): OpenTelemetryTracingBridge =
    OpenTelemetryTracingBridge.new(loggerProvider)

public interface LogProcessor {
    public fun emit(record: LogRecord)

    public fun eventEnabled(
        level: Severity,
        target: String,
        name: String?,
    ): Boolean = true

    public fun forceFlush(): Boolean = true
}

public data class TracingMetadata(
    public val level: Level,
    public val target: String,
    public val name: String,
)

public data class TracingSpanContext(
    public val traceId: TraceId,
    public val spanId: SpanId,
    public val traceFlags: TraceFlags? = null,
)

public class TracingEvent(
    public val metadata: TracingMetadata,
    public val file: String? = null,
    public val line: Long? = null,
    public val modulePath: String? = null,
    public val spanContext: TracingSpanContext? = null,
    private val recordFields: EventVisitor.() -> Unit,
) {
    internal fun record(visitor: EventVisitor) {
        visitor.recordFields()
    }
}

/**
 * Logs from the log crate have duplicated attributes that we removed here.
 */
public fun isDuplicatedMetadata(field: String): Boolean {
    if (field.startsWith("log.")) {
        val remainder = field.substring(4)
        return remainder == "file" || remainder == "line" || remainder == "module_path" || remainder == "target"
    }
    return false
}

public fun getFilename(filepath: String): String {
    val slashIdx = filepath.lastIndexOf('/')
    if (slashIdx >= 0) {
        return filepath.substring(slashIdx + 1)
    }
    val backslashIdx = filepath.lastIndexOf('\\')
    if (backslashIdx >= 0) {
        return filepath.substring(backslashIdx + 1)
    }
    return filepath
}

/**
 * Visitor to record the fields from the event record.
 */
public class EventVisitor internal constructor(
    private val logRecord: LogRecord,
) {
    public fun visitExperimentalMetadata(
        modulePath: String? = null,
        file: String? = null,
        line: Long? = null,
    ) {
        if (modulePath != null) {
            logRecord.addAttribute(Key.new("code.namespace"), AnyValue.of(modulePath))
        }
        if (file != null) {
            logRecord.addAttribute(Key.new("code.filepath"), AnyValue.of(file))
            logRecord.addAttribute(Key.new("code.filename"), AnyValue.of(getFilename(file)))
        }
        if (line != null) {
            logRecord.addAttribute(Key.new("code.lineno"), AnyValue.of(line))
        }
    }

    public fun recordDebug(
        fieldName: String,
        value: Any?,
    ) {
        if (isDuplicatedMetadata(fieldName)) return
        if (fieldName == "message") {
            logRecord.setBody(AnyValue.of(value.toString()))
        } else {
            logRecord.addAttribute(Key.new(fieldName), AnyValue.of(value.toString()))
        }
    }

    public fun recordError(value: Throwable) {
        logRecord.addAttribute(Key.new("exception.message"), AnyValue.of(value.message ?: value.toString()))
    }

    public fun recordBytes(
        fieldName: String,
        value: ByteArray,
    ) {
        logRecord.addAttribute(Key.new(fieldName), AnyValue.of(value))
    }

    public fun recordStr(
        fieldName: String,
        value: String,
    ) {
        if (isDuplicatedMetadata(fieldName)) return
        if (fieldName == "message") {
            logRecord.setBody(AnyValue.of(value))
        } else {
            logRecord.addAttribute(Key.new(fieldName), AnyValue.of(value))
        }
    }

    public fun recordBool(
        fieldName: String,
        value: Boolean,
    ) {
        logRecord.addAttribute(Key.new(fieldName), AnyValue.of(value))
    }

    public fun recordF64(
        fieldName: String,
        value: Double,
    ) {
        logRecord.addAttribute(Key.new(fieldName), AnyValue.of(value))
    }

    public fun recordI64(
        fieldName: String,
        value: Long,
    ) {
        if (isDuplicatedMetadata(fieldName)) return
        logRecord.addAttribute(Key.new(fieldName), AnyValue.of(value))
    }

    public fun recordU64(
        fieldName: String,
        value: ULong,
    ) {
        if (isDuplicatedMetadata(fieldName)) return
        if (value <= Long.MAX_VALUE.toULong()) {
            logRecord.addAttribute(Key.new(fieldName), AnyValue.of(value.toLong()))
        } else {
            logRecord.addAttribute(Key.new(fieldName), AnyValue.of(value.toString()))
        }
    }

    public fun recordI128(
        fieldName: String,
        value: Long,
    ) {
        recordI64(fieldName, value)
    }

    public fun recordI128(
        fieldName: String,
        value: String,
    ) {
        if (isDuplicatedMetadata(fieldName)) return
        val signed = value.toLongOrNull()
        if (signed != null) {
            logRecord.addAttribute(Key.new(fieldName), AnyValue.of(signed))
        } else {
            logRecord.addAttribute(Key.new(fieldName), AnyValue.of(value))
        }
    }

    public fun recordU128(
        fieldName: String,
        value: ULong,
    ) {
        recordU64(fieldName, value)
    }

    public fun recordU128(
        fieldName: String,
        value: String,
    ) {
        if (isDuplicatedMetadata(fieldName)) return
        val unsigned = value.toULongOrNull()
        if (unsigned != null && unsigned <= Long.MAX_VALUE.toULong()) {
            logRecord.addAttribute(Key.new(fieldName), AnyValue.of(unsigned.toLong()))
        } else {
            logRecord.addAttribute(Key.new(fieldName), AnyValue.of(value))
        }
    }
}

public class OpenTelemetryTracingBridge(
    provider: LoggerProvider,
) {
    private val logger: Logger = provider.logger("")

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
        val visitor = EventVisitor(logRecord)
        val eventFile = event.file
        val eventLine = event.line
        val eventModulePath = event.modulePath
        if (eventFile != null || eventLine != null || eventModulePath != null) {
            visitor.visitExperimentalMetadata(eventModulePath, eventFile, eventLine)
        }
        event.record(visitor)

        val spanContext = event.spanContext
        if (spanContext != null) {
            logRecord.setTraceContext(
                spanContext.traceId,
                spanContext.spanId,
                spanContext.traceFlags,
            )
        }

        logger.emit(logRecord)
    }

    public companion object {
        public fun new(provider: LoggerProvider): OpenTelemetryTracingBridge = OpenTelemetryTracingBridge(provider)

        public fun severityOfLevel(level: Level): Severity =
            when (level) {
                Level.TRACE -> Severity.Trace
                Level.DEBUG -> Severity.Debug
                Level.INFO -> Severity.Info
                Level.WARN -> Severity.Warn
                Level.ERROR -> Severity.Error
            }
    }
}
