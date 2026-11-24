-- Manual MySQL migration, following this project's existing db/manual convention.
-- Safe to rerun. Existing nonempty descriptions and known book types are preserved.
DELIMITER $$
DROP PROCEDURE IF EXISTS add_book_metadata_20261124$$
CREATE PROCEDURE add_book_metadata_20261124()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE() AND table_name = 'book_details' AND column_name = 'book_type') THEN
        ALTER TABLE book_details ADD COLUMN book_type VARCHAR(120) NOT NULL DEFAULT 'Unknown';
    END IF;
    UPDATE book_details SET book_type = 'Unknown' WHERE book_type IS NULL OR TRIM(book_type) = '';
    ALTER TABLE book_details MODIFY COLUMN book_type VARCHAR(120) NOT NULL DEFAULT 'Unknown';
END$$
CALL add_book_metadata_20261124()$$
DROP PROCEDURE add_book_metadata_20261124$$
DELIMITER ;
