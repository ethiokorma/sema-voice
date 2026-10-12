// swift-tools-version: 5.9
// Sema Voice iOS — Phase 2 skeleton. NOT yet compiled (authored without Xcode). See docs/IOS_PLAN.md.
import PackageDescription

let package = Package(
    name: "SemaVoiceCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "SemaVoiceCore", targets: ["SemaVoiceCore"]),
    ],
    dependencies: [
        // whisper.cpp ships a Swift package / xcframework (MIT). Pin to the same tag as Android.
        // .package(url: "https://github.com/ggml-org/whisper.cpp", exact: "1.9.5"),
        // ONNX Runtime (MIT) for translation: use the onnxruntime-objc binary package when available.
    ],
    targets: [
        .target(name: "SemaVoiceCore", dependencies: [], path: "Sources/SemaVoiceCore"),
        .testTarget(name: "SemaVoiceCoreTests", dependencies: ["SemaVoiceCore"], path: "Tests/SemaVoiceCoreTests"),
    ]
)
