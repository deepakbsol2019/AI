-- Additive spreadsheet metadata; the existing upload receipt remains the only lifecycle.
SET time_zone = '+00:00';
CREATE TABLE IF NOT EXISTS upload_file_excel_sheet_csv (
 file_id BIGINT NOT NULL AUTO_INCREMENT,
 upload_id BIGINT NOT NULL,
 tenant_id BIGINT NOT NULL,
 file_name VARCHAR(1024) NOT NULL,
 canonical_name VARCHAR(255) NOT NULL,
 original_upload_path VARCHAR(2048) NOT NULL,
 original_format VARCHAR(4) NOT NULL,
 sheet_count INT NOT NULL,
 uploaded_at DATETIME(6) NOT NULL,
 created_by BIGINT NOT NULL,
 PRIMARY KEY(file_id),
 CONSTRAINT uk_spreadsheet_tenant_name UNIQUE(tenant_id,canonical_name),
 CONSTRAINT uk_spreadsheet_upload UNIQUE(upload_id),
 CONSTRAINT fk_spreadsheet_upload FOREIGN KEY(tenant_id,upload_id) REFERENCES book_upload_status(tenant_id,book_upload_id),
 INDEX ix_spreadsheet_tenant_date(tenant_id,uploaded_at,file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS upload_sheet_csv_xlsx (
 sheet_id BIGINT NOT NULL AUTO_INCREMENT,
 file_id BIGINT NOT NULL,
 sheet_index INT NOT NULL,
 sheet_name VARCHAR(255) NOT NULL,
 header_row_number INT NOT NULL,
 `header` JSON NOT NULL,
 column_count INT NOT NULL,
 data_row_count BIGINT NOT NULL,
 upload_path VARCHAR(2048) NOT NULL,
 uploaded_at DATETIME(6) NOT NULL,
 created_by BIGINT NOT NULL,
 PRIMARY KEY(sheet_id),
 CONSTRAINT uk_spreadsheet_sheet_order UNIQUE(file_id,sheet_index),
 CONSTRAINT uk_spreadsheet_sheet_parent UNIQUE(file_id,sheet_id),
 CONSTRAINT fk_spreadsheet_sheet_file FOREIGN KEY(file_id) REFERENCES upload_file_excel_sheet_csv(file_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS upload_sheet_rows_csv_xlsx (
 payload_id BIGINT NOT NULL AUTO_INCREMENT,
 file_id BIGINT NOT NULL,
 sheet_id BIGINT NOT NULL,
 `row_number` BIGINT NOT NULL,
 payload JSON NOT NULL,
 PRIMARY KEY(payload_id),
 CONSTRAINT uk_spreadsheet_source_row UNIQUE(sheet_id,`row_number`),
 CONSTRAINT fk_spreadsheet_row_file FOREIGN KEY(file_id) REFERENCES upload_file_excel_sheet_csv(file_id) ON DELETE CASCADE,
 CONSTRAINT fk_spreadsheet_row_parent FOREIGN KEY(file_id,sheet_id) REFERENCES upload_sheet_csv_xlsx(file_id,sheet_id) ON DELETE CASCADE,
 INDEX ix_spreadsheet_ordered_rows(file_id,sheet_id,`row_number`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
-- created_by is retained numeric provenance, deliberately without an account FK.
-- Empty original/sheet locators are reservation-only while the parent is PROCESSING;
-- publication checks require every locator to be populated before COMPLETED.
