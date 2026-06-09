import Foundation
import UIKit

enum MapLaunchPlanner {
    static func url(for payload: RoutePayload, preferredMapApp: PreferredMapApp, openBehavior: DisplayOpenBehavior) -> URL? {
        let destination = destinationString(from: payload)
        switch preferredMapApp {
        case .googleMaps:
            return googleMapsURL(destination: destination, mode: payload.navigationMode, openBehavior: openBehavior)
        case .appleMaps:
            return appleMapsURL(destination: destination)
        }
    }

    private static func destinationString(from payload: RoutePayload) -> String {
        if let latitude = payload.latitude, let longitude = payload.longitude {
            return "\(latitude),\(longitude)"
        }
        if let query = payload.query, !query.isEmpty {
            return query
        }
        if let placeName = payload.placeName, !placeName.isEmpty {
            return placeName
        }
        if let resolvedURL = payload.resolvedURL, !resolvedURL.isEmpty {
            return resolvedURL
        }
        return payload.originalText
    }

    private static func googleMapsURL(destination: String, mode: DisplayNavigationMode, openBehavior: DisplayOpenBehavior) -> URL? {
        let encoded = destination.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? destination
        let directionsMode = mode == .motorcycle ? "driving" : "driving"
        if UIApplication.shared.canOpenURL(URL(string: "comgooglemaps://")!) {
            let base = "comgooglemaps://?daddr=\(encoded)&directionsmode=\(directionsMode)"
            if openBehavior == .startNavigation {
                return URL(string: base)
            }
            return URL(string: base)
        }

        return URL(string: "https://www.google.com/maps/dir/?api=1&destination=\(encoded)")
    }

    private static func appleMapsURL(destination: String) -> URL? {
        let encoded = destination.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? destination
        return URL(string: "http://maps.apple.com/?daddr=\(encoded)&dirflg=d")
    }
}

