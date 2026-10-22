package com.biecuoguo.message.internal;

import com.biecuoguo.service.PresenceService;

import com.biecuoguo.config.AppTime;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.biecuoguo.domain.User;
import com.biecuoguo.message.api.MessageDtos;
import com.biecuoguo.post.api.PostDtos;
import com.biecuoguo.mapper.UserMapper;
import com.biecuoguo.security.CurrentUser;
import com.biecuoguo.social.api.BlockQueryApi;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class MessageService {
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final UserMapper userMapper;
    private final PresenceService presenceService;
    private final MessageOutboxStore outbox;
    private final BlockQueryApi blockQueryApi;

    public MessageService(ConversationMapper conversationMapper, MessageMapper messageMapper, UserMapper userMapper,
                          PresenceService presenceService, MessageOutboxStore outbox, BlockQueryApi blockQueryApi) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.userMapper = userMapper;
        this.presenceService = presenceService;
        this.outbox = outbox;
        this.blockQueryApi = blockQueryApi;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<MessageDtos.ConversationView> conversations(CurrentUser currentUser) {
        List<Conversation> conversationList = visibleConversations(currentUser.id());
        if (conversationList.isEmpty()) {
            return List.of();
        }
        List<Long> conversationIds = conversationList.stream().map(Conversation::getId).toList();
        Map<Long, Long> peerIdByConversation = conversationList.stream().collect(Collectors.toMap(
                Conversation::getId, conversation -> peerIdOf(conversation, currentUser.id())));

        // 1) 对端用户一次批量查完
        Set<Long> peerIds = conversationList.stream()
                .map(conversation -> peerIdOf(conversation, currentUser.id()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, User> peers = peerIds.isEmpty() ? Map.of() : userMapper.selectList(new LambdaQueryWrapper<User>().in(User::getId, peerIds))
                .stream().collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));

        // 2) 所有会话的消息一次查完，再按会话分组取最后一条（保持 createdAt 升序，最后一条即最新）
        Map<Long, Message> lastMessageByConversation = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                        .in(Message::getConversationId, conversationIds)
                        .orderByAsc(Message::getCreatedAt))
                .stream()
                .collect(Collectors.toMap(Message::getConversationId, m -> m, (a, b) -> b));

        // 3) 只聚合本轮候选会话的未读数，不读取未读正文。
        Map<Long, Long> unreadByConversation = messageMapper.selectUnreadCounts(currentUser.id(), conversationIds)
                .stream()
                .collect(Collectors.toMap(MessageMapper.UnreadCount::conversationId, MessageMapper.UnreadCount::unreadCount));

        List<MessageDtos.ConversationView> views = conversationList.stream()
                .map(conversation -> new MessageDtos.ConversationView(
                        conversation.getId(),
                        authorView(peers.get(peerIdOf(conversation, currentUser.id()))),
                        contentOf(lastMessageByConversation.get(conversation.getId())),
                        unreadByConversation.getOrDefault(conversation.getId(), 0L),
                        AppTime.toOffset(conversation.getLastMessageAt()),
                        AppTime.toOffset(conversation.getUpdatedAt())
                ))
                .toList();

        // This final authority read happens after preview, unread and presence lookups. A block
        // committed before this point must hide the result; responses already in flight cannot be recalled.
        // NOT_SUPPORTED prevents an upstream Repeatable Read snapshot from preserving an old relationship.
        Set<Long> blockedAtReturn = blockQueryApi.blockedUserIds(currentUser.id());
        return views.stream().filter(view -> !blockedAtReturn.contains(peerIdByConversation.get(view.id()))).toList();
    }

    private Long peerIdOf(Conversation conversation, Long currentUserId) {
        return conversation.getUserOneId().equals(currentUserId) ? conversation.getUserTwoId() : conversation.getUserOneId();
    }

    private String contentOf(Message message) {
        return message == null ? "" : message.getContent();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MessageDtos.ConversationView create(MessageDtos.CreateConversationRequest request, CurrentUser currentUser) {
        if (request.peerId() == null && (request.peerUid() == null || request.peerUid().isBlank())) {
            throw new IllegalArgumentException("私信对象不能为空");
        }
        User peer = request.peerId() != null ? userMapper.selectById(request.peerId()) : null;
        if (peer == null && request.peerUid() != null) {
            peer = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPublicUid, request.peerUid().trim()));
        }
        if (peer == null) {
            throw new NoSuchElementException("message peer not found");
        }
        if (peer.getId().equals(currentUser.id())) {
            throw new IllegalArgumentException("不能给自己发私信");
        }
        blockQueryApi.lockPair(currentUser.id(), peer.getId());
        requireNotBlocked(currentUser.id(), peer.getId());
        Conversation conversation = findOrCreate(currentUser.id(), peer.getId());
        return toView(conversation, currentUser.id());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<MessageDtos.MessageView> messages(Long conversationId, CurrentUser currentUser) {
        Conversation conversation = requireConversation(conversationId, currentUser.id());
        Long peerId = peerIdOf(conversation, currentUser.id());
        blockQueryApi.lockPair(currentUser.id(), peerId);
        requireNotBlocked(currentUser.id(), peerId);
        List<Message> messages = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversation.getId())
                .orderByAsc(Message::getCreatedAt));
        List<Message> unread = messages.stream()
                .filter(message -> message.getReceiverId().equals(currentUser.id()) && !Boolean.TRUE.equals(message.getReadFlag()))
                .toList();
        if (!unread.isEmpty()) {
            // 批量标记已读：一条 UPDATE 替代逐条 update 的 N+1
            messageMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Message>()
                    .set(Message::getReadFlag, true)
                    .in(Message::getId, unread.stream().map(Message::getId).toList()));
            unread.forEach(message -> message.setReadFlag(true));
        }
        return messages.stream().map(this::messageView).toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MessageDtos.MessageView send(Long conversationId, MessageDtos.SendMessageRequest request, CurrentUser currentUser) {
        Conversation conversation = requireConversation(conversationId, currentUser.id());
        Long receiverId = conversation.getUserOneId().equals(currentUser.id()) ? conversation.getUserTwoId() : conversation.getUserOneId();
        blockQueryApi.lockPair(currentUser.id(), receiverId);
        requireNotBlocked(currentUser.id(), receiverId);
        LocalDateTime now = LocalDateTime.now();
        Message message = new Message();
        message.setConversationId(conversation.getId());
        message.setSenderId(currentUser.id());
        message.setReceiverId(receiverId);
        message.setContent(request.content().trim());
        message.setReadFlag(false);
        message.setCreatedAt(now);
        messageMapper.insert(message);
        conversation.setLastMessageAt(now);
        conversation.setUpdatedAt(now);
        conversationMapper.updateById(conversation);
        outbox.enqueue(message.getId());
        return messageView(message);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public long unreadCount(CurrentUser currentUser) {
        List<Conversation> candidates = visibleConversations(currentUser.id());
        if (candidates.isEmpty()) { return 0; }
        List<Long> candidateIds = candidates.stream().map(Conversation::getId).toList();
        Map<Long, Long> unread = messageMapper.selectUnreadCounts(currentUser.id(), candidateIds).stream()
                .collect(Collectors.toMap(MessageMapper.UnreadCount::conversationId, MessageMapper.UnreadCount::unreadCount));
        // Recheck after counting so a block committed during the aggregate query cannot leak its count.
        Set<Long> blockedAtReturn = blockQueryApi.blockedUserIds(currentUser.id());
        return candidates.stream()
                .filter(conversation -> !blockedAtReturn.contains(peerIdOf(conversation, currentUser.id())))
                .mapToLong(conversation -> unread.getOrDefault(conversation.getId(), 0L))
                .sum();
    }

    private List<Conversation> visibleConversations(Long userId) {
        Set<Long> blockedUserIds = blockQueryApi.blockedUserIds(userId);
        return conversationMapper.selectList(new LambdaQueryWrapper<Conversation>()
                .and(w -> w.eq(Conversation::getUserOneId, userId).or().eq(Conversation::getUserTwoId, userId))
                .notIn(!blockedUserIds.isEmpty(), Conversation::getUserOneId, blockedUserIds)
                .notIn(!blockedUserIds.isEmpty(), Conversation::getUserTwoId, blockedUserIds)
                .orderByDesc(Conversation::getLastMessageAt));
    }

    private void requireNotBlocked(Long firstUserId, Long secondUserId) {
        if (blockQueryApi.isBlockedInLockedPair(firstUserId, secondUserId)) {
            throw new NoSuchElementException("conversation not found");
        }
    }

    private Conversation findOrCreate(Long userId, Long peerId) {
        Long one = Math.min(userId, peerId);
        Long two = Math.max(userId, peerId);
        Conversation existing = conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserOneId, one)
                .eq(Conversation::getUserTwoId, two));
        if (existing != null) {
            return existing;
        }
        LocalDateTime now = LocalDateTime.now();
        Conversation conversation = new Conversation();
        conversation.setUserOneId(one);
        conversation.setUserTwoId(two);
        conversation.setLastMessageAt(now);
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        conversationMapper.insert(conversation);
        return conversation;
    }

    private Conversation requireConversation(Long conversationId, Long userId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || (!conversation.getUserOneId().equals(userId) && !conversation.getUserTwoId().equals(userId))) {
            throw new NoSuchElementException("conversation not found");
        }
        return conversation;
    }

    private MessageDtos.ConversationView toView(Conversation conversation, Long currentUserId) {
        Long peerId = conversation.getUserOneId().equals(currentUserId) ? conversation.getUserTwoId() : conversation.getUserOneId();
        User peer = userMapper.selectById(peerId);
        Message last = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                        .eq(Message::getConversationId, conversation.getId())
                        .orderByDesc(Message::getCreatedAt))
                .stream().findFirst().orElse(null);
        long unread = messageMapper.selectCount(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversation.getId())
                .eq(Message::getReceiverId, currentUserId)
                .eq(Message::getReadFlag, false));
        return new MessageDtos.ConversationView(
                conversation.getId(),
                authorView(peer),
                last == null ? "" : last.getContent(),
                unread,
                AppTime.toOffset(conversation.getLastMessageAt()),
                AppTime.toOffset(conversation.getUpdatedAt())
        );
    }

    private MessageDtos.MessageView messageView(Message message) {
        return new MessageDtos.MessageView(message.getId(), message.getConversationId(), message.getSenderId(), message.getReceiverId(), message.getContent(), message.getReadFlag(), AppTime.toOffset(message.getCreatedAt()));
    }

    private PostDtos.AuthorView authorView(User user) {
        if (user == null) {
            return new PostDtos.AuthorView(null, "00000000", "Anonymous", null, "USER", "student", 1, "Newcomer", null, null, null, null, false);
        }
        return new PostDtos.AuthorView(
                user.getId(),
                user.getPublicUid(),
                user.getNickname(),
                user.getAvatarUrl(),
                user.getRole(),
                user.getOperatorScope(),
                user.getLevel() == null ? 1 : user.getLevel(),
                user.getLevelTitle() == null ? "Newcomer" : user.getLevelTitle(),
                null,
                user.getCollege(),
                user.getMajor(),
                user.getGrade(),
                presenceService.isOnline(user.getId())
        );
    }
}
