import XCTest
@testable import SemaVoiceCore

final class CatalogTests: XCTestCase {
    func testChecksumsArePresent() {
        for m in ModelCatalog.builtIn {
            XCTAssertTrue(m.isPublishable, m.id)
            for f in m.files { XCTAssertEqual(f.sha256.count, 64) }
        }
    }

    func testTigrinyaAndOromoHaveNoWhisperCode() {
        XCTAssertNil(Language.tigrinya.whisperCode)
        XCTAssertNil(Language.afaanOromoo.whisperCode)
        XCTAssertEqual(Language.amharic.whisperCode, "am")
    }

    func testHandoffRoundTrip() throws {
        let h = DictationHandoff(text: "ሰላም", translation: "hello", language: .amharic)
        let data = try JSONEncoder().encode(h)
        let back = try JSONDecoder().decode(DictationHandoff.self, from: data)
        XCTAssertEqual(h, back)
    }
}
