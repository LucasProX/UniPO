// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "UniPOUI",
    platforms: [.iOS(.v17)],
    products: [
        .library(name: "UniPOUI", targets: ["UniPOUI"])
    ],
    targets: [
        .target(name: "UniPOUI")
    ]
)
