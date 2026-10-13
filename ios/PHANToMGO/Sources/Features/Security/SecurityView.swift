import MapKit
import SwiftUI

struct SecurityView: View {
    @EnvironmentObject private var container: AppContainer
    @EnvironmentObject private var security: AntiTheftService
    @State private var pinSetup = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    if container.hasMainPair {
                        FindDisplayCard()
                        ScreenCard {
                            Label("Display alerts", systemImage: "bell.badge.fill").font(.headline)
                            if container.securityAlerts.isEmpty {
                                Text("No security alerts").foregroundStyle(.secondary)
                            } else {
                                ForEach(container.securityAlerts.prefix(10)) { alert in
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(alert.displayName + ": " + alert.eventType).font(.subheadline.bold())
                                        Text(alert.date.formatted()).font(.caption).foregroundStyle(.secondary)
                                        if let lat = alert.latitude, let lng = alert.longitude {
                                            Text(String(format: "%.5f, %.5f", lat, lng)).font(.caption.monospaced())
                                        }
                                    }.frame(maxWidth: .infinity, alignment: .leading)
                                    Divider()
                                }
                            }
                        }
                    }
                    if container.isDisplayModeEnabled {
                        ScreenCard {
                            Label("Display anti-theft", systemImage: "shield.lefthalf.filled").font(.headline)
                            Text(security.status).foregroundStyle(.secondary)
                            if security.hardwareSupported {
                                LabeledContent("3.5 mm jack", value: security.jackConnected ? "Connected" : "Not connected")
                                Button(PINStore.hasPIN ? "Change local PIN" : "Set local PIN") { pinSetup = true }
                                    .disabled(security.isArmed)
                                Button(security.isArmed ? "Disarm with PIN" : "Arm anti-theft") {
                                    if security.isArmed { security.requestDisarm() }
                                    else if !PINStore.hasPIN { pinSetup = true }
                                    else {
                                        do { try security.arm() }
                                        catch { container.toastMessage = error.localizedDescription }
                                    }
                                }.buttonStyle(PrimaryActionStyle())
                                    .disabled(!security.isArmed && !security.jackConnected)
                            } else {
                                Text("This device has no supported built-in 3.5 mm jack. USB and Bluetooth accessories are not used as theft triggers.")
                                    .font(.footnote)
                            }
                            Text("PIN verification protects this app, not the iOS lock screen. Monitoring can pause if iOS suspends the app. System volume and audio routing remain under iOS control.")
                                .font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                    if !container.hasMainPair && !container.isDisplayModeEnabled {
                        ScreenCard {
                            Label("Security", systemImage: "shield").font(.title3.bold())
                            Text("Pair a Display from Home to find it and receive security alerts, or enable Display Mode for this device.")
                                .foregroundStyle(.secondary)
                        }
                    }
                }.frame(maxWidth: 900).padding(18).frame(maxWidth: .infinity)
            }
            .background(Theme.background.ignoresSafeArea())
            .navigationTitle("Security")
            .sheet(isPresented: $pinSetup) { PINSetupView() }
        }
    }
}

private struct DisplayMapPin: Identifiable {
    let id = "paired-display"
    let coordinate: CLLocationCoordinate2D
}

struct FindDisplayCard: View {
    @EnvironmentObject private var container: AppContainer
    @State private var region = MKCoordinateRegion(
        center: CLLocationCoordinate2D(latitude: 13.7563, longitude: 100.5018),
        span: MKCoordinateSpan(latitudeDelta: 0.015, longitudeDelta: 0.015)
    )
    @State private var centered = false
    private var coordinate: CLLocationCoordinate2D? {
        guard let lat = container.peerPresence?.latitude, let lng = container.peerPresence?.longitude else { return nil }
        let point = CLLocationCoordinate2D(latitude: lat, longitude: lng)
        return CLLocationCoordinate2DIsValid(point) ? point : nil
    }
    var body: some View {
        ScreenCard {
            HStack {
                Label("Find Display", systemImage: "location.fill").font(.headline)
                Spacer()
                Button("Refresh") {
                    Task {
                        await container.refreshDisplayLocation()
                        centerMap()
                    }
                }
            }
            Text(container.pairedDisplaySummary).font(.subheadline.bold())
            Text(container.peerPresence?.isOnline == true ? "Online" : "Last known location / offline")
                .font(.footnote).foregroundStyle(.secondary)
            if let coordinate {
                Map(coordinateRegion: $region, annotationItems: [DisplayMapPin(coordinate: coordinate)]) {
                    MapMarker(coordinate: $0.coordinate, tint: Theme.accent)
                }.frame(height: 270).clipShape(RoundedRectangle(cornerRadius: 16))
                    .onAppear { if !centered { centerMap() } }
                if !container.peerAddress.isEmpty { Text(container.peerAddress).font(.footnote) }
                Text(String(format: "%.5f, %.5f", coordinate.latitude, coordinate.longitude))
                    .font(.caption.monospaced()).textSelection(.enabled)
                if let date = container.peerPresence?.locationDate {
                    Text("Captured " + date.formatted()).font(.caption).foregroundStyle(.secondary)
                }
                Button("Open in Maps") { openMaps(coordinate) }.buttonStyle(PrimaryActionStyle())
            } else {
                Text("Waiting for Display GPS. Enable location sharing on Display.")
                    .foregroundStyle(.secondary)
            }
        }
        .task { await container.refreshDisplayLocation() }
    }
    private func centerMap() {
        guard let coordinate else { return }
        region.center = coordinate
        centered = true
    }
    private func openMaps(_ coordinate: CLLocationCoordinate2D) {
        var components = URLComponents(string: container.preferredMapApp == .appleMaps
                                      ? "https://maps.apple.com/" : "https://www.google.com/maps/search/")!
        if container.preferredMapApp == .appleMaps {
            components.queryItems = [
                URLQueryItem(name: "ll", value: "\(coordinate.latitude),\(coordinate.longitude)"),
                URLQueryItem(name: "q", value: container.pairedDisplaySummary)
            ]
        } else {
            components.queryItems = [
                URLQueryItem(name: "api", value: "1"),
                URLQueryItem(name: "query", value: "\(coordinate.latitude),\(coordinate.longitude)")
            ]
        }
        if let url = components.url { UIApplication.shared.open(url) }
    }
}

struct PINSetupView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var previous = ""
    @State private var pin = ""
    @State private var confirmation = ""
    @State private var busy = false
    @State private var message = ""

    var body: some View {
        NavigationStack {
            Form {
                if PINStore.hasPIN {
                    SecureField("Current PIN", text: $previous).keyboardType(.numberPad)
                }
                SecureField("New PIN (4-8 digits)", text: $pin).keyboardType(.numberPad)
                SecureField("Confirm PIN", text: $confirmation).keyboardType(.numberPad)
                Text("The PIN stays on this device and is stored as a salted password hash. It is not sent to Main.")
                    .font(.footnote).foregroundStyle(.secondary)
                if !message.isEmpty { Text(message).foregroundStyle(.red) }
                Button("Save PIN") {
                    busy = true
                    let old = previous, new = pin, confirm = confirmation
                    Task {
                        let success = await Task.detached(priority: .userInitiated) {
                            (!PINStore.hasPIN || PINStore.verify(old)) && new == confirm && PINStore.set(new)
                        }.value
                        busy = false
                        if success { dismiss() } else { message = "PINs did not match, or the current PIN was incorrect." }
                    }
                }.disabled(busy || !PINStore.valid(pin) || confirmation != pin)
            }
            .navigationTitle("Local PIN")
            .toolbar { Button("Cancel") { dismiss() }.disabled(busy) }
        }
    }
}

struct PINChallengeView: View {
    @EnvironmentObject private var security: AntiTheftService
    @State private var pin = ""
    @State private var failed = false

    var body: some View {
        VStack(spacing: 24) {
            Spacer()
            Image(systemName: "lock.shield").font(.system(size: 64)).foregroundStyle(Theme.accent)
            Text("Security Verification").font(.title.bold())
            Text(security.isBlocked ? "PIN verification is required to continue." : "Verify your local security PIN.")
                .multilineTextAlignment(.center).foregroundStyle(.secondary)
            SecureField("PIN", text: $pin).keyboardType(.numberPad)
                .textFieldStyle(.roundedBorder).font(.title2).privacySensitive()
            if failed { Text("Incorrect PIN").foregroundStyle(.red) }
            Button("Verify") {
                Task {
                    let accepted = await security.verify(pin)
                    failed = !accepted
                    pin = ""
                }
            }.buttonStyle(PrimaryActionStyle()).disabled(security.isVerifying || pin.isEmpty)
            Text("iOS system controls remain available.").font(.caption).foregroundStyle(.secondary)
            Spacer()
        }.padding(30).frame(maxWidth: 520).frame(maxWidth: .infinity)
            .background(Theme.background.ignoresSafeArea())
            .interactiveDismissDisabled()
    }
}
