package com.biecuoguo.achievement.internal;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.biecuoguo.activity.api.ActivityParticipationApi;
import com.biecuoguo.domain.User;
import com.biecuoguo.mapper.UserMapper;
import com.biecuoguo.post.api.PostQueryApi;
import com.biecuoguo.service.ExperienceService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AchievementTitleTest {
    private final BadgeMapper badges = mock(BadgeMapper.class);
    private final UserBadgeMapper userBadges = mock(UserBadgeMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final ActivityParticipationApi participation = mock(ActivityParticipationApi.class);
    private final AchievementTitleMapper titles = mock(AchievementTitleMapper.class);
    private AchievementService service;

    @BeforeAll
    static void lambdaMetadata() {
        for (Class<?> type : new Class<?>[]{AchievementStage.class, AchievementTask.class, AchievementProgress.class,
                Badge.class, UserBadge.class}) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "title-tests"), type);
        }
    }

    private static Badge badge(String code, String rarity) {
        Badge badge = new Badge();
        badge.setCode(code);
        badge.setTitle(code);
        badge.setRarity(rarity);
        badge.setIcon("star");
        return badge;
    }

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setId(5L);
        when(users.selectById(5L)).thenReturn(user);
        when(badges.selectList(any())).thenReturn(List.of(badge("cet4-signup", "RARE"), badge("activity-1", "COMMON")));
        UserBadge owned = new UserBadge();
        owned.setBadgeCode("cet4-signup");
        owned.setEarnedAt(LocalDateTime.of(2026, 10, 1, 9, 0));
        when(userBadges.selectList(any())).thenReturn(List.of(owned));
        when(participation.attendance(eq(5L), anyInt())).thenReturn(new ActivityParticipationApi.AttendanceSummary(0, List.of()));
        service = new AchievementService(mock(AchievementStageMapper.class), mock(AchievementTaskMapper.class),
                mock(AchievementProgressMapper.class), badges, userBadges, users, mock(ExperienceService.class),
                participation, mock(TaskGuidePostMapper.class), mock(PostQueryApi.class));
        service.setTitleMapper(titles);
    }

    @Test
    void onlyEarnedBadgesCanBecomeTheTitle() {
        assertThrows(IllegalArgumentException.class, () -> service.setTitle(5L, "activity-1"));
        assertThrows(IllegalArgumentException.class, () -> service.setTitle(5L, "no-such-badge"));
        verify(titles, never()).upsert(anyLong(), any(), any());
        service.setTitle(5L, " cet4-signup ");
        verify(titles).upsert(eq(5L), eq("cet4-signup"), any());
    }

    @Test
    void blankClearsTheTitle() {
        service.setTitle(5L, "  ");
        verify(titles).deleteById(5L);
    }

    @Test
    void aTitleWhoseBadgeWasLostIsNotShown() {
        AchievementTitle chosen = new AchievementTitle();
        chosen.setUserId(5L);
        chosen.setBadgeCode("activity-1");
        when(titles.selectById(5L)).thenReturn(chosen);
        assertNull(service.view(5L).title());
        chosen.setBadgeCode("cet4-signup");
        assertEquals("cet4-signup", service.view(5L).title().code());
    }
}
