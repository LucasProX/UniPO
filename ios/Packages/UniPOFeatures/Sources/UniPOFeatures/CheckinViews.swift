import SwiftUI
import UIKit
import VisionKit
import CoreImage.CIFilterBuiltins
import UniPOCore
import UniPOUI

// MARK: - 参与者签到

struct CheckinTarget: Identifiable {
    let activityID: Int64
    var token: String?
    var id: Int64 { activityID }
}

/// 签到：输入现场 4 位签到码，或扫描现场二维码。从系统相机打开的链接会带 token 直接提交。
struct CheckinSheet: View {
    @Environment(AuthStore.self) private var auth
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let target: CheckinTarget
    var onDone: (ActivityView) -> Void = { _ in }

    @State private var code = ""
    @State private var scanning = false
    @State private var isSubmitting = false
    @State private var errorMessage: String?
    @State private var result: ActivityCheckinResult?
    @State private var composing = false
    @FocusState private var codeFocused: Bool

    var body: some View {
        NavigationStack {
            Group {
                if let result { success(result) } else { form }
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .background(UniColor.paper)
            .navigationTitle("活动签到")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button(result == nil ? "取消" : "完成") { dismiss() } }
            }
            .task {
                if let token = target.token, result == nil { await submit(token: token, code: nil) }
                else { codeFocused = true }
            }
            .sheet(isPresented: $scanning) {
                QRScannerSheet { payload in
                    scanning = false
                    guard let link = CheckinLink(string: payload), link.activityID == target.activityID else {
                        errorMessage = "这不是本活动的签到码"
                        return
                    }
                    Task { await submit(token: link.token, code: nil) }
                }
            }
        }
    }

    private var form: some View {
        VStack(spacing: 22) {
            VStack(spacing: 6) {
                Text("输入现场签到码").uniFont(22, weight: .heavy).foregroundStyle(UniColor.ink)
                Text("签到码由组织者在现场公布，也可以直接扫现场的二维码")
                    .uniFont(13).foregroundStyle(UniColor.secondary).multilineTextAlignment(.center)
            }
            TextField("", text: $code, prompt: Text("4 位数字"))
                .keyboardType(.numberPad)
                .textContentType(.oneTimeCode)
                .uniFont(34, weight: .bold, design: .monospaced)
                .multilineTextAlignment(.center)
                .frame(height: 72)
                .background(Color.white, in: RoundedRectangle(cornerRadius: UniRadius.card, style: .continuous))
                .focused($codeFocused)
                .onChange(of: code) { _, value in
                    let digits = String(value.filter(\.isNumber).prefix(4))
                    if digits != value { code = digits }
                    errorMessage = nil
                    if digits.count == 4 { Task { await submit(token: nil, code: digits) } }
                }
                .accessibilityLabel("签到码")
            if let errorMessage {
                Text(errorMessage).uniFont(13, weight: .semibold).foregroundStyle(UniColor.danger).multilineTextAlignment(.center)
            }
            if isSubmitting { ProgressView() }
            if QRScannerSheet.isAvailable {
                Button { scanning = true } label: {
                    Label("扫码签到", systemImage: "qrcode.viewfinder")
                        .uniFont(16, weight: .semibold)
                        .frame(maxWidth: .infinity, minHeight: 50)
                }
                .buttonStyle(UniPrimaryButtonStyle())
                .disabled(isSubmitting)
            }
        }
    }

    private func success(_ result: ActivityCheckinResult) -> some View {
        VStack(spacing: 18) {
            Image(systemName: "checkmark.seal.fill")
                .uniFont(64)
                .foregroundStyle(UniColor.blue)
                .symbolEffectIfAvailable(reduceMotion: reduceMotion)
            Text(result.alreadyCheckedIn ? "你已经签到过了" : "签到成功")
                .uniFont(24, weight: .heavy).foregroundStyle(UniColor.ink)
            Text(result.activity.title).uniFont(14).foregroundStyle(UniColor.secondary)
            if result.rewardXp > 0 || result.rewardCoins > 0 {
                HStack(spacing: 12) {
                    if result.rewardXp > 0 { reward("+\(result.rewardXp)", "经验", "bolt.fill") }
                    if result.rewardCoins > 0 { reward("+\(result.rewardCoins)", "金币", "circle.hexagongrid.fill") }
                }
                Text(result.alreadyCheckedIn ? "奖励已在首次签到时发放" : "奖励已到账")
                    .uniFont(12, weight: .semibold).foregroundStyle(UniColor.tertiary)
            }
            Button { composing = true } label: {
                Label("发帖记录现场", systemImage: "square.and.pencil")
                    .uniFont(15, weight: .semibold)
                    .frame(maxWidth: .infinity, minHeight: 48)
            }
            .buttonStyle(.bordered)
            .tint(UniColor.blue)
            .padding(.top, 8)
        }
        .padding(.top, 40)
        .sheet(isPresented: $composing) {
            ComposeSheet(initialLink: .activity(PostLinkRef(id: result.activity.id, title: result.activity.title)))
                .environment(auth)
        }
    }

    private func reward(_ value: String, _ unit: String, _ icon: String) -> some View {
        VStack(spacing: 4) {
            Image(systemName: icon).foregroundStyle(UniColor.gold)
            Text(value).uniFont(26, weight: .heavy).foregroundStyle(UniColor.ink)
            Text(unit).uniFont(11, weight: .semibold).foregroundStyle(UniColor.secondary)
        }
        .frame(width: 110, height: 110)
        .background(Color.white, in: RoundedRectangle(cornerRadius: UniRadius.card, style: .continuous))
    }

    private func submit(token: String?, code: String?) async {
        guard auth.isAuthenticated else { auth.requireLogin(); return }
        guard !isSubmitting else { return }
        isSubmitting = true
        errorMessage = nil
        defer { isSubmitting = false }
        do {
            let value = try await APIClient.shared.checkin(activityID: target.activityID, token: token, code: code)
            result = value
            onDone(value.activity)
            UINotificationFeedbackGenerator().notificationOccurred(.success)
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "签到失败，请重试"
            if code != nil { self.code = "" }
        }
    }
}

private extension View {
    @ViewBuilder
    func symbolEffectIfAvailable(reduceMotion: Bool) -> some View {
        if reduceMotion { self } else { self.symbolEffect(.bounce, value: true) }
    }
}

// MARK: - 扫码

/// VisionKit 扫码；不支持的设备（含模拟器）隐藏入口，只保留签到码。
struct QRScannerSheet: UIViewControllerRepresentable {
    let onFound: (String) -> Void

    static var isAvailable: Bool { DataScannerViewController.isSupported && DataScannerViewController.isAvailable }

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let controller = DataScannerViewController(recognizedDataTypes: [.barcode(symbologies: [.qr])],
                                                   qualityLevel: .balanced, isHighlightingEnabled: true)
        controller.delegate = context.coordinator
        try? controller.startScanning()
        return controller
    }

    func updateUIViewController(_ controller: DataScannerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onFound: onFound) }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let onFound: (String) -> Void
        private var delivered = false

        init(onFound: @escaping (String) -> Void) { self.onFound = onFound }

        func dataScanner(_ scanner: DataScannerViewController, didAdd items: [RecognizedItem], allItems: [RecognizedItem]) {
            guard !delivered else { return }
            for item in items {
                if case .barcode(let barcode) = item, let payload = barcode.payloadStringValue {
                    delivered = true
                    scanner.stopScanning()
                    onFound(payload)
                    return
                }
            }
        }
    }
}

// MARK: - 组织者签到码

/// 现场大屏：二维码每 30 秒轮换，4 位签到码备用，实时显示签到人数。屏幕常亮。
struct OrganizerCheckinView: View {
    @Environment(\.dismiss) private var dismiss
    let activity: ActivityView

    @State private var state: ActivityOrganizerCheckin?
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Text(activity.title).uniFont(20, weight: .bold).foregroundStyle(UniColor.ink)
                    .multilineTextAlignment(.center)
                if let state {
                    if state.windowOpen {
                        if let image = qrImage(state.qrPayload) {
                            Image(uiImage: image)
                                .interpolation(.none)
                                .resizable()
                                .scaledToFit()
                                .frame(maxWidth: 260)
                                .padding(16)
                                .background(Color.white, in: RoundedRectangle(cornerRadius: UniRadius.card, style: .continuous))
                                .accessibilityLabel("签到二维码，约 30 秒刷新一次")
                        }
                        VStack(spacing: 4) {
                            Text("签到码").uniFont(12, weight: .semibold).foregroundStyle(UniColor.secondary)
                            Text(state.code)
                                .uniFont(44, weight: .heavy, design: .monospaced)
                                .tracking(10)
                                .foregroundStyle(UniColor.ink)
                        }
                    } else {
                        VStack(spacing: 8) {
                            Image(systemName: "clock").uniFont(36).foregroundStyle(UniColor.tertiary)
                            Text("签到尚未开放或已结束").uniFont(15).foregroundStyle(UniColor.secondary)
                            if let opens = state.windowOpensAt {
                                Text("开放时间：\(ActivityTime.short(opens)) 起").uniFont(12, weight: .semibold).foregroundStyle(UniColor.tertiary)
                            }
                        }
                        .padding(.vertical, 40)
                    }
                    HStack(spacing: 6) {
                        Image(systemName: "person.crop.circle.badge.checkmark").foregroundStyle(UniColor.blue)
                        Text("已签到 \(state.checkedInCount) / \(state.registeredCount)")
                            .uniFont(17, weight: .semibold).foregroundStyle(UniColor.ink)
                    }
                } else if let errorMessage {
                    Text(errorMessage).uniFont(14).foregroundStyle(UniColor.danger)
                } else {
                    ProgressView().padding(.top, 60)
                }
                Spacer()
            }
            .padding(24)
            .frame(maxWidth: .infinity)
            .background(UniColor.paper)
            .navigationTitle("签到码")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("关闭") { dismiss() } } }
            .task { await refreshLoop() }
            .onAppear { UIApplication.shared.isIdleTimerDisabled = true }
            .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
        }
    }

    private func refreshLoop() async {
        while !Task.isCancelled {
            do {
                let value = try await APIClient.shared.organizerCheckin(activityID: activity.id)
                state = value
                errorMessage = nil
                // 在时间片切换后稍等一秒再取，避免拿到即将过期的码；人数每次刷新顺带更新。
                let wait = UInt64(max(1, min(value.refreshAfterSeconds, 30)) + 1)
                try? await Task.sleep(nanoseconds: wait * 1_000_000_000)
            } catch {
                errorMessage = (error as? LocalizedError)?.errorDescription ?? "签到码获取失败"
                try? await Task.sleep(nanoseconds: 5_000_000_000)
            }
        }
    }

    private func qrImage(_ payload: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(payload.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10)),
              let cgImage = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}

// MARK: - 金币

struct WalletScreen: View {
    @Environment(AuthStore.self) private var auth
    @Environment(\.dismiss) private var dismiss
    @State private var wallet: WalletView?
    @State private var entries: [WalletLedgerEntry] = []
    @State private var errorMessage: String?
    @State private var loadingMore = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("金币余额").uniFont(12, weight: .semibold).foregroundStyle(UniColor.secondary)
                        Text("\(wallet?.balance ?? 0)")
                            .uniFont(40, weight: .heavy).monospacedDigit().foregroundStyle(UniColor.ink)
                        if let wallet {
                            Text("今日已获得 \(wallet.earnedToday) / \(wallet.dailyCap)")
                                .uniFont(12, weight: .semibold).foregroundStyle(UniColor.tertiary)
                        }
                        Text("参加认证组织者发布的活动并签到即可获得金币，用途即将开放。")
                            .uniFont(12).foregroundStyle(UniColor.secondary)
                    }
                    .padding(.vertical, 6)
                }
                Section("收支明细") {
                    if entries.isEmpty {
                        Text(errorMessage ?? (wallet == nil ? "加载中…" : "还没有金币记录"))
                            .uniFont(13).foregroundStyle(UniColor.secondary)
                    }
                    ForEach(entries) { entry in
                        HStack {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(entry.reason).uniFont(14).foregroundStyle(UniColor.ink).lineLimit(1)
                                Text(RelativeTime.text(from: entry.createdAt)).uniFont(11, weight: .semibold).foregroundStyle(UniColor.tertiary)
                            }
                            Spacer()
                            Text(entry.amount > 0 ? "+\(entry.amount)" : "\(entry.amount)")
                                .uniFont(16, weight: .bold).monospacedDigit()
                                .foregroundStyle(entry.amount > 0 ? UniColor.gold : UniColor.secondary)
                        }
                        .onAppear { if entry.id == entries.last?.id { Task { await loadMore() } } }
                    }
                }
            }
            .navigationTitle("金币")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("关闭") { dismiss() } } }
            .refreshable { await load() }
            .task { await load() }
        }
    }

    private func load() async {
        do {
            let value = try await APIClient.shared.wallet()
            wallet = value
            entries = value.entries
            errorMessage = nil
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "金币记录加载失败"
        }
    }

    private func loadMore() async {
        guard let cursor = wallet?.nextCursor, wallet?.hasMore == true, !loadingMore else { return }
        loadingMore = true
        defer { loadingMore = false }
        if let value = try? await APIClient.shared.wallet(cursor: cursor) {
            wallet = value
            let ids = Set(entries.map(\.id))
            entries += value.entries.filter { !ids.contains($0.id) }
        }
    }
}
