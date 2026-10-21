package com.biecuoguo.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
