// swift-tools-version:5.9
// The app's Foundation-only logic (Hadaq/Core) as a package, so it can be unit-tested without a phone:
//   cd apps/ios && swift test
// The app target in Hadaq.xcodeproj compiles the same files directly; this package is only for the tests.
import PackageDescription

let package = Package(
    name: "HadaqCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "HadaqCore", targets: ["HadaqCore"])],
    targets: [
        .target(name: "HadaqCore", path: "Hadaq/Core"),
        .testTarget(name: "HadaqCoreTests", dependencies: ["HadaqCore"], path: "Tests/HadaqCoreTests"),
    ]
)
