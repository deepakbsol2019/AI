-- Additive MySQL 8 migration. Back up before applying; do not use --force.
SET time_zone = '+00:00';
DELIMITER $$
DROP PROCEDURE IF EXISTS add_user_management_20261126$$
CREATE PROCEDURE add_user_management_20261126()
BEGIN
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='users' AND column_name='management_version') THEN
  ALTER TABLE users ADD COLUMN management_version BIGINT NOT NULL DEFAULT 1;
 END IF;
END$$
CALL add_user_management_20261126()$$
DROP PROCEDURE add_user_management_20261126$$
DELIMITER ;
CREATE TABLE IF NOT EXISTS user_deletion_jobs (
 job_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 job_key VARCHAR(36) NOT NULL UNIQUE,
 tenant_id BIGINT NOT NULL,
 target_user_id BIGINT NOT NULL,
 actor_user_id BIGINT NOT NULL,
 deletion_scope VARCHAR(30) NOT NULL,
 status VARCHAR(30) NOT NULL,
 manifest LONGTEXT NOT NULL,
 db_purged BOOLEAN NOT NULL DEFAULT FALSE,
 attempts INT NOT NULL DEFAULT 0,
 last_error VARCHAR(200) NULL,
 lease_until DATETIME(6) NULL,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 active_tenant_id BIGINT GENERATED ALWAYS AS (CASE WHEN status<>'COMPLETE' THEN tenant_id ELSE NULL END) STORED,
 CONSTRAINT uk_user_deletion_active_tenant UNIQUE(active_tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- Jobs intentionally retain numeric references after the account/tenant is removed; no account FK.
INSERT INTO permissions(component_code,permission_code,display_name,permission_kind,prerequisite_permission_id,description,is_active,is_delegable,created_at)
SELECT 'USERS','USER_VIEW','View ordinary employees','VIEW',NULL,'List and view authorized user details',TRUE,TRUE,UTC_TIMESTAMP(6)
WHERE NOT EXISTS(SELECT 1 FROM permissions WHERE permission_code='USER_VIEW');
INSERT INTO permissions(component_code,permission_code,display_name,permission_kind,prerequisite_permission_id,description,is_active,is_delegable,created_at)
SELECT 'USERS',s.code,s.name,'ACTION',v.permission_id,s.name,TRUE,s.delegable,UTC_TIMESTAMP(6)
FROM permissions v CROSS JOIN (
 SELECT 'USER_EDIT' code,'Edit ordinary employee profiles' name,TRUE delegable UNION ALL
 SELECT 'USER_SOFT_DELETE','Deactivate ordinary employees',TRUE UNION ALL
 SELECT 'USER_HARD_DELETE','Delete ordinary employee accounts',TRUE UNION ALL
 SELECT 'USER_ACTIVATE','Activate selected users (super user only)',FALSE UNION ALL
 SELECT 'USER_ROLE_ASSIGN','Change user roles (super user only)',FALSE
) s WHERE v.permission_code='USER_VIEW' AND NOT EXISTS(SELECT 1 FROM permissions p WHERE p.permission_code=s.code);
INSERT INTO api_endpoints(service_code,http_method,path_pattern,description,is_public,is_active,created_at)
SELECT 'BOOK_SEARCH',s.method,s.path,'Authorized user management',FALSE,TRUE,UTC_TIMESTAMP(6) FROM (
 SELECT 'GET' method,'/api/user/users' path UNION ALL
 SELECT 'GET','/api/user/users/{id}' UNION ALL
 SELECT 'GET','/api/user/users/{id}/deletion-preview' UNION ALL
 SELECT 'PUT','/api/user/users/{id}' UNION ALL
 SELECT 'POST','/api/user/users/{id}/soft-delete' UNION ALL
 SELECT 'POST','/api/user/users/{id}/hard-delete' UNION ALL
 SELECT 'POST','/api/user/users/{id}/activate' UNION ALL
 SELECT 'GET','/api/user/users/{id}/assignable-roles' UNION ALL
 SELECT 'PUT','/api/user/users/{id}/roles' UNION ALL
 SELECT 'GET','/api/user/users/jobs/{id}' UNION ALL
 SELECT 'GET','/api/user/users/jobs' UNION ALL
 SELECT 'POST','/api/user/users/jobs/{id}/retry' UNION ALL
 SELECT 'GET','/api/user/profile'
) s WHERE NOT EXISTS(SELECT 1 FROM api_endpoints e WHERE e.service_code='BOOK_SEARCH' AND e.http_method=s.method AND e.path_pattern=s.path);
INSERT INTO endpoint_permissions(endpoint_id,permission_id,created_at)
SELECT e.endpoint_id,p.permission_id,UTC_TIMESTAMP(6) FROM api_endpoints e JOIN permissions p ON
p.permission_code=CASE WHEN e.path_pattern='/api/user/profile' THEN 'ACCOUNT.VIEW'
 WHEN e.path_pattern LIKE '%/activate' THEN 'USER_ACTIVATE'
 WHEN e.path_pattern LIKE '%/roles' OR e.path_pattern LIKE '%/assignable-roles' THEN 'USER_ROLE_ASSIGN'
 WHEN e.path_pattern LIKE '%/soft-delete' THEN 'USER_SOFT_DELETE'
 WHEN e.path_pattern LIKE '%/hard-delete' OR e.path_pattern LIKE '%/retry' THEN 'USER_HARD_DELETE'
 WHEN e.http_method='PUT' THEN 'USER_EDIT' ELSE 'USER_VIEW' END
-- Restrict seeding to this migration's original method/path identities. A prefix match
-- would add USER_VIEW to later creation endpoints when this older migration is rerun.
WHERE e.service_code='BOOK_SEARCH' AND (
 (e.http_method='GET' AND e.path_pattern IN('/api/user/profile','/api/user/users','/api/user/users/{id}',
  '/api/user/users/{id}/deletion-preview','/api/user/users/{id}/assignable-roles','/api/user/users/jobs/{id}','/api/user/users/jobs'))
 OR (e.http_method='PUT' AND e.path_pattern IN('/api/user/users/{id}','/api/user/users/{id}/roles'))
 OR (e.http_method='POST' AND e.path_pattern IN('/api/user/users/{id}/soft-delete','/api/user/users/{id}/hard-delete',
  '/api/user/users/{id}/activate','/api/user/users/jobs/{id}/retry')))
AND NOT EXISTS(SELECT 1 FROM endpoint_permissions ep WHERE ep.endpoint_id=e.endpoint_id AND ep.permission_id=p.permission_id);
