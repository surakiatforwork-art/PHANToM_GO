import Foundation

struct LocalEndpoint: Codable, Equatable {
    let ip: String
    let port: Int
    let token: String
    var tokenHint: String {
        token.count <= 8 ? token : String(token.prefix(4)) + "..." + String(token.suffix(4))
    }
    var isUsable: Bool {
        guard (1...65535).contains(port), !token.isEmpty else { return false }
        let octets = ip.split(separator: ".").compactMap { Int($0) }
        return octets.count == 4 && octets.allSatisfy { (0...255).contains($0) }
            && (octets[0] == 10 || (octets[0] == 192 && octets[1] == 168)
                || (octets[0] == 172 && (16...31).contains(octets[1])))
            && octets[3] != 0 && octets[3] != 255
    }
}
