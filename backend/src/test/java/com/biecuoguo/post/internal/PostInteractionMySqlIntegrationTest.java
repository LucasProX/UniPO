package com.biecuoguo.post.internal;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.biecuoguo.config.AppProperties;
import com.biecuoguo.config.AppTime;
import com.biecuoguo.mapper.CollegeMapper;
import com.biecuoguo.mapper.MajorMapper;
import com.biecuoguo.mapper.SchoolMapper;
import com.biecuoguo.mapper.UserMapper;
import com.biecuoguo.post.api.PostDtos;
import com.biecuoguo.security.CurrentUser;
import com.biecuoguo.service.PresenceService;
import com.biecuoguo.service.ExperienceService;
import com.biecuoguo.social.api.BlockQueryApi;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * DP17 business invariants against real MySQL, MyBatis-Plus and Spring transaction advice.
 * The only permitted database is the fresh owned fixture supplied by post-interactions.sh.
 */
@EnabledIfEnvironmentVariable(named = "UNIPO_DP17_MYSQL_INTEGRATION", matches = "1")
@Timeout(30)
class PostInteractionMySqlIntegrationTest {
    private static final AtomicInteger PUBLIC_UID = new AtomicInteger(71_000_000);
    private static final Set<UserPair> BLOCKED_PAIRS = ConcurrentHashMap.newKeySet();
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static PostService posts;
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

        DriverManagerDataSource dataSource = new DriverManagerDataSource();
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

        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> dataSource);
        context.register(FixtureConfiguration.class);
        context.refresh();
        posts = context.getBean(PostService.class);
        experience = context.getBean(ExperienceService.class);
        transactionManager = context.getBean(DataSourceTransactionManager.class);
        assertTrue(AopUtils.isAopProxy(posts), "Service calls must use real transaction advice");
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
        BLOCKED_PAIRS.clear();
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
            BLOCKED_PAIRS.add(UserPair.of(commenterBlockedParentAuthor ? commenter.id() : parentAuthor.id(),
                    commenterBlockedParentAuthor ? parentAuthor.id() : commenter.id()));

            assertThrows(NoSuchElementException.class, () -> posts.comment(postId,
                    new PostDtos.CreateCommentRequest("Must not cross a block", parentId), commenter,
                    "blocked-parent-" + postId));

            assertEquals(1, commentFacts(postId), "The rejected reply must not be inserted");
            assertEquals(1, postCount(postId, "comment_count"), "The rejected reply must not change post counters");
            assertEquals(0, commentCount(parentId, "reply_count"), "The rejected reply must not change parent counters");
            assertEquals(0, userXp(commenter.id()), "The rejected reply must not grant experience");
            BLOCKED_PAIRS.clear();
        }
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
                    CommentMapper.class, CommentLikeMapper.class, UserMapper.class, SchoolMapper.class,
                    CollegeMapper.class, MajorMapper.class)) {
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
        PostService postService(SqlSessionTemplate template, ExperienceService experienceService, BlockQueryApi blockQueryApi) {
            return new PostService(new AppProperties(null, null, null, null, null, null, 1L),
                    template.getMapper(PostMapper.class), template.getMapper(PostImageMapper.class),
                    template.getMapper(PostLikeMapper.class), template.getMapper(PostFavoriteMapper.class),
                    template.getMapper(PostShareMapper.class), template.getMapper(PostDailyStatMapper.class),
                    template.getMapper(CommentMapper.class), template.getMapper(CommentLikeMapper.class),
                    template.getMapper(UserMapper.class), template.getMapper(SchoolMapper.class),
                    template.getMapper(CollegeMapper.class), template.getMapper(MajorMapper.class),
                    new PresenceService(new MemoryPresenceStore()), experienceService, blockQueryApi);
        }

        @Bean
        BlockQueryApi blockQueryApi() {
            return new BlockQueryApi() {
                @Override
                public Set<Long> blockedUserIds(Long viewerId) {
                    return BLOCKED_PAIRS.stream().filter(pair -> pair.contains(viewerId))
                            .map(pair -> pair.other(viewerId)).collect(java.util.stream.Collectors.toUnmodifiableSet());
                }

                @Override
                public boolean isBlockedEitherWay(Long firstUserId, Long secondUserId) {
                    return BLOCKED_PAIRS.contains(UserPair.of(firstUserId, secondUserId));
                }
            };
        }

        @Bean
        ExperienceService experience(SqlSessionTemplate template) {
            return new ExperienceService(template.getMapper(UserMapper.class));
        }
    }

    private record UserPair(long low, long high) {
        static UserPair of(long first, long second) {
            return new UserPair(Math.min(first, second), Math.max(first, second));
        }

        boolean contains(long userId) { return low == userId || high == userId; }
        long other(long userId) { return low == userId ? high : low; }
    }
}
