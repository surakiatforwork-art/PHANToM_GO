import SwiftUI

struct HomeView: View {
    @EnvironmentObject private var container: AppContainer

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    ScreenCard {
                        Label("MAIN DEVICE", systemImage: "iphone").font(.caption.bold()).foregroundStyle(Theme.accent)
                        Text(container.pairedDisplaySummary).font(.title2.bold())
                        Label(container.hasMainPair ? "Paired" : "Scan a Display QR to get started",
                              systemImage: container.hasMainPair ? "checkmark.circle.fill" : "qrcode")
                        Text(container.authSummary).font(.footnote).foregroundStyle(.secondary)
                    }

                    if container.hasMainPair {
                        ScreenCard {
                            Label("Send route to Display", systemImage: "paperplane.fill").font(.title3.bold())
                            TextField("Google Maps link, coordinates or destination", text: $container.routeInput, axis: .vertical)
                                .lineLimit(3...6).textFieldStyle(.roundedBorder)
                                .autocorrectionDisabled().textInputAutocapitalization(.never)
                                .accessibilityIdentifier("route-input")
                            HStack(spacing: 12) {
                                PasteButton(payloadType: String.self) { strings in
                                    if let text = strings.first { container.routeInput = text }
                                }.labelStyle(.titleAndIcon)
                                Button {
                                    Task { await container.sendCurrentRoute() }
                                } label: { Label("Send Route", systemImage: "paperplane.fill") }
                                    .buttonStyle(PrimaryActionStyle())
                                    .disabled(container.isBusy || container.routeInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                                    .accessibilityIdentifier("send-route")
                            }
                            if container.isBusy {
                                ProgressView("Waiting for Display acknowledgement").font(.footnote)
                            }
                            if container.pendingRoute != nil {
                                Button("Retry pending route") {
                                    Task { await container.sendCurrentRoute(retryPending: true) }
                                }.disabled(container.isBusy)
                            }
                        }
                        ScreenCard {
                            DisclosureGroup("Connection & quick actions") {
                                VStack(spacing: 12) {
                                    Button("Find Display") { container.switchTab(.security) }
                                    Button("Send test destination") {
                                        container.routeInput = "13.7563,100.5018"
                                        Task { await container.sendCurrentRoute() }
                                    }
                                    Button("Disconnect", role: .destructive) {
                                        Task { await container.clearMainPairing() }
                                    }
                                }.padding(.top, 12).disabled(container.isBusy)
                            }
                        }
                    } else {
                        ScreenCard {
                            Text("Pair your Display").font(.title3.bold())
                            Text("Open Display on your other device, then scan its QR. Android and Web Display are supported.")
                                .foregroundStyle(.secondary)
                            Button {
                                container.dashcam.closeCamera()
                                container.scannerPresented = true
                            } label: { Label("Scan QR / Paste pairing", systemImage: "qrcode.viewfinder") }
                                .buttonStyle(PrimaryActionStyle()).disabled(container.isBusy)
                                .accessibilityIdentifier("scan-pairing")
                            if container.displayPairing != nil {
                                Text("This device still has a Display pairing. Disconnect it from Display before pairing as Main.")
                                    .font(.footnote).foregroundStyle(.secondary)
                            }
                        }
                    }
                    ScreenCard {
                        Label("Share from Maps", systemImage: "square.and.arrow.up").font(.headline)
                        Text("Google Maps > Share > PHANToM GO. If your signing method does not enable Share relay, copy the link and use Paste here.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }
                .frame(maxWidth: 760).padding(18).frame(maxWidth: .infinity)
            }
            .background(Theme.background.ignoresSafeArea())
            .navigationTitle("PHANToM GO")
            .sheet(isPresented: $container.scannerPresented) {
                PairingSheetView().environmentObject(container)
            }
        }
    }
}

