// Sema Voice iOS keyboard extension — Phase 2 skeleton (not yet compiled).
//
// IMPORTANT: iOS keyboard extensions cannot use the microphone. This keyboard therefore has
// no recording code. "Dictate" opens the containing app via a URL scheme; the app records,
// transcribes with whisper.cpp, and writes the result to the shared App Group container.
// "Insert last dictation" reads that file and inserts it with textDocumentProxy.
import UIKit
import SemaVoiceCore

final class KeyboardViewController: UIInputViewController {
    private let stack = UIStackView()

    override func viewDidLoad() {
        super.viewDidLoad()
        stack.axis = .vertical
        stack.spacing = 8
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 12),
            stack.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -12),
            stack.topAnchor.constraint(equalTo: view.topAnchor, constant: 8),
            stack.bottomAnchor.constraint(equalTo: view.bottomAnchor, constant: -8),
        ])
        stack.addArrangedSubview(button("Dictate in Sema Voice", #selector(openDictation)))
        stack.addArrangedSubview(button("Insert last dictation", #selector(insertLast)))
        stack.addArrangedSubview(button("Insert last translation", #selector(insertTranslation)))
        stack.addArrangedSubview(button("Next keyboard", #selector(handleInputModeList(from:with:))))
        // TODO(phase 2): QWERTY + Ethiopic rows shared with the Android layout definition.
    }

    private func button(_ title: String, _ action: Selector) -> UIButton {
        let b = UIButton(type: .system)
        b.setTitle(title, for: .normal)
        b.titleLabel?.font = .preferredFont(forTextStyle: .body)
        b.backgroundColor = UIColor(red: 0.31, green: 0.27, blue: 0.90, alpha: 1) // #4F46E5
        b.setTitleColor(.white, for: .normal)
        b.layer.cornerRadius = 10
        b.heightAnchor.constraint(equalToConstant: 44).isActive = true
        b.addTarget(self, action: action, for: .touchUpInside)
        return b
    }

    @objc private func openDictation() {
        // Keyboard extensions cannot call UIApplication.shared.open; walk the responder chain.
        guard let url = URL(string: "semavoice://dictate") else { return }
        var responder: UIResponder? = self
        while let r = responder {
            if let app = r as? UIApplication { app.perform(#selector(UIApplication.open(_:options:completionHandler:)), with: url); return }
            responder = r.next
        }
        extensionContext?.open(url, completionHandler: nil)
    }

    @objc private func insertLast() {
        guard let h = DictationHandoff.read() else { return }
        // Secure fields: never insert dictated text into password fields.
        if textDocumentProxy.isSecureTextEntry { return }
        textDocumentProxy.insertText(h.text)
    }

    @objc private func insertTranslation() {
        guard let h = DictationHandoff.read(), let t = h.translation, !textDocumentProxy.isSecureTextEntry else { return }
        textDocumentProxy.insertText(t)
    }
}
