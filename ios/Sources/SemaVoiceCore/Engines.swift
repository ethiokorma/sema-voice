// Sema Voice iOS — shared engine contracts, mirroring the Kotlin interfaces in core/*.
// Phase 2 skeleton; not yet compiled. See docs/IOS_PLAN.md.
import Foundation

public enum Language: String, CaseIterable, Codable {
    case auto, amharic = "am", english = "en", afaanOromoo = "om", tigrinya = "ti", somali = "so", arabic = "ar"

    /// Whisper language code, nil when Whisper does not cover the language (Tigrinya, Oromo).
    public var whisperCode: String? {
        switch self {
        case .amharic: return "am"
        case .english: return "en"
        case .somali: return "so"
        case .arabic: return "ar"
        default: return nil
        }
    }
}

public enum AppError: Error, Equatable {
    case microphonePermissionDenied
    case noMicrophone
    case microphoneBusy
    case emptyRecording
    case unsupportedLanguage(Language)
    case modelNotInstalled(String)
    case modelCorrupt(String)
    case insufficientStorage(needed: Int64, available: Int64)
    case translationUnavailable(from: Language, to: Language)
    case checksumMismatch(String)
    case downloadInterrupted(String)
    case cancelled
    case inferenceFailed(String)
}

/// 16 kHz mono float PCM in [-1, 1], identical to the Android `PcmAudio`.
public struct PcmAudio {
    public static let whisperSampleRate = 16_000
    public var samples: [Float]
    public var sampleRate: Int
    public init(samples: [Float], sampleRate: Int) { self.samples = samples; self.sampleRate = sampleRate }
    public var durationMs: Int { sampleRate == 0 ? 0 : samples.count * 1000 / sampleRate }
}

public struct TranscriptSegment: Codable, Equatable { public let startMs: Int; public let endMs: Int; public let text: String }

public struct Transcription: Equatable {
    public let text: String
    public let segments: [TranscriptSegment]
    public let language: Language
    public let inferenceMs: Int
    public let audioMs: Int
    public let modelId: String
    public var realTimeFactor: Double { audioMs == 0 ? 0 : Double(inferenceMs) / Double(audioMs) }
}

public struct TranscribeOptions {
    public var language: Language = .auto
    public var translateToEnglish = false
    public var threads = 0
    public var initialPrompt: String? = nil
    public init() {}
}

public enum SpeechModelStatus: Equatable { case notLoaded, loading(String), ready(modelId: String, multilingual: Bool), error(String, String) }

/// Mirrors `SpeechRecognitionEngine` (Kotlin). Implemented in the containing app with
/// whisper.cpp's Swift package; never inside the keyboard extension (no microphone, no memory).
public protocol SpeechRecognitionEngine: AnyObject {
    var status: SpeechModelStatus { get }
    func initialize(modelId: String, modelPath: URL) async -> Result<Void, AppError>
    func transcribe(_ audio: PcmAudio, options: TranscribeOptions) async -> Result<Transcription, AppError>
    func cancel()
    func release() async
    func supportedLanguages() -> [Language]
}

public struct TranslationResult: Equatable {
    public let original: String
    public let translated: String
    public let from: Language
    public let to: Language
    public let inferenceMs: Int
    public let modelId: String
    public let processedLocally: Bool
}

public protocol TranslationEngine: AnyObject {
    func initialize(modelId: String, modelDir: URL) async -> Result<Void, AppError>
    func supports(from: Language, to: Language) -> Bool
    func translate(_ text: String, from: Language, to: Language) async -> Result<TranslationResult, AppError>
    func cancel()
    func release() async
}

public enum WritingAction: CaseIterable { case correctSpelling, improvePunctuation, makeProfessional, makeFriendly, makeShorter, makeLonger, simplify, informalToFormal, removeRepetition, cleanDisfluencies, preserveMeaning }
public enum Availability { case ruleBased, localModel, unavailable }

public protocol WritingAssistantEngine {
    func availability(_ action: WritingAction, language: Language) -> Availability
    func apply(_ action: WritingAction, text: String, language: Language) async -> Result<String, AppError>
}

/// Backed by AVSpeechSynthesizer in the app (Apple ships a downloadable Amharic voice on iOS 16+).
public protocol TtsEngine: AnyObject {
    var isLocal: Bool { get }
    func availableLanguages() -> [Language]
    func speak(_ text: String, language: Language) async -> Result<Void, AppError>
    func stop()
}

/// App Group hand-off between the containing app (which records + transcribes) and the keyboard
/// extension (which can only insert text). This is the legally compliant replacement for a
/// microphone inside the keyboard.
public struct DictationHandoff: Codable, Equatable {
    public static let appGroup = "group.com.semavoice.app"
    public static let fileName = "last-dictation.json"
    public let text: String
    public let translation: String?
    public let language: Language
    public let createdAt: Date

    public init(text: String, translation: String?, language: Language, createdAt: Date = Date()) {
        self.text = text; self.translation = translation; self.language = language; self.createdAt = createdAt
    }

    public static var url: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup)?.appendingPathComponent(fileName)
    }

    public func write() throws {
        guard let url = Self.url else { throw AppError.inferenceFailed("no app group container") }
        let data = try JSONEncoder().encode(self)
        try data.write(to: url, options: [.atomic, .completeFileProtection])
    }

    public static func read() -> DictationHandoff? {
        guard let url = url, let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(DictationHandoff.self, from: data)
    }

    public static func clear() { if let url = url { try? FileManager.default.removeItem(at: url) } }
}
