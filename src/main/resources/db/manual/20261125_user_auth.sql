-- Manual, additive MySQL 8 migration. Back up first; run without --force.
-- All chart TIMESTAMPTZ values are UTC DATETIME(6); the connection must use UTC.
-- BYTEA -> VARBINARY(32) SHA-256 digests; JSONB -> JSON; INET -> VARCHAR(45).
-- Never assign historical book/receipt rows to a newly registered tenant.
SET time_zone = '+00:00';

CREATE TABLE IF NOT EXISTS users (
 user_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 display_name VARCHAR(150) NOT NULL,
 primary_email VARCHAR(320) NOT NULL,
 email_verified_at DATETIME(6) NULL,
 status VARCHAR(20) NOT NULL,
 security_version BIGINT NOT NULL DEFAULT 1,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 CONSTRAINT uk_users_email UNIQUE(primary_email),
 CONSTRAINT ck_users_security_version CHECK(security_version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS tenants (
 tenant_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_type VARCHAR(20) NOT NULL,
 login_policy VARCHAR(30) NOT NULL,
 owner_user_id BIGINT NULL,
 tenant_code VARCHAR(80) NOT NULL,
 display_name VARCHAR(180) NOT NULL,
 status VARCHAR(20) NOT NULL,
 authz_version BIGINT NOT NULL DEFAULT 1,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 CONSTRAINT uk_tenants_owner UNIQUE(owner_user_id),
 CONSTRAINT uk_tenants_code UNIQUE(tenant_code),
 CONSTRAINT fk_tenants_owner FOREIGN KEY(owner_user_id) REFERENCES users(user_id),
 CONSTRAINT ck_tenants_type CHECK(tenant_type IN ('INDIVIDUAL','ORGANIZATION')),
 CONSTRAINT ck_tenants_policy CHECK(login_policy IN ('PASSWORD','SSO')),
 CONSTRAINT ck_individual_password CHECK(tenant_type <> 'INDIVIDUAL' OR login_policy = 'PASSWORD'),
 CONSTRAINT ck_tenants_authz_version CHECK(authz_version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS tenant_memberships (
 membership_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 status VARCHAR(20) NOT NULL,
 authz_version BIGINT NOT NULL DEFAULT 1,
 joined_at DATETIME(6) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 current_user_id BIGINT GENERATED ALWAYS AS (CASE WHEN status IN ('ACTIVE','SUSPENDED') THEN user_id ELSE NULL END) STORED,
 CONSTRAINT uk_membership_current_user UNIQUE(current_user_id),
 CONSTRAINT uk_membership_tenant_id UNIQUE(tenant_id,membership_id),
 CONSTRAINT uk_membership_tenant_user_id UNIQUE(tenant_id,membership_id,user_id),
 CONSTRAINT fk_membership_tenant FOREIGN KEY(tenant_id) REFERENCES tenants(tenant_id),
 CONSTRAINT fk_membership_user FOREIGN KEY(user_id) REFERENCES users(user_id),
 CONSTRAINT ck_membership_status CHECK(status IN ('ACTIVE','SUSPENDED','LEFT')),
 CONSTRAINT ck_membership_authz_version CHECK(authz_version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS roles (
 role_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_id BIGINT NOT NULL,
 role_code VARCHAR(80) NOT NULL,
 display_name VARCHAR(120) NOT NULL,
 description TEXT NULL,
 is_system BOOLEAN NOT NULL,
 status VARCHAR(20) NOT NULL,
 authz_version BIGINT NOT NULL DEFAULT 1,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 CONSTRAINT uk_roles_tenant_code UNIQUE(tenant_id,role_code),
 CONSTRAINT uk_roles_tenant_id UNIQUE(tenant_id,role_id),
 CONSTRAINT fk_roles_tenant FOREIGN KEY(tenant_id) REFERENCES tenants(tenant_id),
 CONSTRAINT ck_roles_authz_version CHECK(authz_version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS user_roles (
 tenant_id BIGINT NOT NULL,
 membership_id BIGINT NOT NULL,
 role_id BIGINT NOT NULL,
 granted_by_membership_id BIGINT NULL,
 granted_at DATETIME(6) NOT NULL,
 PRIMARY KEY(membership_id,role_id),
 CONSTRAINT fk_user_roles_membership FOREIGN KEY(tenant_id,membership_id) REFERENCES tenant_memberships(tenant_id,membership_id),
 CONSTRAINT fk_user_roles_role FOREIGN KEY(tenant_id,role_id) REFERENCES roles(tenant_id,role_id),
 CONSTRAINT fk_user_roles_granter FOREIGN KEY(tenant_id,granted_by_membership_id) REFERENCES tenant_memberships(tenant_id,membership_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS permissions (
 permission_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 component_code VARCHAR(80) NOT NULL,
 permission_code VARCHAR(120) NOT NULL,
 display_name VARCHAR(150) NOT NULL,
 permission_kind VARCHAR(20) NOT NULL,
 prerequisite_permission_id BIGINT NULL,
 description TEXT NULL,
 is_active BOOLEAN NOT NULL,
 created_at DATETIME(6) NOT NULL,
 CONSTRAINT uk_permission_code UNIQUE(permission_code),
 CONSTRAINT uk_permission_component_id UNIQUE(permission_id,component_code),
 CONSTRAINT fk_permission_view_component FOREIGN KEY(prerequisite_permission_id,component_code) REFERENCES permissions(permission_id,component_code),
 CONSTRAINT ck_permission_prerequisite CHECK((permission_kind = 'VIEW' AND prerequisite_permission_id IS NULL) OR (permission_kind <> 'VIEW' AND prerequisite_permission_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS role_permissions (
 tenant_id BIGINT NOT NULL,
 role_id BIGINT NOT NULL,
 permission_id BIGINT NOT NULL,
 is_explicit BOOLEAN NOT NULL,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 PRIMARY KEY(role_id,permission_id),
 CONSTRAINT fk_role_permissions_role FOREIGN KEY(tenant_id,role_id) REFERENCES roles(tenant_id,role_id),
 CONSTRAINT fk_role_permissions_permission FOREIGN KEY(permission_id) REFERENCES permissions(permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS api_endpoints (
 endpoint_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 service_code VARCHAR(80) NOT NULL,
 http_method VARCHAR(10) NOT NULL,
 path_pattern VARCHAR(300) NOT NULL,
 description VARCHAR(200) NOT NULL,
 is_public BOOLEAN NOT NULL,
 is_active BOOLEAN NOT NULL,
 created_at DATETIME(6) NOT NULL,
 CONSTRAINT uk_api_endpoint_route UNIQUE(service_code,http_method,path_pattern)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS endpoint_permissions (
 endpoint_id BIGINT NOT NULL,
 permission_id BIGINT NOT NULL,
 created_at DATETIME(6) NOT NULL,
 PRIMARY KEY(endpoint_id,permission_id),
 CONSTRAINT fk_endpoint_permission_endpoint FOREIGN KEY(endpoint_id) REFERENCES api_endpoints(endpoint_id),
 CONSTRAINT fk_endpoint_permission_permission FOREIGN KEY(permission_id) REFERENCES permissions(permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS identity_providers (
 identity_provider_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_id BIGINT NULL,
 provider_type VARCHAR(20) NOT NULL,
 provider_code VARCHAR(80) NOT NULL,
 issuer_url VARCHAR(500) NULL,
 external_tenant_id VARCHAR(255) NULL,
 client_id VARCHAR(250) NULL,
 client_secret_ref VARCHAR(500) NULL,
 status VARCHAR(20) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 active_company_tenant_id BIGINT GENERATED ALWAYS AS (CASE WHEN provider_type = 'ORG_OIDC' AND status = 'ACTIVE' THEN tenant_id ELSE NULL END) STORED,
 CONSTRAINT uk_provider_code UNIQUE(provider_code),
 CONSTRAINT uk_provider_active_company UNIQUE(active_company_tenant_id),
 CONSTRAINT fk_provider_tenant FOREIGN KEY(tenant_id) REFERENCES tenants(tenant_id),
 CONSTRAINT ck_provider_type CHECK((provider_type = 'ORG_OIDC' AND tenant_id IS NOT NULL) OR (provider_type = 'GOOGLE' AND tenant_id IS NULL)),
 CONSTRAINT ck_provider_configuration CHECK(status = 'PENDING' OR (issuer_url IS NOT NULL AND CHAR_LENGTH(TRIM(issuer_url)) > 0 AND client_id IS NOT NULL AND CHAR_LENGTH(TRIM(client_id)) > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS external_identities (
 external_identity_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 identity_provider_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 subject VARCHAR(255) COLLATE utf8mb4_0900_bin NOT NULL,
 last_seen_at DATETIME(6) NULL,
 created_at DATETIME(6) NOT NULL,
 CONSTRAINT uk_external_provider_subject UNIQUE(identity_provider_id,subject),
 CONSTRAINT fk_external_provider FOREIGN KEY(identity_provider_id) REFERENCES identity_providers(identity_provider_id),
 CONSTRAINT fk_external_user FOREIGN KEY(user_id) REFERENCES users(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS auth_sessions (
 session_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 tenant_id BIGINT NOT NULL,
 membership_id BIGINT NOT NULL,
 auth_method VARCHAR(20) NOT NULL,
 identity_provider_id BIGINT NULL,
 mfa_completed_at DATETIME(6) NULL,
 user_security_version BIGINT NOT NULL,
 tenant_authz_version BIGINT NOT NULL,
 membership_authz_version BIGINT NOT NULL,
 authorization_fingerprint VARCHAR(64) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 last_seen_at DATETIME(6) NOT NULL,
 refresh_expires_at DATETIME(6) NOT NULL,
 absolute_expires_at DATETIME(6) NULL,
 revoked_at DATETIME(6) NULL,
 CONSTRAINT fk_session_user FOREIGN KEY(user_id) REFERENCES users(user_id),
 CONSTRAINT fk_session_membership FOREIGN KEY(tenant_id,membership_id,user_id) REFERENCES tenant_memberships(tenant_id,membership_id,user_id),
 CONSTRAINT fk_session_provider FOREIGN KEY(identity_provider_id) REFERENCES identity_providers(identity_provider_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS refresh_tokens (
 refresh_token_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 session_id BIGINT NOT NULL,
 parent_refresh_token_id BIGINT NULL,
 token_digest VARBINARY(32) NOT NULL,
 issued_at DATETIME(6) NOT NULL,
 expires_at DATETIME(6) NOT NULL,
 consumed_at DATETIME(6) NULL,
 revoked_at DATETIME(6) NULL,
 active_session_id BIGINT GENERATED ALWAYS AS (CASE WHEN consumed_at IS NULL AND revoked_at IS NULL THEN session_id ELSE NULL END) STORED,
 CONSTRAINT uk_refresh_digest UNIQUE(token_digest),
 CONSTRAINT uk_refresh_parent UNIQUE(parent_refresh_token_id),
 CONSTRAINT uk_refresh_session_id UNIQUE(session_id,refresh_token_id),
 CONSTRAINT uk_refresh_one_active UNIQUE(active_session_id),
 CONSTRAINT fk_refresh_session FOREIGN KEY(session_id) REFERENCES auth_sessions(session_id),
 CONSTRAINT fk_refresh_parent_family FOREIGN KEY(session_id,parent_refresh_token_id) REFERENCES refresh_tokens(session_id,refresh_token_id),
 CONSTRAINT ck_refresh_expiry CHECK(expires_at > issued_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS audit_events (
 audit_event_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_id BIGINT NULL,
 actor_user_id BIGINT NULL,
 event_code VARCHAR(100) NOT NULL,
 target_type VARCHAR(80) NULL,
 target_id VARCHAR(120) NULL,
 outcome VARCHAR(20) NOT NULL,
 request_id VARCHAR(100) NULL,
 source_ip VARCHAR(45) NULL,
 details JSON NULL,
 occurred_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_audit_tenant FOREIGN KEY(tenant_id) REFERENCES tenants(tenant_id),
 CONSTRAINT fk_audit_actor FOREIGN KEY(actor_user_id) REFERENCES users(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS local_credentials (
 user_id BIGINT NOT NULL PRIMARY KEY,
 password_hash VARCHAR(255) NOT NULL,
 password_changed_at DATETIME(6) NOT NULL,
 failed_attempts INTEGER NOT NULL DEFAULT 0,
 locked_until DATETIME(6) NULL,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_local_credential_user FOREIGN KEY(user_id) REFERENCES users(user_id),
 CONSTRAINT ck_local_failed_attempts CHECK(failed_attempts >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS credential_tokens (
 token_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 purpose VARCHAR(30) NOT NULL,
 target_email VARCHAR(320) NOT NULL,
 token_digest VARBINARY(32) NOT NULL,
 issued_security_version BIGINT NOT NULL,
 expires_at DATETIME(6) NOT NULL,
 consumed_at DATETIME(6) NULL,
 revoked_at DATETIME(6) NULL,
 created_at DATETIME(6) NOT NULL,
 identity_provider_id BIGINT NULL,
 CONSTRAINT uk_credential_token_digest UNIQUE(token_digest),
 CONSTRAINT fk_credential_token_user FOREIGN KEY(user_id) REFERENCES users(user_id),
 CONSTRAINT fk_credential_token_provider FOREIGN KEY(identity_provider_id) REFERENCES identity_providers(identity_provider_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS local_mfa_factors (
 factor_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 factor_type VARCHAR(20) NOT NULL,
 secret_ref VARCHAR(500) NOT NULL,
 status VARCHAR(20) NOT NULL,
 confirmed_at DATETIME(6) NULL,
 last_accepted_step BIGINT NULL,
 created_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_mfa_factor_user FOREIGN KEY(user_id) REFERENCES users(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS recovery_codes (
 recovery_code_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 code_hash VARCHAR(255) NOT NULL,
 used_at DATETIME(6) NULL,
 revoked_at DATETIME(6) NULL,
 created_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_recovery_user FOREIGN KEY(user_id) REFERENCES users(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Existing catalog rows remain NULL/unassigned. No registration claims historical data.
DELIMITER $$
DROP PROCEDURE IF EXISTS add_catalog_tenants_20261125$$
CREATE PROCEDURE add_catalog_tenants_20261125()
BEGIN
 DECLARE old_index VARCHAR(64);
 IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='book_details' AND column_name='tenant_id') THEN
  ALTER TABLE book_details ADD COLUMN tenant_id BIGINT NULL;
 END IF;
 IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='book_upload_status' AND column_name='tenant_id') THEN
  ALTER TABLE book_upload_status ADD COLUMN tenant_id BIGINT NULL;
 END IF;
 SELECT MIN(index_name) INTO old_index FROM (SELECT index_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='book_details' AND non_unique=0 AND index_name<>'PRIMARY' GROUP BY index_name HAVING COUNT(*)=1 AND MAX(column_name)='book_name') single_name_indexes;
 WHILE old_index IS NOT NULL DO
  SET @drop_book_name_index = CONCAT('ALTER TABLE book_details DROP INDEX `',REPLACE(old_index,'`','``'),'`');
  PREPARE statement_to_run FROM @drop_book_name_index;
  EXECUTE statement_to_run;
  DEALLOCATE PREPARE statement_to_run;
  SET old_index = NULL;
  SELECT MIN(index_name) INTO old_index FROM (SELECT index_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='book_details' AND non_unique=0 AND index_name<>'PRIMARY' GROUP BY index_name HAVING COUNT(*)=1 AND MAX(column_name)='book_name') single_name_indexes;
 END WHILE;
 IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='book_details' AND index_name='uk_book_tenant_name') THEN
  ALTER TABLE book_details ADD CONSTRAINT uk_book_tenant_name UNIQUE(tenant_id,book_name);
 END IF;
 IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='book_details' AND constraint_name='fk_book_tenant') THEN
  ALTER TABLE book_details ADD CONSTRAINT fk_book_tenant FOREIGN KEY(tenant_id) REFERENCES tenants(tenant_id);
 END IF;
 IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='book_upload_status' AND constraint_name='fk_upload_tenant') THEN
  ALTER TABLE book_upload_status ADD CONSTRAINT fk_upload_tenant FOREIGN KEY(tenant_id) REFERENCES tenants(tenant_id);
 END IF;
 IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='book_upload_status' AND index_name='uk_upload_tenant_receipt') THEN
  ALTER TABLE book_upload_status ADD CONSTRAINT uk_upload_tenant_receipt UNIQUE(tenant_id,book_upload_id);
 END IF;
 IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='book_details' AND constraint_name='fk_book_tenant_receipt') THEN
  ALTER TABLE book_details ADD CONSTRAINT fk_book_tenant_receipt FOREIGN KEY(tenant_id,book_upload_id) REFERENCES book_upload_status(tenant_id,book_upload_id);
 END IF;
END$$
CALL add_catalog_tenants_20261125()$$
DROP PROCEDURE add_catalog_tenants_20261125$$
DELIMITER ;

-- The catalogue lists real application operations. Explicit field filters and search behavior are unchanged.
INSERT INTO permissions(component_code,permission_code,display_name,permission_kind,prerequisite_permission_id,description,is_active,created_at)
 SELECT 'BOOKS','BOOKS.VIEW','View books','VIEW',NULL,'Search and read books within the current tenant',TRUE,UTC_TIMESTAMP(6)
 WHERE NOT EXISTS(SELECT 1 FROM permissions WHERE permission_code='BOOKS.VIEW');
INSERT INTO permissions(component_code,permission_code,display_name,permission_kind,prerequisite_permission_id,description,is_active,created_at)
 SELECT 'ACCOUNT','ACCOUNT.VIEW','View own account','VIEW',NULL,'Read the authenticated account and session',TRUE,UTC_TIMESTAMP(6)
 WHERE NOT EXISTS(SELECT 1 FROM permissions WHERE permission_code='ACCOUNT.VIEW');
INSERT INTO permissions(component_code,permission_code,display_name,permission_kind,prerequisite_permission_id,description,is_active,created_at)
 SELECT 'BOOKS','BOOKS.UPLOAD','Upload books','ACTION',v.permission_id,'Upload PDFs and inspect tenant upload receipts',TRUE,UTC_TIMESTAMP(6)
 FROM permissions v WHERE v.permission_code='BOOKS.VIEW' AND NOT EXISTS(SELECT 1 FROM permissions WHERE permission_code='BOOKS.UPLOAD');
INSERT INTO permissions(component_code,permission_code,display_name,permission_kind,prerequisite_permission_id,description,is_active,created_at)
 SELECT 'BOOKS','BOOKS.DOWNLOAD','Download books','ACTION',v.permission_id,'Download complete or selected topic PDFs',TRUE,UTC_TIMESTAMP(6)
 FROM permissions v WHERE v.permission_code='BOOKS.VIEW' AND NOT EXISTS(SELECT 1 FROM permissions WHERE permission_code='BOOKS.DOWNLOAD');
INSERT INTO permissions(component_code,permission_code,display_name,permission_kind,prerequisite_permission_id,description,is_active,created_at)
 SELECT 'BOOKS','BOOKS.EDIT','Edit book metadata','ACTION',v.permission_id,'Edit existing metadata without regenerating PDFs',TRUE,UTC_TIMESTAMP(6)
 FROM permissions v WHERE v.permission_code='BOOKS.VIEW' AND NOT EXISTS(SELECT 1 FROM permissions WHERE permission_code='BOOKS.EDIT');

INSERT INTO api_endpoints(service_code,http_method,path_pattern,description,is_public,is_active,created_at)
 SELECT 'BOOK_SEARCH',s.method,s.path,s.description,FALSE,TRUE,UTC_TIMESTAMP(6) FROM (
 SELECT 'GET' method,'/api/search/books' path,'Search tenant books' description UNION ALL
 SELECT 'GET','/api/read/book','Read tenant book' UNION ALL
 SELECT 'GET','/api/read/topics','Read selected tenant topics' UNION ALL
 SELECT 'GET','/api/download/book-download','Download tenant book' UNION ALL
 SELECT 'GET','/api/download/book-topic-download','Download selected tenant topics' UNION ALL
 SELECT 'POST','/api/upload-file','Upload tenant PDF' UNION ALL
 SELECT 'GET','/api/upload-status/{bookUploadId}','Read tenant upload status' UNION ALL
 SELECT 'PATCH','/api/books/{bookId}/metadata','Edit tenant book metadata' UNION ALL
 SELECT 'PATCH','/api/books/{bookId}/topics/{topicId}/metadata','Edit tenant topic metadata' UNION ALL
 SELECT 'GET','/api/user/auth/me','Read own account'
 ) s WHERE NOT EXISTS(SELECT 1 FROM api_endpoints e WHERE e.service_code='BOOK_SEARCH' AND e.http_method=s.method AND e.path_pattern=s.path);
INSERT INTO endpoint_permissions(endpoint_id,permission_id,created_at)
 SELECT e.endpoint_id,p.permission_id,UTC_TIMESTAMP(6) FROM api_endpoints e JOIN permissions p ON
  p.permission_code=CASE
   WHEN e.path_pattern='/api/user/auth/me' THEN 'ACCOUNT.VIEW'
   WHEN e.path_pattern LIKE '/api/download/%' THEN 'BOOKS.DOWNLOAD'
   WHEN e.path_pattern='/api/upload-file' OR e.path_pattern='/api/upload-status/{bookUploadId}' THEN 'BOOKS.UPLOAD'
   WHEN e.http_method='PATCH' THEN 'BOOKS.EDIT' ELSE 'BOOKS.VIEW' END
 WHERE e.service_code='BOOK_SEARCH' AND e.path_pattern IN('/api/search/books','/api/read/book','/api/read/topics','/api/download/book-download','/api/download/book-topic-download','/api/upload-file','/api/upload-status/{bookUploadId}','/api/books/{bookId}/metadata','/api/books/{bookId}/topics/{topicId}/metadata','/api/user/auth/me')
 AND NOT EXISTS(SELECT 1 FROM endpoint_permissions ep WHERE ep.endpoint_id=e.endpoint_id AND ep.permission_id=p.permission_id);
