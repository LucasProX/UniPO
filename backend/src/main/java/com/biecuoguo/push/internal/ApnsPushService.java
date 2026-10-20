package com.biecuoguo.push.internal;

import com.biecuoguo.push.api.PushService;
import com.eatthepath.pushy.apns.ApnsClient;
import com.eatthepath.pushy.apns.ApnsClientBuilder;
import com.eatthepath.pushy.apns.PushNotificationResponse;
import com.eatthepath.pushy.apns.auth.ApnsSigningKey;
import com.eatthepath.pushy.apns.util.SimpleApnsPushNotification;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * APNs 真实推送。只有 app.push.apns.enabled=true 时才创建，避免本地开发硬依赖 .p8。
 *
 * 需要的配置：
 *  - APNS_KEY_PATH  .p8 文件路径（AuthKey_XXXX.p8）
 *  - APNS_KEY_ID    10 位 Key ID
 *  - APNS_TEAM_ID   Apple Developer Team ID
 *  - APNS_TOPIC     bundle id，默认 com.unipo.app
 *  - APNS_PRODUCTION true/false
 */
@Service
@ConditionalOnProperty(prefix = "app.push.apns", name = "enabled", havingValue = "true")
public class ApnsPushService implements PushService {
    private static final Logger log = LoggerFactory.getLogger(ApnsPushService.class);

    private final PushTokenService tokenService;
    private final ObjectMapper objectMapper;
    private final String keyPath;
    private final String keyId;
    private final String teamId;
    private final String topic;
    private final boolean production;

    private ApnsClient client;

    public ApnsPushService(PushTokenService tokenService, ObjectMapper objectMapper,
                           @Value("${app.push.apns.key-path:}") String keyPath,
                           @Value("${app.push.apns.key-id:}") String keyId,
                           @Value("${app.push.apns.team-id:}") String teamId,
                           @Value("${app.push.apns.topic:com.unipo.app}") String topic,
                           @Value("${app.push.apns.production:false}") boolean production) {
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
        this.keyPath = keyPath;
        this.keyId = keyId;
        this.teamId = teamId;
        this.topic = topic;
        this.production = production;
    }

    @PostConstruct
    void init() throws Exception {
        if (keyPath == null || keyPath.isBlank() || keyId == null || keyId.isBlank()
                || teamId == null || teamId.isBlank()) {
            throw new IllegalStateException("APNs 已启用，但缺少 APNS_KEY_PATH / APNS_KEY_ID / APNS_TEAM_ID");
        }
        client = new ApnsClientBuilder()
                .setApnsServer(production ? ApnsClientBuilder.PRODUCTION_APNS_HOST
                        : ApnsClientBuilder.DEVELOPMENT_APNS_HOST)
                .setSigningKey(ApnsSigningKey.loadFromPkcs8File(new File(keyPath), teamId, keyId))
                .build();
        log.info("APNs client ready (production={}, topic={})", production, topic);
    }

    @PreDestroy
    void close() throws Exception {
        if (client != null) {
            client.close();
        }
    }

    @Override
    public void send(long userId, String title, String body, Map<String, String> data) {
        if (client == null) return;
        List<String> tokens = tokenService.tokensForUser(userId);
        if (tokens.isEmpty()) return;
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "aps", Map.of("alert", Map.of("title", title, "body", body),
                            "sound", "default", "badge", 1),
                    "data", data
            ));
            for (String token : tokens) {
                SimpleApnsPushNotification notification = new SimpleApnsPushNotification(token, topic, payload);
                PushNotificationResponse<SimpleApnsPushNotification> response =
                        client.sendNotification(notification).get(10, TimeUnit.SECONDS);
                if (!response.isAccepted()) {
                    log.warn("APNs rejected token={} reason={}", token, response.getRejectionReason());
                }
            }
        } catch (Exception error) {
            log.warn("APNs push failed user={}: {}", userId, error.getMessage());
        }
    }
}
