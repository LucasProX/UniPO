package com.biecuoguo.push.internal;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.biecuoguo.auth.api.AccountSecurityApi;
import com.biecuoguo.auth.api.DeviceSessionsEnded;
import com.biecuoguo.domain.DeviceToken;
import com.biecuoguo.mapper.DeviceTokenMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PushTokenServiceTest {
    private final DeviceTokenMapper mapper = mock(DeviceTokenMapper.class);
    private final AccountSecurityApi sessions = mock(AccountSecurityApi.class);
    private final PushTokenService service = new PushTokenService(mapper, sessions);

    @BeforeAll
    static void lambdaMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "push-tokens"), DeviceToken.class);
    }

    @Test
    void registrationRemembersTheDeviceSession() {
        service.register(7L, "s".repeat(43), "abc", null, null);
        ArgumentCaptor<DeviceToken> row = ArgumentCaptor.forClass(DeviceToken.class);
        verify(mapper).insert(row.capture());
        assertThat(row.getValue().getDeviceSessionId()).isEqualTo("s".repeat(43));
        assertThat(row.getValue().getUserId()).isEqualTo(7L);
        assertThat(row.getValue().getPlatform()).isEqualTo("ios");
    }

    @Test
    @SuppressWarnings("unchecked")
    void loggingOutOneDeviceOnlyDropsThatDevice() {
        service.onSessionsEnded(new DeviceSessionsEnded(7L, "s".repeat(43)));
        ArgumentCaptor<LambdaQueryWrapper<DeviceToken>> rows = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).delete(rows.capture());
        assertThat(rows.getValue().getSqlSegment()).contains("user_id").contains("device_session_id");

        service.onSessionsEnded(new DeviceSessionsEnded(7L, null));
        verify(mapper, times(2)).delete(rows.capture());
        assertThat(rows.getValue().getSqlSegment()).contains("user_id").doesNotContain("device_session_id");
    }

    @Test
    @SuppressWarnings("unchecked")
    void cleanupDropsDevicesWhoseSessionsEnded() {
        DeviceToken live = new DeviceToken();
        live.setDeviceSessionId("live");
        DeviceToken gone = new DeviceToken();
        gone.setDeviceSessionId("gone");
        when(mapper.selectList(any())).thenReturn(List.of(live, gone));
        when(sessions.activeSessions(List.of("live", "gone"))).thenReturn(Set.of("live"));
        service.pruneEndedSessions();
        ArgumentCaptor<LambdaQueryWrapper<DeviceToken>> rows = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper, times(2)).delete(rows.capture());
        assertThat(rows.getAllValues().get(0).getSqlSegment()).contains("device_session_id IS NULL");
        assertThat(rows.getAllValues().get(1).getSqlSegment()).contains("device_session_id IN");
        assertThat(rows.getAllValues().get(1).getParamNameValuePairs().values()).containsExactly("gone");
    }

    @Test
    void cleanupFailureIsSwallowed() {
        when(mapper.delete(any())).thenThrow(new IllegalStateException("db down"));
        service.pruneEndedSessions();
        verify(sessions, never()).activeSessions(any());
    }
}
