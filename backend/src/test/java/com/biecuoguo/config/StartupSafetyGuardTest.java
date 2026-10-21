package com.biecuoguo.config;

import org.junit.jupiter.api.Test;
import com.biecuoguo.service.VerificationMailer;
import org.springframework.core.env.Environment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StartupSafetyGuardTest {

    @Test
    void rejectsMissingBootstrapAdminPassword() {
        assertNotNull(StartupSafetyGuard.bootstrapAdminPasswordProblem(null, false));
        assertNotNull(StartupSafetyGuard.bootstrapAdminPasswordProblem("  ", true));
    }

    @Test
    void rejectsShortOrPublicPlaceholderBootstrapAdminPassword() {
        assertNotNull(StartupSafetyGuard.bootstrapAdminPasswordProblem("Admin@123456", false));
        assertNotNull(StartupSafetyGuard.bootstrapAdminPasswordProblem("change_me_admin_password", false));
        assertNotNull(StartupSafetyGuard.bootstrapAdminPasswordProblem("local-dev-only-admin-password", false));
        assertNotNull(StartupSafetyGuard.bootstrapAdminPasswordProblem(
                "change-this-admin-password-" + "x".repeat(20), false));
        assertNotNull(StartupSafetyGuard.bootstrapAdminPasswordProblem(
                "local-dev-only-" + "x".repeat(24), false));
    }

    @Test
    void acceptsExplicitLongBootstrapAdminPassword() {
        assertNull(StartupSafetyGuard.bootstrapAdminPasswordProblem("a".repeat(32), false));
        assertNull(StartupSafetyGuard.bootstrapAdminPasswordProblem("a".repeat(72), false));
        assertNotNull(StartupSafetyGuard.bootstrapAdminPasswordProblem("a".repeat(73), false));
        assertNull(StartupSafetyGuard.bootstrapAdminPasswordProblem("Admin@123456", true));
        assertNotNull(StartupSafetyGuard.bootstrapAdminPasswordProblem("界".repeat(25), true));
    }

    @Test
    void onlyExplicitDevelopmentProfilesEnableDevelopmentOverrides() {
        assertTrue(StartupSafetyGuard.isDevOnlyProfiles(List.of("local")));
        assertTrue(StartupSafetyGuard.isDevOnlyProfiles(List.of("dev", "test")));
        assertFalse(StartupSafetyGuard.isDevOnlyProfiles(List.of()));
        assertFalse(StartupSafetyGuard.isDevOnlyProfiles(List.of("prod", "local")));
    }

    @Test
    void smsMockIsAllowedInExplicitTestProfileButRejectedByProductionGuard() {
        AppProperties properties = mock(AppProperties.class, RETURNS_DEEP_STUBS);
        when(properties.jwt().secret()).thenReturn("a".repeat(64));
        when(properties.bootstrap().adminPassword()).thenReturn("b".repeat(64));
        VerificationMailer mailer = mock(VerificationMailer.class);
        when(mailer.available()).thenReturn(true);
        Environment environment = mock(Environment.class);
        when(environment.getProperty("app.sms.mock-accept-any-code", Boolean.class, false)).thenReturn(true);
        StartupSafetyGuard guard = new StartupSafetyGuard(properties, environment, mailer);

        when(environment.getActiveProfiles()).thenReturn(new String[]{"test"});
        assertDoesNotThrow(guard::check);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});
        assertThrows(IllegalStateException.class, guard::check);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"test", "prod"});
        assertThrows(IllegalStateException.class, guard::check);
    }
}
