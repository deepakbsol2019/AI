-- Authenticated acknowledgment only; no model, tools or support-query persistence.
SET time_zone = '+00:00';
INSERT INTO api_endpoints(service_code,http_method,path_pattern,description,is_public,is_active,created_at)
SELECT 'BOOK_SEARCH','POST','/api/ai-help/chat','Authenticated AI Help acknowledgment',FALSE,TRUE,UTC_TIMESTAMP(6)
WHERE NOT EXISTS(SELECT 1 FROM api_endpoints WHERE service_code='BOOK_SEARCH' AND http_method='POST' AND path_pattern='/api/ai-help/chat');
