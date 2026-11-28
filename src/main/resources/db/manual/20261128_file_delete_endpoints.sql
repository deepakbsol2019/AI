-- Apply after the existing document and authorization migrations.
-- Implements section 2 of the user's attached request; this script only registers
-- authorized DELETE routes and does not delete uploaded data or stored files.
SET time_zone = '+00:00';
INSERT INTO api_endpoints(service_code,http_method,path_pattern,description,is_public,is_active,created_at)
SELECT 'BOOK_SEARCH','DELETE',n.path,n.description,FALSE,TRUE,UTC_TIMESTAMP(6)
FROM (
 SELECT '/api/books/{bookId}' path,'Delete an owned PDF and associated artifacts by unique ID' description
 UNION ALL SELECT '/api/spreadsheets/{fileId}','Delete an owned CSV or Excel upload by unique ID'
 UNION ALL SELECT '/api/uploads/{uploadId}','Delete an owned completed or failed upload by receipt ID'
) n WHERE NOT EXISTS(SELECT 1 FROM api_endpoints e WHERE e.service_code='BOOK_SEARCH' AND e.http_method='DELETE' AND e.path_pattern=n.path);
INSERT INTO endpoint_permissions(endpoint_id,permission_id,created_at)
SELECT e.endpoint_id,p.permission_id,UTC_TIMESTAMP(6)
FROM api_endpoints e JOIN permissions p ON p.permission_code='BOOKS.DELETE'
WHERE e.service_code='BOOK_SEARCH' AND e.http_method='DELETE'
AND e.path_pattern IN('/api/books/{bookId}','/api/spreadsheets/{fileId}','/api/uploads/{uploadId}')
AND NOT EXISTS(SELECT 1 FROM endpoint_permissions ep WHERE ep.endpoint_id=e.endpoint_id AND ep.permission_id=p.permission_id);
