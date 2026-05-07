package com.biecuoguo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.biecuoguo.domain.Conversation;
import com.biecuoguo.domain.Message;
import com.biecuoguo.domain.User;
import com.biecuoguo.dto.MessageDtos;
import com.biecuoguo.dto.PostDtos;
import com.biecuoguo.mapper.ConversationMapper;
import com.biecuoguo.mapper.MessageMapper;
import com.biecuoguo.mapper.UserMapper;
import com.biecuoguo.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class MessageService {
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final UserMapper userMapper;
    private final PresenceService presenceService;

    public MessageService(ConversationMapper conversationMapper, MessageMapper messageMapper, UserMapper userMapper, PresenceService presenceService) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.userMapper = userMapper;
        this.presenceService = presenceService;
    }

    public List<MessageDtos.ConversationView> conversations(CurrentUser currentUser) {
        return conversationMapper.selectList(new LambdaQueryWrapper<Conversation>()
                        .and(w -> w.eq(Conversation::getUserOneId, currentUser.id()).or().eq(Conversation::getUserTwoId, currentUser.id()))
                        .orderByDesc(Conversation::getLastMessageAt))
                .stream()
                .map(conversation -> toView(conversation, currentUser.id()))
                .toList();
    }

    @Transactional
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
        Conversation conversation = findOrCreate(currentUser.id(), peer.getId());
        return toView(conversation, currentUser.id());
    }

    public List<MessageDtos.MessageView> messages(Long conversationId, CurrentUser currentUser) {
        Conversation conversation = requireConversation(conversationId, currentUser.id());
        List<Message> messages = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversation.getId())
                .orderByAsc(Message::getCreatedAt));
        messages.stream()
                .filter(message -> message.getReceiverId().equals(currentUser.id()) && !Boolean.TRUE.equals(message.getRead()))
                .forEach(message -> {
                    message.setRead(true);
                    messageMapper.updateById(message);
                });
        return messages.stream().map(this::messageView).toList();
    }

    @Transactional
    public MessageDtos.MessageView send(Long conversationId, MessageDtos.SendMessageRequest request, CurrentUser currentUser) {
        Conversation conversation = requireConversation(conversationId, currentUser.id());
        Long receiverId = conversation.getUserOneId().equals(currentUser.id()) ? conversation.getUserTwoId() : conversation.getUserOneId();
        LocalDateTime now = LocalDateTime.now();
        Message message = new Message();
        message.setConversationId(conversation.getId());
        message.setSenderId(currentUser.id());
        message.setReceiverId(receiverId);
        message.setContent(request.content().trim());
        message.setRead(false);
        message.setCreatedAt(now);
        messageMapper.insert(message);
        conversation.setLastMessageAt(now);
        conversation.setUpdatedAt(now);
        conversationMapper.updateById(conversation);
        return messageView(message);
    }

    public long unreadCount(CurrentUser currentUser) {
        return messageMapper.selectCount(new LambdaQueryWrapper<Message>()
                .eq(Message::getReceiverId, currentUser.id())
                .eq(Message::getRead, false));
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
                .eq(Message::getRead, false));
        return new MessageDtos.ConversationView(
                conversation.getId(),
                authorView(peer),
                last == null ? "" : last.getContent(),
                unread,
                conversation.getLastMessageAt(),
                conversation.getUpdatedAt()
        );
    }

    private MessageDtos.MessageView messageView(Message message) {
        return new MessageDtos.MessageView(message.getId(), message.getConversationId(), message.getSenderId(), message.getReceiverId(), message.getContent(), message.getRead(), message.getCreatedAt());
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
