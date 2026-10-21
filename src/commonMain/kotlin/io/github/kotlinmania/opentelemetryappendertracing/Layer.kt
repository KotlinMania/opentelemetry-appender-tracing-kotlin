// port-lint: source layer.rs
package io.github.kotlinmania.opentelemetryappendertracing

import io.github.kotlinmania.tracing.Level

public enum class Severity {
    Trace,
    Debug,
    Info,
    Warn,
    Error,
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

public interface RecordAttributeLookup {
    public fun containsAttribute(key: Key, value: AnyValue): Boolean
}

public fun attributesContains(logRecord: LogRecord, key: Key, value: AnyValue): Boolean {
    val lookup = logRecord as? RecordAttributeLookup ?: return false
    return lookup.containsAttribute(key, value)
}

public fun createTracingSubscriber(loggerProvider: LoggerProvider): OpenTelemetryTracingBridge {
    return OpenTelemetryTracingBridge.new(loggerProvider)
}

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
    public val traceId: String,
    public val spanId: String,
    public val traceFlags: String? = null,
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
            logRecord.addAttribute(Key("code.namespace"), AnyValue.Text(modulePath))
        }
        if (file != null) {
            logRecord.addAttribute(Key("code.filepath"), AnyValue.Text(file))
            logRecord.addAttribute(Key("code.filename"), AnyValue.Text(getFilename(file)))
        }
        if (line != null) {
            logRecord.addAttribute(Key("code.lineno"), AnyValue.Integer(line))
        }
    }

    public fun recordDebug(
        fieldName: String,
        value: Any?,
    ) {
        if (isDuplicatedMetadata(fieldName)) return
        if (fieldName == "message") {
            logRecord.setBody(AnyValue.Text(value.toString()))
        } else {
            logRecord.addAttribute(Key(fieldName), AnyValue.Text(value.toString()))
        }
    }

    public fun recordError(value: Throwable) {
        logRecord.addAttribute(Key("exception.message"), AnyValue.Text(value.message ?: value.toString()))
    }

    public fun recordBytes(
        fieldName: String,
        value: ByteArray,
    ) {
        logRecord.addAttribute(Key(fieldName), AnyValue.Bytes(value.asList()))
    }

    public fun recordStr(
        fieldName: String,
        value: String,
    ) {
        if (isDuplicatedMetadata(fieldName)) return
        if (fieldName == "message") {
            logRecord.setBody(AnyValue.Text(value))
        } else {
            logRecord.addAttribute(Key(fieldName), AnyValue.Text(value))
        }
    }

    public fun recordBool(
        fieldName: String,
        value: Boolean,
    ) {
        logRecord.addAttribute(Key(fieldName), AnyValue.BooleanValue(value))
    }

    public fun recordF64(
        fieldName: String,
        value: Double,
    ) {
        logRecord.addAttribute(Key(fieldName), AnyValue.FloatingPoint(value))
    }

    public fun recordI64(
        fieldName: String,
        value: Long,
    ) {
        if (isDuplicatedMetadata(fieldName)) return
        logRecord.addAttribute(Key(fieldName), AnyValue.Integer(value))
    }

    public fun recordU64(
        fieldName: String,
        value: ULong,
    ) {
        if (isDuplicatedMetadata(fieldName)) return
        if (value <= Long.MAX_VALUE.toULong()) {
            logRecord.addAttribute(Key(fieldName), AnyValue.Integer(value.toLong()))
        } else {
            logRecord.addAttribute(Key(fieldName), AnyValue.Text(value.toString()))
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
            logRecord.addAttribute(Key(fieldName), AnyValue.Integer(signed))
        } else {
            logRecord.addAttribute(Key(fieldName), AnyValue.Text(value))
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
            logRecord.addAttribute(Key(fieldName), AnyValue.Integer(unsigned.toLong()))
        } else {
            logRecord.addAttribute(Key(fieldName), AnyValue.Text(value))
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
