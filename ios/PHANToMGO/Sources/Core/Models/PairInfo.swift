import Foundation

struct PairInfo: Equatable {
    let ip: String?
    let port: Int?
    let token: String?
    let displayDeviceId: String?
    let pairCode: String?

    var isCloudPairing: Bool {
        !(displayDeviceId?.isEmpty ?? true) && !(pairCode?.isEmpty ?? true)
    }

    var hasLocalTransport: Bool {
        !(ip?.isEmpty ?? true) && port != nil && !(token?.isEmpty ?? true)
    }
}

