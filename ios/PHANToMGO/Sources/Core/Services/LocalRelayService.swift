import Combine
import Darwin
import Foundation
import Network

@MainActor
final class LocalRelayService: ObservableObject {
    @Published private(set) var ready = false
    @Published private(set) var status = "Local relay off"
    var mainId: String?
    var onRoute: ((String, String) -> Bool)?
    var onEndpointChange: (() -> Void)?
    private var listener: NWListener?
    private var monitor: NWPathMonitor?
    private let port: UInt16 = 8765
    private var token = ""

    var endpoint: LocalEndpoint? {
        guard ready else { return nil }
        return LocalEndpoint(ip: Self.localIP() ?? "", port: Int(port), token: token)
    }

    func start(token: String) {
        guard listener == nil else { return }
        self.token = token
        do {
            let listener = try NWListener(using: .tcp, on: NWEndpoint.Port(rawValue: port)!)
            self.listener = listener
            listener.service = NWListener.Service(name: "PHANToM GO", type: "_phantomgo._tcp")
            listener.stateUpdateHandler = { [weak self, weak listener] state in
                Task { @MainActor in
                    guard let self, let listener, self.listener === listener else { return }
                    switch state {
                    case .ready:
                        self.ready = true; self.status = "Local relay ready"
                        self.onEndpointChange?()
                    case .failed:
                        self.ready = false; self.status = "Local relay unavailable; using cloud"
                    default: break
                    }
                }
            }
            listener.newConnectionHandler = { [weak self] connection in
                Task { @MainActor in self?.accept(connection) }
            }
            listener.start(queue: .main)
            let monitor = NWPathMonitor()
            self.monitor = monitor
            monitor.pathUpdateHandler = { [weak self] _ in
                Task { @MainActor in self?.onEndpointChange?() }
            }
            monitor.start(queue: .main)
        } catch {
            status = "Local relay unavailable; using cloud"
        }
    }
    func stop() {
        listener?.cancel(); listener = nil
        monitor?.cancel(); monitor = nil
        ready = false; status = "Local relay off"
    }

    private func accept(_ connection: NWConnection) {
        connection.start(queue: .main)
        read(connection, buffer: Data())
        DispatchQueue.main.asyncAfter(deadline: .now() + 10) { connection.cancel() }
    }
    private func read(_ connection: NWConnection, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 8192) { [weak self] data, _, finished, error in
            Task { @MainActor in
                guard let self else { connection.cancel(); return }
                var buffer = buffer
                if let data { buffer.append(data) }
                guard buffer.count <= 32768, error == nil else { connection.cancel(); return }
                if let split = buffer.range(of: Data("\r\n\r\n".utf8)),
                   let header = String(data: buffer[..<split.lowerBound], encoding: .utf8) {
                    let rows = header.components(separatedBy: "\r\n")
                    let length = rows.first { $0.lowercased().hasPrefix("content-length:") }
                        .flatMap { Int($0.components(separatedBy: ":").last?.trimmingCharacters(in: .whitespaces) ?? "") } ?? 0
                    guard length >= 0 && length <= 16384 else { connection.cancel(); return }
                    let body = Data(buffer[split.upperBound...])
                    if body.count >= length {
                        self.respond(connection, firstLine: rows.first ?? "", body: Data(body.prefix(length)))
                        return
                    }
                }
                if finished { connection.cancel() }
                else { self.read(connection, buffer: buffer) }
            }
        }
    }
    private func respond(_ connection: NWConnection, firstLine: String, body: Data) {
        let request = firstLine.components(separatedBy: " ")
        var code = 200
        var result: [String: Any] = ["ok": false]
        let path = request.count > 1 ? request[1] : ""
        let method = request.first ?? ""
        if method == "GET" && path == "/status" {
            let endpoint = endpoint
            result = [
                "ok": true, "ip": endpoint?.ip ?? "", "port": Int(port),
                "paired": mainId != nil, "pairedMainId": mainId ?? "",
                "tokenHint": endpoint?.tokenHint ?? "", "version": "1.0"
            ]
        } else if method == "POST", let json = try? JSONSerialization.jsonObject(with: body) as? [String: Any] {
            if json["token"] as? String != token || token.isEmpty {
                code = 401; result = ["ok": false, "reason": "UNAUTHORIZED"]
            } else if path == "/pair" {
                let requestedMain = json["mainId"] as? String
                let accepted = mainId != nil && mainId == requestedMain
                code = accepted ? 200 : 409
                result = ["ok": accepted, "reason": accepted ? "" : "Pair through Firebase QR first"]
            } else if path == "/open-url", mainId != nil, let url = json["url"] as? String,
                      RouteParser.parse(url, navMode: .driving).resolvedURL != nil {
                let id = json["clientRequestId"] as? String ?? UUID().uuidString
                let accepted = onRoute?(url, id) ?? false
                code = accepted ? 200 : 409
                result = ["ok": accepted, "message": accepted ? "Route received" : "Display unavailable"]
            } else {
                code = 400; result = ["ok": false, "reason": "INVALID_REQUEST"]
            }
        } else { code = 404 }
        let data = (try? JSONSerialization.data(withJSONObject: result)) ?? Data("{}".utf8)
        let header = "HTTP/1.1 \(code) \(code == 200 ? "OK" : "Error")\r\nContent-Type: application/json\r\nContent-Length: \(data.count)\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        connection.send(content: Data(header.utf8) + data, completion: .contentProcessed { _ in connection.cancel() })
    }

    static func send(url: String, requestId: String, endpoint: LocalEndpoint) async -> Bool {
        guard endpoint.isUsable, let address = URL(string: "http://\(endpoint.ip):\(endpoint.port)/open-url") else { return false }
        var request = URLRequest(url: address)
        request.httpMethod = "POST"; request.timeoutInterval = 2.5
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONSerialization.data(withJSONObject: [
            "token": endpoint.token, "url": url, "clientRequestId": requestId
        ])
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard (response as? HTTPURLResponse)?.statusCode == 200,
                  let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { return false }
            return json["ok"] as? Bool == true
        } catch { return false }
    }

    private static func localIP() -> String? {
        var first: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&first) == 0, let first else { return nil }
        defer { freeifaddrs(first) }
        var cursor: UnsafeMutablePointer<ifaddrs>? = first
        while let item = cursor {
            defer { cursor = item.pointee.ifa_next }
            guard let address = item.pointee.ifa_addr,
                  address.pointee.sa_family == UInt8(AF_INET) else { continue }
            let name = String(cString: item.pointee.ifa_name)
            guard name == "en0" || name == "bridge100" || name == "ap1" else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(address, socklen_t(address.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                return String(cString: host)
            }
        }
        return nil
    }
}
