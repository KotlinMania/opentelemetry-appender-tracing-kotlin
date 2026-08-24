import Testing
import OpentelemetryAppenderTracing

@Suite("OpentelemetryAppenderTracing Export Smoke Tests")
struct OpentelemetryAppenderTracingExportTests {
    @Test("Swift module loads cleanly")
    func testSwiftModuleLoads() throws {
        #expect(true)
    }
}
