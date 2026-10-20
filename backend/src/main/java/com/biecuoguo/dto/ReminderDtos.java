package com.biecuoguo.dto;

import com.biecuoguo.domain.Reminder;
import jakarta.validation.constraints.NotNull;

import com.biecuoguo.config.AppTime;

import java.time.OffsetDateTime;

public final class ReminderDtos {
    private ReminderDtos() {}

    public record ReminderRequest(@NotNull OffsetDateTime remindAt, String channel) {}

    public record ReminderView(
            Long id,
            Long noticeId,
            String noticeTitle,
            OffsetDateTime deadlineAt,
            OffsetDateTime remindAt,
            String status,
            String channel
    ) {
        public static ReminderView from(Reminder reminder, String title, OffsetDateTime deadlineAt) {
            return new ReminderView(
                    reminder.getId(),
                    reminder.getNoticeId(),
                    title,
                    deadlineAt,
                    AppTime.toOffset(reminder.getRemindAt()),
                    reminder.getStatus(),
                    reminder.getChannel()
            );
        }
    }
}
