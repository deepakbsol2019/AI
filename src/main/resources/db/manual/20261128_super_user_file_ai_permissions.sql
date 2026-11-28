-- Explicit, repeatable SUPER_USER catalog grants. Run after persistent-chats and file-delete migrations.
-- This never assigns a role to a user, grants ADMIN global access, or exposes another user's chats.
SET time_zone = '+00:00';

INSERT INTO permissions(component_code,permission_code,display_name,permission_kind,
 prerequisite_permission_id,description,is_active,is_delegable,created_at)
SELECT 'AI_HELP','AI_HELP.VIEW','View AI Help','VIEW',NULL,
 'Open AI Help and view the authenticated owner''s private chats',TRUE,FALSE,UTC_TIMESTAMP(6)
WHERE NOT EXISTS(SELECT 1 FROM permissions WHERE permission_code='AI_HELP.VIEW');

INSERT INTO permissions(component_code,permission_code,display_name,permission_kind,
 prerequisite_permission_id,description,is_active,is_delegable,created_at)
SELECT 'AI_HELP','AI_HELP.CHAT','Use AI chat','ACTION',v.permission_id,
 'Create, send, rename, select, export, clean and delete the authenticated owner''s private chats',TRUE,FALSE,UTC_TIMESTAMP(6)
FROM permissions v WHERE v.permission_code='AI_HELP.VIEW'
AND NOT EXISTS(SELECT 1 FROM permissions WHERE permission_code='AI_HELP.CHAT');

INSERT INTO permissions(component_code,permission_code,display_name,permission_kind,
 prerequisite_permission_id,description,is_active,is_delegable,created_at)
SELECT 'BOOKS','BOOKS.DELETE','Delete books and files','ACTION',v.permission_id,
 'Delete files through the implemented role-scoped deletion endpoints',TRUE,TRUE,UTC_TIMESTAMP(6)
FROM permissions v WHERE v.permission_code='BOOKS.VIEW'
AND NOT EXISTS(SELECT 1 FROM permissions WHERE permission_code='BOOKS.DELETE');

INSERT INTO endpoint_permissions(endpoint_id,permission_id,created_at)
SELECT e.endpoint_id,p.permission_id,UTC_TIMESTAMP(6)
FROM api_endpoints e JOIN permissions p ON p.permission_code=
 CASE WHEN e.http_method='GET' THEN 'AI_HELP.VIEW' ELSE 'AI_HELP.CHAT' END
WHERE e.service_code='BOOK_SEARCH' AND e.path_pattern LIKE '/api/ai-help/%'
AND NOT EXISTS(SELECT 1 FROM endpoint_permissions ep WHERE ep.endpoint_id=e.endpoint_id AND ep.permission_id=p.permission_id);

INSERT INTO role_permissions(tenant_id,role_id,permission_id,is_explicit,created_at,updated_at)
SELECT r.tenant_id,r.role_id,p.permission_id,TRUE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM roles r CROSS JOIN permissions p
WHERE r.role_code='SUPER_USER' AND r.is_system=TRUE AND r.status='ACTIVE'
AND p.permission_code IN ('BOOKS.VIEW','BOOKS.DELETE','AI_HELP.VIEW','AI_HELP.CHAT') AND p.is_active=TRUE
AND (p.permission_kind='VIEW' OR EXISTS(SELECT 1 FROM permissions v
 WHERE v.permission_id=p.prerequisite_permission_id AND v.component_code=p.component_code
 AND v.permission_kind='VIEW' AND v.is_active=TRUE))
AND NOT EXISTS(SELECT 1 FROM role_permissions rp WHERE rp.role_id=r.role_id AND rp.permission_id=p.permission_id);
