package com.biecuoguo.dto;

import com.biecuoguo.domain.Reminder;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public final class ReminderDtos {
    private ReminderDtos() {}

    public record ReminderRequest(@NotNull LocalDateTime remindAt, String channel) {}

    public record ReminderView(
            Long id,
            Long noticeId,
            String noticeTitle,
            LocalDateTime deadlineAt,
            LocalDateTime remindAt,
            String status,
            String channel
    ) {
        public static ReminderView from(Reminder reminder, String title, LocalDateTime deadlineAt) {
            return new ReminderView(
                    reminder.getId(),
                    reminder.getNoticeId(),
                    title,
                    deadlineAt,
                    reminder.getRemindAt(),
                    reminder.getStatus(),
                    reminder.getChannel()
            );
        }
    }
}
