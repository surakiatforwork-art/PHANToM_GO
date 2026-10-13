import SwiftUI

struct PairingSheetView: View {
    @EnvironmentObject private var container: AppContainer
    @Environment(\.dismiss) private var dismiss
    @State private var scannerStatus = "Point at the QR shown on Display."
    @State private var cameraVisible = true

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    if cameraVisible {
                        QRScannerView(onCodeScanned: { code in
                            cameraVisible = false
                            Task { await container.handleScannedPairPayload(code) }
                        }, onError: { scannerStatus = $0 })
                            .frame(height: 280).clipShape(RoundedRectangle(cornerRadius: 20))
                    }
                    Text(scannerStatus).font(.footnote).foregroundStyle(.secondary)
                    ScreenCard {
                        Text("Paste QR payload").font(.headline)
                        TextField("phantomgo://pair?...", text: $container.manualPairPayload, axis: .vertical)
                            .lineLimit(3...6).textFieldStyle(.roundedBorder)
                            .autocorrectionDisabled().textInputAutocapitalization(.never)
                        PasteButton(payloadType: String.self) { values in
                            if let text = values.first { container.manualPairPayload = text }
                        }
                        Button("Connect") {
                            cameraVisible = false
                            Task { await container.claimPairingFromManualPayload() }
                        }.buttonStyle(PrimaryActionStyle())
                            .disabled(container.isBusy || container.manualPairPayload.isEmpty)
                        if container.isBusy { ProgressView("Connecting") }
                        if let message = container.toastMessage {
                            Text(message).font(.footnote).foregroundStyle(.secondary)
                        }
                        Button("Scan again") { cameraVisible = true }.disabled(container.isBusy)
                    }
                }.padding(18)
            }.background(Theme.background.ignoresSafeArea())
                .navigationTitle("Pair Display")
                .toolbar { Button("Cancel") { dismiss() } }
        }
    }
}

