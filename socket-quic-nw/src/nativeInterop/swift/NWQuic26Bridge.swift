import Foundation

// STEP 1 (build-plumbing de-risk only): the most trivial @objc/NSObject surface that proves the
// swiftc -> static lib -> generated -Swift.h -> cinterop -> Kotlin/Native call chain works.
// No Network.framework / OS-26 API yet — that is step 2 (see OS26_SWIFT_BRIDGE_PLAN.md).
@objc public final class NWQuic26Bridge: NSObject {
    // Static scalar marshaling check.
    @objc public static func ping(_ value: Int32) -> Int32 {
        return value + 26
    }

    // Instance + ObjC object (NSString) marshaling check.
    @objc public func greeting() -> NSString {
        return "nwquic26-bridge-ok" as NSString
    }
}
