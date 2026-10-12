// Sema Voice iOS containing app — Phase 2 skeleton (not yet compiled). See docs/IOS_PLAN.md.
import SwiftUI
import AVFoundation
import SemaVoiceCore

@main
struct SemaVoiceApp: App {
    @StateObject private var dictation = DictationController()

    var body: some Scene {
        WindowGroup {
            DictationView()
                .environmentObject(dictation)
                .onOpenURL { url in
                    // semavoice://dictate is opened by the keyboard extension.
                    if url.host == "dictate" { dictation.startFromKeyboard() }
                }
        }
    }
}

/// Records with AVAudioEngine at 16 kHz mono and hands PCM to a SpeechRecognitionEngine.
/// Recording happens only in the app process, never in the keyboard extension.
@MainActor
final class DictationController: ObservableObject {
    @Published var isRecording = false
    @Published var transcript = ""
    @Published var error: AppError?
    @Published var cameFromKeyboard = false

    private let engine = AVAudioEngine()
    private var buffer: [Float] = []
    var speech: SpeechRecognitionEngine?   // whisper.cpp implementation injected in Phase 2

    func startFromKeyboard() { cameFromKeyboard = true; Task { await start() } }

    func start() async {
        let granted = await AVAudioApplication.requestRecordPermission()
        guard granted else { error = .microphonePermissionDenied; return }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.record, mode: .measurement, options: [.duckOthers])
            try session.setActive(true)
            let input = engine.inputNode
            let format = input.outputFormat(forBus: 0)
            let target = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16_000, channels: 1, interleaved: false)!
            let converter = AVAudioConverter(from: format, to: target)!
            buffer.removeAll()
            input.installTap(onBus: 0, bufferSize: 4096, format: format) { [weak self] pcm, _ in
                guard let self else { return }
                let frames = AVAudioFrameCount(Double(pcm.frameLength) * 16_000 / format.sampleRate) + 16
                guard let out = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: frames) else { return }
                var err: NSError?
                converter.convert(to: out, error: &err) { _, status in status.pointee = .haveData; return pcm }
                if let ch = out.floatChannelData { self.buffer.append(contentsOf: UnsafeBufferPointer(start: ch[0], count: Int(out.frameLength))) }
            }
            engine.prepare()
            try engine.start()
            isRecording = true
        } catch {
            self.error = .microphoneBusy
        }
    }

    func stop() async {
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        isRecording = false
        let audio = PcmAudio(samples: buffer, sampleRate: 16_000)
        guard audio.durationMs > 200 else { error = .emptyRecording; return }
        guard let speech else { error = .modelNotInstalled("speech"); return }
        switch await speech.transcribe(audio, options: TranscribeOptions()) {
        case .success(let t):
            transcript = t.text
            try? DictationHandoff(text: t.text, translation: nil, language: t.language).write()
        case .failure(let e):
            error = e
        }
    }
}

struct DictationView: View {
    @EnvironmentObject var c: DictationController

    var body: some View {
        VStack(spacing: 24) {
            Text("Sema Voice").font(.largeTitle.bold()).foregroundStyle(Color(red: 0.31, green: 0.27, blue: 0.90))
            Text(c.transcript.isEmpty ? "Tap the microphone and speak." : c.transcript).font(.title3).padding()
            Button {
                Task { if c.isRecording { await c.stop() } else { await c.start() } }
            } label: {
                Image(systemName: c.isRecording ? "stop.fill" : "mic.fill")
                    .font(.system(size: 36)).foregroundStyle(.white)
                    .frame(width: 88, height: 88)
                    .background(c.isRecording ? Color(red: 0.94, green: 0.27, blue: 0.27) : Color(red: 0.31, green: 0.27, blue: 0.90))
                    .clipShape(Circle())
            }
            .accessibilityLabel(c.isRecording ? "Stop recording" : "Start dictation")
            if c.cameFromKeyboard && !c.transcript.isEmpty {
                Text("Go back to your app and tap “Insert last dictation” on the Sema keyboard.").font(.footnote).foregroundStyle(.secondary)
            }
            if let e = c.error { Text(String(describing: e)).foregroundStyle(.red).font(.footnote) }
        }
        .padding()
    }
}
