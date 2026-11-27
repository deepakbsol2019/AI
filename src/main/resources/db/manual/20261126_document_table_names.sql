-- Terminal MySQL 8 table-name migration. Run after all earlier manual migrations,
-- with the application/upload workers stopped and a verified schema/data backup.
-- This script NEVER purges rows. PDF data cleanup is a separate explicit operation.
-- Earlier historical CREATE TABLE scripts must not be rerun after this migration.
-- MySQL DDL commits implicitly; the preflight completes before any table is changed.
SET time_zone = '+00:00';
DELIMITER $$
DROP PROCEDURE IF EXISTS normalize_document_tables_20261126$$
CREATE PROCEDURE normalize_document_tables_20261126()
BEGIN
    DECLARE finished BOOLEAN DEFAULT FALSE;
    DECLARE cursor_open BOOLEAN DEFAULT FALSE;
    DECLARE legacy_name VARCHAR(64);
    DECLARE canonical_name VARCHAR(64);
    DECLARE legacy_exists INT;
    DECLARE canonical_exists INT;
    DECLARE action_name VARCHAR(8);
    DECLARE drop_names TEXT DEFAULT '';
    DECLARE rename_pairs TEXT DEFAULT '';
    DECLARE failure_message VARCHAR(128);
    DECLARE table_pairs CURSOR FOR
        SELECT old_name,new_name,transition_action
          FROM document_table_name_map_20261126 ORDER BY drop_order;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET finished = TRUE;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        IF cursor_open THEN CLOSE table_pairs; END IF;
        DROP TEMPORARY TABLE IF EXISTS document_table_name_map_20261126;
        RESIGNAL;
    END;

    DROP TEMPORARY TABLE IF EXISTS document_table_name_map_20261126;
    CREATE TEMPORARY TABLE document_table_name_map_20261126 (
        old_name VARCHAR(64) NOT NULL PRIMARY KEY,
        new_name VARCHAR(64) NOT NULL,
        drop_order INT NOT NULL,
        transition_action VARCHAR(8) NOT NULL DEFAULT 'KEEP'
    );
    INSERT INTO document_table_name_map_20261126(old_name,new_name,drop_order) VALUES
        ('upload_sheet_rows_csv_xlsx','spreadsheet_sheet_rows',1),
        ('upload_sheet_csv_xlsx','spreadsheet_sheet_details',2),
        ('topic_details','pdf_file_topic_details',3),
        ('book_details','pdf_file_details',4),
        ('upload_file_excel_sheet_csv','spreadsheet_file_details',5),
        ('author_details','pdf_file_author_details',6),
        ('book_upload_status','pdf_file_status',7);

    -- Decide every action and reject every ambiguous/data-bearing collision first.
    OPEN table_pairs;
    SET cursor_open=TRUE;
    preflight: LOOP
        FETCH table_pairs INTO legacy_name,canonical_name,action_name;
        IF finished THEN LEAVE preflight; END IF;
        IF EXISTS (
            SELECT 1 FROM information_schema.tables
             WHERE table_schema=DATABASE() AND table_name IN (legacy_name,canonical_name)
               AND table_type <> 'BASE TABLE'
        ) THEN
            SET failure_message=LEFT(CONCAT('Document name is occupied by a non-table object: ',legacy_name,' / ',canonical_name),128);
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT=failure_message;
        END IF;
        SELECT COUNT(*) INTO legacy_exists FROM information_schema.tables
         WHERE table_schema=DATABASE() AND table_name=legacy_name AND table_type='BASE TABLE';
        SELECT COUNT(*) INTO canonical_exists FROM information_schema.tables
         WHERE table_schema=DATABASE() AND table_name=canonical_name AND table_type='BASE TABLE';
        IF legacy_exists=0 AND canonical_exists=0 THEN
            SET failure_message=LEFT(CONCAT('Apply prior migrations: neither ',legacy_name,' nor ',canonical_name,' exists'),128);
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT=failure_message;
        ELSEIF legacy_exists=1 AND canonical_exists=1 THEN
            SET @document_legacy_rows_20261126=0;
            SET @document_name_sql_20261126=CONCAT('SELECT COUNT(*) INTO @document_legacy_rows_20261126 FROM `',legacy_name,'`');
            PREPARE document_name_statement FROM @document_name_sql_20261126;
            EXECUTE document_name_statement;
            DEALLOCATE PREPARE document_name_statement;
            IF @document_legacy_rows_20261126<>0 THEN
                SET failure_message=LEFT(CONCAT('Both table names exist and legacy data requires review: ',legacy_name,' / ',canonical_name),128);
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT=failure_message;
            END IF;
            IF (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=legacy_name)
                 <> (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=canonical_name)
               OR EXISTS (
                    SELECT 1 FROM information_schema.columns old_column
                    LEFT JOIN information_schema.columns new_column
                      ON new_column.table_schema=old_column.table_schema
                     AND new_column.table_name=canonical_name
                     AND new_column.ordinal_position=old_column.ordinal_position
                    WHERE old_column.table_schema=DATABASE() AND old_column.table_name=legacy_name
                      AND (new_column.column_name IS NULL
                        OR NOT (old_column.column_name <=> new_column.column_name)
                        OR NOT (old_column.column_type <=> new_column.column_type)
                        OR NOT (old_column.is_nullable <=> new_column.is_nullable)
                        OR NOT (old_column.column_default <=> new_column.column_default)
                        OR NOT (old_column.extra <=> new_column.extra)
                        OR NOT (old_column.generation_expression <=> new_column.generation_expression)
                        OR NOT (old_column.character_set_name <=> new_column.character_set_name)
                        OR NOT (old_column.collation_name <=> new_column.collation_name))
               ) THEN
                SET failure_message=LEFT(CONCAT('Empty legacy table differs from canonical schema; review ',legacy_name),128);
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT=failure_message;
            END IF;
            UPDATE document_table_name_map_20261126 SET transition_action='DROP' WHERE old_name=legacy_name;
            SET drop_names=CONCAT_WS(',',NULLIF(drop_names,''),legacy_name);
        ELSEIF legacy_exists=1 THEN
            UPDATE document_table_name_map_20261126 SET transition_action='RENAME' WHERE old_name=legacy_name;
            SET rename_pairs=CONCAT_WS(',',NULLIF(rename_pairs,''),CONCAT('`',legacy_name,'` TO `',canonical_name,'`'));
        END IF;
    END LOOP;
    CLOSE table_pairs;
    SET cursor_open=FALSE;

    -- An empty duplicate parent can still be referenced by a nonduplicate child.
    -- Never disable FK checks, drop an unrelated child, or silently repoint such data.
    IF EXISTS (
        SELECT 1 FROM information_schema.key_column_usage
         WHERE referenced_table_schema=DATABASE()
           AND FIND_IN_SET(referenced_table_name,drop_names)>0
           AND (table_schema<>DATABASE() OR FIND_IN_SET(table_name,drop_names)=0)
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT='An empty legacy duplicate has references outside the duplicate cleanup set; review foreign keys first';
    END IF;

    -- Only proven-empty legacy duplicates are removed; children precede parents.
    SET finished=FALSE;
    OPEN table_pairs;
    SET cursor_open=TRUE;
    cleanup: LOOP
        FETCH table_pairs INTO legacy_name,canonical_name,action_name;
        IF finished THEN LEAVE cleanup; END IF;
        IF action_name='DROP' THEN
            SET @document_name_sql_20261126=CONCAT('DROP TABLE `',legacy_name,'`');
            PREPARE document_name_statement FROM @document_name_sql_20261126;
            EXECUTE document_name_statement;
            DEALLOCATE PREPARE document_name_statement;
        END IF;
    END LOOP;
    CLOSE table_pairs;
    SET cursor_open=FALSE;

    -- One atomic multi-table rename preserves rows, indexes and FK relationships.
    IF rename_pairs<>'' THEN
        SET @document_name_sql_20261126=CONCAT('RENAME TABLE ',rename_pairs);
        PREPARE document_name_statement FROM @document_name_sql_20261126;
        EXECUTE document_name_statement;
        DEALLOCATE PREPARE document_name_statement;
    END IF;
    DROP TEMPORARY TABLE document_table_name_map_20261126;
END$$
CALL normalize_document_tables_20261126()$$
DROP PROCEDURE normalize_document_tables_20261126$$
DELIMITER ;
