import SwiftUI

struct DisplayView: View {
    @EnvironmentObject private var container: AppContainer

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    ScreenCard {
                        Toggle("Display Mode", isOn: Binding(
                            get: { container.isDisplayModeEnabled },
                            set: { enabled in Task { await container.setDisplayModeEnabled(enabled) } }
                        ))
                        .font(.headline).disabled(container.isBusy)
                        .accessibilityIdentifier("display-mode")
                        Text(container.isDisplayModeEnabled ? "Receives routes from your paired Main." : "Use this device as your in-car Display.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    if container.isDisplayModeEnabled {
                        if let pairing = container.displayPairing {
                            SpeedometerCard(service: container.location)
                            ScreenCard {
                                HStack {
                                    Label("Connected", systemImage: "checkmark.circle.fill").foregroundStyle(.green)
                                    Spacer()
                                    Text(pairing.mainName ?? pairing.mainDeviceId).font(.caption).lineLimit(1)
                                }
                                HStack {
                                    Text(container.localRelay.ready ? "Cloud + local LAN" : "Cloud relay")
                                        .font(.caption).foregroundStyle(.secondary)
                                    Spacer()
                                    Button("Disconnect") { Task { await container.clearDisplayPairing() } }
                                        .font(.caption).disabled(container.isBusy)
                                }
                            }
                        } else {
                            ScreenCard {
                                VStack(spacing: 16) {
                                    Text("Scan from Main").font(.title2.bold())
                                    if let qr = QRCodeRenderer.image(from: container.pairingPayload) {
                                        qr.resizable().interpolation(.none).scaledToFit()
                                            .frame(maxWidth: 300).padding(14).background(.white, in: RoundedRectangle(cornerRadius: 16))
                                            .accessibilityIdentifier("pairing-qr")
                                    }
                                    Text("Both devices need internet for initial cloud pairing.")
                                        .font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
                                    ShareLink(item: container.pairingPayload) { Label("Share pairing payload", systemImage: "square.and.arrow.up") }
                                    Button("Reset pairing QR") { Task { await container.clearDisplayPairing() } }
                                        .disabled(container.isBusy)
                                }.frame(maxWidth: .infinity)
                            }
                        }
                        if let command = container.latestCommand {
                            ScreenCard {
                                Label("Latest route", systemImage: "map.fill").font(.headline)
                                Text(command.originalText).lineLimit(3).textSelection(.enabled)
                                Button {
                                    Task { await container.openLatestRoute() }
                                } label: { Label("Open in Maps", systemImage: "arrow.up.right.square") }
                                    .buttonStyle(PrimaryActionStyle())
                                Text("Navigation uses this Display's settings. Motorcycle routes depend on Maps support in your region.")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        ScreenCard {
                            Label("Receive the next route", systemImage: "arrow.clockwise").font(.headline)
                            Text("After using Maps, return to Display and check the latest route. This free-account build has no remote push while the app is suspended.")
                                .font(.footnote).foregroundStyle(.secondary)
                            Button {
                                Task { await container.refreshLatestRoute(openAfterRefresh: true) }
                            } label: { Label("Check latest route and open Maps", systemImage: "map") }
                                .buttonStyle(PrimaryActionStyle())
                                .disabled(container.isFetchingLatestRoute || container.security.challengeVisible || container.displayPairing == nil)
                                .accessibilityIdentifier("check-latest-route")
                            if container.isFetchingLatestRoute { ProgressView("Checking current pairing and latest destination") }
                        }
                        ScreenCard {
                            Label("GO Cam", systemImage: "video.fill").font(.headline)
                            Text("Native loop recording and clip export. Recording stops when this app leaves the foreground.")
                                .font(.footnote).foregroundStyle(.secondary)
                            Button("Open Dashcam") { container.dashcamPresented = true }
                                .buttonStyle(PrimaryActionStyle())
                        }
                    }
                }.frame(maxWidth: 900).padding(18).frame(maxWidth: .infinity)
            }
            .background(Theme.background.ignoresSafeArea())
            .navigationTitle("Display")
            .sheet(isPresented: $container.dashcamPresented) {
                DashcamView(service: container.dashcam)
            }
        }
    }
}

struct SpeedometerCard: View {
    @ObservedObject var service: LocationService

    var body: some View {
        ScreenCard {
            VStack(spacing: 6) {
                Text("CURRENT SPEED").font(.caption.bold()).tracking(2).foregroundStyle(.secondary)
                Text(service.speedKmh.map { String(Int($0.rounded())) } ?? "--")
                    .font(.system(size: 150, weight: .bold, design: .rounded))
                    .monospacedDigit().lineLimit(1).minimumScaleFactor(0.2)
                    .frame(maxWidth: .infinity).padding(.vertical, 8)
                    .accessibilityIdentifier("speed-value")
                Text("km/h").font(.title3).foregroundStyle(Theme.accent)
                Text(service.status).font(.caption).foregroundStyle(.secondary)
                if service.authorization == .notDetermined || service.authorization == .denied {
                    Button("Enable GPS") { service.requestPermission() }.padding(.top, 8)
                }
            }.frame(maxWidth: .infinity).frame(minHeight: 230)
        }
    }
}

