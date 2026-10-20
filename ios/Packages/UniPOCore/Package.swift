// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "UniPOCore",
    platforms: [.iOS(.v17)],
    products: [
        .library(name: "UniPOCore", targets: ["UniPOCore"])
    ],
    targets: [
        .target(name: "UniPOCore")
    ]
)
