-- Additive MySQL 8 migration; existing INACTIVE users are not treated as deleted.
SET time_zone = '+00:00';
DELIMITER $$
DROP PROCEDURE IF EXISTS add_user_creation_20261126$$
CREATE PROCEDURE add_user_creation_20261126()
BEGIN
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='users' AND column_name='deleted_at') THEN
  ALTER TABLE users ADD COLUMN deleted_at DATETIME(6) NULL;
 END IF;
END$$
CALL add_user_creation_20261126()$$
DROP PROCEDURE add_user_creation_20261126$$
DELIMITER ;
-- A standalone entry capability: granting Create User grants no user-list, delete or role capability.
-- VIEW is the existing model's prerequisite-free entry-capability kind, not an implicit USER_VIEW grant.
INSERT INTO permissions(component_code,permission_code,display_name,permission_kind,prerequisite_permission_id,description,is_active,is_delegable,created_at)
SELECT 'USER_CREATION','USER_CREATE','Create User','VIEW',NULL,'Invite a new user into the authenticated current organisation only',TRUE,TRUE,UTC_TIMESTAMP(6)
WHERE NOT EXISTS(SELECT 1 FROM permissions WHERE permission_code='USER_CREATE');
INSERT INTO api_endpoints(service_code,http_method,path_pattern,description,is_public,is_active,created_at)
SELECT 'BOOK_SEARCH',s.method,s.path,s.description,FALSE,TRUE,UTC_TIMESTAMP(6) FROM (
 SELECT 'GET' method,'/api/user/users/capabilities' path,'Authenticated navigation capabilities' description UNION ALL
 SELECT 'GET','/api/user/users/create-options','Current-tenant user invitation options' UNION ALL
 SELECT 'POST','/api/user/users','Create a user in the authenticated tenant'
) s WHERE NOT EXISTS(SELECT 1 FROM api_endpoints e WHERE e.service_code='BOOK_SEARCH' AND e.http_method=s.method AND e.path_pattern=s.path);
INSERT INTO endpoint_permissions(endpoint_id,permission_id,created_at)
SELECT e.endpoint_id,p.permission_id,UTC_TIMESTAMP(6) FROM api_endpoints e JOIN permissions p ON
p.permission_code=CASE WHEN e.path_pattern='/api/user/users/capabilities' THEN 'ACCOUNT.VIEW' ELSE 'USER_CREATE' END
WHERE e.service_code='BOOK_SEARCH' AND ((e.http_method='GET' AND e.path_pattern IN('/api/user/users/capabilities','/api/user/users/create-options')) OR e.path_pattern='/api/user/users' AND e.http_method='POST')
AND NOT EXISTS(SELECT 1 FROM endpoint_permissions ep WHERE ep.endpoint_id=e.endpoint_id AND ep.permission_id=p.permission_id);
-- Repair the specific obsolete USER_VIEW association an older prefix-based management
-- seeder could attach to these new entry routes. Preserve every unrelated route/grant.
-- These entry policies intentionally do not require the user-list capability.
DELETE FROM endpoint_permissions
WHERE permission_id IN(SELECT permission_id FROM permissions WHERE permission_code='USER_VIEW')
AND endpoint_id IN(SELECT endpoint_id FROM api_endpoints WHERE service_code='BOOK_SEARCH' AND
 ((http_method='GET' AND path_pattern IN('/api/user/users/capabilities','/api/user/users/create-options'))
 OR (http_method='POST' AND path_pattern='/api/user/users')));
