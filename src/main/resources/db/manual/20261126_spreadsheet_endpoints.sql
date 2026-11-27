-- Browser endpoints reuse the established library permission model. Internal JSON has its own service-token chain.
SET time_zone = '+00:00';
INSERT INTO api_endpoints(service_code,http_method,path_pattern,description,is_public,is_active,created_at)
SELECT 'BOOK_SEARCH','GET',n.path,n.description,FALSE,TRUE,UTC_TIMESTAMP(6)
FROM (
 SELECT '/api/spreadsheets' path,'Tenant spreadsheet file listing' description
 UNION ALL SELECT '/api/spreadsheets/{fileId}','Tenant spreadsheet worksheet details'
 UNION ALL SELECT '/api/spreadsheets/{fileId}/sheets/{sheetId}/rows','Paged worksheet preview'
 UNION ALL SELECT '/api/spreadsheets/{fileId}/download','Exact original spreadsheet download'
 UNION ALL SELECT '/api/spreadsheets/{fileId}/sheets/{sheetId}/download','Standalone worksheet download'
) n
WHERE NOT EXISTS(SELECT 1 FROM api_endpoints e WHERE e.service_code='BOOK_SEARCH' AND e.http_method='GET' AND e.path_pattern=n.path);
INSERT INTO endpoint_permissions(endpoint_id,permission_id,created_at)
SELECT e.endpoint_id,p.permission_id,UTC_TIMESTAMP(6)
FROM api_endpoints e JOIN permissions p ON p.permission_code=CASE WHEN e.path_pattern LIKE '%/download' THEN 'BOOKS.DOWNLOAD' ELSE 'BOOKS.VIEW' END
WHERE e.service_code='BOOK_SEARCH' AND e.http_method='GET' AND e.path_pattern IN (
 '/api/spreadsheets','/api/spreadsheets/{fileId}','/api/spreadsheets/{fileId}/sheets/{sheetId}/rows',
 '/api/spreadsheets/{fileId}/download','/api/spreadsheets/{fileId}/sheets/{sheetId}/download')
AND NOT EXISTS(SELECT 1 FROM endpoint_permissions ep WHERE ep.endpoint_id=e.endpoint_id AND ep.permission_id=p.permission_id);
