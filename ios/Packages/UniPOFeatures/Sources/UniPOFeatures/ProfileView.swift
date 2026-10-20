import SwiftUI
import UniPOCore
import UniPOUI

struct ProfileView: View {
    @Environment(AuthStore.self) private var auth
    let onRequestAuth: () -> Void

    @State private var showDeleteConfirm = false

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                if let profile = auth.profile {
                    signedIn(profile)
                } else {
                    signedOut
                }
            }
            .padding(.horizontal, 18)
            .padding(.top, 10)
            .padding(.bottom, 120)
        }
        .scrollIndicators(.hidden)
    }

    private func signedIn(_ profile: UserProfile) -> some View {
        VStack(spacing: 16) {
            VStack(alignment: .leading, spacing: 18) {
                HStack(spacing: 14) {
                    UniAvatar(url: profile.avatarUrl, name: profile.nickname, size: 66)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(profile.nickname)
                            .font(.system(size: 24, weight: .heavy))
                            .foregroundStyle(.white)
                        Text("UID \(profile.publicUid)")
                            .font(.uniLabel(11))
                            .foregroundStyle(.white.opacity(0.55))
                    }
                    Spacer()
                }

                HStack(spacing: 20) {
                    stat("\(profile.level)", "等级")
                    stat("\(profile.xp ?? 0)", "经验")
                    stat(profile.grade ?? "—", "年级")
                }
            }
            .padding(22)
            .background(
                LinearGradient(colors: [UniColor.ink, Color(hex: 0x16274B)],
                               startPoint: .topLeading, endPoint: .bottomTrailing),
                in: RoundedRectangle(cornerRadius: 30, style: .continuous)
            )

            VStack(spacing: 0) {
                row("person.2.fill", "关注与粉丝", "查看关系")
                Divider().padding(.leading, 48)
                row("rosette", "我的成就", "徽章与进度")
                Divider().padding(.leading, 48)
                row("gearshape.fill", "设置", "账号与隐私")
                Divider().padding(.leading, 48)
                Button {
                    showDeleteConfirm = true
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "trash")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(UniColor.danger)
                            .frame(width: 24)
                        Text("注销账号")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(UniColor.danger)
                        Spacer()
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 16)
                }
                .buttonStyle(.plain)
            }
            .background(Color.white, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .stroke(UniColor.mist.opacity(0.7), lineWidth: 1)
            )

            Button {
                Task { await auth.logout() }
            } label: {
                Text("退出登录")
            }
            .buttonStyle(UniGhostButtonStyle())
        }
        .alert("注销账号", isPresented: $showDeleteConfirm) {
            Button("取消", role: .cancel) {}
            Button("确认注销", role: .destructive) {
                Task { _ = await auth.deleteAccount() }
            }
        } message: {
            Text("注销后账号会被停用、个人资料会被匿名化，且无法恢复。确定继续吗？")
        }
    }

    private var signedOut: some View {
        VStack(spacing: 18) {
            Spacer(minLength: 40)
            ZStack {
                RoundedRectangle(cornerRadius: 26, style: .continuous)
                    .fill(UniColor.ink)
                    .frame(width: 88, height: 88)
                Text("PO")
                    .font(.system(size: 28, weight: .heavy))
                    .foregroundStyle(.white)
            }
            Text("登录 UniPO")
                .font(.system(size: 26, weight: .heavy))
                .foregroundStyle(UniColor.ink)
            Text("同步你的成就、关注和校园动态")
                .font(.uniBody(14))
                .foregroundStyle(UniColor.secondary)
            if let error = auth.errorMessage {
                Text(error)
                    .font(.uniLabel(12))
                    .foregroundStyle(UniColor.danger)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)
            }
            Button {
                onRequestAuth()
            } label: {
                Text("登录 / 注册")
            }
            .buttonStyle(UniPrimaryButtonStyle())
            .padding(.horizontal, 30)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 60)
    }

    private func stat(_ value: String, _ label: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(value)
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(.white)
            Text(label)
                .font(.uniLabel(10))
                .foregroundStyle(.white.opacity(0.5))
        }
    }

    private func row(_ icon: String, _ title: String, _ detail: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(UniColor.blue)
                .frame(width: 24)
            Text(title)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(UniColor.ink)
            Spacer()
            Text(detail)
                .font(.uniLabel(11))
                .foregroundStyle(UniColor.tertiary)
            Image(systemName: "chevron.right")
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(UniColor.tertiary)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 16)
    }
}
