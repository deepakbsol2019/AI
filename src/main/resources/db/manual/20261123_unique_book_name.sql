-- Manual MySQL migration for databases created before book-name uniqueness.
-- Run against search_book_download after reviewing the audit results below.
-- No rows are renamed, truncated, or deleted by this script.

SELECT book_id, book_name, CHAR_LENGTH(book_name) AS name_length
FROM book_details
WHERE book_name IS NULL OR TRIM(book_name) = '' OR CHAR_LENGTH(book_name) > 255;

SELECT LOWER(TRIM(book_name)) AS duplicate_name, COUNT(*) AS book_count,
       GROUP_CONCAT(book_id ORDER BY book_id) AS book_ids
FROM book_details
GROUP BY LOWER(TRIM(book_name))
HAVING COUNT(*) > 1;

-- Use a MySQL client supporting DELIMITER. Abort rather than changing existing data
-- if the audit finds a conflict that needs an explicit operator decision.
DELIMITER $$
DROP PROCEDURE IF EXISTS apply_unique_book_name_20261123$$
CREATE PROCEDURE apply_unique_book_name_20261123()
BEGIN
    IF EXISTS (SELECT 1 FROM book_details
               WHERE book_name IS NULL OR TRIM(book_name) = '' OR CHAR_LENGTH(book_name) > 255) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Book names are blank or exceed 255 characters; review audit first';
    END IF;
    IF EXISTS (SELECT 1 FROM book_details GROUP BY LOWER(TRIM(book_name)) HAVING COUNT(*) > 1) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Duplicate book names exist; review audit before adding uniqueness';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'book_details'
                     AND index_name = 'uk_book_details_book_name' AND non_unique = 0) THEN
        ALTER TABLE book_details
            MODIFY COLUMN book_name VARCHAR(255) NOT NULL,
            ADD CONSTRAINT uk_book_details_book_name UNIQUE (book_name);
    END IF;
END$$
CALL apply_unique_book_name_20261123()$$
DROP PROCEDURE apply_unique_book_name_20261123$$
DELIMITER ;
