import CoreLocation
import CoreMotion
import SwiftUI
import UIKit
import UserNotifications
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
/// 2. **Shows the lines an operator mid-walk can act on**, one per lane — has each family's connection
///    migrated, and has the new path carried enough traffic to be worth anything.
///
/// Location data itself is never read, stored, or logged: only the number of updates.
@main
struct QuicProbeApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate

    var body: some Scene {
        WindowGroup { ProbeView() }
    }
}

/// Decides, on every launch, whether a walk begins, resumes, or waits for a tap — including a launch
/// iOS makes in the background for a significant location change, which brings no window with it.
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        let byLocation = launchOptions?[.location] != nil
        MainActor.assumeIsolated { ProbeRunner.shared.launched(byLocation: byLocation) }
        return true
    }
}

/// The walk this phone is on, kept across launches so a process that died can pick it back up.
struct WalkRecord {
    static let startedAtKey = "walk.startedAt"
    static let endsAtKey = "walk.endsAt"
    static let pinnedKey = "walk.pinned"

    let startedAt: Double
    let endsAt: Double
    /// Radios each target also gets a pinned lane on (`cellular`), kept so a resumed walk keeps its lanes.
    let pinned: String

    /// The walk still running at [now], if there is one.
    static func active(at now: Double, _ defaults: UserDefaults = .standard) -> WalkRecord? {
        let endsAt = defaults.double(forKey: endsAtKey)
        guard endsAt > now else { return nil }
        return WalkRecord(
            startedAt: defaults.double(forKey: startedAtKey),
            endsAt: endsAt,
            pinned: defaults.string(forKey: pinnedKey) ?? ""
        )
    }

    func save(_ defaults: UserDefaults = .standard) {
        defaults.set(startedAt, forKey: WalkRecord.startedAtKey)
        defaults.set(endsAt, forKey: WalkRecord.endsAtKey)
        defaults.set(pinned, forKey: WalkRecord.pinnedKey)
    }

    func minutesLeft(at now: Double) -> Int32 { Int32(((endsAt - now) / 60).rounded(.up)) }
}

/// Where the walk connects. There is no built-in server: `launch.sh` passes `-host` (and `-port`) from
/// the local walk-server.env, and the app keeps them for later launches from the icon.
enum ServerConfig {
    case configured(hosts: String, port: Int32)
    case missing

    static let hostKey = "host"
    static let portKey = "port"
    static let defaultPort: Int32 = 44433

    /// Reads `-host`/`-port` from the launch arguments (UserDefaults' argument domain) or, failing
    /// that, what an earlier launch stored; a launch argument is persisted so an icon tap reuses it.
    static func load(_ defaults: UserDefaults = .standard) -> ServerConfig {
        let hosts = (defaults.string(forKey: hostKey) ?? "").trimmingCharacters(in: .whitespaces)
        if hosts.isEmpty { return .missing }
        let storedPort = Int32(defaults.integer(forKey: portKey))
        let port = storedPort > 0 ? storedPort : defaultPort
        defaults.set(hosts, forKey: hostKey)
        defaults.set(Int(port), forKey: portKey)
        return .configured(hosts: hosts, port: port)
    }

    var summary: String {
        switch self {
        case let .configured(hosts, port): return "targets: \(hosts) port \(String(port))"
        case .missing: return "no server configured — run ios-probe/device/launch.sh (reads walk-server.env)"
        }
    }
}

@MainActor
final class ProbeRunner: NSObject, ObservableObject, CLLocationManagerDelegate {
    static let shared = ProbeRunner()

    @Published var status: String = "not started"
    @Published var locUpdates: Int = 0
    @Published var authorization: String = "not requested"
    @Published var started: Bool = false

    private let manager = CLLocationManager()
    private var ticker: Timer?
    private var walk: WalkRecord?
    private var lastWatchdogAt: Double = 0
    private let activity = CMMotionActivityManager()
    private var moving = false

    // The server is set per launch without a rebuild: `devicectl device process launch … -- -host
    // <addr> -port <n>` lands in UserDefaults' argument domain (launch.sh does this from
    // walk-server.env). A tap on the icon reuses what the last such launch stored; with neither, the
    // app shows that no server is configured and does not start.
    //
    // COMMA-SEPARATED is one lane per target, all running at once, so one phone exercises both
    // address families on every network it crosses: `-host "192.0.2.1,2001:db8::1"`.
    // A colon cannot be the separator — an IPv6 literal is made of them.
    let server: ServerConfig = ServerConfig.load()
    // `-minutes <n>` on the same launch (launch.sh's WALK_MINUTES); 4500 (75 h) without it.
    var minutes: Int32 = {
        let stored = UserDefaults.standard.integer(forKey: "minutes")
        return stored > 0 ? Int32(stored) : 4500
    }()

    override init() {
        super.init()
        manager.delegate = self
        // Coarse on purpose: the session exists to keep the process scheduled, not to know where the
        // phone is. Three kilometres is the cheapest accuracy that still delivers updates on a walk.
        manager.desiredAccuracy = kCLLocationAccuracyThreeKilometers
        manager.distanceFilter = 50
        manager.pausesLocationUpdatesAutomatically = false
        for (name, label) in [
            (UIApplication.didEnterBackgroundNotification, "background"),
            (UIApplication.willEnterForegroundNotification, "foreground"),
        ] {
            NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { _ in
                IosHandoffProbe.shared.noteRun(line: "APP-STATE \(label)")
            }
        }
    }

    /// Every launch: `-autostart` (launch.sh's WALK_AUTOSTART) begins a new walk; otherwise a walk the
    /// phone is still on resumes, whatever brought the app up; otherwise it waits for the button.
    func launched(byLocation: Bool) {
        let now = Date().timeIntervalSince1970
        if UserDefaults.standard.bool(forKey: "autostart") {
            begin()
        } else if let active = WalkRecord.active(at: now) {
            resume(active, reason: byLocation ? "location-relaunch" : "relaunch", now: now)
        }
    }

    func begin() {
        guard !started, case let .configured(hosts, port) = server else { return }
        started = true
        let now = Date().timeIntervalSince1970
        // `-pinned cellular` on the launch (launch.sh's WALK_PINNED): a pinned lane per target as well.
        let pinned = UserDefaults.standard.string(forKey: "pinned") ?? ""
        let record = WalkRecord(startedAt: now, endsAt: now + Double(minutes) * 60, pinned: pinned)
        record.save()
        walk = record
        IosHandoffProbe.shared.start(
            hosts: hosts,
            port: port,
            minutes: minutes,
            echoIntervalMs: 250,
            pinned: pinned
        )
        keepResident()
    }

    private func resume(_ record: WalkRecord, reason: String, now: Double) {
        guard !started, case let .configured(hosts, port) = server else { return }
        started = true
        walk = record
        minutes = record.minutesLeft(at: now)
        IosHandoffProbe.shared.resume(
            hosts: hosts,
            port: port,
            minutes: minutes,
            echoIntervalMs: 250,
            startedAtEpoch: record.startedAt,
            reason: reason,
            pinned: record.pinned
        )
        keepResident()
    }

    /// Location keeps the process scheduled; significant-change monitoring is what lets iOS relaunch it
    /// after it was terminated; the watchdog notification says when neither worked.
    private func keepResident() {
        manager.requestAlwaysAuthorization()
        applyAuthorization(manager.authorizationStatus)
        manager.startMonitoringSignificantLocationChanges()
        followMotion()
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { granted, _ in
            IosHandoffProbe.shared.noteRun(line: "NOTIFICATIONS \(granted ? "granted" : "denied")")
        }

        ticker = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.status = IosHandoffProbe.shared.status()
                self?.feedWatchdog()
                if self?.moving == true { IosHandoffProbe.shared.noteMovement() }
            }
        }
    }

    /// A dead man's switch: a notification always pending five minutes out, pushed back once a minute
    /// while this process runs. If it crashes, is terminated or suspended, nothing pushes it back and it
    /// fires; tapping it opens the app, which resumes the walk. Withdrawn once the walk is over.
    private func feedWatchdog() {
        let now = Date().timeIntervalSince1970
        guard now - lastWatchdogAt >= 60 else { return }
        lastWatchdogAt = now
        let center = UNUserNotificationCenter.current()
        guard let walk, now < walk.endsAt else {
            center.removePendingNotificationRequests(withIdentifiers: [ProbeRunner.watchdogId])
            return
        }
        let content = UNMutableNotificationContent()
        content.title = "QUIC walk probe stopped"
        content.body = "No heartbeat for 5 minutes. Open the app to resume the walk."
        content.sound = .default
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: ProbeRunner.watchdogAfter, repeats: false)
        center.add(UNNotificationRequest(identifier: ProbeRunner.watchdogId, content: content, trigger: trigger))
    }

    /// Core Motion's activity classifier as the walk's sign of movement: what keeps the echo pace fast
    /// while the phone travels, and lets it idle while it sits still.
    private func followMotion() {
        guard CMMotionActivityManager.isActivityAvailable() else {
            IosHandoffProbe.shared.noteRun(line: "MOTION-SOURCE none — pace sees no movement on this device")
            return
        }
        IosHandoffProbe.shared.noteRun(line: "MOTION-SOURCE core-motion-activity")
        activity.startActivityUpdates(to: .main) { [weak self] a in
            guard let self, let a else { return }
            let now = a.confidence != .low && (a.walking || a.running || a.cycling || a.automotive)
            if now != self.moving {
                self.moving = now
                IosHandoffProbe.shared.noteRun(line: "MOTION \(now ? "moving" : "still") \(ProbeRunner.describe(a))")
            }
        }
    }

    private static func describe(_ a: CMMotionActivity) -> String {
        let kinds = [(a.walking, "walking"), (a.running, "running"), (a.cycling, "cycling"),
                     (a.automotive, "automotive"), (a.stationary, "stationary")]
        let names = kinds.filter { $0.0 }.map { $0.1 }
        return "activity=\(names.isEmpty ? "unknown" : names.joined(separator: "+")) confidence=\(a.confidence.rawValue)"
    }

    static let watchdogId = "probe-stopped"
    static let watchdogAfter: TimeInterval = 300

    private func applyAuthorization(_ s: CLAuthorizationStatus) {
        IosHandoffProbe.shared.noteRun(line: "LOCATION-AUTH \(ProbeRunner.token(s))")
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

    private static func token(_ s: CLAuthorizationStatus) -> String {
        switch s {
        case .authorizedAlways: return "always"
        case .authorizedWhenInUse: return "when-in-use"
        case .notDetermined: return "not-determined"
        case .denied: return "denied"
        case .restricted: return "restricted"
        @unknown default: return "unknown"
        }
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
    @ObservedObject private var runner = ProbeRunner.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("QUIC handoff probe").font(.title2).bold()

            // The walk's own state, then one line per lane (`v4: …`, `v6: …`).
            Text(runner.status)
                .font(.system(.body, design: .monospaced))
                .fixedSize(horizontal: false, vertical: true)

            Divider()

            Group {
                Text(runner.server.summary)
                Text("location: \(runner.authorization)")
                Text("location updates: \(runner.locUpdates)")
                    .foregroundStyle(runner.locUpdates > 0 ? .primary : .secondary)
            }
            .font(.system(.footnote, design: .monospaced))

            if case .missing = runner.server {
                Text("No server configured: nothing to connect to.")
                    .font(.footnote).foregroundStyle(.red)
            } else if !runner.started {
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
