package com.book.user;

import com.book.user.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/** Catalog tests explicitly operate as tenant 1; security tests use their own scopes. */
public abstract class TenantAwareTest {
    private TenantContext.Scope tenant;
    @BeforeEach protected void beginTrustedTestTenant() { tenant = TenantContext.use(1L); }
    @AfterEach protected void endTrustedTestTenant() { if (tenant != null) tenant.close(); }
}
