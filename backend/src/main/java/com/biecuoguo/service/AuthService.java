package com.biecuoguo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.biecuoguo.config.AppProperties;
import com.biecuoguo.domain.User;
import com.biecuoguo.domain.UserPreference;
import com.biecuoguo.dto.AuthDtos;
import com.biecuoguo.dto.UserProfile;
import com.biecuoguo.mapper.UserMapper;
import com.biecuoguo.mapper.UserPreferenceMapper;
import com.biecuoguo.security.CurrentUser;
import com.biecuoguo.security.JwtService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class AuthService implements CommandLineRunner {
    private static final String GOOD_LOGIN = "good";
    private static final String GOOD_EMAIL = "good@biecuoguo.local";
    private static final String GOOD_PASSWORD = "good";
    private static final String XIAOBA_LOGIN = "xiaoba";
    private static final String XIAOBA_EMAIL = "school-op@example.com";
    private static final String XIAOBA_PASSWORD = "xiaoba";
    private static final String YUANHUA_LOGIN = "yuanhua";
    private static final String YUANHUA_EMAIL = "college-op@example.com";
    private static final String YUANHUA_PASSWORD = "yuanhua";

    private final AppProperties properties;
    private final UserMapper userMapper;
    private final UserPreferenceMapper preferenceMapper;
    private final VerificationService verificationService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final PresenceService presenceService;

    public AuthService(AppProperties properties, UserMapper userMapper, UserPreferenceMapper preferenceMapper, VerificationService verificationService, PasswordEncoder passwordEncoder, JwtService jwtService, PresenceService presenceService) {
        this.properties = properties;
        this.userMapper = userMapper;
        this.preferenceMapper = preferenceMapper;
        this.verificationService = verificationService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.presenceService = presenceService;
    }

    @Transactional
    public AuthDtos.AuthResponse register(AuthDtos.RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (!request.password().equals(request.confirmPassword())) {
            throw new IllegalArgumentException("两次输入的密码不一致");
        }
        if (findByEmail(email) != null) {
            throw new IllegalArgumentException("该邮箱已注册");
        }
        String nickname = normalizeNickname(request.nickname());
        if (findByNickname(nickname) != null) {
            throw new IllegalArgumentException("该用户名已被占用");
        }
        verificationService.verifyCode(email, "register", request.verificationCode());
        LocalDateTime now = LocalDateTime.now();
        User user = new User();
        user.setEmail(email);
        user.setPublicUid(generatePublicUid());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setNickname(nickname);
        user.setAvatarUrl("https://api.dicebear.com/8.x/initials/svg?seed=" + nickname);
        user.setSchoolId(1L);
        user.setGrade("大二");
        user.setMajor("未设置专业");
        user.setVerifiedStatus("none");
        user.setRole("USER");
        user.setOperatorScope("student");
        user.setLevel(1);
        user.setXp(0);
        user.setLevelTitle("萌新探路员");
        user.setStatus("active");
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        user.setLastLoginAt(now);
        userMapper.insert(user);
        UserPreference preference = createDefaultPreference(user.getId(), now);
        String token = jwtService.createToken(new CurrentUser(user.getId(), user.getEmail(), user.getRole()));
        presenceService.markOnline(user.getId());
        return new AuthDtos.AuthResponse(token, UserProfile.from(user, preference, true));
    }

    public void requestVerificationCode(AuthDtos.VerificationRequest request) {
        String email = normalizeEmail(request.email());
        User user = findByEmail(email);
        if ("register".equals(request.purpose()) && user != null) {
            throw new IllegalArgumentException("该邮箱已注册");
        }
        if ("reset-password".equals(request.purpose()) && user == null) {
            throw new IllegalArgumentException("该邮箱尚未注册");
        }
        verificationService.requestCode(email, request.purpose());
    }

    public void verifyRegistrationCode(AuthDtos.VerifyCodeRequest request) {
        verificationService.verifyCode(request.email(), request.purpose(), request.code());
    }

    public AuthDtos.AvailabilityResponse emailAvailability(String email, String purpose) {
        String normalizedPurpose = purpose == null || purpose.isBlank() ? "register" : purpose.trim();
        User user = findByEmail(email);
        boolean available = "reset-password".equals(normalizedPurpose) ? user != null : user == null;
        return new AuthDtos.AvailabilityResponse(available);
    }

    public AuthDtos.AvailabilityResponse nicknameAvailability(String nickname) {
        String normalized = normalizeNickname(nickname);
        if (normalized.isBlank()) {
            return new AuthDtos.AvailabilityResponse(false);
        }
        User user = findByNickname(normalized);
        return new AuthDtos.AvailabilityResponse(user == null);
    }

    @Transactional
    public void resetPassword(AuthDtos.ResetPasswordRequest request) {
        String email = normalizeEmail(request.email());
        if (!request.password().equals(request.confirmPassword())) {
            throw new IllegalArgumentException("两次输入的密码不一致");
        }
        User user = findByEmail(email);
        if (user == null) {
            throw new IllegalArgumentException("该邮箱尚未注册");
        }
        verificationService.verifyCode(email, "reset-password", request.verificationCode());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
    }

    public AuthDtos.AuthResponse login(AuthDtos.LoginRequest request) {
        User user = findByLogin(request.email());
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("bad credentials");
        }
        if (!"active".equals(user.getStatus())) {
            throw new IllegalArgumentException("账号已被禁用");
        }
        user.setLastLoginAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        UserPreference preference = preferenceFor(user.getId());
        String token = jwtService.createToken(new CurrentUser(user.getId(), user.getEmail(), user.getRole()));
        presenceService.markOnline(user.getId());
        return new AuthDtos.AuthResponse(token, UserProfile.from(user, preference, true));
    }

    public UserProfile me(Long userId) {
        presenceService.markOnline(userId);
        User user = userMapper.selectById(userId);
        return UserProfile.from(user, preferenceFor(userId), presenceService.isOnline(userId));
    }

    public boolean heartbeat(Long userId) {
        presenceService.markOnline(userId);
        return true;
    }

    public boolean logout(Long userId) {
        presenceService.markOffline(userId);
        return true;
    }

    @Override
    @Transactional
    public void run(String... args) {
        ensureGoodUser();
        ensureMockOperator(XIAOBA_EMAIL, XIAOBA_PASSWORD, "24052002", "校霸情报台", "https://api.dicebear.com/8.x/initials/svg?seed=XB", "SCHOOL_OPERATOR", "school", 88, 3560);
        ensureMockOperator(YUANHUA_EMAIL, YUANHUA_PASSWORD, "24052003", "计院院花", "https://api.dicebear.com/8.x/initials/svg?seed=YH", "COLLEGE_OPERATOR", "college", 72, 2880);
        String adminEmail = properties.bootstrap().adminEmail().trim().toLowerCase();
        if (findByEmail(adminEmail) != null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        User admin = new User();
        admin.setEmail(adminEmail);
        admin.setPublicUid(generatePublicUid());
        admin.setPasswordHash(passwordEncoder.encode(properties.bootstrap().adminPassword()));
        admin.setNickname("运营管理员");
        admin.setAvatarUrl("https://api.dicebear.com/8.x/initials/svg?seed=Admin");
        admin.setSchoolId(1L);
        admin.setCollege("信息运营中心");
        admin.setMajor("校园信息运营");
        admin.setGrade("运营");
        admin.setVerifiedStatus("verified");
        admin.setRole("ADMIN");
        admin.setOperatorScope("admin");
        admin.setLevel(100);
        admin.setXp(99999);
        admin.setLevelTitle("传说中的不鸽王");
        admin.setStatus("active");
        admin.setCreatedAt(now);
        admin.setUpdatedAt(now);
        admin.setLastLoginAt(now);
        userMapper.insert(admin);
        createDefaultPreference(admin.getId(), now);
    }

    private void ensureGoodUser() {
        User existing = findByEmail(GOOD_EMAIL);
        LocalDateTime now = LocalDateTime.now();
        if (existing != null) {
            existing.setPasswordHash(passwordEncoder.encode(GOOD_PASSWORD));
            existing.setStatus("active");
            existing.setUpdatedAt(now);
            userMapper.updateById(existing);
            preferenceFor(existing.getId());
            return;
        }

        User user = new User();
        user.setEmail(GOOD_EMAIL);
        user.setPublicUid(generatePublicUid());
        user.setPasswordHash(passwordEncoder.encode(GOOD_PASSWORD));
        user.setNickname("good");
        user.setAvatarUrl("https://api.dicebear.com/8.x/initials/svg?seed=good");
        user.setSchoolId(1L);
        user.setCollegeId(1L);
        user.setMajorId(1L);
        user.setCollege("计算机学院");
        user.setMajor("计算机科学与技术");
        user.setGrade("大二");
        user.setVerifiedStatus("verified");
        user.setRole("USER");
        user.setOperatorScope("student");
        user.setLevel(1);
        user.setXp(0);
        user.setLevelTitle("新同学");
        user.setStatus("active");
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userMapper.insert(user);
        createDefaultPreference(user.getId(), now);
    }

    private void ensureMockOperator(String email, String password, String publicUid, String nickname, String avatarUrl, String role, String scope, int level, int xp) {
        User existing = findByEmail(email);
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            existing = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPublicUid, publicUid));
        }
        User user = existing == null ? new User() : existing;
        user.setEmail(email);
        if (user.getPublicUid() == null || user.getPublicUid().isBlank()) {
            user.setPublicUid(publicUid);
        }
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setNickname(nickname);
        user.setAvatarUrl(avatarUrl);
        user.setSchoolId(1L);
        user.setCollegeId(1L);
        user.setMajorId(1L);
        user.setCollege("计算机学院");
        user.setMajor("计算机科学与技术");
        user.setGrade("运营");
        user.setVerifiedStatus("verified");
        user.setRole(role);
        user.setOperatorScope(scope);
        user.setLevel(level);
        user.setXp(xp);
        user.setLevelTitle(levelTitle(level));
        user.setStatus("active");
        if (user.getCreatedAt() == null) {
            user.setCreatedAt(now);
        }
        user.setUpdatedAt(now);
        if (user.getLastLoginAt() == null) {
            user.setLastLoginAt(now);
        }
        if (user.getId() == null) {
            userMapper.insert(user);
        } else {
            userMapper.updateById(user);
        }
        preferenceFor(user.getId());
    }

    private User findByLogin(String login) {
        String normalized = login.trim().toLowerCase();
        if (GOOD_LOGIN.equals(normalized)) {
            normalized = GOOD_EMAIL;
        } else if (XIAOBA_LOGIN.equals(normalized)) {
            normalized = XIAOBA_EMAIL;
        } else if (YUANHUA_LOGIN.equals(normalized)) {
            normalized = YUANHUA_EMAIL;
        }
        return findByEmail(normalized);
    }

    private String levelTitle(int level) {
        return switch ((Math.max(1, Math.min(100, level)) - 1) / 10) {
            case 0 -> "星途引路人";
            case 1 -> "食域裁决官";
            case 2 -> "万社登临者";
            case 3 -> "书渊夜帝君";
            case 4 -> "课业天机主";
            case 5 -> "实训执掌者";
            case 6 -> "百味揽星客";
            case 7 -> "绩点造化尊";
            case 8 -> "学海无双尊";
            default -> "校园万古名";
        };
    }

    private User findByEmail(String email) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getEmail, normalizeEmail(email)));
    }

    private User findByNickname(String nickname) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getNickname, normalizeNickname(nickname)));
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private String normalizeNickname(String nickname) {
        return nickname == null ? "" : nickname.trim();
    }

    private String generatePublicUid() {
        for (int i = 0; i < 20; i++) {
            String uid = String.valueOf(10000000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(90000000));
            User existing = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPublicUid, uid));
            if (existing == null) {
                return uid;
            }
        }
        throw new IllegalStateException("无法生成唯一 UID");
    }

    private UserPreference preferenceFor(Long userId) {
        UserPreference preference = preferenceMapper.selectOne(new LambdaQueryWrapper<UserPreference>()
                .eq(UserPreference::getUserId, userId));
        if (preference != null) {
            return preference;
        }
        return createDefaultPreference(userId, LocalDateTime.now());
    }

    private UserPreference createDefaultPreference(Long userId, LocalDateTime now) {
        UserPreference preference = new UserPreference();
        preference.setUserId(userId);
        preference.setGoalTags("奖学金,保研,竞赛");
        preference.setInterestCategories("exam,scholarship,research,internship");
        preference.setPreferUrgent(true);
        preference.setPreferHighRisk(true);
        preference.setPreferSameMajor(false);
        preference.setEmailNotificationEnabled(false);
        preference.setBrowserNotificationEnabled(true);
        preference.setRemindBeforeHours(24);
        preference.setCreatedAt(now);
        preference.setUpdatedAt(now);
        preferenceMapper.insert(preference);
        return preference;
    }
}
