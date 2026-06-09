import SwiftUI

struct PairingSheetView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var container: AppContainer
    @State private var scannerMode = true

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                Picker("Input", selection: $scannerMode) {
                    Text("Scan QR").tag(true)
                    Text("Paste Payload").tag(false)
                }
                .pickerStyle(.segmented)

                if scannerMode {
                    QRScannerView { raw in
                        Task {
                            await container.handleScannedPairPayload(raw)
                            dismiss()
                        }
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                } else {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Paste phantomgo:// payload")
                            .font(.headline)
                        TextEditor(text: $container.manualPairPayload)
                            .frame(minHeight: 180)
                            .padding(8)
                            .background(Color.secondary.opacity(0.08))
                            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))

                        Button {
                            Task {
                                await container.claimPairingFromManualPayload()
                                dismiss()
                            }
                        } label: {
                            Label("Pair Now", systemImage: "link")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                    }
                }
            }
            .padding(20)
            .navigationTitle("Pair Display")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Close") {
                        dismiss()
                    }
                }
            }
        }
    }
}

