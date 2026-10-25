package com.biecuoguo.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.biecuoguo.domain.User;
import com.biecuoguo.domain.UserBlock;
import com.biecuoguo.domain.UserFollow;
import com.biecuoguo.domain.UserPreference;
import com.biecuoguo.dto.UserDtos;
import com.biecuoguo.mapper.DeviceTokenMapper;
import com.biecuoguo.mapper.UserBlockMapper;
import com.biecuoguo.mapper.UserCheckInMapper;
import com.biecuoguo.mapper.UserFollowMapper;
import com.biecuoguo.mapper.UserMapper;
import com.biecuoguo.mapper.UserPreferenceMapper;
import com.biecuoguo.post.api.PostQueryApi;
import com.biecuoguo.security.SessionService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class UserProfileServiceTest {
    private static final long OWNER = 42L;
    private static final long VIEWER = 9L;
    private final UserMapper users = mock(UserMapper.class);
    private final UserPreferenceMapper preferences = mock(UserPreferenceMapper.class);
    private final UserFollowMapper follows = mock(UserFollowMapper.class);
    private final UserBlockMapper blocks = mock(UserBlockMapper.class);
    private final PresenceService presence = mock(PresenceService.class);
    private final PostQueryApi posts = mock(PostQueryApi.class);
    private final UserService service = new UserService(users, preferences, follows, blocks,
            mock(UserCheckInMapper.class), mock(DeviceTokenMapper.class), presence,
            mock(PasswordEncoder.class), mock(SessionService.class), posts, mock(ExperienceService.class),
            mock(com.biecuoguo.mapper.SchoolMapper.class), mock(com.biecuoguo.permission.api.PermissionApi.class),
            new com.biecuoguo.moderation.api.ContentFilterApi() {
                    public Verdict check(String... texts) { return Verdict.ALLOW; }
                    public boolean reviewAllPosts() { return false; }
                });
    private User owner;
    private UserPreference preference;
    private boolean viewerFollowsOwner;
    private boolean ownerFollowsViewer;

    @BeforeAll
    static void initializeLambdaMetadata() {
        for (Class<?> type : new Class<?>[]{User.class, UserPreference.class, UserFollow.class, UserBlock.class}) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "profile-tests"), type);
        }
    }

    @BeforeEach
    void setUp() {
        owner = new User();
        owner.setId(OWNER);
        owner.setPublicUid("profile-owner");
        owner.setEmail("private-account@unipo.invalid");
        owner.setPhone("13900003333");
        owner.setNickname("同学");
        owner.setRole("USER");
        owner.setStatus("active");
        owner.setBackgroundUrl("/api/media/7/content?v=cover.jpg");
        owner.setGender("female");
        owner.setBirthYear(2006);
        owner.setGrade("大二");
        owner.setShowGender(false);
        owner.setShowAge(false);
        owner.setShowGrade(false);
        owner.setLastLoginAt(LocalDateTime.of(2026, 10, 21, 12, 0));
        owner.setOnboardingCompleted(true);
        preference = new UserPreference();
        preference.setGoalTags("private-goals");
        preference.setInterestCategories("private-interests");
        preference.setEmailNotificationEnabled(true);
        preference.setBrowserNotificationEnabled(true);
        preference.setRemindBeforeHours(12);
        when(users.selectOne(any())).thenReturn(owner);
        when(users.selectById(OWNER)).thenReturn(owner);
        when(users.lockById(OWNER)).thenReturn(owner);
        when(preferences.selectOne(any())).thenReturn(preference);
        when(presence.isOnline(anyLong())).thenReturn(true);
        when(posts.statsForUser(OWNER)).thenReturn(new PostQueryApi.UserPostStats(10, 4, 2, 3, 1));
        when(follows.selectCount(any())).thenAnswer(invocation -> {
            LambdaQueryWrapper<UserFollow> query = invocation.getArgument(0);
            query.getSqlSegment();
            Map<String, Object> values = query.getParamNameValuePairs();
            if (values.size() != 2) return 0L;
            long follower = ((Number) values.get("MPGENVAL1")).longValue();
            long following = ((Number) values.get("MPGENVAL2")).longValue();
            if (follower == VIEWER && following == OWNER) return viewerFollowsOwner ? 1L : 0L;
            if (follower == OWNER && following == VIEWER) return ownerFollowsViewer ? 1L : 0L;
            fail("Unexpected follow relationship query");
            return 0L;
        });
    }

    @Test
    void anonymousProfileHasNoRelationshipAndDoesNotFetchPrivatePreferences() {
        UserDtos.PublicProfile result = service.publicProfile(owner.getPublicUid(), null);

        assertFalse(result.following());
        assertFalse(result.followedBy());
        assertFalse(result.mine());
        verifyNoInteractions(preferences);
        // Only bounded aggregate queries are allowed, never a viewer relationship for anonymous requests.
        verify(follows, times(2)).selectCount(any());
    }

    @Test
    void reverseOnlyFollowDoesNotBecomeAnOutgoingFollow() {
        ownerFollowsViewer = true;

        UserDtos.PublicProfile result = service.publicProfile(owner.getPublicUid(), VIEWER);

        assertFalse(result.following());
        assertTrue(result.followedBy());
    }

    @Test
    void outgoingOnlyFollowDoesNotBecomeMutual() {
        viewerFollowsOwner = true;

        UserDtos.PublicProfile result = service.publicProfile(owner.getPublicUid(), VIEWER);

        assertTrue(result.following());
        assertFalse(result.followedBy());
    }

    @Test
    void mutualFollowReturnsBothDirections() {
        viewerFollowsOwner = true;
        ownerFollowsViewer = true;

        UserDtos.PublicProfile result = service.publicProfile(owner.getPublicUid(), VIEWER);

        assertTrue(result.following());
        assertTrue(result.followedBy());
    }

    @Test
    void ownProfileKeepsPrivateDataAndNeverFollowsItself() {
        UserDtos.PublicProfile result = service.publicProfile(owner.getPublicUid(), OWNER);

        assertTrue(result.mine());
        assertFalse(result.following());
        assertFalse(result.followedBy());
        assertEquals(owner.getEmail(), result.profile().email());
        assertEquals(preference.getGoalTags(), result.profile().goalTags());
        assertEquals(owner.getGender(), result.profile().gender());
        assertEquals(owner.getBirthYear(), result.profile().birthYear());
        assertEquals(owner.getGrade(), result.profile().grade());
        assertNotNull(result.profile().lastLoginAt());
    }

    @Test
    void visitorsSeeBackgroundWithoutAccountPreferencesOrHiddenDemographics() {
        var profile = service.publicProfile(owner.getPublicUid(), VIEWER).profile();

        assertEquals(owner.getBackgroundUrl(), profile.backgroundUrl());
        assertNull(profile.email());
        assertNull(profile.goalTags());
        assertNull(profile.interestCategories());
        assertNull(profile.emailNotificationEnabled());
        assertNull(profile.browserNotificationEnabled());
        assertNull(profile.remindBeforeHours());
        assertNull(profile.lastLoginAt());
        assertNull(profile.onboardingCompleted());
        assertNull(profile.gender());
        assertNull(profile.birthYear());
        assertNull(profile.grade());
    }

    @Test
    void explicitlyPublicDemographicsRemainVisible() {
        owner.setShowAge(true);
        owner.setShowGender(true);
        owner.setShowGrade(true);

        var profile = service.publicProfile(owner.getPublicUid(), null).profile();

        assertEquals(owner.getGender(), profile.gender());
        assertEquals(owner.getBirthYear(), profile.birthYear());
        assertEquals(owner.getGrade(), profile.grade());
    }

    @Test
    void publicProfileDoesNotExposeInactiveAccounts() {
        owner.setStatus("deleted");

        assertThrows(NoSuchElementException.class, () -> service.publicProfile(owner.getPublicUid(), null));
        verifyNoInteractions(preferences, follows, blocks, posts);
    }

    @Test
    void backgroundUpdateTargetsAuthenticatedUserAndIsVisibleToVisitors() {
        var result = service.updateProfile(OWNER, backgroundRequest(" /api/media/8/content?v=new.jpg "));

        assertEquals("/api/media/8/content?v=new.jpg", result.backgroundUrl());
        verify(users).lockById(OWNER);
        verify(users).updateById(owner);
        verify(users, never()).selectById(VIEWER);
        assertEquals(result.backgroundUrl(), service.publicProfile(owner.getPublicUid(), null).profile().backgroundUrl());
    }

    @Test
    void omittedBackgroundDoesNotReplaceExistingPicture() {
        String original = owner.getBackgroundUrl();

        var result = service.updateProfile(OWNER, backgroundRequest(null));

        assertEquals(original, result.backgroundUrl());
    }

    @Test
    void emptyBackgroundRestoresDefaultAndRemainsEmptyForVisitors() {
        var result = service.updateProfile(OWNER, backgroundRequest("   "));

        assertNull(result.backgroundUrl());
        assertNull(owner.getBackgroundUrl());
        assertNull(service.publicProfile(owner.getPublicUid(), null).profile().backgroundUrl());
    }

    @Test
    void invalidBackgroundSchemesAndMalformedMediaPathsAreRejectedBeforeWriting() {
        for (String url : new String[]{"javascript:alert(1)", "data:image/png;base64,AA==",
                "file:///private/image.jpg", "//images.unipo.invalid/image.jpg",
                "/unrelated/image.jpg", "https://person@images.unipo.invalid/image.jpg",
                "/api/media/7/content#fragment", "x".repeat(501)}) {
            assertThrows(IllegalArgumentException.class, () -> service.updateProfile(OWNER, backgroundRequest(url)));
        }
        verify(users, never()).updateById(any(User.class));
    }

    @Test
    void inactiveOrMissingUsersCannotUpdateBackground() {
        owner.setStatus("disabled");
        assertThrows(AccessDeniedException.class, () -> service.updateProfile(OWNER, backgroundRequest("/api/media/8/content")));
        assertThrows(NoSuchElementException.class, () -> service.updateProfile(404L, backgroundRequest("/api/media/8/content")));
        verify(users, never()).updateById(any(User.class));
    }

    @Test
    void editProfileUpdatesOnboardingFieldsAndVisibility() {
        service.updateProfile(OWNER, new UserDtos.UpdateProfileRequest(" 新名字 ", null, null, null, null, null, null, null,
                "  新的简介 ", null, "male", 2005, "天秤", "enfp", true, true, false));
        assertEquals("新名字", owner.getNickname());
        assertEquals("新的简介", owner.getBio());
        assertEquals("male", owner.getGender());
        assertEquals(2005, owner.getBirthYear());
        assertEquals("天秤", owner.getZodiac());
        assertEquals("ENFP", owner.getMbti());
        assertTrue(owner.getShowGender());
        assertTrue(owner.getShowAge());
        assertFalse(owner.getShowGrade());
        verify(users).updateById(owner);
    }

    @Test
    void emptyZodiacAndMbtiClearThemWhileNullLeavesFieldsAlone() {
        owner.setZodiac("白羊");
        owner.setMbti("INTJ");
        service.updateProfile(OWNER, profileRequest(null, null, "", "", null));
        assertEquals("", owner.getZodiac());
        assertEquals("", owner.getMbti());
        assertEquals("female", owner.getGender());
        assertEquals(2006, owner.getBirthYear());
        assertFalse(owner.getShowGender());
    }

    @Test
    void invalidProfileChoicesAreRejectedBeforeWriting() {
        assertThrows(IllegalArgumentException.class, () -> service.updateProfile(OWNER, profileRequest("robot", null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> service.updateProfile(OWNER, profileRequest(null, 1900, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> service.updateProfile(OWNER, profileRequest(null, LocalDateTime.now().getYear(), null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> service.updateProfile(OWNER, profileRequest(null, null, "蛇夫", null, null)));
        assertThrows(IllegalArgumentException.class, () -> service.updateProfile(OWNER, profileRequest(null, null, null, "ABCD", null)));
        verify(users, never()).updateById(any(User.class));
    }

    private UserDtos.UpdateProfileRequest profileRequest(String gender, Integer birthYear, String zodiac, String mbti, Boolean showGender) {
        return new UserDtos.UpdateProfileRequest(null, null, null, null, null, null, null, null, null, null,
                gender, birthYear, zodiac, mbti, showGender, null, null);
    }

    private UserDtos.UpdateProfileRequest backgroundRequest(String background) {
        return new UserDtos.UpdateProfileRequest(null, null, null, null, null, null, null, null, null, background,
                null, null, null, null, null, null, null);
    }
}
