// swift-tools-version:5.9
// MailKit — the portable (Foundation-only) core of the iOS port of "You've got Mail".
//
// A Swift re-implementation of the pure-logic slice of :mail-core (see docs/PORTING_PLAN.md for why
// it is a port and not a Kotlin/Native bridge). Everything outside Net/ builds and tests on Linux;
// Net/ is Apple-only (Network.framework) and is compiled out elsewhere.
import PackageDescription

let package = Package(
    name: "MailKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "MailKit", targets: ["MailKit"]),
    ],
    targets: [
        .target(name: "MailKit", path: "Sources/MailKit"),
        .testTarget(name: "MailKitTests", dependencies: ["MailKit"], path: "Tests/MailKitTests"),
    ]
)
