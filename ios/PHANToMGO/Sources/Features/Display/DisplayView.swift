import SwiftUI

struct DisplayView: View {
    @EnvironmentObject private var container: AppContainer

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    displayModeCard

                    if container.isDisplayModeEnabled {
                        if let pairing = container.displayPairing {
                            connectedCard(pairing: pairing)
                        } else {
                            qrCard
                        }
                    }

                    latestRouteCard
                }
                .padding(20)
            }
            .background(
                LinearGradient(
                    colors: [Color(red: 0.04, green: 0.05, blue: 0.10), Color(red: 0.07, green: 0.11, blue: 0.18)],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
                .ignoresSafeArea()
            )
            .navigationTitle("Display")
        }
    }

    private var displayModeCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Display Mode")
                        .font(.headline)
                        .foregroundStyle(.white)
                    Text(container.isDisplayModeEnabled ? "Publishing QR and listening for commands" : "Enable to use this iPhone as Display")
                        .font(.footnote)
                        .foregroundStyle(.white.opacity(0.72))
                }
                Spacer()
                Toggle("", isOn: Binding(
                    get: { container.isDisplayModeEnabled },
                    set: { newValue in
                        Task { await container.setDisplayModeEnabled(newValue) }
                    }
                ))
                .labelsHidden()
            }
        }
        .padding(20)
        .background(cardBackground)
    }

    private var qrCard: some View {
        VStack(spacing: 16) {
            Text("Pair this Display")
                .font(.headline)
                .foregroundStyle(.white)

            if let qrImage = QRCodeRenderer.image(from: container.pairingPayload) {
                qrImage
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 220, height: 220)
                    .padding(12)
                    .background(Color.white)
                    .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            }

            Text(container.pairingPayload)
                .font(.caption2.monospaced())
                .foregroundStyle(.cyan)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(20)
        .background(cardBackground)
    }

    private func connectedCard(pairing: PairingRecord) -> some View {
        VStack(spacing: 12) {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 52))
                .foregroundStyle(.green)
            Text("Connected")
                .font(.largeTitle.bold())
                .foregroundStyle(.white)
            Text("Main: \(pairing.mainName ?? pairing.mainDeviceId)")
                .font(.body)
                .foregroundStyle(.white.opacity(0.82))
            Button("Reset Pairing") {
                Task { await container.clearDisplayPairing() }
            }
            .buttonStyle(.bordered)
        }
        .frame(maxWidth: .infinity)
        .padding(24)
        .background(cardBackground)
    }

    @ViewBuilder
    private var latestRouteCard: some View {
        if let command = container.latestCommand {
            VStack(alignment: .leading, spacing: 12) {
                Text("Latest Route")
                    .font(.headline)
                    .foregroundStyle(.white)
                Text(command.originalText)
                    .font(.body)
                    .foregroundStyle(.white.opacity(0.86))
                HStack {
                    Text("Mode: \(command.navigationMode)")
                        .font(.caption)
                        .foregroundStyle(.cyan)
                    Text("Open: \(command.openBehavior)")
                        .font(.caption)
                        .foregroundStyle(.purple)
                }
                Button {
                    container.openLatestRoute()
                } label: {
                    Label("Open in Maps", systemImage: "map.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(.cyan)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(20)
            .background(cardBackground)
        }
    }

    private var cardBackground: some View {
        RoundedRectangle(cornerRadius: 24, style: .continuous)
            .fill(Color.white.opacity(0.08))
            .overlay(
                RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .stroke(Color.cyan.opacity(0.25), lineWidth: 1)
            )
    }
}

