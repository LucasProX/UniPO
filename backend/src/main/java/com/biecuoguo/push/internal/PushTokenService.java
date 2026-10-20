package com.biecuoguo.push.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.biecuoguo.domain.DeviceToken;
import com.biecuoguo.mapper.DeviceTokenMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class PushTokenService {
    private final DeviceTokenMapper mapper;

    public PushTokenService(DeviceTokenMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public void register(Long userId, String token, String platform, String environment) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token 不能为空");
        }
        mapper.delete(new LambdaQueryWrapper<DeviceToken>().eq(DeviceToken::getToken, token));
        DeviceToken entity = new DeviceToken();
        entity.setUserId(userId);
        entity.setToken(token);
        entity.setPlatform(platform == null || platform.isBlank() ? "ios" : platform);
        entity.setEnvironment(environment == null || environment.isBlank() ? "sandbox" : environment);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setLastSeenAt(LocalDateTime.now());
        mapper.insert(entity);
    }

    @Transactional
    public void unregister(String token) {
        mapper.delete(new LambdaQueryWrapper<DeviceToken>().eq(DeviceToken::getToken, token));
    }

    @Transactional
    public void deleteAllForUser(Long userId) {
        mapper.delete(new LambdaQueryWrapper<DeviceToken>().eq(DeviceToken::getUserId, userId));
    }

    public List<String> tokensForUser(Long userId) {
        return mapper.selectList(new LambdaQueryWrapper<DeviceToken>().eq(DeviceToken::getUserId, userId))
                .stream().map(DeviceToken::getToken).toList();
    }
}
