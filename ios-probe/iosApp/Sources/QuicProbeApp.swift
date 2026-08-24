import CoreLocation
import SwiftUI
import probe

/// Host app for the Kotlin/Native handoff probe.
///
/// It does exactly two things the Kotlin side cannot do for itself:
///
/// 1. **Keeps the process resident.** iOS suspends an app whose screen has locked, and a coroutine
///    `delay` does not prevent that — so the echo loop and the QUIC keepalive would both stall the
///    moment the phone went into a pocket, which is precisely when the walk gets interesting. A
///    background location session is what keeps the process scheduled. Every fix is reported to the
///    probe so the recording itself carries the proof (`KEEPALIVE-STATUS … locUpdates=N`); if that
///    count is stuck at zero after the screen locks, every gap in the log below it is an artefact of
///    suspension rather than a network event.
/// 2. **Shows the one line an operator mid-walk can act on** — has it migrated, and has the new path
///    carried enough traffic to be worth anything.
///
/// Location data itself is never read, stored, or logged: only the number of updates.
@main
struct QuicProbeApp: App {
    var body: some Scene {
        WindowGroup { ProbeView() }
    }
}

@MainActor
final class ProbeRunner: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published var status: String = "not started"
    @Published var locUpdates: Int = 0
    @Published var authorization: String = "not requested"
    @Published var started: Bool = false

    private let manager = CLLocationManager()
    private var ticker: Timer?

    // Defaults match the Android probe so the two recordings can be read side by side.
    var host: String = "178.156.248.95"
    var port: Int32 = 44433
    var minutes: Int32 = 120

    override init() {
        super.init()
        manager.delegate = self
        // Coarse on purpose: the session exists to keep the process scheduled, not to know where the
        // phone is. Three kilometres is the cheapest accuracy that still delivers updates on a walk.
        manager.desiredAccuracy = kCLLocationAccuracyThreeKilometers
        manager.distanceFilter = 50
        manager.pausesLocationUpdatesAutomatically = false
    }

    func begin() {
        guard !started else { return }
        started = true
        manager.requestAlwaysAuthorization()
        applyAuthorization(manager.authorizationStatus)

        IosHandoffProbe.shared.start(
            host: host,
            port: port,
            minutes: minutes,
            readTimeoutMs: 400,
            echoIntervalMs: 100
        )

        ticker = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.status = IosHandoffProbe.shared.status()
            }
        }
    }

    private func applyAuthorization(_ s: CLAuthorizationStatus) {
        switch s {
        case .authorizedAlways:
            authorization = "always ✅ (survives a locked screen)"
            startResidency(background: true)
        case .authorizedWhenInUse:
            authorization = "when-in-use ⚠️ (grant Always, or a locked screen suspends the walk)"
            startResidency(background: true)
        case .notDetermined:
            authorization = "waiting for permission…"
        case .denied, .restricted:
            authorization = "DENIED ⚠️ — the recording will stop when the screen locks"
        @unknown default:
            authorization = "unknown"
        }
    }

    private func startResidency(background: Bool) {
        // Only legal once authorization is granted AND UIBackgroundModes contains `location`;
        // setting it before either is in place throws.
        if background {
            manager.allowsBackgroundLocationUpdates = true
            manager.showsBackgroundLocationIndicator = true
        }
        manager.startUpdatingLocation()
    }

    nonisolated func locationManagerDidChangeAuthorization(_ m: CLLocationManager) {
        Task { @MainActor in self.applyAuthorization(m.authorizationStatus) }
    }

    nonisolated func locationManager(_ m: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        // The coordinates are deliberately ignored — only the fact that a fix arrived matters.
        Task { @MainActor in
            self.locUpdates += locations.count
            IosHandoffProbe.shared.noteLocationUpdate()
        }
    }

    nonisolated func locationManager(_ m: CLLocationManager, didFailWithError error: Error) {
        Task { @MainActor in self.authorization = "location error: \(error.localizedDescription)" }
    }
}

struct ProbeView: View {
    @StateObject private var runner = ProbeRunner()

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("QUIC handoff probe").font(.title2).bold()

            Text(runner.status)
                .font(.system(.body, design: .monospaced))
                .fixedSize(horizontal: false, vertical: true)

            Divider()

            Group {
                Text("target: \(runner.host):\(String(runner.port))")
                Text("location: \(runner.authorization)")
                Text("location updates: \(runner.locUpdates)")
                    .foregroundStyle(runner.locUpdates > 0 ? .primary : .secondary)
            }
            .font(.system(.footnote, design: .monospaced))

            if !runner.started {
                Button("Start walk (\(String(runner.minutes)) min)") { runner.begin() }
                    .buttonStyle(.borderedProminent)
            } else {
                Text("recording — keep the app running, screen may lock")
                    .font(.footnote).foregroundStyle(.secondary)
            }

            Spacer()

            Text("Walk somewhere the signal DIES (elevator, garage) rather than hands over cleanly — a clean handoff answers every path probe and proves only half of what this is for.")
                .font(.caption).foregroundStyle(.secondary)
        }
        .padding()
    }
}
