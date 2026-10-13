import SwiftUI
import UIKit
import UniformTypeIdentifiers

final class ShareViewController: UIViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        let model = ShareRelayModel(context: extensionContext)
        let host = UIHostingController(rootView: ShareRelayView(model: model))
        addChild(host)
        view.addSubview(host.view)
        host.view.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            host.view.topAnchor.constraint(equalTo: view.topAnchor),
            host.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            host.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            host.view.trailingAnchor.constraint(equalTo: view.trailingAnchor)
        ])
        host.didMove(toParent: self)
        Task { await model.load() }
    }
}

@MainActor
final class ShareRelayModel: ObservableObject {
    @Published var route = ""
    @Published var message = "Loading shared route"
    @Published var busy = false
    @Published var sent = false
    private let context: NSExtensionContext?
    let session = ShareSession.current
    private var didLoad = false
    private var payload: RoutePayload?
    init(context: NSExtensionContext?) { self.context = context }

    func load() async {
        guard !didLoad else { return }
        didLoad = true
        for item in context?.inputItems as? [NSExtensionItem] ?? [] {
            for provider in item.attachments ?? [] {
                for type in [UTType.url.identifier, UTType.plainText.identifier] where provider.hasItemConformingToTypeIdentifier(type) {
                    let value: NSSecureCoding? = await withCheckedContinuation { continuation in
                        provider.loadItem(forTypeIdentifier: type, options: nil) { value, _ in
                            continuation.resume(returning: value)
                        }
                    }
                    let text = (value as? URL)?.absoluteString ?? (value as? String) ?? ""
                    if RouteParser.parse(text, navMode: session?.navMode ?? .driving).resolvedURL != nil {
                        route = text
                        payload = RouteParser.parse(text, navMode: session?.navMode ?? .driving)
                        message = session == nil
                            ? "Shared session unavailable. Open PHANToM GO to pair first. If already paired, signing may not allow the shared Keychain. Copy Route and tap Paste on Home instead."
                            : "Send this destination to your paired Display."
                        if session?.autoSend ?? false { await send() }
                        return
                    }
                }
            }
        }
        message = "No supported map link found. Share a Google Maps destination or copy its link."
    }
    func send() async {
        guard !busy, !sent, let session, let payload else { return }
        busy = true
        defer { busy = false }
        do {
            _ = try await AuthSession.shared.current(allowCreate: false)
            let result = try await FirebaseService.shared.sendRouteCommand(
                to: session.displayDeviceId, from: session.mainDeviceId, mainName: session.deviceName,
                payload: payload, openBehavior: session.openBehavior, requestId: payload.id,
                maxAttempts: 1, acknowledgementTimeout: 2
            )
            sent = true
            message = result == .acknowledged ? "Display acknowledged the route."
                : "Saved to cloud. Display has not received it yet. Open PHANToM GO on Display to receive the latest route."
        } catch { message = error.localizedDescription }
    }
    func copy() { UIPasteboard.general.string = route; message = "Copied. Paste the route in PHANToM GO." }
    func close() { context?.completeRequest(returningItems: nil) }
}

private struct ShareRelayView: View {
    @ObservedObject var model: ShareRelayModel
    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text("PHANToM GO").font(.title.bold())
            Text("Route relay").font(.headline)
            Text(model.route).font(.footnote).lineLimit(4)
            Text(model.message).foregroundStyle(.secondary)
            if model.busy { ProgressView("Sending route") }
            if model.session != nil && !model.sent {
                Button("Send to Display") { Task { await model.send() } }
                    .buttonStyle(.borderedProminent).disabled(model.busy || model.route.isEmpty)
            }
            Button("Copy Route") { model.copy() }.disabled(model.route.isEmpty || model.busy)
            Button(model.sent ? "Done" : "Close") { model.close() }.disabled(model.busy)
            Spacer()
        }.padding(24)
    }
}
