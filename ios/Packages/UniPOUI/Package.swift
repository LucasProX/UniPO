// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "UniPOUI",
    platforms: [.iOS(.v17)],
    products: [
        .library(name: "UniPOUI", targets: ["UniPOUI"])
    ],
    dependencies: [
        .package(path: "../UniPOCore")
    ],
    targets: [
        .target(name: "UniPOUI", dependencies: [.product(name: "UniPOCore", package: "UniPOCore")])
    ]
)
