import Foundation

enum RouteParser {
    static func parse(_ rawInput: String, navMode: DisplayNavigationMode) -> RoutePayload {
        let raw = rawInput.trimmingCharacters(in: .whitespacesAndNewlines)
        let input = extractMapLink(raw) ?? raw
        if let pair = parsePairInfo(input), pair.isCloudPairing || pair.hasLocalTransport {
            return invalid(raw, mode: navMode)
        }
        if input.lowercased().hasPrefix("geo:") {
            let part = String(input.dropFirst(4))
            let coordinateText = part.components(separatedBy: "?").first ?? ""
            let query = queryItems(input)["q"]
            let target = query ?? coordinateText
            return destination(target, original: raw, mode: navMode, source: .geoScheme)
        }
        if input.lowercased().hasPrefix("google.navigation:") {
            let query = queryItems("https://phantom.invalid/?" + input.dropFirst("google.navigation:".count))["q"]
            return query.map { destination($0, original: raw, mode: navMode, source: .googleNavigationScheme) }
                ?? invalid(raw, mode: navMode)
        }
        if let url = URL(string: input), ["http", "https"].contains(url.scheme?.lowercased() ?? "") {
            guard isMapURL(url) else { return invalid(raw, mode: navMode) }
            let items = queryItems(input)
            let target = items["destination"] ?? items["daddr"] ?? items["query"] ?? items["q"] ?? pathDestination(url)
            let coordinates = target.flatMap(parseCoordinatePair) ?? embeddedDestination(input)
            let source: RoutePayload.ParserSource = url.host?.lowercased() == "maps.app.goo.gl" ? .mapsAppShortLink : .googleMapsUrl
            return RoutePayload(
                originalText: raw, sourceApp: "share", resolvedURL: input,
                latitude: coordinates?.0, longitude: coordinates?.1,
                query: target, placeName: target, navigationMode: navMode,
                confidence: coordinates == nil ? .medium : .high, parserSource: source
            )
        }
        if let coordinates = parseCoordinatePair(input) {
            return coordinatePayload(coordinates, original: raw, mode: navMode, source: .coordinates)
        }
        if input.range(of: #"^\s*-?[\d.]+\s*,\s*-?[\d.]+\s*$"#, options: .regularExpression) != nil {
            return invalid(raw, mode: navMode)
        }
        guard !input.isEmpty, input.count <= 2000 else { return invalid(raw, mode: navMode) }
        return destination(input, original: raw, mode: navMode, source: .placeName)
    }

    static func parsePairInfo(_ rawInput: String) -> PairInfo? {
        let raw = rawInput.trimmingCharacters(in: .whitespacesAndNewlines)
        var values: [String: String] = [:]
        if let components = URLComponents(string: raw),
           components.scheme?.lowercased() == "phantomgo", components.host == "pair" {
            for item in components.queryItems ?? [] { values[item.name] = item.value }
        } else if let data = raw.data(using: .utf8),
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            for (key, value) in json {
                if let string = value as? String { values[key] = string }
                else if let number = value as? NSNumber { values[key] = number.stringValue }
            }
        } else {
            let parts = raw.components(separatedBy: "|")
            let endpoint = parts.first?.components(separatedBy: ":") ?? []
            guard parts.count == 2, endpoint.count == 2 else { return nil }
            values = ["ip": endpoint[0], "port": endpoint[1], "token": parts[1]]
        }
        let info = PairInfo(
            ip: values["ip"], port: values["port"].flatMap(Int.init), token: values["token"],
            displayDeviceId: values["deviceId"] ?? values["displayDeviceId"],
            pairCode: values["pairCode"]
        )
        if info.isCloudPairing {
            guard FirebaseKeyValidation.valid(info.displayDeviceId!), FirebaseKeyValidation.valid(info.pairCode!) else { return nil }
            return info
        }
        return info.hasLocalTransport ? info : nil
    }

    static func buildCloudPairPayload(deviceId: String, pairCode: String, local: LocalEndpoint? = nil) -> String {
        var components = URLComponents()
        components.scheme = "phantomgo"; components.host = "pair"
        components.queryItems = [
            URLQueryItem(name: "mode", value: "cloud"), URLQueryItem(name: "deviceId", value: deviceId),
            URLQueryItem(name: "pairCode", value: pairCode)
        ]
        if let local, local.isUsable {
            components.queryItems?.append(contentsOf: [
                URLQueryItem(name: "ip", value: local.ip), URLQueryItem(name: "port", value: String(local.port)),
                URLQueryItem(name: "token", value: local.token)
            ])
        }
        return components.string ?? ""
    }

    static func parseCoordinatePair(_ raw: String) -> (Double, Double)? {
        let pattern = #"^\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)(?:\s*\([^)]*\))?\s*$"#
        guard let regex = try? NSRegularExpression(pattern: pattern),
              let match = regex.firstMatch(in: raw, range: NSRange(raw.startIndex..., in: raw)),
              let first = Range(match.range(at: 1), in: raw), let second = Range(match.range(at: 2), in: raw),
              let lat = Double(raw[first]), let lng = Double(raw[second]),
              (-90...90).contains(lat), (-180...180).contains(lng) else { return nil }
        return (lat, lng)
    }

    static func isMapURL(_ url: URL) -> Bool {
        guard ["http", "https"].contains(url.scheme?.lowercased() ?? ""),
              let host = url.host?.lowercased() else { return false }
        if host == "maps.app.goo.gl" || host == "maps.google.com" || host == "maps.apple.com" { return true }
        if host == "goo.gl" { return url.path.hasPrefix("/maps/") }
        let hosts = ["google.com", "www.google.com", "google.co.th", "www.google.co.th",
                     "google.co.uk", "www.google.co.uk", "google.co.jp", "www.google.co.jp"]
        return hosts.contains(host) && url.path.hasPrefix("/maps")
    }

    static func extractMapLink(_ text: String) -> String? {
        guard let regex = try? NSRegularExpression(pattern: #"https?://[^\s<>]+"#) else { return nil }
        for match in regex.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
            guard let range = Range(match.range, in: text) else { continue }
            let candidate = String(text[range]).trimmingCharacters(in: CharacterSet(charactersIn: ".,;\""))
            if let url = URL(string: candidate), isMapURL(url) { return candidate }
        }
        return nil
    }

    private static func queryItems(_ value: String) -> [String: String] {
        var result: [String: String] = [:]
        for item in URLComponents(string: value)?.queryItems ?? [] { result[item.name] = item.value }
        return result
    }
    private static func pathDestination(_ url: URL) -> String? {
        let pieces = url.pathComponents
        if let index = pieces.firstIndex(of: "place"), pieces.indices.contains(index + 1) {
            return pieces[index + 1].replacingOccurrences(of: "+", with: " ")
        }
        if let index = pieces.firstIndex(of: "dir") {
            let destinations = pieces.dropFirst(index + 1)
                .prefix { !$0.hasPrefix("@") && !$0.hasPrefix("data=") }
                .filter { !$0.isEmpty && $0 != "/" }
            return destinations.last?.replacingOccurrences(of: "+", with: " ")
        }
        return nil
    }

    private static func embeddedDestination(_ text: String) -> (Double, Double)? {
        // Maps @lat,lng is the viewport, not necessarily the destination.
        guard let regex = try? NSRegularExpression(pattern: #"!3d(-?\d+(?:\.\d+)?)!4d(-?\d+(?:\.\d+)?)"#),
              let last = regex.matches(in: text, range: NSRange(text.startIndex..., in: text)).last,
              let first = Range(last.range(at: 1), in: text), let second = Range(last.range(at: 2), in: text) else { return nil }
        return parseCoordinatePair(String(text[first]) + "," + String(text[second]))
    }

    private static func coordinatePayload(_ pair: (Double, Double), original: String,
                                          mode: DisplayNavigationMode, source: RoutePayload.ParserSource) -> RoutePayload {
        var components = URLComponents(string: "https://www.google.com/maps/search/")!
        components.queryItems = [
            URLQueryItem(name: "api", value: "1"), URLQueryItem(name: "query", value: "\(pair.0),\(pair.1)")
        ]
        return RoutePayload(originalText: original, sourceApp: "share", resolvedURL: components.string,
                            latitude: pair.0, longitude: pair.1, navigationMode: mode,
                            confidence: .high, parserSource: source)
    }

    private static func destination(_ text: String, original: String, mode: DisplayNavigationMode,
                                    source: RoutePayload.ParserSource) -> RoutePayload {
        if let pair = parseCoordinatePair(text) {
            return coordinatePayload(pair, original: original, mode: mode, source: source)
        }
        guard !text.trimmingCharacters(in: .whitespaces).isEmpty else { return invalid(original, mode: mode) }
        var components = URLComponents(string: source == .googleNavigationScheme
                                      ? "https://www.google.com/maps/dir/" : "https://www.google.com/maps/search/")!
        components.queryItems = [URLQueryItem(name: "api", value: "1"),
                                URLQueryItem(name: source == .googleNavigationScheme ? "destination" : "query", value: text)]
        return RoutePayload(originalText: original, sourceApp: "share", resolvedURL: components.string,
                            query: text, placeName: text, navigationMode: mode,
                            confidence: .medium, parserSource: source)
    }
    private static func invalid(_ text: String, mode: DisplayNavigationMode) -> RoutePayload {
        RoutePayload(originalText: text, navigationMode: mode, confidence: .low, parserSource: .manualInput)
    }
}

enum FirebaseKeyValidation {
    static func valid(_ value: String) -> Bool {
        !value.isEmpty && value.count <= 128
            && value.rangeOfCharacter(from: CharacterSet(charactersIn: ".#$[]/")) == nil
    }
}
