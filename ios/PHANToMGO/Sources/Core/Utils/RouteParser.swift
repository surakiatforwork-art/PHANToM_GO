import Foundation

enum RouteParser {
    static func parse(_ rawInput: String, navMode: DisplayNavigationMode) -> RoutePayload {
        let raw = rawInput.trimmingCharacters(in: .whitespacesAndNewlines)

        if let pairInfo = parsePairInfo(raw), pairInfo.isCloudPairing || pairInfo.hasLocalTransport {
            return RoutePayload(
                originalText: raw,
                sourceApp: "pairing",
                resolvedURL: nil,
                query: nil,
                placeName: nil,
                navigationMode: navMode,
                confidence: .high,
                parserSource: .manualInput
            )
        }

        if raw.lowercased().hasPrefix("google.navigation:"),
           let navigationPayload = parseGoogleNavigation(raw, navMode: navMode) {
            return navigationPayload
        }

        if let url = URL(string: raw), let scheme = url.scheme?.lowercased() {
            if raw.contains("maps.app.goo.gl") {
                return RoutePayload(
                    originalText: raw,
                    sourceApp: "manual",
                    resolvedURL: raw,
                    navigationMode: navMode,
                    confidence: .medium,
                    parserSource: .mapsAppShortLink
                )
            }

            if scheme == "geo", let geoPayload = parseGeo(raw, navMode: navMode) {
                return geoPayload
            }

            if scheme == "google.navigation", let navigationPayload = parseGoogleNavigation(raw, navMode: navMode) {
                return navigationPayload
            }

            if scheme == "http" || scheme == "https" {
                return parseHTTP(raw, navMode: navMode)
            }
        }

        if let coordinatesPayload = parseCoordinates(raw, navMode: navMode) {
            return coordinatesPayload
        }

        return RoutePayload(
            originalText: raw,
            sourceApp: "manual",
            resolvedURL: "https://www.google.com/maps/search/?api=1&query=\(raw.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? raw)",
            query: raw,
            placeName: raw,
            navigationMode: navMode,
            confidence: .medium,
            parserSource: .placeName
        )
    }

    static func parsePairInfo(_ rawInput: String) -> PairInfo? {
        let raw = rawInput.trimmingCharacters(in: .whitespacesAndNewlines)

        if raw.lowercased().hasPrefix("phantomgo://"), let components = URLComponents(string: raw) {
            let items = components.queryItems ?? []
            let deviceId = items.first(where: { $0.name == "deviceId" })?.value
            let pairCode = items.first(where: { $0.name == "pairCode" })?.value
            let ip = items.first(where: { $0.name == "ip" })?.value
            let port = items.first(where: { $0.name == "port" })?.value.flatMap(Int.init)
            let token = items.first(where: { $0.name == "token" })?.value
            return PairInfo(ip: ip, port: port, token: token, displayDeviceId: deviceId, pairCode: pairCode)
        }

        if raw.hasPrefix("{"), raw.hasSuffix("}") {
            guard let data = raw.data(using: .utf8),
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                return nil
            }
            return PairInfo(
                ip: json["ip"] as? String,
                port: json["port"] as? Int,
                token: json["token"] as? String,
                displayDeviceId: json["deviceId"] as? String,
                pairCode: json["pairCode"] as? String
            )
        }

        let parts = raw.split(separator: "|")
        if parts.count == 2 {
            let left = parts[0].split(separator: ":")
            if left.count == 2, let port = Int(left[1]) {
                return PairInfo(
                    ip: String(left[0]),
                    port: port,
                    token: String(parts[1]),
                    displayDeviceId: nil,
                    pairCode: nil
                )
            }
        }

        return nil
    }

    static func buildCloudPairPayload(deviceId: String, pairCode: String) -> String {
        var components = URLComponents()
        components.scheme = "phantomgo"
        components.host = "pair"
        components.queryItems = [
            URLQueryItem(name: "mode", value: "cloud"),
            URLQueryItem(name: "deviceId", value: deviceId),
            URLQueryItem(name: "pairCode", value: pairCode)
        ]
        return components.string ?? "phantomgo://pair?mode=cloud&deviceId=\(deviceId)&pairCode=\(pairCode)"
    }

    private static func parseGeo(_ raw: String, navMode: DisplayNavigationMode) -> RoutePayload? {
        guard let components = URLComponents(string: raw) else { return nil }
        let query = components.queryItems?.first(where: { $0.name == "q" })?.value
        if let query, !query.isEmpty {
            return RoutePayload(
                originalText: raw,
                sourceApp: "manual",
                resolvedURL: "https://www.google.com/maps/search/?api=1&query=\(query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? query)",
                query: query,
                placeName: query,
                navigationMode: navMode,
                confidence: .high,
                parserSource: .geoScheme
            )
        }

        let value = raw.replacingOccurrences(of: "geo:", with: "")
        let location = value.split(separator: "?").first.map(String.init) ?? value
        let pair = location.split(separator: ",")
        guard pair.count == 2,
              let lat = Double(pair[0]),
              let lng = Double(pair[1]) else {
            return nil
        }
        return RoutePayload(
            originalText: raw,
            sourceApp: "manual",
            resolvedURL: "https://www.google.com/maps/search/?api=1&query=\(lat),\(lng)",
            latitude: lat,
            longitude: lng,
            navigationMode: navMode,
            confidence: .high,
            parserSource: .geoScheme
        )
    }

    private static func parseGoogleNavigation(_ raw: String, navMode: DisplayNavigationMode) -> RoutePayload? {
        let prefix = "google.navigation:"
        guard raw.lowercased().hasPrefix(prefix) else { return nil }

        let queryString = String(raw.dropFirst(prefix.count)).trimmingCharacters(in: CharacterSet(charactersIn: "?"))
        guard let components = URLComponents(string: "https://phantom.invalid/?\(queryString)"),
              let query = components.queryItems?.first(where: { $0.name == "q" })?.value else {
            return nil
        }
        return RoutePayload(
            originalText: raw,
            sourceApp: "manual",
            resolvedURL: "https://www.google.com/maps/dir/?api=1&destination=\(query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? query)",
            query: query,
            placeName: query,
            navigationMode: navMode,
            confidence: .high,
            parserSource: .googleNavigationScheme
        )
    }

    private static func parseHTTP(_ raw: String, navMode: DisplayNavigationMode) -> RoutePayload {
        RoutePayload(
            originalText: raw,
            sourceApp: "manual",
            resolvedURL: raw,
            navigationMode: navMode,
            confidence: .medium,
            parserSource: .googleMapsUrl
        )
    }

    private static func parseCoordinates(_ raw: String, navMode: DisplayNavigationMode) -> RoutePayload? {
        let pattern = #"^\s*(-?\d+(\.\d+)?)\s*,\s*(-?\d+(\.\d+)?)\s*$"#
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return nil }
        let range = NSRange(location: 0, length: raw.utf16.count)
        guard regex.firstMatch(in: raw, options: [], range: range) != nil else { return nil }

        let parts = raw.split(separator: ",").map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
        guard parts.count == 2, let lat = Double(parts[0]), let lng = Double(parts[1]) else { return nil }

        return RoutePayload(
            originalText: raw,
            sourceApp: "manual",
            resolvedURL: "https://www.google.com/maps/search/?api=1&query=\(lat),\(lng)",
            latitude: lat,
            longitude: lng,
            navigationMode: navMode,
            confidence: .high,
            parserSource: .coordinates
        )
    }
}
