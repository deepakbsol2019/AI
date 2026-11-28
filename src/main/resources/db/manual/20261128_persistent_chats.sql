-- Private, persistent AI Help conversations. Apply after the existing authentication/catalog migrations.
SET time_zone = '+00:00';
CREATE TABLE IF NOT EXISTS ai_chat_pages (
 page_id BIGINT NOT NULL AUTO_INCREMENT,
 owner_user_id BIGINT NOT NULL,
 tenant_id BIGINT NOT NULL,
 name VARCHAR(150) NOT NULL,
 description TEXT NULL,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 revision BIGINT NOT NULL DEFAULT 1,
 next_message_order BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(page_id),
 CONSTRAINT uk_chat_owner_name UNIQUE(owner_user_id,tenant_id,name),
 CONSTRAINT fk_chat_owner FOREIGN KEY(owner_user_id) REFERENCES users(user_id) ON DELETE CASCADE,
 CONSTRAINT fk_chat_tenant FOREIGN KEY(tenant_id) REFERENCES tenants(tenant_id) ON DELETE CASCADE,
 INDEX ix_chat_owner_updated(owner_user_id,tenant_id,updated_at,page_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS ai_chat_messages (
 message_id BIGINT NOT NULL AUTO_INCREMENT,
 page_id BIGINT NOT NULL,
 sender_role VARCHAR(16) NOT NULL,
 content LONGTEXT NOT NULL,
 created_at DATETIME(6) NOT NULL,
 message_order BIGINT NOT NULL,
 request_id VARCHAR(36) NULL,
 PRIMARY KEY(message_id),
 CONSTRAINT fk_chat_message_page FOREIGN KEY(page_id) REFERENCES ai_chat_pages(page_id) ON DELETE CASCADE,
 CONSTRAINT uk_chat_message_order UNIQUE(page_id,message_order),
 CONSTRAINT uk_chat_request UNIQUE(page_id,request_id),
 CONSTRAINT ck_chat_sender CHECK(sender_role IN ('USER','ASSISTANT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS ai_chat_preferences (
 owner_user_id BIGINT NOT NULL,
 tenant_id BIGINT NOT NULL,
 selected_page_id BIGINT NULL,
 next_default_number BIGINT NOT NULL DEFAULT 1,
 PRIMARY KEY(owner_user_id,tenant_id),
 CONSTRAINT fk_chat_preference_owner FOREIGN KEY(owner_user_id) REFERENCES users(user_id) ON DELETE CASCADE,
 CONSTRAINT fk_chat_preference_tenant FOREIGN KEY(tenant_id) REFERENCES tenants(tenant_id) ON DELETE CASCADE,
 CONSTRAINT fk_chat_selection FOREIGN KEY(selected_page_id) REFERENCES ai_chat_pages(page_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Owner/tenant checks are mandatory in ChatService for every operation, including privileged users.
INSERT INTO api_endpoints(service_code,http_method,path_pattern,description,is_public,is_active,created_at)
SELECT 'BOOK_SEARCH',n.method,n.path,n.description,FALSE,TRUE,UTC_TIMESTAMP(6)
FROM (
 SELECT 'GET' method,'/api/ai-help/pages' path,'List own chat metadata' description
 UNION ALL SELECT 'POST','/api/ai-help/pages','Create own chat page'
 UNION ALL SELECT 'PUT','/api/ai-help/selection','Persist own selected chat'
 UNION ALL SELECT 'PATCH','/api/ai-help/pages/{pageId}','Rename own chat page'
 UNION ALL SELECT 'GET','/api/ai-help/pages/{pageId}/messages','Read one own conversation window'
 UNION ALL SELECT 'POST','/api/ai-help/chat','Save user message in one own chat'
 UNION ALL SELECT 'DELETE','/api/ai-help/pages/{pageId}/messages','Clean one own conversation'
 UNION ALL SELECT 'DELETE','/api/ai-help/pages/{pageId}','Delete one own chat and messages'
 UNION ALL SELECT 'GET','/api/ai-help/pages/{pageId}/pdf','Export one full own conversation'
) n
WHERE NOT EXISTS(SELECT 1 FROM api_endpoints e WHERE e.service_code='BOOK_SEARCH' AND e.http_method=n.method AND e.path_pattern=n.path);
