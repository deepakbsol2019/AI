-- Additive upload provenance/history and tenant/canonical-name uniqueness.
-- Legacy receipts retain NULL uploader; no historical ownership or event times are guessed.
SET time_zone = '+00:00';
DELIMITER $$
DROP PROCEDURE IF EXISTS add_upload_history_20261126$$
CREATE PROCEDURE add_upload_history_20261126()
BEGIN
 DECLARE old_index VARCHAR(128);
 IF EXISTS(SELECT 1 FROM book_details WHERE tenant_id IS NOT NULL GROUP BY tenant_id,LOWER(TRIM(book_name)) HAVING COUNT(*)>1) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Resolve tenant book-name comparison collisions before applying upload history; no data was changed';
 END IF;
 SELECT MIN(index_name) INTO old_index FROM (SELECT index_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='book_details' AND non_unique=0 AND index_name<>'PRIMARY' GROUP BY index_name HAVING COUNT(*)=1 AND MAX(column_name)='book_name') global_name_indexes;
 WHILE old_index IS NOT NULL DO
  SET @drop_global_book_name = CONCAT('ALTER TABLE book_details DROP INDEX `',REPLACE(old_index,'`','``'),'`');
  PREPARE name_index_statement FROM @drop_global_book_name;
  EXECUTE name_index_statement;
  DEALLOCATE PREPARE name_index_statement;
  SELECT MIN(index_name) INTO old_index FROM (SELECT index_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='book_details' AND non_unique=0 AND index_name<>'PRIMARY' GROUP BY index_name HAVING COUNT(*)=1 AND MAX(column_name)='book_name') global_name_indexes;
 END WHILE;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='book_upload_status' AND column_name='uploader_user_id') THEN
  ALTER TABLE book_upload_status ADD COLUMN uploader_user_id BIGINT NULL;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='book_upload_status' AND column_name='file_name') THEN
  ALTER TABLE book_upload_status ADD COLUMN file_name VARCHAR(1024) NULL;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='book_upload_status' AND column_name='book_name') THEN
  ALTER TABLE book_upload_status ADD COLUMN book_name VARCHAR(255) NULL;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='book_upload_status' AND column_name='created_at') THEN
  ALTER TABLE book_upload_status ADD COLUMN created_at DATETIME(6) NULL;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='book_upload_status' AND column_name='updated_at') THEN
  ALTER TABLE book_upload_status ADD COLUMN updated_at DATETIME(6) NULL;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='book_upload_status' AND column_name='processing_state') THEN
  ALTER TABLE book_upload_status ADD COLUMN processing_state VARCHAR(16) NULL;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='book_upload_status' AND index_name='ix_upload_actor_created') THEN
  CREATE INDEX ix_upload_actor_created ON book_upload_status(tenant_id,uploader_user_id,created_at,book_upload_id);
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='book_details' AND column_name='canonical_book_name') THEN
  ALTER TABLE book_details ADD COLUMN canonical_book_name VARCHAR(255) GENERATED ALWAYS AS (LOWER(TRIM(book_name))) STORED;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='book_details' AND index_name='uk_book_tenant_canonical_name') THEN
  CREATE UNIQUE INDEX uk_book_tenant_canonical_name ON book_details(tenant_id,canonical_book_name);
 END IF;
END$$
CALL add_upload_history_20261126()$$
DROP PROCEDURE add_upload_history_20261126$$
DELIMITER ;
-- uploader_user_id intentionally has no account FK: account-only deletion preserves receipts/content.
INSERT INTO api_endpoints(service_code,http_method,path_pattern,description,is_public,is_active,created_at)
SELECT 'BOOK_SEARCH','GET','/api/my-uploads','Authenticated uploader history',FALSE,TRUE,UTC_TIMESTAMP(6)
WHERE NOT EXISTS(SELECT 1 FROM api_endpoints WHERE service_code='BOOK_SEARCH' AND http_method='GET' AND path_pattern='/api/my-uploads');
INSERT INTO endpoint_permissions(endpoint_id,permission_id,created_at)
SELECT e.endpoint_id,p.permission_id,UTC_TIMESTAMP(6) FROM api_endpoints e JOIN permissions p ON p.permission_code='BOOKS.UPLOAD'
WHERE e.service_code='BOOK_SEARCH' AND e.http_method='GET' AND e.path_pattern='/api/my-uploads'
AND NOT EXISTS(SELECT 1 FROM endpoint_permissions ep WHERE ep.endpoint_id=e.endpoint_id AND ep.permission_id=p.permission_id);
