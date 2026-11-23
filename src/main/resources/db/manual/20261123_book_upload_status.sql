-- Manual MySQL migration for existing databases. Legacy books retain a NULL upload ID.
CREATE TABLE IF NOT EXISTS book_upload_status (
    book_upload_id BIGINT NOT NULL AUTO_INCREMENT,
    status VARCHAR(16) NOT NULL,
    message LONGTEXT NOT NULL,
    PRIMARY KEY (book_upload_id)
) ENGINE=InnoDB;

DELIMITER $$
DROP PROCEDURE IF EXISTS add_book_upload_tracking_20261123$$
CREATE PROCEDURE add_book_upload_tracking_20261123()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE() AND table_name = 'book_details' AND column_name = 'book_upload_id') THEN
        ALTER TABLE book_details ADD COLUMN book_upload_id BIGINT NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'book_details'
                     AND column_name = 'book_upload_id' AND non_unique = 0) THEN
        ALTER TABLE book_details ADD CONSTRAINT uk_book_details_book_upload_id UNIQUE (book_upload_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.key_column_usage
                   WHERE table_schema = DATABASE() AND table_name = 'book_details'
                     AND column_name = 'book_upload_id' AND referenced_table_name = 'book_upload_status') THEN
        ALTER TABLE book_details ADD CONSTRAINT fk_book_details_upload_status
            FOREIGN KEY (book_upload_id) REFERENCES book_upload_status (book_upload_id);
    END IF;
END$$
CALL add_book_upload_tracking_20261123()$$
DROP PROCEDURE add_book_upload_tracking_20261123$$
DELIMITER ;
