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
import com.biecuoguo.security.TokenBlacklistService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
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
    private final TokenBlacklistService tokenBlacklistService;
    private final PresenceService presenceService;
    private final SmsService smsService;

    public AuthService(AppProperties properties, UserMapper userMapper, UserPreferenceMapper preferenceMapper, VerificationService verificationService, PasswordEncoder passwordEncoder, JwtService jwtService, TokenBlacklistService tokenBlacklistService, PresenceService presenceService, SmsService smsService) {
        this.properties = properties;
        this.userMapper = userMapper;
        this.preferenceMapper = preferenceMapper;
        this.verificationService = verificationService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.presenceService = presenceService;
        this.smsService = smsService;
    }

    @Transactional
    public AuthDtos.AuthResponse register(AuthDtos.RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (email.isBlank()) {
            throw new IllegalArgumentException("请输入账号");
        }
        // confirmPassword 可选：给了就必须一致，没给就跳过。
        if (request.confirmPassword() != null && !request.confirmPassword().isEmpty()
                && !request.password().equals(request.confirmPassword())) {
            throw new IllegalArgumentException("两次输入的密码不一致");
        }
        assertPasswordAcceptable(request.password());
        // 『账号』唯一即可，不校验邮箱格式：开发阶段要能随手建测试号。
        if (findByEmail(email) != null) {
            throw new IllegalArgumentException("该账号已被注册");
        }
        // nickname 可选，不填就用账号本身（此时唯一性由上面的账号判重保证）。
        String nickname = normalizeNickname(request.nickname());
        if (nickname.isBlank()) {
            nickname = email;
        }
        if (findByNickname(nickname) != null) {
            throw new IllegalArgumentException("该用户名已被占用");
        }
        // 验证码始终必填。开发模式只是让任意 4 位数字通过，流程本身不跳过 ——
        // 这样本地和线上的调用链一致，不会出现「本地能过、上线才发现漏了校验」。
        verificationService.verifyCode(email, "register", request.verificationCode());
        LocalDateTime now = LocalDateTime.now();
        User user = new User();
        user.setEmail(email);
        user.setPublicUid(generatePublicUid());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setNickname(nickname);
        user.setAvatarUrl("https://api.dicebear.com/8.x/initials/svg?seed=" + nickname);
        user.setSchoolId(defaultSchoolId());
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
        presenceService.markOnline(user.getId());
        return issueTokens(user, preference);
    }

    /**
     * 用 refresh token 换一对新 token。
     *
     * <p>刻意**不做轮换**（旧 refresh token 换完仍然有效，直到过期或退出登录被拉黑）。
     * 轮换需要「旧 token 立刻作废」，而拉黑表是进程内 ConcurrentHashMap（见 ISSUES Q-4），
     * 于是多标签页并发刷新、或多实例部署时会出现「A 刚刷新成功，B 拿刚被作废的旧 token 刷新失败」，
     * 用户莫名其妙被踢下线。而 refresh token 的安全边界本来就等价于「已登录」，
     * 轮换带来的收益要等有共享存储（Redis）之后才值得。
     */
    public AuthDtos.AuthResponse refresh(String refreshToken) {
        // 退出登录会把 refresh token 也写进拉黑表，这里必须先查——否则「退出登录」只是本地清了个 key，
        // 手里留着 refresh token 的人还能一直换出新的 access token。
        if (tokenBlacklistService.isRevoked(refreshToken)) {
            throw new InsufficientAuthenticationException("refresh token 已失效");
        }
        CurrentUser claims;
        try {
            claims = jwtService.parseRefresh(refreshToken);
        } catch (RuntimeException ex) {
            // 过期 / 签名不对 / 拿 access token 来冒充，一律当成「未登录」。
            throw new InsufficientAuthenticationException("refresh token 无效或已过期");
        }
        User user = userMapper.selectById(claims.id());
        if (user == null || !"active".equals(user.getStatus())) {
            throw new InsufficientAuthenticationException("账号不存在或已被禁用");
        }
        return issueTokens(user, preferenceFor(user.getId()));
    }

    public AuthDtos.SmsCodeResponse requestSmsCode(String phone) {
        smsService.send(phone);
        return new AuthDtos.SmsCodeResponse(true, SmsService.expireMinutes() * 60);
    }

    /**
     * 手机号验证码登录：没有账号就自动注册。
     * 手机号用户的 email 列存手机号（该列本来就不校验邮箱格式），password 是随机值。
     */
    @Transactional
    public AuthDtos.AuthResponse smsLogin(AuthDtos.SmsLoginRequest request) {
        String phone = SmsService.normalize(request.phone());
        smsService.verify(phone, request.code());
        User user = findByPhone(phone);
        if (user == null) {
            User byEmail = findByEmail(phone);
            if (byEmail != null) {
                user = byEmail;
                user.setPhone(phone);
            }
        }
        if (user == null) {
            user = createPhoneUser(phone, request.nickname());
        }
        if (!"active".equals(user.getStatus())) {
            throw new IllegalArgumentException("账号已被停用");
        }
        user.setLastLoginAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        presenceService.markOnline(user.getId());
        return issueTokens(user, preferenceFor(user.getId()));
    }

    private User createPhoneUser(String phone, String requestedNickname) {
        LocalDateTime now = LocalDateTime.now();
        String nickname = requestedNickname == null ? "" : requestedNickname.trim();
        if (nickname.isBlank()) {
            nickname = "同学" + phone.substring(phone.length() - 4);
        }
        String unique = nickname;
        int suffix = 2;
        while (findByNickname(unique) != null) {
            unique = nickname + suffix++;
        }
        User user = new User();
        user.setEmail(phone);
        user.setPhone(phone);
        user.setPublicUid(generatePublicUid());
        user.setPasswordHash(passwordEncoder.encode(java.util.UUID.randomUUID().toString()));
        user.setNickname(unique);
        user.setAvatarUrl("https://api.dicebear.com/8.x/initials/svg?seed=" + unique);
        user.setSchoolId(defaultSchoolId());
        user.setGrade("大一");
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
        createDefaultPreference(user.getId(), now);
        return user;
    }

    private User findByPhone(String phone) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
    }

    /** 签发 access + refresh 并组装返回。登录、注册、刷新三条路径都走这里，别各写一遍。 */
    private AuthDtos.AuthResponse issueTokens(User user, UserPreference preference) {
        CurrentUser current = new CurrentUser(user.getId(), user.getEmail(), user.getRole());
        return new AuthDtos.AuthResponse(
                jwtService.createToken(current),
                jwtService.createRefreshToken(current),
                jwtService.accessExpiresInSeconds(),
                UserProfile.from(user, preference, true)
        );
    }

    /**
     * 是不是开发模式（验证码放行）。
     *
     * <p>只用于决定“开发便利”类行为（比如密码长度下限），
     * **不再**用来决定要不要校验验证码 —— 那件事现在由 {@link VerificationService#mockMode()} 内部区分，
     * 两种模式下都调用 {@code verifyCode}。
     */
    private boolean mockVerification() {
        return verificationService.mockMode();
    }

    /**
     * 新账号还没选学校时落在哪。
     *
     * <p>以前这里写死 {@code 1L}（管理员、演示账号、注册用户四处各写一遍），
     * 和访客 feed 的默认学校是同一个概念却分在两处维护。现在统一走 {@code app.default-school-id}。
     */
    private Long defaultSchoolId() {
        Long configured = properties.defaultSchoolId();
        return configured == null ? 1L : configured;
    }

    /**
     * 密码长度校验。
     *
     * <p>下限：mock 模式（开发）下 1 位即可，方便随手建测试号；真实环境仍是 6 位。
     *
     * <p>上限：**BCrypt 的有效输入只有 72 字节**，超出部分会被静默忽略（等于两个不同的长密码
     * 可能算出同一个哈希）。这里显式拒绝而不是截断，免得埋一个看不出来的坑。
     */
    private void assertPasswordAcceptable(String password) {
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("请输入密码");
        }
        int min = mockVerification() ? 1 : 6;
        if (password.length() < min) {
            throw new IllegalArgumentException("密码至少 " + min + " 位");
        }
        int bytes = password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bytes > 72) {
            throw new IllegalArgumentException(
                    "密码太长：最多 72 字节（约 72 个英文字符或 24 个汉字），当前 " + bytes + " 字节");
        }
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
        if (request.confirmPassword() != null && !request.confirmPassword().isEmpty()
                && !request.password().equals(request.confirmPassword())) {
            throw new IllegalArgumentException("两次输入的密码不一致");
        }
        assertPasswordAcceptable(request.password());
        User user = findByEmail(email);
        if (user == null) {
            throw new IllegalArgumentException("该账号尚未注册");
        }
        // 同上：验证码必填，开发模式下任意 4 位数字可过。
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
        presenceService.markOnline(user.getId());
        return issueTokens(user, preference);
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
        // 演示账号（good / xiaoba / yuanhua）默认不写。以前这里无条件执行——
        // 等于任何人拿到源码就知道生产上有三个弱口令账号能直接登录。
        // 现在由 app.bootstrap.seed-demo-data 控制：本地 profile 打开，生产默认关闭，
        // 而且在生产 profile 下打开会被 StartupSafetyGuard 直接拒绝启动。
        if (properties.bootstrap() != null && properties.bootstrap().seedDemoData()) {
            ensureGoodUser();
            ensureMockOperator(XIAOBA_EMAIL, XIAOBA_PASSWORD, "24052002", "校霸情报台", "https://api.dicebear.com/8.x/initials/svg?seed=XB", "SCHOOL_OPERATOR", "school", 88, 3560);
            ensureMockOperator(YUANHUA_EMAIL, YUANHUA_PASSWORD, "24052003", "计院院花", "https://api.dicebear.com/8.x/initials/svg?seed=YH", "COLLEGE_OPERATOR", "college", 72, 2880);
        }
        String adminEmail = normalizeEmail(properties.bootstrap().adminEmail());
        LocalDateTime now = LocalDateTime.now();
        User admin = findByEmail(adminEmail);
        if (admin == null) {
            admin = userMapper.selectOne(new LambdaQueryWrapper<User>()
                    .eq(User::getRole, "ADMIN")
                    .or()
                    .eq(User::getOperatorScope, "admin")
                    .last("LIMIT 1"));
        }
        if (admin == null) {
            // ---- 首次创建：用 bootstrap 配置初始化 ----
            admin = new User();
            admin.setEmail(adminEmail);
            admin.setPublicUid(generatePublicUid());
            // 这个密码只在「账号不存在」时用一次。已存在就绝不再碰——
            // 否则每次重启都会把线上管理员的密码覆盖回 compose 里的默认值，
            // 等于永久保留一个公开的万能口令，而且管理员自己改了也没用。
            admin.setPasswordHash(passwordEncoder.encode(properties.bootstrap().adminPassword()));
            admin.setNickname("运营管理员");
            admin.setAvatarUrl("https://api.dicebear.com/8.x/initials/svg?seed=Admin");
            admin.setSchoolId(defaultSchoolId());
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
            admin.setLastLoginAt(now);
            admin.setUpdatedAt(now);
            userMapper.insert(admin);
        } else {
            // ---- 已存在：只补关键字段，不动密码、不改昵称 ----
            // 老库可能缺 publicUid / role / status，补上即可；除此之外不该有任何写操作，
            // 否则管理员在后台改的昵称、头像会在下次重启时被悄悄改回去。
            boolean dirty = false;
            if (admin.getPublicUid() == null || admin.getPublicUid().isBlank()) {
                admin.setPublicUid(generatePublicUid());
                dirty = true;
            }
            if (!"ADMIN".equals(admin.getRole())) {
                admin.setRole("ADMIN");
                admin.setOperatorScope("admin");
                dirty = true;
            }
            if (!"active".equals(admin.getStatus())) {
                admin.setStatus("active");
                dirty = true;
            }
            if (dirty) {
                admin.setUpdatedAt(now);
                userMapper.updateById(admin);
            }
        }
        preferenceFor(admin.getId());
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
        user.setSchoolId(defaultSchoolId());
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
        user.setSchoolId(defaultSchoolId());
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
