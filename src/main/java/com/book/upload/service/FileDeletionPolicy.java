package com.book.upload.service;

import com.book.user.security.UserPrincipal;
import com.book.user.service.AuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Scope comes exclusively from a freshly authenticated principal and current database roles. */
@Service
public class FileDeletionPolicy {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    public FileDeletionPolicy(JdbcTemplate jdbc, AuthorizationService authorization) {
        this.jdbc=jdbc; this.authorization=authorization;
    }
    public record Scope(long tenantId,long userId,boolean global,boolean admin,boolean permitted) {
        public boolean allows(long tenant,long uploader) {
            return permitted && (global || tenant==tenantId && (admin || uploader==userId));
        }
        public void require(long tenant,long uploader) {
            if(!allows(tenant,uploader)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"The upload was not found in your permitted deletion scope.");
        }
    }
    public Scope scope(UserPrincipal actor) {
        if(actor==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Authentication is required.");
        boolean global=authorization.isSuperUser(actor),admin=authorization.isTenantAdmin(actor);
        boolean permitted=global || admin;
        if(!permitted) {
            var grants=authorization.effectivePermissions(actor);
            var ids=jdbc.queryForList("SELECT permission_id FROM permissions WHERE permission_code='BOOKS.DELETE' AND is_active=TRUE",Long.class);
            permitted=ids.size()==1 && grants.contains(ids.getFirst());
        }
        return new Scope(actor.tenantId(),actor.userId(),global,admin,permitted);
    }
}
