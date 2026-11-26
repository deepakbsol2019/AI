-- Additive MySQL migration: uses the existing roles/user_roles/role_permissions schema.
-- SUPER_USER assignments are operator-only, never a public registration option.
-- This catalog entry grants deletion capability when an actual DELETE operation exists;
-- it does not create a new deletion API or delete any application records.
SET time_zone = '+00:00';

INSERT INTO permissions(component_code,permission_code,display_name,permission_kind,
                        prerequisite_permission_id,description,is_active,created_at)
SELECT 'BOOKS','BOOKS.DELETE','Delete books','ACTION',v.permission_id,
       'Delete assigned book data through an explicitly implemented deletion operation',TRUE,UTC_TIMESTAMP(6)
  FROM permissions v
 WHERE v.permission_code='BOOKS.VIEW'
   AND NOT EXISTS(SELECT 1 FROM permissions WHERE permission_code='BOOKS.DELETE');

-- Keep existing explicit super-user grants complete when this migration is rerun.
-- New permissions are also resolved authoritatively by AuthorizationService.
INSERT INTO role_permissions(tenant_id,role_id,permission_id,is_explicit,created_at,updated_at)
SELECT r.tenant_id,r.role_id,p.permission_id,TRUE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
  FROM roles r CROSS JOIN permissions p
 WHERE r.role_code='SUPER_USER' AND r.is_system=TRUE AND r.status='ACTIVE' AND p.is_active=TRUE
   AND (p.permission_kind='VIEW' OR EXISTS
       (SELECT 1 FROM permissions v WHERE v.permission_id=p.prerequisite_permission_id
         AND v.component_code=p.component_code AND v.permission_kind='VIEW' AND v.is_active=TRUE))
   AND NOT EXISTS(SELECT 1 FROM role_permissions rp WHERE rp.role_id=r.role_id AND rp.permission_id=p.permission_id);
