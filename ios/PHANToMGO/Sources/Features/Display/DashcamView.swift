import AVFoundation
import AVKit
import SwiftUI

struct DashcamView: View {
    @ObservedObject var service: DashcamService
    @Environment(\.dismiss) private var dismiss
    @State private var selectedClip: RecordingClip?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    CameraPreview(session: service.engine.session)
                        .frame(height: 270).clipShape(RoundedRectangle(cornerRadius: 20))
                        .background(.black, in: RoundedRectangle(cornerRadius: 20))
                    ScreenCard {
                        Text(service.status).font(.headline)
                        if service.isRecording {
                            TimelineView(.periodic(from: Date(), by: 1)) { context in
                                Text("Recording " + elapsed(context.date)).font(.title2.monospacedDigit()).foregroundStyle(.red)
                            }
                        }
                        Button {
                            if service.isRecording { service.stop() }
                            else { Task { await service.start() } }
                        } label: {
                            Label(service.isRecording ? "Stop recording" : "Start loop recording",
                                  systemImage: service.isRecording ? "stop.circle.fill" : "record.circle")
                        }.buttonStyle(PrimaryActionStyle())
                        Text("60-second files, hardware video encoding and stabilization where supported. Old loop files are removed at the storage limit; exports are retained.")
                            .font(.footnote).foregroundStyle(.secondary)
                        Text("Keep PHANToM GO open to record. Opening Maps stops the camera on iOS.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    ScreenCard {
                        Text("Recordings").font(.title3.bold())
                        if service.clips.isEmpty { Text("No recordings yet").foregroundStyle(.secondary) }
                        ForEach(service.clips.prefix(100)) { clip in
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(clip.title).font(.subheadline.bold())
                                    Text(ByteCountFormatter.string(fromByteCount: clip.bytes, countStyle: .file))
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                                Button("Play / Export") {
                                    service.closeCamera()
                                    selectedClip = clip
                                }.disabled(service.isRecording || service.isExporting)
                            }
                            Divider()
                        }
                    }
                }.frame(maxWidth: 900).padding(18).frame(maxWidth: .infinity)
            }.background(Theme.background.ignoresSafeArea())
                .navigationTitle("GO Cam")
                .toolbar { Button("Done") { service.closeCamera(); dismiss() } }
                .task { await service.prepare() }
                .onDisappear { service.closeCamera() }
                .sheet(item: $selectedClip) { ClipEditorView(service: service, clip: $0) }
        }
    }
    private func elapsed(_ date: Date) -> String {
        let seconds = max(0, Int(date.timeIntervalSince(service.recordingStartedAt ?? date)))
        return String(format: "%02d:%02d", seconds / 60, seconds % 60)
    }
}

private final class PreviewSurface: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var preview: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
}

private struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession
    func makeUIView(context: Context) -> PreviewSurface {
        let surface = PreviewSurface()
        surface.preview.session = session
        surface.preview.videoGravity = .resizeAspectFill
        return surface
    }
    func updateUIView(_ view: PreviewSurface, context: Context) {
        view.preview.session = session
    }
}

struct ClipEditorView: View {
    @ObservedObject var service: DashcamService
    let clip: RecordingClip
    @Environment(\.dismiss) private var dismiss
    @State private var player = AVPlayer()
    @State private var duration = 0.0
    @State private var start = 0.0
    @State private var end = 0.0

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    VideoPlayer(player: player).frame(height: 270)
                    ScreenCard {
                        Text(clip.title).font(.headline)
                        if duration > 0.5 {
                            Text(String(format: "Start %.1fs · End %.1fs · Length %.1fs", start, end, end - start))
                                .font(.subheadline.monospacedDigit())
                            Text("Start").font(.caption)
                            Slider(value: Binding(
                                get: { start }, set: { start = min($0, end - 0.5); seek(start) }
                            ), in: 0...max(0.01, duration - 0.5))
                            Text("End").font(.caption)
                            Slider(value: Binding(
                                get: { end }, set: { end = max($0, start + 0.5); seek(end) }
                            ), in: 0.5...duration)
                            HStack {
                                Button("-0.1s") { start = max(0, start - 0.1); seek(start) }
                                Button("+0.1s") { start = min(end - 0.5, start + 0.1); seek(start) }
                                Spacer()
                                Text("Fine start adjustment").font(.caption).foregroundStyle(.secondary)
                            }
                            Button("Export selected range") {
                                player.pause()
                                Task { await service.export(clip, start: start, end: end) }
                            }.buttonStyle(PrimaryActionStyle()).disabled(service.isExporting)
                        } else { ProgressView("Loading clip") }
                        if service.isExporting {
                            ProgressView(value: Double(service.exportProgress))
                            Button("Cancel export", role: .cancel) { service.cancelExport() }
                        }
                        Text(service.status).font(.footnote).foregroundStyle(.secondary)
                        if let url = service.latestExport {
                            ShareLink(item: url) { Label("Share exported video", systemImage: "square.and.arrow.up") }
                            Button("Save to Photos") { Task { await service.saveExportToPhotos() } }
                        }
                    }
                }.padding(18).frame(maxWidth: 900).frame(maxWidth: .infinity)
            }
            .background(Theme.background.ignoresSafeArea())
            .navigationTitle("Clip export")
            .toolbar { Button("Done") { player.pause(); dismiss() }.disabled(service.isExporting) }
            .task {
                service.clearExportPreview()
                player.replaceCurrentItem(with: AVPlayerItem(url: clip.url))
                if let time = try? await AVURLAsset(url: clip.url).load(.duration), time.seconds.isFinite {
                    duration = max(0, time.seconds); end = duration
                }
            }
            .onDisappear { player.pause() }
            .interactiveDismissDisabled(service.isExporting)
        }
    }
    private func seek(_ seconds: Double) {
        player.seek(to: CMTime(seconds: seconds, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
    }
}
