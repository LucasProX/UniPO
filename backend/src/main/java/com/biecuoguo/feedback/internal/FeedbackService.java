package com.biecuoguo.feedback.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.biecuoguo.config.AppTime;
import com.biecuoguo.domain.User;
import com.biecuoguo.feedback.api.FeedbackDtos;
import com.biecuoguo.mapper.UserMapper;
import com.biecuoguo.security.CurrentUser;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class FeedbackService {
    static final Set<String> CATEGORIES = Set.of("bug", "suggestion", "content", "other");
    static final int PAGE_SIZE = 50;

    private final FeedbackMapper feedbackMapper;
    private final UserMapper userMapper;

    public FeedbackService(FeedbackMapper feedbackMapper, UserMapper userMapper) {
        this.feedbackMapper = feedbackMapper;
        this.userMapper = userMapper;
    }

    public long submit(FeedbackDtos.CreateFeedbackRequest request, CurrentUser currentUser) {
        if (currentUser == null || currentUser.id() == null) throw new AuthenticationCredentialsNotFoundException("请先登录");
        if (!CATEGORIES.contains(request.category())) throw new IllegalArgumentException("反馈类型不正确");
        String content = request.content().strip();
        if (content.codePointCount(0, content.length()) < 5) throw new IllegalArgumentException("请至少写 5 个字");
        Feedback feedback = new Feedback();
        feedback.setUserId(currentUser.id());
        feedback.setCategory(request.category());
        feedback.setContent(content);
        feedback.setAppVersion(blankToNull(request.appVersion()));
        feedback.setDevice(blankToNull(request.device()));
        feedback.setStatus("new");
        feedback.setCreatedAt(AppTime.nowLocal());
        feedbackMapper.insert(feedback);
        return feedback.getId();
    }

    /** 管理员查看，按提交时间倒序；beforeId 为上一页最后一条的 id。 */
    public FeedbackDtos.FeedbackPage list(Long beforeId) {
        List<Feedback> rows = feedbackMapper.selectList(new LambdaQueryWrapper<Feedback>()
                .lt(beforeId != null, Feedback::getId, beforeId)
                .orderByDesc(Feedback::getId)
                .last("LIMIT " + (PAGE_SIZE + 1)));
        boolean hasMore = rows.size() > PAGE_SIZE;
        List<Feedback> page = hasMore ? rows.subList(0, PAGE_SIZE) : rows;
        Map<Long, User> users = new LinkedHashMap<>();
        if (!page.isEmpty()) {
            userMapper.selectBatchIds(page.stream().map(Feedback::getUserId).distinct().toList())
                    .forEach(user -> users.put(user.getId(), user));
        }
        List<FeedbackDtos.FeedbackView> items = page.stream().map(row -> {
            User user = users.get(row.getUserId());
            return new FeedbackDtos.FeedbackView(row.getId(), row.getCategory(), row.getContent(), row.getAppVersion(),
                    row.getDevice(), user == null ? null : user.getPublicUid(), user == null ? null : user.getNickname(),
                    row.getStatus(), AppTime.toOffset(row.getCreatedAt()));
        }).toList();
        return new FeedbackDtos.FeedbackPage(items, hasMore ? page.get(page.size() - 1).getId() : null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
