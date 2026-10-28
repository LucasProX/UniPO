import Foundation

/// U05：私信里的时间分隔。和上一条隔了 5 分钟以上（或是第一条）才在中间显示一次时间，
/// 时间按离现在多远写成「14:05」「昨天 14:05」「10月21日 14:05」「2025年10月21日 14:05」。
public enum MessageTimeline {
    public static let gap: TimeInterval = 5 * 60

    public static func showsTime(previous: String?, current: String?) -> Bool {
        guard let now = ActivityTime.parse(current) else { return false }
        guard let before = ActivityTime.parse(previous) else { return true }
        return now.timeIntervalSince(before) >= gap
    }

    public static func label(_ raw: String?, now: Date = Date()) -> String {
        guard let date = ActivityTime.parse(raw) else { return "" }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = ActivityTime.zone
        let time = format("HH:mm", date)
        if calendar.isDate(date, inSameDayAs: now) { return time }
        if let yesterday = calendar.date(byAdding: .day, value: -1, to: now), calendar.isDate(date, inSameDayAs: yesterday) {
            return "昨天 \(time)"
        }
        if calendar.component(.year, from: date) == calendar.component(.year, from: now) {
            return format("M月d日 HH:mm", date)
        }
        return format("yyyy年M月d日 HH:mm", date)
    }

    private static func format(_ pattern: String, _ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.timeZone = ActivityTime.zone
        formatter.dateFormat = pattern
        return formatter.string(from: date)
    }
}
