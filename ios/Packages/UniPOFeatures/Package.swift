// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "UniPOFeatures",
    platforms: [.iOS(.v17)],
    products: [
        .library(name: "UniPOFeatures", targets: ["UniPOFeatures"])
    ],
    dependencies: [
        .package(path: "../UniPOCore"),
        .package(path: "../UniPOUI")
    ],
    targets: [
        .target(
            name: "UniPOFeatures",
            dependencies: [
                .product(name: "UniPOCore", package: "UniPOCore"),
                .product(name: "UniPOUI", package: "UniPOUI")
            ]
        )
    ]
)
