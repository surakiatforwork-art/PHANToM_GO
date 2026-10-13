import AVFoundation
import Combine
import Foundation
import Photos
import UIKit

struct RecordingClip: Identifiable, Equatable {
    let url: URL
    let date: Date
    let bytes: Int64
    var id: String { url.lastPathComponent }
    var title: String { date.formatted(date: .abbreviated, time: .shortened) }
}

@MainActor
final class DashcamService: ObservableObject {
    @Published private(set) var isRecording = false
    @Published private(set) var isReady = false
    @Published private(set) var status = "Camera not started"
    @Published private(set) var clips: [RecordingClip] = []
    @Published private(set) var isExporting = false
    @Published private(set) var exportProgress: Float = 0
    @Published private(set) var latestExport: URL?
    @Published private(set) var recordingStartedAt: Date?
    let engine = DashcamEngine()
    private var loopWanted = false
    private var exportSession: AVAssetExportSession?

    static var recordingsDirectory: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Dashcam", isDirectory: true)
    }

    init() {
        engine.onFinished = { [weak self] error in
            Task { @MainActor in
                guard let self else { return }
                self.refreshLibrary()
                if let error {
                    self.loopWanted = false
                    self.isRecording = false
                    self.status = error
                } else if self.loopWanted {
                    self.beginSegment()
                } else {
                    self.isRecording = false
                    self.status = "Recording saved"
                }
            }
        }
        refreshLibrary()
    }

    func prepare() async {
        if AVCaptureDevice.authorizationStatus(for: .video) != .authorized {
            guard await AVCaptureDevice.requestAccess(for: .video) else {
                status = "Allow Camera in iOS Settings to record."
                return
            }
        }
        let audio = PrefsStore.shared.flag("dashcamAudio")
        if audio && AVCaptureDevice.authorizationStatus(for: .audio) != .authorized {
            guard await AVCaptureDevice.requestAccess(for: .audio) else {
                status = "Microphone was not allowed. Disable record audio to record silent video."
                return
            }
        }
        let ready = await engine.prepare(audio: audio, highQuality: PrefsStore.shared.flag("dashcam1080p"))
        isReady = ready
        status = ready ? "Camera ready" : "Camera is unavailable on this device."
    }

    func start() async {
        guard !isRecording else { return }
        if !isReady { await prepare() }
        guard isReady else { return }
        loopWanted = true
        isRecording = true
        recordingStartedAt = Date()
        beginSegment()
    }
    func stop() {
        loopWanted = false
        engine.stopRecording()
        if !isRecording { status = "Camera ready" }
    }
    func closeCamera() {
        stop()
        engine.stopSession()
        isReady = false
    }
    func stopForBackground() {
        guard isReady || isRecording else { return }
        closeCamera()
        status = "Recording stopped because the app left the foreground."
        NotificationService.shared.post(id: "dashcam-paused", title: "Dashcam stopped",
                                        body: "iOS pauses camera recording when this app is not active. Saved clips are kept.")
    }
    private func beginSegment() {
        do {
            let directory = Self.recordingsDirectory
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            pruneStorage()
            let available = try directory.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
                .volumeAvailableCapacityForImportantUsage ?? 0
            guard available > 150_000_000 else {
                throw RelayError.message("Not enough free storage for another video segment.")
            }
            let file = directory.appendingPathComponent(
                String(Int(Date().timeIntervalSince1970 * 1000)) + "-" + UUID().uuidString + ".mp4"
            )
            engine.record(to: file)
            status = "Recording 60-second loop segments"
        } catch {
            loopWanted = false; isRecording = false
            status = error.localizedDescription
        }
    }
    func refreshLibrary() {
        let directory = Self.recordingsDirectory
        let files = (try? FileManager.default.contentsOfDirectory(
            at: directory, includingPropertiesForKeys: [.creationDateKey, .fileSizeKey]
        )) ?? []
        clips = files.filter { $0.pathExtension.lowercased() == "mp4" }.compactMap { url in
            guard let values = try? url.resourceValues(forKeys: [.creationDateKey, .fileSizeKey]),
                  let size = values.fileSize, size > 0 else { return nil }
            return RecordingClip(url: url, date: values.creationDate ?? .distantPast, bytes: Int64(size))
        }.sorted { $0.date > $1.date }
    }
    private func pruneStorage() {
        refreshLibrary()
        let limit = Int64(PrefsStore.shared.number("dashcamStorageGB", default: 2) * 1024 * 1024 * 1024)
        var total = clips.reduce(Int64(0)) { $0 + $1.bytes }
        for clip in clips.reversed() where total > max(100_000_000, limit - 100_000_000) {
            // Only app-owned loop files are pruned; exported files live separately.
            guard clip.url.deletingLastPathComponent().standardizedFileURL == Self.recordingsDirectory.standardizedFileURL else { continue }
            if (try? FileManager.default.removeItem(at: clip.url)) != nil { total -= clip.bytes }
        }
        refreshLibrary()
    }
    func remove(_ clip: RecordingClip) {
        guard !isRecording, !isExporting,
              clip.url.deletingLastPathComponent().standardizedFileURL == Self.recordingsDirectory.standardizedFileURL else { return }
        do { try FileManager.default.removeItem(at: clip.url); refreshLibrary() }
        catch { status = error.localizedDescription }
    }

    func export(_ clip: RecordingClip, start: Double, end: Double) async {
        guard !isExporting else { return }
        isExporting = true
        exportProgress = 0
        defer { isExporting = false; exportSession = nil }
        do {
            let asset = AVURLAsset(url: clip.url)
            let duration = try await asset.load(.duration).seconds
            guard start >= 0, end > start, end <= duration + 0.05, duration.isFinite else {
                throw RelayError.message("Choose a valid start and end time.")
            }
            let folder = Self.recordingsDirectory.deletingLastPathComponent().appendingPathComponent("Exports", isDirectory: true)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            let output = folder.appendingPathComponent("PHANToMGO-" + UUID().uuidString + ".mp4")
            guard let exporter = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetHighestQuality) else {
                throw RelayError.message("This recording cannot be exported.")
            }
            exportSession = exporter
            exporter.outputURL = output
            exporter.outputFileType = .mp4
            exporter.shouldOptimizeForNetworkUse = true
            exporter.timeRange = CMTimeRange(
                start: CMTime(seconds: start, preferredTimescale: 600),
                end: CMTime(seconds: end, preferredTimescale: 600)
            )
            let progress = Task { [weak self] in
                while !Task.isCancelled {
                    self?.exportProgress = exporter.progress
                    try? await Task.sleep(nanoseconds: 250_000_000)
                }
            }
            defer { progress.cancel() }
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                exporter.exportAsynchronously { continuation.resume() }
            }
            guard exporter.status == .completed else {
                throw exporter.error ?? RelayError.message("Export was cancelled or failed.")
            }
            latestExport = output
            exportProgress = 1
            status = "Export ready to share"
        } catch { status = error.localizedDescription }
    }

    func cancelExport() { exportSession?.cancelExport() }
    func clearExportPreview() {
        if !isExporting { latestExport = nil }
    }
    func saveExportToPhotos() async {
        guard let url = latestExport else { return }
        let permission = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard permission == .authorized || permission == .limited else {
            status = "Allow adding photos/videos in iOS Settings."
            return
        }
        do {
            try await PHPhotoLibrary.shared().performChanges {
                PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: url)
            }
            status = "Video saved to Photos"
        } catch { status = error.localizedDescription }
    }
}

final class DashcamEngine: NSObject, AVCaptureFileOutputRecordingDelegate {
    let session = AVCaptureSession()
    private let output = AVCaptureMovieFileOutput()
    private let queue = DispatchQueue(label: "com.phantom.dashcam.camera", qos: .userInitiated)
    var onFinished: ((String?) -> Void)?
    private var configured = false
    private var configuredAudio = false
    private var configuredHighQuality = false

    func prepare(audio: Bool, highQuality: Bool) async -> Bool {
        await withCheckedContinuation { continuation in
            queue.async { [self] in
                if configured && (configuredAudio != audio || configuredHighQuality != highQuality) {
                    if session.isRunning { session.stopRunning() }
                    session.beginConfiguration()
                    session.inputs.forEach(session.removeInput)
                    session.outputs.forEach(session.removeOutput)
                    session.commitConfiguration()
                    configured = false
                }
                if !configured {
                    session.beginConfiguration()
                    let preset: AVCaptureSession.Preset = highQuality ? .hd1920x1080 : .hd1280x720
                    session.sessionPreset = session.canSetSessionPreset(preset) ? preset : .hd1280x720
                    guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
                          let input = try? AVCaptureDeviceInput(device: camera), session.canAddInput(input) else {
                        session.commitConfiguration(); continuation.resume(returning: false); return
                    }
                    session.addInput(input)
                    if audio, let microphone = AVCaptureDevice.default(for: .audio),
                       let input = try? AVCaptureDeviceInput(device: microphone), session.canAddInput(input) {
                        session.addInput(input)
                    }
                    guard session.canAddOutput(output) else {
                        session.commitConfiguration(); continuation.resume(returning: false); return
                    }
                    session.addOutput(output)
                    output.maxRecordedDuration = CMTime(seconds: 60, preferredTimescale: 600)
                    output.maxRecordedFileSize = 500_000_000
                    if let connection = output.connection(with: .video) {
                        if connection.isVideoOrientationSupported { connection.videoOrientation = .portrait }
                        if connection.isVideoStabilizationSupported { connection.preferredVideoStabilizationMode = .auto }
                        if output.availableVideoCodecTypes.contains(.h264) {
                            output.setOutputSettings([AVVideoCodecKey: AVVideoCodecType.h264], for: connection)
                        }
                    }
                    session.commitConfiguration()
                    configured = true
                    configuredAudio = audio
                    configuredHighQuality = highQuality
                }
                if !session.isRunning { session.startRunning() }
                continuation.resume(returning: true)
            }
        }
    }
    func record(to url: URL) {
        queue.async { [self] in
            guard session.isRunning && !output.isRecording else { return }
            output.startRecording(to: url, recordingDelegate: self)
        }
    }
    func stopRecording() {
        queue.async { [self] in if output.isRecording { output.stopRecording() } }
    }
    func stopSession() {
        queue.async { [self] in
            if output.isRecording { output.stopRecording() }
            if session.isRunning { session.stopRunning() }
        }
    }
    func fileOutput(_ output: AVCaptureFileOutput, didFinishRecordingTo outputFileURL: URL,
                    from connections: [AVCaptureConnection], error: Error?) {
        let succeeded = (error as NSError?)?.userInfo[AVErrorRecordingSuccessfullyFinishedKey] as? Bool ?? false
        onFinished?(error == nil || succeeded ? nil : error?.localizedDescription)
    }
}
