import AVFoundation
import SwiftUI

struct QRScannerView: UIViewControllerRepresentable {
    let onCodeScanned: (String) -> Void
    let onError: (String) -> Void

    final class Coordinator: NSObject, AVCaptureMetadataOutputObjectsDelegate {
        let parent: QRScannerView
        private var scanned = false
        init(parent: QRScannerView) { self.parent = parent }
        func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject],
                            from connection: AVCaptureConnection) {
            guard !scanned, let item = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
                  let text = item.stringValue else { return }
            guard RouteParser.parsePairInfo(text)?.isCloudPairing == true else {
                parent.onError("Scan a PHANToM GO cloud pairing QR.")
                return
            }
            scanned = true
            parent.onCodeScanned(text)
        }
    }
    func makeCoordinator() -> Coordinator { Coordinator(parent: self) }
    func makeUIViewController(context: Context) -> QRScannerViewController {
        let controller = QRScannerViewController()
        controller.delegate = context.coordinator
        controller.onError = onError
        return controller
    }
    func updateUIViewController(_ controller: QRScannerViewController, context: Context) {}
    static func dismantleUIViewController(_ controller: QRScannerViewController, coordinator: Coordinator) {
        controller.stopScanning()
    }
}

private final class QRScannerEngine {
    let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "com.phantom.qr.camera", qos: .userInitiated)
    private var configured = false

    func prepare(delegate: AVCaptureMetadataOutputObjectsDelegate?) async -> Bool {
        await withCheckedContinuation { continuation in
            queue.async { [self] in
                if configured { continuation.resume(returning: true); return }
                guard let camera = AVCaptureDevice.default(for: .video),
                      let input = try? AVCaptureDeviceInput(device: camera), session.canAddInput(input) else {
                    continuation.resume(returning: false); return
                }
                session.beginConfiguration()
                session.addInput(input)
                let output = AVCaptureMetadataOutput()
                guard session.canAddOutput(output) else {
                    session.commitConfiguration(); continuation.resume(returning: false); return
                }
                session.addOutput(output)
                output.setMetadataObjectsDelegate(delegate, queue: .main)
                output.metadataObjectTypes = [.qr]
                session.commitConfiguration()
                configured = true
                continuation.resume(returning: true)
            }
        }
    }
    func start() { queue.async { [session] in if !session.isRunning { session.startRunning() } } }
    func stop() { queue.async { [session] in if session.isRunning { session.stopRunning() } } }
}

final class QRScannerViewController: UIViewController {
    weak var delegate: AVCaptureMetadataOutputObjectsDelegate?
    var onError: ((String) -> Void)?
    private let engine = QRScannerEngine()
    private var preview: AVCaptureVideoPreviewLayer?
    private var configured = false
    private var visible = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        Task {
            let permission = AVCaptureDevice.authorizationStatus(for: .video)
            let allowed: Bool
            if permission == .authorized { allowed = true }
            else if permission == .notDetermined { allowed = await AVCaptureDevice.requestAccess(for: .video) }
            else { allowed = false }
            guard allowed else {
                onError?("Allow Camera in Settings or paste the pairing payload below.")
                return
            }
            guard await engine.prepare(delegate: delegate) else {
                onError?("No camera available. Paste the pairing payload instead.")
                return
            }
            configured = true
            let layer = AVCaptureVideoPreviewLayer(session: engine.session)
            layer.videoGravity = .resizeAspectFill
            layer.frame = view.bounds
            view.layer.addSublayer(layer)
            preview = layer
            if visible { engine.start() }
        }
    }
    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        visible = true
        if configured { engine.start() }
    }
    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        visible = false
        stopScanning()
    }
    func stopScanning() { engine.stop() }
    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview?.frame = view.bounds
    }
}

