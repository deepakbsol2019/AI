-- Authenticated display hints; current role/ownership checks still run for every DELETE.
SET time_zone = '+00:00';
INSERT INTO api_endpoints(service_code,http_method,path_pattern,description,is_public,is_active,created_at)
SELECT 'BOOK_SEARCH','GET','/api/files/delete-capabilities','Authenticated file deletion scope hints',FALSE,TRUE,UTC_TIMESTAMP(6)
WHERE NOT EXISTS(SELECT 1 FROM api_endpoints WHERE service_code='BOOK_SEARCH' AND http_method='GET' AND path_pattern='/api/files/delete-capabilities');
INSERT INTO api_endpoints(service_code,http_method,path_pattern,description,is_public,is_active,created_at)
SELECT 'BOOK_SEARCH','GET',n.path,n.description,FALSE,TRUE,UTC_TIMESTAMP(6) FROM (
 SELECT '/api/files/manage' path,'Role-scoped upload management metadata' description
 UNION ALL SELECT '/api/files/manage/{uploadId}','Role-scoped upload status'
) n WHERE NOT EXISTS(SELECT 1 FROM api_endpoints e WHERE e.service_code='BOOK_SEARCH' AND e.http_method='GET' AND e.path_pattern=n.path);
