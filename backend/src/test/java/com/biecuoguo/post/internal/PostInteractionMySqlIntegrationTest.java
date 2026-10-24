package com.biecuoguo.post.internal;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.biecuoguo.config.AppProperties;
import com.biecuoguo.config.AppTime;
import com.biecuoguo.dto.UserDtos;
import com.biecuoguo.mapper.CollegeMapper;
import com.biecuoguo.mapper.DeviceTokenMapper;
import com.biecuoguo.mapper.MajorMapper;
import com.biecuoguo.mapper.SchoolMapper;
import com.biecuoguo.mapper.UserBlockMapper;
import com.biecuoguo.mapper.UserFollowMapper;
import com.biecuoguo.mapper.UserMapper;
import com.biecuoguo.mapper.UserReportMapper;
import com.biecuoguo.mapper.SocialRelationshipLockMapper;
import com.biecuoguo.post.api.PostDtos;
import com.biecuoguo.security.CurrentUser;
import com.biecuoguo.security.SessionService;
import com.biecuoguo.service.PresenceService;
import com.biecuoguo.service.ExperienceService;
import com.biecuoguo.service.SocialService;
import com.biecuoguo.service.UserService;
import com.biecuoguo.state.MemoryPresenceStore;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.function.Executable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * DP17 business invariants against real MySQL, MyBatis-Plus and Spring transaction advice.
 * The only permitted database is the fresh owned fixture supplied by post-interactions.sh.
 */
@EnabledIfEnvironmentVariable(named = "UNIPO_DP17_MYSQL_INTEGRATION", matches = "1")
@Timeout(30)
class PostInteractionMySqlIntegrationTest {
    private static final AtomicInteger PUBLIC_UID = new AtomicInteger(71_000_000);
    private static final AtomicInteger CONNECTION_OPENS = new AtomicInteger();
    private static final AtomicReference<StatusReadGate> STATUS_READ_GATE = new AtomicReference<>();
    private static final AtomicReference<RelationshipReadGate> RELATIONSHIP_READ_GATE = new AtomicReference<>();
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static PostService posts;
    private static SocialService social;
    private static UserService userProfiles;
    private static ExperienceService experience;
    private static DataSourceTransactionManager transactionManager;
    private static long ownedSchoolId;
    private final List<Long> ownedUsers = new ArrayList<>();
    private final List<Long> ownedPosts = new ArrayList<>();

    @BeforeAll
    static void connectOnlyToFreshOwnedFixtureAndApplyAllMigrations() {
        assertEquals("1", System.getenv("UNIPO_DP02_MYSQL_INTEGRATION"),
                "Start through the existing fresh MySQL fixture lifecycle");
        assertEquals("127.0.0.1", System.getenv("UNIPO_DP02_MYSQL_HOST"));
        assertEquals("22306", System.getenv("UNIPO_DP02_MYSQL_PORT"));
        assertEquals("unipo_fixture", System.getenv("UNIPO_DP02_MYSQL_USER"));
        String runId = System.getenv("UNIPO_DP02_MYSQL_RUN_ID");
        assertNotNull(runId, "Start with scripts/integration/post-interactions.sh");
        assertTrue(runId.matches("[0-9a-f]{16}"), "Invalid fixture ownership marker");
        String schema = System.getenv("UNIPO_DP02_MYSQL_SCHEMA");
        assertEquals("unipo_dp02_" + runId, schema, "Fixture schema must belong to this run");
        String password = System.getenv("UNIPO_DP02_MYSQL_PASSWORD");
        assertNotNull(password, "A generated fixture password is required");
        assertTrue(password.matches("[0-9a-f]{48}"), "Invalid generated fixture password");

        DriverManagerDataSource dataSource = new DriverManagerDataSource() {
            @Override
            public java.sql.Connection getConnection() throws java.sql.SQLException {
                java.sql.Connection connection = super.getConnection();
                CONNECTION_OPENS.incrementAndGet();
                return connection;
            }
        };
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl("jdbc:mysql://127.0.0.1:22306/" + schema
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                + "&connectTimeout=2000&socketTimeout=10000");
        dataSource.setUsername("unipo_fixture");
        dataSource.setPassword(password);
        jdbc = new JdbcTemplate(dataSource);
        // No schema or application write is allowed before all three ownership checks succeed.
        assertEquals(schema, jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertEquals(runId, jdbc.queryForObject(
                "SELECT LOWER(HEX(run_id)) FROM dp02_fixture_marker", String.class));
        assertEquals(List.of("dp02_fixture_marker"), jdbc.queryForList("SHOW TABLES", String.class),
                "Refusing to migrate a schema containing anything beyond this run's marker");

        Flyway flyway = Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration").baselineOnMigrate(true).baselineVersion("0")
                .createSchemas(false).cleanDisabled(true).load();
        flyway.migrate();
        assertEquals(0, flyway.info().pending().length, "All repository migrations must execute");
        assertTrue(flyway.validateWithResult().validationSuccessful, "The migration chain must validate");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history "
                + "WHERE version = '22' AND success = 1", Integer.class),
                "The social pair-lock migration must be applied successfully");

        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> dataSource);
        context.register(FixtureConfiguration.class);
        context.refresh();
        posts = context.getBean(PostService.class);
        social = context.getBean(SocialService.class);
        userProfiles = context.getBean(UserService.class);
        experience = context.getBean(ExperienceService.class);
        transactionManager = context.getBean(DataSourceTransactionManager.class);
        assertTrue(AopUtils.isAopProxy(posts), "Service calls must use real transaction advice");
        assertTrue(AopUtils.isAopProxy(social), "Relationship and authority calls must use real transaction advice");
        String schoolCode = "dp17-" + UUID.randomUUID();
        jdbc.update("INSERT INTO schools (name, code, created_at, updated_at) VALUES (?, ?, NOW(), NOW())",
                "DP17 fixture school", schoolCode);
        Long schoolId = jdbc.queryForObject("SELECT id FROM schools WHERE code = ?", Long.class, schoolCode);
        assertNotNull(schoolId);
        ownedSchoolId = schoolId;
        System.out.println("DP17 fresh MySQL marker verified; complete Flyway chain and transaction advice active");
    }

    @AfterEach
    void removeOnlyRowsCreatedByThisTest() {
        for (Long postId : ownedPosts) {
            jdbc.update("DELETE FROM comment_likes WHERE comment_id IN (SELECT id FROM comments WHERE post_id = ?)", postId);
            jdbc.update("DELETE FROM comments WHERE post_id = ?", postId);
            // The post's existing foreign keys cascade only its own interaction/image/stat rows.
            jdbc.update("DELETE FROM posts WHERE id = ?", postId);
        }
        for (Long userId : ownedUsers) {
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    @AfterAll
    static void closeFixture() {
        try {
            if (ownedSchoolId != 0) {
                jdbc.update("DELETE FROM schools WHERE id = ?", ownedSchoolId);
            }
        } finally {
            if (context != null) {
                context.close();
            }
        }
    }

    @Test
    void anOldSnapshotCannotOverwriteADifferentInteractionCounter() {
        CurrentUser author = newUser();
        CurrentUser liker = newUser();
        CurrentUser collector = newUser();
        long postId = newPost(author);

        assertTrue(withOldSnapshot(postId, () -> assertTrue(posts.like(postId, liker)),
                () -> posts.favorite(postId, collector)));

        assertEquals(1, likeFacts(postId), "The first transaction's fact must remain");
        assertEquals(1, favoriteFacts(postId), "The second transaction's fact must commit");
        assertEquals(1, postCount(postId, "like_count"), "A later favorite must not overwrite an earlier like");
        assertEquals(1, postCount(postId, "favorite_count"));
    }

    @Test
    void repeatedLikesFromOneUserAreSuccessfulAndCountExactlyOnce() {
        CurrentUser author = newUser();
        CurrentUser liker = newUser();
        long postId = newPost(author);

        assertTrue(withOldSnapshot(postId, () -> assertTrue(posts.like(postId, liker)),
                () -> posts.like(postId, liker)), "A stale duplicate request must succeed idempotently");

        assertEquals(1, likeFacts(postId));
        assertEquals(1, postCount(postId, "like_count"));
        assertEquals(1, dailyLikes(postId), "A retry must not add another daily interaction");
    }

    @Test
    void withdrawalsByDifferentUsersConvergeToZeroWithoutDuplicateDecrements() {
        CurrentUser author = newUser();
        CurrentUser first = newUser();
        CurrentUser second = newUser();
        long postId = newPost(author);
        seedLike(postId, first);
        seedLike(postId, second);
        jdbc.update("UPDATE posts SET like_count = 2 WHERE id = ?", postId);

        assertTrue(withOldSnapshot(postId, () -> assertTrue(posts.unlike(postId, first)),
                () -> posts.unlike(postId, second)));

        assertEquals(0, likeFacts(postId));
        assertEquals(0, postCount(postId, "like_count"), "Both committed withdrawals must decrement exactly once");
        assertTrue(posts.unlike(postId, first));
        assertTrue(posts.unlike(postId, second));
        assertEquals(0, postCount(postId, "like_count"), "Repeated DELETE must not drive the count below zero");
    }

    @Test
    void anOldSnapshotCannotResurrectADeletedOrModeratedPost() {
        assertAll("Both author deletion and moderation must remain effective",
                List.of("deleted", "removed").stream().map(finalStatus -> (Executable) () -> {
            CurrentUser author = newUser();
            CurrentUser liker = newUser();
            long postId = newPost(author);
            withOldSnapshot(postId, () -> {
                if ("deleted".equals(finalStatus)) {
                    assertTrue(posts.deleteOwnPost(postId, author));
                } else {
                    posts.moderate(postId, null, null, null, finalStatus);
                }
            }, () -> posts.like(postId, liker), true);

            assertEquals(finalStatus, jdbc.queryForObject("SELECT status FROM posts WHERE id = ?",
                    String.class, postId), "An old interaction must never restore published status");
            assertEquals(0, likeFacts(postId), "An interaction after removal must not leave a new fact");
            assertEquals(0, postCount(postId, "like_count"));
        }));
    }

    @Test
    void likesFromDifferentUsersPreserveBothFactsAndBothDailyActions() {
        CurrentUser author = newUser();
        CurrentUser first = newUser();
        CurrentUser second = newUser();
        long postId = newPost(author);

        assertTrue(withOldSnapshot(postId, () -> assertTrue(posts.like(postId, first)),
                () -> posts.like(postId, second)));

        assertEquals(2, likeFacts(postId));
        assertEquals(2, postCount(postId, "like_count"));
        assertEquals(2, dailyLikes(postId));
    }

    @Test
    void favoriteRetriesAndConcurrentWithdrawalsChangeEachFactExactlyOnce() {
        CurrentUser author = newUser();
        CurrentUser first = newUser();
        CurrentUser second = newUser();
        long postId = newPost(author);
        assertTrue(withOldSnapshot(postId, () -> assertTrue(posts.favorite(postId, first)),
                () -> posts.favorite(postId, first)));
        assertEquals(1, favoriteFacts(postId));
        assertEquals(1, postCount(postId, "favorite_count"));
        assertTrue(posts.favorite(postId, second));
        assertTrue(withOldSnapshot(postId, () -> assertTrue(posts.unfavorite(postId, first)),
                () -> posts.unfavorite(postId, second)));

        assertEquals(0, favoriteFacts(postId));
        assertEquals(0, postCount(postId, "favorite_count"));
        assertTrue(posts.unfavorite(postId, first));
        assertTrue(posts.unfavorite(postId, second));
        assertEquals(0, postCount(postId, "favorite_count"));
    }

    @Test
    void commentLikeRetriesAndConcurrentWithdrawalsUseTheCommentFacts() {
        CurrentUser author = newUser();
        CurrentUser first = newUser();
        CurrentUser second = newUser();
        long postId = newPost(author);
        long commentId = seedComment(postId, author, "visible");
        assertTrue(withOldSnapshot(postId, () -> assertTrue(posts.likeComment(commentId, first)),
                () -> posts.likeComment(commentId, first)));
        assertEquals(1, commentLikeFacts(commentId));
        assertEquals(1, commentCount(commentId, "like_count"));
        assertTrue(posts.likeComment(commentId, second));
        assertTrue(withOldSnapshot(postId, () -> assertTrue(posts.unlikeComment(commentId, first)),
                () -> posts.unlikeComment(commentId, second)));

        assertEquals(0, commentLikeFacts(commentId));
        assertEquals(0, commentCount(commentId, "like_count"));
        assertTrue(posts.unlikeComment(commentId, first));
        assertTrue(posts.unlikeComment(commentId, second));
        assertEquals(0, commentCount(commentId, "like_count"));
    }

    @Test
    void firstDailyUpsertAccumulatesActionsAndWithdrawalDoesNotRewriteHistory() {
        CurrentUser author = newUser();
        CurrentUser liker = newUser();
        CurrentUser sharer = newUser();
        long postId = newPost(author);
        jdbc.update("DELETE FROM post_daily_stats WHERE post_id = ?", postId);

        withOldSnapshot(postId, () -> assertTrue(posts.like(postId, liker)),
                () -> posts.share(postId, sharer, "first-share"));
        assertEquals(1, dailyLikes(postId));
        assertEquals(1, dailyCount(postId, "share_count"));
        assertTrue(posts.unlike(postId, liker));
        assertEquals(1, dailyLikes(postId), "Withdrawal must preserve the historical successful action");
        assertTrue(posts.like(postId, liker));
        assertTrue(posts.like(postId, liker));

        assertEquals(1, likeFacts(postId));
        assertEquals(1, postCount(postId, "like_count"));
        assertEquals(2, dailyLikes(postId), "A new like counts, its same-intent retry does not");
        assertEquals(1, dailyCount(postId, "share_count"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM post_daily_stats WHERE post_id = ?",
                Integer.class, postId));
    }

    @Test
    void aCommentRetryReturnsTheSameFactAndRewardsOnlyOnce() {
        CurrentUser author = newUser();
        CurrentUser commenter = newUser();
        long postId = newPost(author);
        PostDtos.CreateCommentRequest request = new PostDtos.CreateCommentRequest("A useful reply", null);

        PostDtos.CommentView first = posts.comment(postId, request, commenter, "comment-retry");
        PostDtos.CommentView retried = posts.comment(postId, request, commenter, "comment-retry");

        assertEquals(first.id(), retried.id());
        assertEquals(1, commentFacts(postId));
        assertEquals(1, postCount(postId, "comment_count"));
        assertEquals(1, dailyCount(postId, "comment_count"));
        assertEquals(4, userXp(commenter.id()));
    }

    @Test
    void commentRewardUsesTheSharedCumulativeLevelThresholds() {
        CurrentUser author = newUser();
        CurrentUser commenter = newUser();
        long postId = newPost(author);
        jdbc.update("UPDATE users SET level = 1, xp = 544, level_title = '萌新探路员' WHERE id = ?", commenter.id());

        posts.comment(postId, new PostDtos.CreateCommentRequest("Reach next level", null), commenter,
                "level-threshold-comment");

        assertEquals(2, jdbc.queryForObject("SELECT level FROM users WHERE id = ?", Integer.class, commenter.id()));
        assertEquals(0, userXp(commenter.id()));
        assertEquals("萌新探路员", jdbc.queryForObject("SELECT level_title FROM users WHERE id = ?",
                String.class, commenter.id()));
    }

    @Test
    void experienceAwardsFromAStaleTransactionPreserveBothBusinessUpdates() {
        CurrentUser author = newUser();
        CurrentUser recipient = newUser();
        long postId = newPost(author);

        withOldSnapshot(postId, () -> experience.award(recipient.id(), 12),
                () -> experience.award(recipient.id(), 4));

        assertEquals(16, userXp(recipient.id()));
        assertEquals(1, jdbc.queryForObject("SELECT level FROM users WHERE id = ?", Integer.class, recipient.id()));
    }

    @Test
    void aCommentRetryFromAnOldSnapshotReturnsTheCommittedFact() {
        CurrentUser author = newUser();
        CurrentUser commenter = newUser();
        long postId = newPost(author);
        PostDtos.CreateCommentRequest request = new PostDtos.CreateCommentRequest("A raced reply", null);
        List<Long> firstId = new ArrayList<>();

        PostDtos.CommentView retried = withOldSnapshot(postId,
                () -> firstId.add(posts.comment(postId, request, commenter, "raced-comment").id()),
                () -> posts.comment(postId, request, commenter, "raced-comment"));

        assertEquals(firstId.get(0), retried.id());
        assertEquals(1, commentFacts(postId));
        assertEquals(1, postCount(postId, "comment_count"));
        assertEquals(1, dailyCount(postId, "comment_count"));
        assertEquals(4, userXp(commenter.id()));
    }

    @Test
    void reusingACommentKeyForAnotherPayloadOrPostIsRejectedWithoutSideEffects() {
        CurrentUser author = newUser();
        CurrentUser commenter = newUser();
        long firstPost = newPost(author);
        long otherPost = newPost(author);
        PostDtos.CreateCommentRequest original = new PostDtos.CreateCommentRequest("First intent", null);
        PostDtos.CommentView first = posts.comment(firstPost, original, commenter, "bound-comment");

        assertThrows(IllegalArgumentException.class, () -> posts.comment(firstPost,
                new PostDtos.CreateCommentRequest("Changed intent", null), commenter, "bound-comment"));
        assertThrows(IllegalArgumentException.class, () -> posts.comment(otherPost, original, commenter, "bound-comment"));

        assertEquals(first.id(), posts.comment(firstPost, original, commenter, "bound-comment").id());
        assertEquals(1, commentFacts(firstPost));
        assertEquals(0, commentFacts(otherPost));
        assertEquals(1, postCount(firstPost, "comment_count"));
        assertEquals(0, postCount(otherPost, "comment_count"));
        assertEquals(4, userXp(commenter.id()));
    }

    @Test
    void commentsWithoutAKeyRemainSeparateLegacyIntents() {
        CurrentUser author = newUser();
        CurrentUser commenter = newUser();
        long postId = newPost(author);
        PostDtos.CreateCommentRequest request = new PostDtos.CreateCommentRequest("Same text, separate requests", null);

        PostDtos.CommentView first = posts.comment(postId, request, commenter, null);
        PostDtos.CommentView second = posts.comment(postId, request, commenter, null);

        assertTrue(!first.id().equals(second.id()));
        assertEquals(2, commentFacts(postId));
        assertEquals(2, postCount(postId, "comment_count"));
        assertEquals(2, dailyCount(postId, "comment_count"));
        assertEquals(8, userXp(commenter.id()));
    }

    @Test
    void parentsMustExistBeVisibleAndBelongToTheSamePost() {
        CurrentUser author = newUser();
        CurrentUser commenter = newUser();
        long targetPost = newPost(author);
        long otherPost = newPost(author);
        long foreignParent = seedComment(otherPost, author, "visible");
        long hiddenParent = seedComment(targetPost, author, "hidden");

        for (long invalidParent : List.of(Long.MAX_VALUE, foreignParent, hiddenParent)) {
            RuntimeException rejection = assertThrows(RuntimeException.class, () -> posts.comment(targetPost,
                    new PostDtos.CreateCommentRequest("Invalid reply", invalidParent), commenter,
                    "invalid-parent-" + invalidParent));
            assertTrue(rejection instanceof IllegalArgumentException || rejection instanceof NoSuchElementException
                            || rejection instanceof AccessDeniedException,
                    "An invalid parent must receive a deliberate domain rejection, not a SQL or unexpected failure");
        }

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM comments WHERE post_id = ? AND status = 'visible'",
                Integer.class, targetPost));
        assertEquals(0, postCount(targetPost, "comment_count"));
        assertEquals(0, dailyCount(targetPost, "comment_count"));
        assertEquals(0, userXp(commenter.id()));
        assertEquals(0, commentCount(hiddenParent, "reply_count"));
        assertEquals(0, commentCount(foreignParent, "reply_count"));
    }

    @Test
    void cannotReplyToACommentAuthorWhoBlockedTheViewerInEitherDirection() {
        for (boolean commenterBlockedParentAuthor : List.of(false, true)) {
            CurrentUser postAuthor = newUser();
            CurrentUser parentAuthor = newUser();
            CurrentUser commenter = newUser();
            long postId = newPost(postAuthor);
            long parentId = seedComment(postId, parentAuthor, "visible");
            CurrentUser blocker = commenterBlockedParentAuthor ? commenter : parentAuthor;
            CurrentUser blocked = commenterBlockedParentAuthor ? parentAuthor : commenter;
            social.block(uid(blocked.id()), blocker);

            assertThrows(NoSuchElementException.class, () -> posts.comment(postId,
                    new PostDtos.CreateCommentRequest("Must not cross a block", parentId), commenter,
                    "blocked-parent-" + postId));

            assertEquals(1, commentFacts(postId), "The rejected reply must not be inserted");
            assertEquals(1, postCount(postId, "comment_count"), "The rejected reply must not change post counters");
            assertEquals(0, commentCount(parentId, "reply_count"), "The rejected reply must not change parent counters");
            assertEquals(0, userXp(commenter.id()), "The rejected reply must not grant experience");
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_blocks WHERE blocker_id = ? AND blocked_id = ?",
                    Integer.class, blocker.id(), blocked.id()));
            jdbc.update("DELETE FROM user_blocks WHERE blocker_id = ? AND blocked_id = ?", blocker.id(), blocked.id());
        }
    }

    @Test
    void realBlocksRemoveMutualFollowsHideFeedAndProfileWorksAndUnblockRestoresContent() {
        CurrentUser first = newUser();
        CurrentUser second = newUser();
        long firstPost = newPost(first);
        long secondPost = newPost(second);
        String firstUid = uid(first.id());
        String secondUid = uid(second.id());

        for (CurrentUser blocker : List.of(first, second)) {
            CurrentUser blocked = blocker.id().equals(first.id()) ? second : first;
            String blockerUid = blocker.id().equals(first.id()) ? firstUid : secondUid;
            String blockedUid = blocker.id().equals(first.id()) ? secondUid : firstUid;
            long blockerPost = blocker.id().equals(first.id()) ? firstPost : secondPost;
            long blockedPost = blocker.id().equals(first.id()) ? secondPost : firstPost;

            social.follow(secondUid, first);
            social.follow(firstUid, second);
            assertTrue(userProfiles.publicProfile(blockedUid, blocker.id()).following());
            assertTrue(userProfiles.publicProfile(blockedUid, blocker.id()).followedBy());

            UserDtos.BlockStatusView status = social.block(blockedUid, blocker);
            assertTrue(status.blocked());
            assertFalse(status.blockedBy());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_blocks WHERE blocker_id = ? AND blocked_id = ?",
                    Integer.class, blocker.id(), blocked.id()));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_follows WHERE "
                    + "(follower_id = ? AND following_id = ?) OR (follower_id = ? AND following_id = ?)",
                    Integer.class, first.id(), second.id(), second.id(), first.id()),
                    "Blocking must remove both follow directions");

            UserDtos.PublicProfile profileWhileBlocked = userProfiles.publicProfile(blockedUid, blocker.id());
            assertTrue(profileWhileBlocked.blocked());
            assertFalse(profileWhileBlocked.blockedBy());
            assertFalse(profileWhileBlocked.following());
            assertFalse(profileWhileBlocked.followedBy());
            assertTrue(posts.userPostsByUid(blockedUid, blocker).isEmpty(),
                    "A blocked peer's profile works must be hidden");
            assertFalse(posts.list("school", blocker).stream().anyMatch(post -> post.id().equals(blockedPost)),
                    "The feed must hide the blocked peer's posts");
            assertTrue(posts.list("school", blocker).stream().anyMatch(post -> post.id().equals(blockerPost)));
            assertTrue(posts.userPostsByUid(blockerUid, blocked).isEmpty(),
                    "The content restriction must apply in the reverse direction too");
            assertFalse(posts.list("school", blocked).stream().anyMatch(post -> post.id().equals(blockerPost)));

            UserDtos.BlockStatusView unblocked = social.unblock(blockedUid, blocker);
            assertFalse(unblocked.blocked());
            assertFalse(unblocked.blockedBy());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_blocks WHERE "
                    + "(blocker_id = ? AND blocked_id = ?) OR (blocker_id = ? AND blocked_id = ?)",
                    Integer.class, first.id(), second.id(), second.id(), first.id()));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_follows WHERE "
                    + "(follower_id = ? AND following_id = ?) OR (follower_id = ? AND following_id = ?)",
                    Integer.class, first.id(), second.id(), second.id(), first.id()),
                    "Unblocking must not silently restore either follow edge");
            assertFalse(userProfiles.publicProfile(blockedUid, blocker.id()).blocked());
            assertFalse(userProfiles.publicProfile(blockedUid, blocker.id()).blockedBy());
            assertTrue(posts.userPostsByUid(blockedUid, blocker).stream().anyMatch(post -> post.id().equals(blockedPost)),
                    "Unblocking must restore profile works");
            assertTrue(posts.list("school", blocker).stream().anyMatch(post -> post.id().equals(blockedPost)),
                    "Unblocking must restore feed visibility");
            assertTrue(posts.userPostsByUid(blockerUid, blocked).stream().anyMatch(post -> post.id().equals(blockerPost)));
            assertTrue(posts.list("school", blocked).stream().anyMatch(post -> post.id().equals(blockerPost)));
        }
    }

    @Test
    void concurrentDuplicateBlocksConvergeToOneRealBlockRow() throws Exception {
        CurrentUser blocker = newUser();
        CurrentUser target = newUser();
        String targetUid = uid(target.id());
        String trigger = "dp03_delay_block_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        createInsertDelayTrigger(trigger, "user_blocks", blocker.id(), target.id());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<UserDtos.BlockStatusView> first = executor.submit(() -> {
                ready.countDown();
                await(start);
                return social.block(targetUid, blocker);
            });
            Future<UserDtos.BlockStatusView> second = executor.submit(() -> {
                ready.countDown();
                await(start);
                return social.block(targetUid, blocker);
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS), "Both block requests must reach the start gate");
            start.countDown();

            assertTrue(first.get(8, TimeUnit.SECONDS).blocked());
            assertTrue(second.get(8, TimeUnit.SECONDS).blocked());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_blocks WHERE blocker_id = ? AND blocked_id = ?",
                    Integer.class, blocker.id(), target.id()));
            assertTrue(social.isBlockedEitherWay(blocker.id(), target.id()));
        } finally {
            executor.shutdownNow();
            dropTrigger(trigger);
        }
    }

    @Test
    void aConcurrentFollowAndBlockCannotCommitBothRelationships() throws Exception {
        CurrentUser blocker = newUser();
        CurrentUser target = newUser();
        String blockerUid = uid(blocker.id());
        String targetUid = uid(target.id());
        String trigger = "dp03_delay_follow_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        createInsertDelayTrigger(trigger, "user_follows", blocker.id(), target.id());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> follow = executor.submit(() -> {
                try {
                    return social.follow(targetUid, blocker);
                } catch (IllegalArgumentException blockedByConcurrentRequest) {
                    return false;
                }
            });
            // The fixture trigger holds the follow write inside its transaction while block starts.
            Thread.sleep(200);
            Future<UserDtos.BlockStatusView> block = executor.submit(() -> social.block(targetUid, blocker));

            assertTrue(block.get(8, TimeUnit.SECONDS).blocked());
            follow.get(8, TimeUnit.SECONDS);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_blocks WHERE blocker_id = ? AND blocked_id = ?",
                    Integer.class, blocker.id(), target.id()));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_follows WHERE "
                    + "(follower_id = ? AND following_id = ?) OR (follower_id = ? AND following_id = ?)",
                    Integer.class, blocker.id(), target.id(), target.id(), blocker.id()),
                    "A block racing a follow must win without leaving a follow edge");
            assertTrue(social.isBlockedEitherWay(blocker.id(), target.id()));
        } finally {
            executor.shutdownNow();
            dropTrigger(trigger);
        }
    }

    @Test
    void deletedParticipantsCannotCreateFollowOrBlockRelationships() {
        for (boolean deleteActor : List.of(false, true)) {
            CurrentUser actor = newUser();
            CurrentUser target = newUser();
            String targetUid = uid(target.id());
            userProfiles.deleteAccount(deleteActor ? actor.id() : target.id());

            assertThrows(NoSuchElementException.class, () -> social.follow(targetUid, actor));
            assertThrows(NoSuchElementException.class, () -> social.block(targetUid, actor));
            assertNoRelationshipFacts(actor.id(), target.id());
        }
    }

    @Test
    void deletionCleanupCannotBeUndoneByWaitingFollowOrBlockWriters() throws Exception {
        for (boolean deleteActor : List.of(false, true)) {
            for (boolean writeBlock : List.of(false, true)) {
                CurrentUser actor = newUser();
                CurrentUser target = newUser();
                String targetUid = uid(target.id());
                long deletedId = deleteActor ? actor.id() : target.id();
                // Exercise an already existing pair row, not just an FK wait on first creation.
                social.follow(targetUid, actor);
                social.follow(uid(actor.id()), target);
                CountDownLatch cleanupFinished = new CountDownLatch(1);
                CountDownLatch commitDeletion = new CountDownLatch(1);
                StatusReadGate gate = new StatusReadGate(deletedId, new CountDownLatch(1));
                ExecutorService workers = Executors.newFixedThreadPool(2);
                try {
                    Future<?> deletion = workers.submit(() -> {
                        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
                        transaction.executeWithoutResult(status -> {
                            userProfiles.deleteAccount(deletedId);
                            assertNoRelationshipFacts(actor.id(), target.id());
                            cleanupFinished.countDown();
                            await(commitDeletion, "The deletion commit gate was not released");
                        });
                    });
                    await(cleanupFinished, "Account deletion must finish its relationship cleanup first");
                    STATUS_READ_GATE.set(gate);
                    Future<?> lateWriter = workers.submit(() -> {
                        assertThrows(NoSuchElementException.class, () -> {
                            if (writeBlock) { social.block(targetUid, actor); }
                            else { social.follow(targetUid, actor); }
                        });
                    });
                    await(gate.reached(), "The late relationship writer must reach the deleted participant's shared read");
                    assertThrows(java.util.concurrent.TimeoutException.class,
                            () -> lateWriter.get(200, TimeUnit.MILLISECONDS),
                            "A shared participant read must wait for the deletion's exclusive user lock");
                    commitDeletion.countDown();
                    deletion.get(5, TimeUnit.SECONDS);
                    lateWriter.get(5, TimeUnit.SECONDS);
                    assertEquals("deleted", jdbc.queryForObject("SELECT status FROM users WHERE id = ?",
                            String.class, deletedId));
                    assertNoRelationshipFacts(actor.id(), target.id());
                } finally {
                    STATUS_READ_GATE.compareAndSet(gate, null);
                    commitDeletion.countDown();
                    stopWorkers(workers);
                }
            }
        }
    }

    @Test
    void differentPairsCanPerformTheirFirstFollowAndBlockWritesConcurrently() throws Exception {
        for (boolean writeBlock : List.of(false, true)) {
            CurrentUser actor = newUser();
            CurrentUser firstTarget = newUser();
            CurrentUser secondTarget = newUser();
            String firstUid = uid(firstTarget.id());
            String secondUid = uid(secondTarget.id());
            RelationshipReadGate gate = new RelationshipReadGate();
            ExecutorService workers = Executors.newFixedThreadPool(2);
            RELATIONSHIP_READ_GATE.set(gate);
            try {
                Future<?> first = workers.submit(() -> {
                    if (writeBlock) { social.block(firstUid, actor); }
                    else { assertTrue(social.follow(firstUid, actor)); }
                });
                Future<?> second = workers.submit(() -> {
                    if (writeBlock) { social.block(secondUid, actor); }
                    else { assertTrue(social.follow(secondUid, actor)); }
                });
                await(gate.bothRead, "Both different pairs must reach their first absent relation read concurrently");
                gate.continueWrites.countDown();
                first.get(5, TimeUnit.SECONDS);
                second.get(5, TimeUnit.SECONDS);
                if (writeBlock) {
                    assertTrue(social.isBlockedEitherWay(actor.id(), firstTarget.id()));
                    assertTrue(social.isBlockedEitherWay(actor.id(), secondTarget.id()));
                } else {
                    assertEquals(2, social.followingCount(actor.id()));
                }
            } finally {
                RELATIONSHIP_READ_GATE.compareAndSet(gate, null);
                gate.continueWrites.countDown();
                stopWorkers(workers);
            }
        }
    }

    @Test
    void authorityQueriesReadCommittedRelationshipsOutsideOldRepeatableReadSnapshots() throws Exception {
        CurrentUser actor = newUser();
        CurrentUser target = newUser();
        String targetUid = uid(target.id());
        ExecutorService worker = Executors.newSingleThreadExecutor();
        TransactionTemplate oldSnapshot = new TransactionTemplate(transactionManager);
        oldSnapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        oldSnapshot.setTimeout(15);
        try {
            oldSnapshot.executeWithoutResult(status -> {
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_blocks WHERE blocker_id = ?",
                        Integer.class, actor.id()), "Create the caller's empty Repeatable Read snapshot");
                assertFalse(social.isBlockedEitherWay(actor.id(), target.id()));
                assertTrue(social.blockedUserIds(actor.id()).isEmpty());
                awaitWorker(worker.submit(() -> social.block(targetUid, actor)));

                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_blocks WHERE blocker_id = ?",
                        Integer.class, actor.id()), "The surrounding business snapshot must still be old");
                assertTrue(social.isBlockedEitherWay(actor.id(), target.id()),
                        "Authority cannot inherit the caller's old snapshot or previously cached false");
                assertEquals(Set.of(target.id()), social.blockedUserIds(actor.id()));
                assertEquals(Set.of(actor.id()), social.blockedUserIds(target.id()));
                awaitWorker(worker.submit(() -> social.unblock(targetUid, actor)));

                assertFalse(social.isBlockedEitherWay(actor.id(), target.id()),
                        "Previously cached true must not hide a committed unblock");
                assertTrue(social.blockedUserIds(actor.id()).isEmpty());

                awaitWorker(worker.submit(() -> social.block(targetUid, actor)));
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_blocks WHERE blocker_id = ?",
                        Integer.class, actor.id()), "The caller still holds its original empty snapshot");
                social.lockPair(actor.id(), target.id());
                int connectionsBeforeLockedRead = CONNECTION_OPENS.get();
                assertTrue(social.isBlockedInLockedPair(actor.id(), target.id()),
                        "The locked-pair read must see committed facts beyond an old snapshot");
                assertTrue(social.isBlockedInLockedPair(target.id(), actor.id()),
                        "A single committed direction must block reads in both participant orders");
                assertEquals(connectionsBeforeLockedRead, CONNECTION_OPENS.get(),
                        "Holding a pair lock must not require borrowing an additional connection");
            });
        } finally {
            stopWorkers(worker);
        }
    }

    @Test
    void relationshipLocksRequireAnActiveDatabaseTransaction() {
        CurrentUser actor = newUser();
        CurrentUser target = newUser();
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                () -> social.lockPair(actor.id(), target.id()));
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                () -> social.isBlockedInLockedPair(actor.id(), target.id()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM social_relationship_pair_locks "
                + "WHERE user_low_id = ? AND user_high_id = ?", Integer.class,
                Math.min(actor.id(), target.id()), Math.max(actor.id(), target.id())));

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            social.lockPair(actor.id(), target.id());
            int connectionsBeforeLockedRead = CONNECTION_OPENS.get();
            assertFalse(social.isBlockedInLockedPair(actor.id(), target.id()));
            // JDBC setup deliberately does not flush MyBatis' local cache.
            jdbc.update("INSERT INTO user_blocks (blocker_id, blocked_id, created_at) VALUES (?, ?, NOW())",
                    target.id(), actor.id());
            assertTrue(social.isBlockedInLockedPair(actor.id(), target.id()),
                    "A prior false must not survive a new relationship fact in the same transaction");
            assertEquals(connectionsBeforeLockedRead, CONNECTION_OPENS.get(),
                    "Locked relationship queries must stay on the existing transaction connection");
        });
    }

    @Test
    void aValidReplyUpdatesItsParentOnceAndKeyCannotChangeTheParent() {
        CurrentUser author = newUser();
        CurrentUser commenter = newUser();
        long postId = newPost(author);
        long parentId = seedComment(postId, author, "visible");
        long anotherParent = seedComment(postId, author, "visible");
        PostDtos.CreateCommentRequest request = new PostDtos.CreateCommentRequest("Valid reply", parentId);

        PostDtos.CommentView reply = posts.comment(postId, request, commenter, "parent-bound");
        assertEquals(reply.id(), posts.comment(postId, request, commenter, "parent-bound").id());
        assertThrows(IllegalArgumentException.class, () -> posts.comment(postId,
                new PostDtos.CreateCommentRequest("Valid reply", anotherParent), commenter, "parent-bound"));

        assertEquals(parentId, reply.parentId());
        assertEquals(1, commentCount(parentId, "reply_count"));
        assertEquals(0, commentCount(anotherParent, "reply_count"));
        assertEquals(3, commentFacts(postId));
        assertEquals(3, postCount(postId, "comment_count"));
        assertEquals(1, dailyCount(postId, "comment_count"));
        assertEquals(4, userXp(commenter.id()));
    }

    @Test
    void shareRetriesReturnTheirOriginalResultAfterLaterShares() {
        CurrentUser author = newUser();
        CurrentUser sharer = newUser();
        long postId = newPost(author);
        PostDtos.ShareResponse original = posts.share(postId, sharer, "share-original");
        PostDtos.ShareResponse later = posts.share(postId, sharer, "share-later");

        PostDtos.ShareResponse retried = posts.share(postId, sharer, "share-original");

        assertEquals(1, original.shareCount());
        assertEquals(2, later.shareCount());
        assertEquals(original, retried, "A retry must return its stored result, not the latest global counter");
        assertEquals(2, shareFacts(postId));
        assertEquals(2, postCount(postId, "share_count"));
        assertEquals(2, dailyCount(postId, "share_count"));
    }

    @Test
    void aShareRetryFromAnOldSnapshotDoesNotCreateAnotherFact() {
        CurrentUser author = newUser();
        CurrentUser sharer = newUser();
        long postId = newPost(author);
        List<PostDtos.ShareResponse> original = new ArrayList<>();

        PostDtos.ShareResponse retried = withOldSnapshot(postId,
                () -> original.add(posts.share(postId, sharer, "raced-share")),
                () -> posts.share(postId, sharer, "raced-share"));

        assertEquals(original.get(0), retried);
        assertEquals(1, shareFacts(postId));
        assertEquals(1, postCount(postId, "share_count"));
        assertEquals(1, dailyCount(postId, "share_count"));
    }

    @Test
    void aShareKeyCannotBeReusedForAnotherPost() {
        CurrentUser author = newUser();
        CurrentUser sharer = newUser();
        long firstPost = newPost(author);
        long otherPost = newPost(author);
        PostDtos.ShareResponse original = posts.share(firstPost, sharer, "bound-share");

        assertThrows(IllegalArgumentException.class, () -> posts.share(otherPost, sharer, "bound-share"));

        assertEquals(original, posts.share(firstPost, sharer, "bound-share"));
        assertEquals(1, shareFacts(firstPost));
        assertEquals(0, shareFacts(otherPost));
        assertEquals(1, postCount(firstPost, "share_count"));
        assertEquals(0, postCount(otherPost, "share_count"));
    }

    @Test
    void sharesWithoutAKeyRemainSeparateLegacyIntents() {
        CurrentUser author = newUser();
        CurrentUser sharer = newUser();
        long postId = newPost(author);

        assertEquals(1, posts.share(postId, sharer, null).shareCount());
        assertEquals(2, posts.share(postId, sharer, null).shareCount());

        assertEquals(2, shareFacts(postId));
        assertEquals(2, postCount(postId, "share_count"));
        assertEquals(2, dailyCount(postId, "share_count"));
    }

    @Test
    void factsCountersDailyActionsAndRewardsRollBackTogether() {
        CurrentUser author = newUser();
        CurrentUser actor = newUser();
        long postId = newPost(author);
        TransactionTemplate business = new TransactionTemplate(transactionManager);

        business.executeWithoutResult(status -> {
            posts.comment(postId, new PostDtos.CreateCommentRequest("Will roll back", null), actor, "rollback-comment");
            posts.share(postId, actor, "rollback-share");
            assertTrue(posts.like(postId, actor));
            status.setRollbackOnly();
        });

        assertEquals(0, commentFacts(postId));
        assertEquals(0, shareFacts(postId));
        assertEquals(0, likeFacts(postId));
        assertEquals(0, postCount(postId, "comment_count"));
        assertEquals(0, postCount(postId, "share_count"));
        assertEquals(0, postCount(postId, "like_count"));
        assertEquals(0, dailyCount(postId, "comment_count"));
        assertEquals(0, dailyCount(postId, "share_count"));
        assertEquals(0, dailyLikes(postId));
        assertEquals(0, userXp(actor.id()));
    }

    @Test
    void oneUsersConcurrentCommentsOnDifferentPostsPreserveBothRewardsWithoutDeadlock() {
        CurrentUser author = newUser();
        CurrentUser commenter = newUser();
        long firstPost = newPost(author);
        long otherPost = newPost(author);

        together(() -> posts.comment(firstPost, new PostDtos.CreateCommentRequest("First post", null),
                        commenter, "different-post-one"),
                () -> posts.comment(otherPost, new PostDtos.CreateCommentRequest("Other post", null),
                        commenter, "different-post-two"));

        assertEquals(1, commentFacts(firstPost));
        assertEquals(1, commentFacts(otherPost));
        assertEquals(1, postCount(firstPost, "comment_count"));
        assertEquals(1, postCount(otherPost, "comment_count"));
        assertEquals(8, userXp(commenter.id()), "Concurrent rewards must both be retained");
    }

    @Test
    void deletionAndModerationCannotOverwriteACountCommittedAfterTheirSnapshot() {
        assertAll("State changes must update their own fields without restoring stale counts",
                List.of("deleted", "removed").stream().map(finalStatus -> (Executable) () -> {
                    CurrentUser author = newUser();
                    CurrentUser liker = newUser();
                    long postId = newPost(author);
                    withOldSnapshot(postId, () -> assertTrue(posts.like(postId, liker)), () -> {
                        if ("deleted".equals(finalStatus)) {
                            return posts.deleteOwnPost(postId, author);
                        }
                        posts.moderate(postId, true, null, null, finalStatus);
                        return true;
                    });

                    assertEquals(finalStatus, jdbc.queryForObject("SELECT status FROM posts WHERE id = ?",
                            String.class, postId));
                    assertEquals(1, likeFacts(postId));
                    assertEquals(1, postCount(postId, "like_count"));
                }));
    }

    @Test
    void staleOrmEntitiesCannotOverwritePostAndCommentCounters() {
        CurrentUser author = newUser();
        CurrentUser actor = newUser();
        long postId = newPost(author);
        long parentId = seedComment(postId, author, "visible");
        SqlSessionTemplate template = context.getBean(SqlSessionTemplate.class);
        PostMapper postMapper = template.getMapper(PostMapper.class);
        CommentMapper commentMapper = template.getMapper(CommentMapper.class);
        Post stalePost = postMapper.selectById(postId);
        Comment staleParent = commentMapper.selectById(parentId);
        assertTrue(posts.like(postId, actor));
        assertTrue(posts.favorite(postId, actor));
        posts.share(postId, actor, "orm-share");
        posts.comment(postId, new PostDtos.CreateCommentRequest("Child", parentId), actor, "orm-reply");
        assertTrue(posts.likeComment(parentId, actor));
        stalePost.setTitle("Updated fixture title");
        stalePost.setLikeCount(99);
        stalePost.setCommentCount(99);
        stalePost.setFavoriteCount(99);
        stalePost.setShareCount(99);
        staleParent.setContent("Updated fixture parent");
        staleParent.setLikeCount(99);
        staleParent.setReplyCount(99);

        assertEquals(1, postMapper.updateById(stalePost));
        assertEquals(1, commentMapper.updateById(staleParent));

        assertEquals("Updated fixture title", jdbc.queryForObject("SELECT title FROM posts WHERE id = ?",
                String.class, postId), "The ordinary non-counter edit must still succeed");
        assertEquals("Updated fixture parent", jdbc.queryForObject("SELECT content FROM comments WHERE id = ?",
                String.class, parentId));
        assertEquals(1, postCount(postId, "like_count"));
        assertEquals(1, postCount(postId, "favorite_count"));
        assertEquals(1, postCount(postId, "share_count"));
        assertEquals(2, postCount(postId, "comment_count"));
        assertEquals(1, commentCount(parentId, "like_count"));
        assertEquals(1, commentCount(parentId, "reply_count"));
    }

    @Test
    void malformedRequestKeysAreRejectedBeforeAnyInteractionOrReward() {
        CurrentUser author = newUser();
        CurrentUser actor = newUser();
        long postId = newPost(author);
        PostDtos.CreateCommentRequest request = new PostDtos.CreateCommentRequest("Must not persist", null);

        for (String invalid : List.of("", " ", "non-ascii-中文", "x".repeat(65))) {
            assertThrows(IllegalArgumentException.class, () -> posts.comment(postId, request, actor, invalid));
            assertThrows(IllegalArgumentException.class, () -> posts.share(postId, actor, invalid));
        }

        assertEquals(0, commentFacts(postId));
        assertEquals(0, shareFacts(postId));
        assertEquals(0, postCount(postId, "comment_count"));
        assertEquals(0, postCount(postId, "share_count"));
        assertEquals(0, dailyCount(postId, "comment_count"));
        assertEquals(0, dailyCount(postId, "share_count"));
        assertEquals(0, userXp(actor.id()));
    }

    /**
     * The worker holds a real RR snapshot before the other thread commits; no locks or sleeps
     * simulate a database. A corrected current read or atomic update can naturally pass this schedule.
     */
    private <T> T withOldSnapshot(long postId, Runnable committingChange, Supplier<T> staleChange) {
        return withOldSnapshot(postId, committingChange, staleChange, false);
    }

    private <T> T withOldSnapshot(long postId, Runnable committingChange, Supplier<T> staleChange,
                                  boolean allowRemovalRejection) {
        CountDownLatch snapshotRead = new CountDownLatch(1);
        CountDownLatch firstCommitted = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<T> staleTransaction = worker.submit(() -> {
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            transaction.setTimeout(15);
            try {
                return transaction.execute(status -> {
                    assertEquals("published", jdbc.queryForObject(
                            "SELECT status FROM posts WHERE id = ?", String.class, postId));
                    jdbc.queryForObject("SELECT COUNT(*) FROM post_likes WHERE post_id = ?", Integer.class, postId);
                    snapshotRead.countDown();
                    await(firstCommitted, "The first business transaction did not commit");
                    return staleChange.get();
                });
            } catch (NoSuchElementException | AccessDeniedException removedPost) {
                // Let transaction advice roll back before accepting a deliberate domain rejection.
                // Swallowing it inside execute() would produce UnexpectedRollbackException instead.
                if (allowRemovalRejection) { return null; }
                throw removedPost;
            }
        });
        try {
            await(snapshotRead, "The worker did not establish its repeatable-read snapshot");
            committingChange.run();
            firstCommitted.countDown();
            return staleTransaction.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running the fixture transactions", interrupted);
        } catch (ExecutionException failure) {
            throw new IllegalStateException("The stale business request must complete without an unexpected error",
                    failure.getCause());
        } catch (java.util.concurrent.TimeoutException timeout) {
            throw new IllegalStateException("The fixture transaction did not complete", timeout);
        } finally {
            firstCommitted.countDown();
            worker.shutdownNow();
            try {
                assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS),
                        "Fixture workers must terminate before owned-row cleanup");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while stopping the fixture worker", interrupted);
            }
        }
    }

    private static void await(CountDownLatch latch, String message) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), message);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a fixture latch", interrupted);
        }
    }

    private void together(Runnable first, Runnable second) {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        List<Future<?>> tasks = new ArrayList<>();
        for (Runnable action : List.of(first, second)) {
            tasks.add(workers.submit(() -> {
                ready.countDown();
                await(start, "Concurrent fixture actions were not released");
                action.run();
            }));
        }
        try {
            await(ready, "Concurrent fixture actions were not ready");
            start.countDown();
            for (Future<?> task : tasks) {
                task.get(10, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during concurrent fixture actions", interrupted);
        } catch (ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new IllegalStateException("Both concurrent business requests must complete without a deadlock", failure);
        } finally {
            start.countDown();
            workers.shutdownNow();
            try {
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "Fixture workers must stop before cleanup");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while stopping fixture workers", interrupted);
            }
        }
    }

    private CurrentUser newUser() {
        String email = UUID.randomUUID() + "@example.invalid";
        jdbc.update("INSERT INTO users (public_uid, email, password_hash, nickname, school_id, role, status, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'USER', 'active', NOW(), NOW())",
                Integer.toString(PUBLIC_UID.getAndIncrement()), email, UUID.randomUUID().toString(),
                "dp17-" + UUID.randomUUID(), ownedSchoolId);
        Long userId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        assertNotNull(userId);
        ownedUsers.add(userId);
        return new CurrentUser(userId, email, "USER");
    }

    private long newPost(CurrentUser author) {
        String title = "dp17-" + UUID.randomUUID();
        jdbc.update("INSERT INTO posts (author_id, school_id, board, title, content, status, published_at, "
                        + "created_at, updated_at) VALUES (?, ?, 'school', ?, 'fixture', 'published', NOW(), NOW(), NOW())",
                author.id(), ownedSchoolId, title);
        Long postId = jdbc.queryForObject("SELECT id FROM posts WHERE title = ?", Long.class, title);
        assertNotNull(postId);
        ownedPosts.add(postId);
        // A first daily-stat insert race must not obscure the counter invariant this test targets.
        jdbc.update("INSERT INTO post_daily_stats (post_id, stat_date, like_count, comment_count, share_count) "
                + "VALUES (?, ?, 0, 0, 0)", postId, AppTime.nowLocal().toLocalDate());
        return postId;
    }

    private void seedLike(long postId, CurrentUser user) {
        jdbc.update("INSERT INTO post_likes (post_id, user_id, created_at) VALUES (?, ?, NOW())", postId, user.id());
    }

    private long seedComment(long postId, CurrentUser author, String status) {
        String content = "parent-" + UUID.randomUUID();
        jdbc.update("INSERT INTO comments (post_id, user_id, content, status, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, NOW(), NOW())", postId, author.id(), content, status);
        Long commentId = jdbc.queryForObject("SELECT id FROM comments WHERE post_id = ? AND content = ?",
                Long.class, postId, content);
        assertNotNull(commentId);
        if ("visible".equals(status)) {
            jdbc.update("UPDATE posts SET comment_count = comment_count + 1 WHERE id = ?", postId);
        }
        return commentId;
    }

    private int likeFacts(long postId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM post_likes WHERE post_id = ?", Integer.class, postId);
    }

    private int favoriteFacts(long postId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM post_favorites WHERE post_id = ?", Integer.class, postId);
    }

    private int commentFacts(long postId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM comments WHERE post_id = ?", Integer.class, postId);
    }

    private int shareFacts(long postId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM post_shares WHERE post_id = ?", Integer.class, postId);
    }

    private int commentLikeFacts(long commentId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM comment_likes WHERE comment_id = ?", Integer.class, commentId);
    }

    private int userXp(long userId) {
        return jdbc.queryForObject("SELECT xp FROM users WHERE id = ?", Integer.class, userId);
    }

    private int dailyLikes(long postId) {
        return jdbc.queryForObject("SELECT like_count FROM post_daily_stats WHERE post_id = ? AND stat_date = ?",
                Integer.class, postId, AppTime.nowLocal().toLocalDate());
    }

    private int dailyCount(long postId, String column) {
        if (!List.of("like_count", "comment_count", "share_count").contains(column)) {
            throw new IllegalArgumentException("Only fixed fixture daily columns may be selected");
        }
        return jdbc.queryForObject("SELECT " + column + " FROM post_daily_stats WHERE post_id = ? AND stat_date = ?",
                Integer.class, postId, AppTime.nowLocal().toLocalDate());
    }

    private int commentCount(long commentId, String column) {
        if (!List.of("like_count", "reply_count").contains(column)) {
            throw new IllegalArgumentException("Only fixed fixture comment columns may be selected");
        }
        return jdbc.queryForObject("SELECT " + column + " FROM comments WHERE id = ?", Integer.class, commentId);
    }

    private int postCount(long postId, String column) {
        if (!List.of("like_count", "favorite_count", "comment_count", "share_count").contains(column)) {
            throw new IllegalArgumentException("Only fixed fixture count columns may be selected");
        }
        return jdbc.queryForObject("SELECT " + column + " FROM posts WHERE id = ?", Integer.class, postId);
    }

    private void assertNoRelationshipFacts(long firstId, long secondId) {
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_follows WHERE "
                + "(follower_id = ? AND following_id = ?) OR (follower_id = ? AND following_id = ?)",
                Integer.class, firstId, secondId, secondId, firstId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_blocks WHERE "
                + "(blocker_id = ? AND blocked_id = ?) OR (blocker_id = ? AND blocked_id = ?)",
                Integer.class, firstId, secondId, secondId, firstId));
    }

    private static void awaitWorker(Future<?> work) {
        try {
            work.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for a committed relationship write", interrupted);
        } catch (ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new IllegalStateException("The relationship write must commit before the authority recheck", failure);
        }
    }

    private static void stopWorkers(ExecutorService workers) {
        workers.shutdownNow();
        try {
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "Fixture workers must stop before row cleanup");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping relationship fixture workers", interrupted);
        }
    }

    private record StatusReadGate(long userId, CountDownLatch reached) {}

    private static final class RelationshipReadGate {
        private final CountDownLatch bothRead = new CountDownLatch(2);
        private final CountDownLatch continueWrites = new CountDownLatch(1);
        private final Set<Long> enteredThreads = ConcurrentHashMap.newKeySet();

        void afterAbsentRead() {
            if (enteredThreads.add(Thread.currentThread().threadId())) {
                bothRead.countDown();
                await(continueWrites, "The different-pair write gate was not released");
            }
        }
    }

    /** Only observes/gates calls; every actual mapper operation still executes against MySQL. */
    private static UserMapper statusObservingUserMapper(SqlSessionTemplate template) {
        UserMapper delegate = template.getMapper(UserMapper.class);
        return (UserMapper) Proxy.newProxyInstance(UserMapper.class.getClassLoader(), new Class<?>[]{UserMapper.class},
                (proxy, method, args) -> {
                    StatusReadGate gate = STATUS_READ_GATE.get();
                    if (gate != null && "lockStatusForShare".equals(method.getName())
                            && ((Long) args[0]).longValue() == gate.userId()) {
                        gate.reached().countDown();
                    }
                    try { return method.invoke(delegate, args); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
    }

    private static UserBlockMapper readGatedBlockMapper(SqlSessionTemplate template) {
        UserBlockMapper delegate = template.getMapper(UserBlockMapper.class);
        return (UserBlockMapper) Proxy.newProxyInstance(UserBlockMapper.class.getClassLoader(),
                new Class<?>[]{UserBlockMapper.class}, (proxy, method, args) -> {
                    Object result;
                    try { result = method.invoke(delegate, args); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                    RelationshipReadGate gate = RELATIONSHIP_READ_GATE.get();
                    if (gate != null && "selectOne".equals(method.getName()) && result == null) {
                        gate.afterAbsentRead();
                    }
                    return result;
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class FixtureConfiguration {
        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setDefaultStatementTimeout(5);
            for (Class<?> mapper : List.of(PostMapper.class, PostImageMapper.class, PostLikeMapper.class,
                    PostFavoriteMapper.class, PostShareMapper.class, PostDailyStatMapper.class,
                    CommentMapper.class, CommentLikeMapper.class, UserMapper.class, UserBlockMapper.class,
                    UserFollowMapper.class, UserReportMapper.class, SocialRelationshipLockMapper.class, DeviceTokenMapper.class,
                    SchoolMapper.class, CollegeMapper.class,
                    MajorMapper.class)) {
                configuration.addMapper(mapper);
            }
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        DataSourceTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SocialService socialService(SqlSessionTemplate template) {
            return new SocialService(statusObservingUserMapper(template), template.getMapper(UserFollowMapper.class),
                    readGatedBlockMapper(template), template.getMapper(UserReportMapper.class),
                    template.getMapper(SocialRelationshipLockMapper.class));
        }

        @Bean
        PostService postService(SqlSessionTemplate template, ExperienceService experienceService, SocialService socialService) {
            return new PostService(new AppProperties(null, null, null, null, null, null, 1L),
                    template.getMapper(PostMapper.class), template.getMapper(PostImageMapper.class),
                    template.getMapper(PostLikeMapper.class), template.getMapper(PostFavoriteMapper.class),
                    template.getMapper(PostShareMapper.class), template.getMapper(PostDailyStatMapper.class),
                    template.getMapper(CommentMapper.class), template.getMapper(CommentLikeMapper.class),
                    template.getMapper(UserMapper.class), template.getMapper(SchoolMapper.class),
                    template.getMapper(CollegeMapper.class), template.getMapper(MajorMapper.class),
                    new PresenceService(new MemoryPresenceStore()), experienceService, socialService,
                    org.mockito.Mockito.mock(com.biecuoguo.admin.api.AdminAuditApi.class),
                    org.mockito.Mockito.mock(com.biecuoguo.activity.api.ActivityParticipationApi.class),
                    org.mockito.Mockito.mock(com.biecuoguo.achievement.api.TaskLookupApi.class),
                    org.mockito.Mockito.mock(com.biecuoguo.notification.api.NotificationApi.class),
                    template.getMapper(com.biecuoguo.mapper.UserFollowMapper.class));
        }

        @Bean
        UserService userService(SqlSessionTemplate template, PostService postService,
                                ExperienceService experienceService) {
            return new UserService(template.getMapper(UserMapper.class), null,
                    template.getMapper(UserFollowMapper.class), template.getMapper(UserBlockMapper.class),
                    null, template.getMapper(DeviceTokenMapper.class), new PresenceService(new MemoryPresenceStore()),
                    new BCryptPasswordEncoder(4), mock(SessionService.class), postService, experienceService);
        }

        @Bean
        ExperienceService experience(SqlSessionTemplate template) {
            return new ExperienceService(template.getMapper(UserMapper.class));
        }
    }

    private String uid(long userId) {
        return jdbc.queryForObject("SELECT public_uid FROM users WHERE id = ?", String.class, userId);
    }

    private void createInsertDelayTrigger(String triggerName, String table, long firstUserId, long secondUserId) {
        if (!Set.of("user_blocks", "user_follows").contains(table)
                || !triggerName.matches("dp03_delay_(block|follow)_[0-9a-f]{12}")) {
            throw new IllegalArgumentException("Invalid integration trigger name or target");
        }
        jdbc.execute("CREATE TRIGGER " + triggerName + " BEFORE INSERT ON " + table
                + " FOR EACH ROW SET @dp03_insert_delay = IF(NEW." + ("user_blocks".equals(table) ? "blocker_id" : "follower_id")
                + " = " + firstUserId + " AND NEW." + ("user_blocks".equals(table) ? "blocked_id" : "following_id")
                + " = " + secondUserId + ", SLEEP(1), 0)");
    }

    private void dropTrigger(String triggerName) {
        if (triggerName.matches("dp03_delay_(block|follow)_[0-9a-f]{12}")) {
            jdbc.execute("DROP TRIGGER IF EXISTS " + triggerName);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the test start gate");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The test start gate was interrupted", interrupted);
        }
    }
}
