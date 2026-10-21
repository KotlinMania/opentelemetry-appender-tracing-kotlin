import Testing
import OpentelemetryAppenderTracing

@Suite("OpentelemetryAppenderTracing Export Tests")
struct OpentelemetryAppenderTracingExportTests {
    @Test("Verify getFilename extracts file component from posix and windows paths")
    func testGetFilename() throws {
        #expect(getFilename(filepath: "foo/bar/baz.rs") == "baz.rs")
        #expect(getFilename(filepath: "C:\\path\\to\\file.rs") == "file.rs")
        #expect(getFilename(filepath: "plain.rs") == "plain.rs")
    }

    @Test("Verify isDuplicatedMetadata detects log crate duplicated fields")
    func testIsDuplicatedMetadata() throws {
        #expect(isDuplicatedMetadata(field: "log.file") == true)
        #expect(isDuplicatedMetadata(field: "log.line") == true)
        #expect(isDuplicatedMetadata(field: "log.module_path") == true)
        #expect(isDuplicatedMetadata(field: "log.target") == true)
        #expect(isDuplicatedMetadata(field: "log.custom") == false)
        #expect(isDuplicatedMetadata(field: "other.file") == false)
    }

    @Test("Verify Severity enum representation")
    func testSeverityEnum() throws {
        let trace = Severity.trace
        let debug = Severity.debug
        let info = Severity.info
        let warn = Severity.warn
        let error = Severity.error
        #expect(trace != debug)
        #expect(info != warn)
        #expect(warn != error)
    }
}
