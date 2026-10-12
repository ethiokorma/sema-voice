// Mirrors core/models/ModelCatalog.kt. Keep URLs, sizes and SHA-256 in sync with the Kotlin file
// (the Kotlin side is the source of truth; checksums verified 2026-10-12).
import Foundation

public enum ModelKind: String, Codable { case speech, translation }
public enum PerformanceTier: Int, Codable { case low, mid, high }

public struct ModelFile: Codable, Equatable {
    public let name: String
    public let url: URL
    public let sizeBytes: Int64
    public let sha256: String
}

public struct ModelInfo: Codable, Equatable {
    public let id: String
    public let kind: ModelKind
    public let displayName: String
    public let files: [ModelFile]
    public let languages: [Language]
    public let requiredRamMb: Int
    public let minTier: PerformanceTier
    public let license: String
    public var totalBytes: Int64 { files.reduce(0) { $0 + $1.sizeBytes } }
    public var isPublishable: Bool { files.allSatisfy { $0.sha256.count == 64 && $0.sizeBytes > 0 } }
}

public enum ModelCatalog {
    static let hfWhisper = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

    static func whisper(_ id: String, _ name: String, _ file: String, _ size: Int64, _ sha: String, ram: Int, tier: PerformanceTier) -> ModelInfo {
        ModelInfo(id: id, kind: .speech, displayName: name,
                  files: [ModelFile(name: file, url: URL(string: "\(hfWhisper)/\(file)")!, sizeBytes: size, sha256: sha)],
                  languages: [.english, .arabic, .somali], requiredRamMb: ram, minTier: tier,
                  license: "MIT (whisper.cpp ggml conversion) / MIT (OpenAI Whisper weights)")
    }

    public static let whisperTinyQ5 = whisper("whisper-tiny-q5_1", "Whisper Tiny (quantized)", "ggml-tiny-q5_1.bin", 32_152_673, "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7", ram: 180, tier: .low)
    public static let whisperBaseQ5 = whisper("whisper-base-q5_1", "Whisper Base (quantized)", "ggml-base-q5_1.bin", 59_707_625, "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898", ram: 300, tier: .low)
    public static let whisperSmallQ5 = whisper("whisper-small-q5_1", "Whisper Small (quantized)", "ggml-small-q5_1.bin", 190_085_487, "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb", ram: 700, tier: .mid)

    public static let builtIn: [ModelInfo] = [whisperTinyQ5, whisperBaseQ5, whisperSmallQ5]
}
