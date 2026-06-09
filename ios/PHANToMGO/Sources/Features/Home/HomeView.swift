import SwiftUI
import UIKit

struct HomeView: View {
    @EnvironmentObject private var container: AppContainer
    @State private var routeInput = ""

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    heroCard
                    quickSendCard
                    pairingCard
                    latestPayloadCard
                }
                .padding(20)
            }
            .background(
                LinearGradient(
                    colors: [Color(red: 0.05, green: 0.07, blue: 0.13), Color(red: 0.09, green: 0.12, blue: 0.20)],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
                .ignoresSafeArea()
            )
            .navigationTitle("PHANToM GO")
            .sheet(isPresented: $container.scannerPresented) {
                PairingSheetView()
                    .environmentObject(container)
            }
            .alert("Notice", isPresented: Binding(
                get: { container.toastMessage != nil },
                set: { if !$0 { container.toastMessage = nil } }
            )) {
                Button("OK", role: .cancel) {
                    container.toastMessage = nil
                }
            } message: {
                Text(container.toastMessage ?? "")
            }
            .onAppear {
                routeInput = container.routeInput
            }
        }
    }

    private var heroCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Main Device")
                .font(.headline)
                .foregroundStyle(.cyan)
            Text(container.pairedDisplaySummary)
                .font(.title2.bold())
                .foregroundStyle(.white)
            Text(container.authSummary)
                .font(.footnote)
                .foregroundStyle(.white.opacity(0.72))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(20)
        .background(cardBackground)
    }

    private var quickSendCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Send route to Display")
                .font(.headline)
                .foregroundStyle(.white)

            TextEditor(text: Binding(
                get: { routeInput },
                set: {
                    routeInput = $0
                    container.routeInput = $0
                }
            ))
            .frame(minHeight: 140)
            .padding(8)
            .background(Color.white.opacity(0.08))
            .clipShape(RoundedRectangle(cornerRadius: 16))
            .foregroundStyle(.white)

            HStack(spacing: 12) {
                Button("Paste") {
                    routeInput = UIPasteboard.general.string ?? routeInput
                    container.routeInput = routeInput
                }
                .buttonStyle(.bordered)

                Button {
                    Task {
                        container.routeInput = routeInput
                        await container.sendCurrentRoute()
                    }
                } label: {
                    Label("Send Route", systemImage: "paperplane.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(.cyan)
            }
        }
        .padding(20)
        .background(cardBackground)
    }

    private var pairingCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Pairing")
                .font(.headline)
                .foregroundStyle(.white)

            Text("Scan a Display QR code or paste a phantomgo:// payload to pair this iPhone with a Display device.")
                .font(.footnote)
                .foregroundStyle(.white.opacity(0.72))

            HStack(spacing: 12) {
                Button {
                    container.scannerPresented = true
                } label: {
                    Label("Scan QR", systemImage: "qrcode.viewfinder")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(.purple)

                Button("Clear Pairing") {
                    Task { await container.clearMainPairing() }
                }
                .buttonStyle(.bordered)
            }
        }
        .padding(20)
        .background(cardBackground)
    }

    @ViewBuilder
    private var latestPayloadCard: some View {
        if let latestPayload = container.latestPayload {
            VStack(alignment: .leading, spacing: 10) {
                Text("Latest Parsed Route")
                    .font(.headline)
                    .foregroundStyle(.white)
                Text(latestPayload.originalText)
                    .font(.footnote)
                    .foregroundStyle(.white.opacity(0.8))
                Text("Parser: \(latestPayload.parserSource.rawValue) • Confidence: \(latestPayload.confidence.rawValue)")
                    .font(.caption)
                    .foregroundStyle(.cyan)
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

