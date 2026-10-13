import Foundation

enum MapLaunchPlanner {
    static func url(for payload: RoutePayload, preferredMapApp: PreferredMapApp,
                    openBehavior: DisplayOpenBehavior) -> URL? {
        let target: String?
        if let lat = payload.latitude, let lng = payload.longitude { target = "\(lat),\(lng)" }
        else { target = payload.query ?? payload.placeName }

        // Keep opaque short links intact. A URL is never a destination address.
        guard let target, !target.isEmpty else {
            guard let raw = payload.resolvedURL, let url = URL(string: raw), RouteParser.isMapURL(url) else { return nil }
            return url
        }
        var components = URLComponents(string: preferredMapApp == .appleMaps
                                      ? "https://maps.apple.com/" : "https://www.google.com/maps/dir/")!
        if preferredMapApp == .appleMaps {
            components.queryItems = [
                URLQueryItem(name: "daddr", value: target), URLQueryItem(name: "dirflg", value: "d")
            ]
        } else {
            components.queryItems = [
                URLQueryItem(name: "api", value: "1"), URLQueryItem(name: "destination", value: target),
                URLQueryItem(name: "travelmode", value: payload.navigationMode == .motorcycle ? "two-wheeler" : "driving")
            ]
            if openBehavior == .startNavigation {
                components.queryItems?.append(URLQueryItem(name: "dir_action", value: "navigate"))
            }
        }
        return components.url
    }

    static func resolve(_ payload: RoutePayload) async -> RoutePayload {
        guard payload.parserSource == .mapsAppShortLink,
              let raw = payload.resolvedURL, let url = URL(string: raw) else { return payload }
        var request = URLRequest(url: url)
        request.httpMethod = "HEAD"
        request.timeoutInterval = 8
        do {
            let (_, response) = try await URLSession.shared.data(for: request)
            guard let finalURL = response.url, RouteParser.isMapURL(finalURL),
                  finalURL.host != "maps.app.goo.gl" else { return payload }
            let parsed = RouteParser.parse(finalURL.absoluteString, navMode: payload.navigationMode)
            return RoutePayload(originalText: payload.originalText, sourceApp: payload.sourceApp,
                                resolvedURL: parsed.resolvedURL, latitude: parsed.latitude, longitude: parsed.longitude,
                                query: parsed.query, placeName: parsed.placeName, navigationMode: parsed.navigationMode,
                                confidence: parsed.confidence, parserSource: parsed.parserSource)
        } catch { return payload }
    }
}

