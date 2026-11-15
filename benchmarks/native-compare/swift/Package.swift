// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "native-compare-swift",
    platforms: [.macOS(.v13)],
    dependencies: [.package(url: "https://github.com/apple/swift-nio.git", exact: "2.99.0")],
    targets: [
        .target(name: "Rec"),
        .executableTarget(name: "nwtcp", dependencies: ["Rec"]),
        .executableTarget(
            name: "niotcp",
            dependencies: ["Rec", .product(name: "NIOCore", package: "swift-nio"), .product(name: "NIOPosix", package: "swift-nio")]
        ),
    ]
)
