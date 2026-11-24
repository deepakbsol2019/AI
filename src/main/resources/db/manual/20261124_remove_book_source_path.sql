-- Correct the earlier, unnecessary book-level path column without losing any saved paths.
-- Existing PDF files are not moved or renamed. Safe to rerun when the column is absent.
DELIMITER $$
DROP PROCEDURE IF EXISTS restore_topic_pdf_storage_20261124$$
CREATE PROCEDURE restore_topic_pdf_storage_20261124()
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='book_details' AND column_name='source_pdf_path') THEN
        IF EXISTS (SELECT 1 FROM book_details b
                    WHERE b.source_pdf_path IS NOT NULL AND TRIM(b.source_pdf_path)<>''
                      AND (b.total_pages<1 OR CHAR_LENGTH(CONCAT(b.book_name,'_offset'))>255)) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='A saved book cannot form the reserved offset row; keep source paths for manual review.';
        END IF;
        IF EXISTS (SELECT 1 FROM book_details b JOIN topic_details t ON t.book_id=b.book_id
                    WHERE b.source_pdf_path IS NOT NULL AND TRIM(b.source_pdf_path)<>''
                      AND (t.topic_name<>CONCAT(b.book_name,'_offset')
                        OR NOT(t.details<=>CONCAT(b.book_name,'_offset detail'))
                        OR NOT(t.start_page<=>1) OR NOT(t.end_page<=>b.total_pages)
                        OR t.printed_start_page IS NOT NULL OR t.printed_end_page IS NOT NULL
                        OR (t.pdf_path IS NOT NULL AND TRIM(t.pdf_path)<>'' AND t.pdf_path<>b.source_pdf_path))) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Conflicting topic PDF metadata found; preserve source paths for manual review.';
        END IF;
        IF EXISTS (SELECT 1 FROM book_details b
                    WHERE b.source_pdf_path IS NOT NULL AND TRIM(b.source_pdf_path)<>''
                      AND (SELECT COUNT(*) FROM topic_details t WHERE t.book_id=b.book_id)>1) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Duplicate topic metadata found; preserve source paths for manual review.';
        END IF;
        INSERT INTO topic_details (created_at,updated_at,details,end_page,pdf_path,printed_end_page,
                                   printed_start_page,start_page,topic_name,book_id)
            SELECT CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CONCAT(b.book_name,'_offset detail'),
                   b.total_pages,b.source_pdf_path,NULL,NULL,1,CONCAT(b.book_name,'_offset'),b.book_id
              FROM book_details b
             WHERE b.source_pdf_path IS NOT NULL AND TRIM(b.source_pdf_path)<>''
               AND NOT EXISTS(SELECT 1 FROM topic_details t WHERE t.book_id=b.book_id);
        UPDATE topic_details t JOIN book_details b ON b.book_id=t.book_id
           SET t.pdf_path=b.source_pdf_path,t.updated_at=CURRENT_TIMESTAMP(6)
         WHERE b.source_pdf_path IS NOT NULL AND TRIM(b.source_pdf_path)<>''
           AND t.topic_name=CONCAT(b.book_name,'_offset') AND (t.pdf_path IS NULL OR TRIM(t.pdf_path)='');
        IF EXISTS (SELECT 1 FROM book_details b
                    WHERE b.source_pdf_path IS NOT NULL AND TRIM(b.source_pdf_path)<>''
                      AND NOT EXISTS(SELECT 1 FROM topic_details t WHERE t.book_id=b.book_id
                                     AND t.topic_name=CONCAT(b.book_name,'_offset') AND t.pdf_path=b.source_pdf_path
                                     AND t.start_page=1 AND t.end_page=b.total_pages)) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Not every saved path was migrated; source column has been retained.';
        END IF;
        ALTER TABLE book_details DROP COLUMN source_pdf_path;
    END IF;
END$$
CALL restore_topic_pdf_storage_20261124()$$
DROP PROCEDURE restore_topic_pdf_storage_20261124$$
DELIMITER ;
