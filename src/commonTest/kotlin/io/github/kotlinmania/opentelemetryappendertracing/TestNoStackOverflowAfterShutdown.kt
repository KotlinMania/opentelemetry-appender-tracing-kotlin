// port-lint: tests tests/test_no_stack_overflow_after_shutdown.rs
package io.github.kotlinmania.opentelemetryappendertracing

import io.github.kotlinmania.tracing.Level
import kotlin.test.Test
import kotlin.test.assertEquals

class TestNoStackOverflowAfterShutdown {
    /**
     * Reproduces [#3161](https://github.com/open-telemetry/opentelemetry-rust/issues/3161)
     */
    @Test
    fun testLoggingAfterShutdownDoesNotCauseTelemetryInducedTelemetry() {
        val provider = InMemoryLoggerProvider(enabled = false)
        val otelLayer = OpenTelemetryTracingBridge.new(provider)

        // After shutdown, logging events should not cause recursion or crashes.
        otelLayer.onEvent(
            TracingEvent(
                TracingMetadata(
                    level = Level.INFO,
                    target = "test-system",
                    name = "dont-crash",
                ),
            ) {
                recordStr("message", "Don't crash")
            },
        )

        assertEquals(emptyList(), provider.exported)
    }
}
