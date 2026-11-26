-- Catalog-only additive migration. No account, tenant or role grants are changed.
SET time_zone = '+00:00';
INSERT INTO api_endpoints(service_code,http_method,path_pattern,description,is_public,is_active,created_at)
SELECT 'BOOK_SEARCH','GET',s.path,s.description,FALSE,TRUE,UTC_TIMESTAMP(6) FROM (
 SELECT '/api/user/profile/details' path,'Authenticated own profile details' description UNION ALL
 SELECT '/api/user/profile/permissions','Authenticated own effective permission information' UNION ALL
 SELECT '/api/user/users/create-tenants','Authorized tenant choices for Add User'
) s WHERE NOT EXISTS(SELECT 1 FROM api_endpoints e WHERE e.service_code='BOOK_SEARCH' AND e.http_method='GET' AND e.path_pattern=s.path);
INSERT INTO endpoint_permissions(endpoint_id,permission_id,created_at)
SELECT e.endpoint_id,p.permission_id,UTC_TIMESTAMP(6) FROM api_endpoints e JOIN permissions p ON
p.permission_code=CASE WHEN e.path_pattern='/api/user/users/create-tenants' THEN 'USER_CREATE' ELSE 'ACCOUNT.VIEW' END
WHERE e.service_code='BOOK_SEARCH' AND e.http_method='GET' AND e.path_pattern IN('/api/user/profile/details','/api/user/profile/permissions','/api/user/users/create-tenants')
AND NOT EXISTS(SELECT 1 FROM endpoint_permissions ep WHERE ep.endpoint_id=e.endpoint_id AND ep.permission_id=p.permission_id);
