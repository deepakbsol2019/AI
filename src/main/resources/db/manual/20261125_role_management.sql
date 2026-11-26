-- Apply after user_auth and super_user_permissions. Does not infer ownership or grant management to ADMIN.
SET time_zone = '+00:00';
DELIMITER $$
DROP PROCEDURE IF EXISTS add_role_delegation_20261125$$
CREATE PROCEDURE add_role_delegation_20261125()
BEGIN
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='permissions' AND column_name='is_delegable') THEN
  ALTER TABLE permissions ADD COLUMN is_delegable BOOLEAN NOT NULL DEFAULT FALSE;
  UPDATE permissions SET is_delegable=TRUE WHERE permission_code IN
  ('BOOKS.VIEW','BOOKS.UPLOAD','BOOKS.DOWNLOAD','BOOKS.EDIT','BOOKS.DELETE','ACCOUNT.VIEW');
 END IF;
 IF EXISTS(SELECT 1 FROM roles WHERE UPPER(role_code) IN ('ADMIN','SUPER_USER') AND is_system=FALSE) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Reserved role collision: resolve explicitly before role-management rollout';
 END IF;
END$$
CALL add_role_delegation_20261125()$$
DROP PROCEDURE add_role_delegation_20261125$$
DELIMITER ;

INSERT INTO permissions(component_code,permission_code,display_name,permission_kind,prerequisite_permission_id,description,is_active,is_delegable,created_at)
SELECT 'ROLES','ROLE_VIEW','View organization roles','VIEW',NULL,'View roles and grantable permissions in authorized scope',TRUE,TRUE,UTC_TIMESTAMP(6)
WHERE NOT EXISTS(SELECT 1 FROM permissions WHERE permission_code='ROLE_VIEW');
INSERT INTO permissions(component_code,permission_code,display_name,permission_kind,prerequisite_permission_id,description,is_active,is_delegable,created_at)
SELECT 'ROLES',s.code,s.name,'ACTION',v.permission_id,s.name,TRUE,TRUE,UTC_TIMESTAMP(6)
FROM permissions v CROSS JOIN (
 SELECT 'ROLE_CREATE' code,'Create ordinary roles' name UNION ALL
 SELECT 'ROLE_EDIT','Edit ordinary roles' UNION ALL
 SELECT 'ROLE_ASSIGN','Assign ordinary roles'
) s WHERE v.permission_code='ROLE_VIEW' AND NOT EXISTS(SELECT 1 FROM permissions p WHERE p.permission_code=s.code);
-- Unknown/future capabilities are non-delegable until reviewed. Reruns preserve operator changes.

INSERT INTO api_endpoints(service_code,http_method,path_pattern,description,is_public,is_active,created_at)
SELECT 'BOOK_SEARCH',s.method,s.path,s.description,FALSE,TRUE,UTC_TIMESTAMP(6) FROM (
 SELECT 'GET' method,'/api/user/roles' path,'List authorized organization roles' description UNION ALL
 SELECT 'GET','/api/user/roles/catalog','Read grantable permission catalog' UNION ALL
 SELECT 'GET','/api/user/roles/organizations','Select authorized role organization' UNION ALL
 SELECT 'GET','/api/user/roles/{roleId}','Read organization role' UNION ALL
 SELECT 'POST','/api/user/roles','Create organization role' UNION ALL
 SELECT 'PUT','/api/user/roles/{roleId}','Edit organization role' UNION ALL
 SELECT 'PUT','/api/user/roles/{roleId}/members/{membershipId}','Assign organization role' UNION ALL
 SELECT 'DELETE','/api/user/roles/{roleId}/members/{membershipId}','Remove organization role assignment'
) s WHERE NOT EXISTS(SELECT 1 FROM api_endpoints e WHERE e.service_code='BOOK_SEARCH' AND e.http_method=s.method AND e.path_pattern=s.path);
INSERT INTO endpoint_permissions(endpoint_id,permission_id,created_at)
SELECT e.endpoint_id,p.permission_id,UTC_TIMESTAMP(6) FROM api_endpoints e JOIN permissions p ON
p.permission_code=CASE WHEN e.http_method='GET' THEN 'ROLE_VIEW' WHEN e.http_method='POST' THEN 'ROLE_CREATE'
 WHEN e.path_pattern LIKE '%/members/%' THEN 'ROLE_ASSIGN' ELSE 'ROLE_EDIT' END
WHERE e.service_code='BOOK_SEARCH' AND e.path_pattern LIKE '/api/user/roles%'
AND NOT EXISTS(SELECT 1 FROM endpoint_permissions ep WHERE ep.endpoint_id=e.endpoint_id AND ep.permission_id=p.permission_id);
