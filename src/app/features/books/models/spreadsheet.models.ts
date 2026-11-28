export interface SpreadsheetFile {
  fileId: number;
  uploadId: number;
  fileName: string;
  fileType: string;
  sheetCount: number;
  uploadedAt: string;
  createdBy: number;
  createdByName: string | null;
}

export interface SpreadsheetSheet {
  sheetId: number;
  sheetIndex: number;
  sheetName: string;
  columnCount: number;
  dataRowCount: number;
  header: Record<string, string>;
  uploadedAt: string;
  createdBy: number;
}

export interface SpreadsheetDetails extends SpreadsheetFile {
  sheets: SpreadsheetSheet[];
}

export interface SpreadsheetPage {
  content: SpreadsheetFile[];
  number: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface SpreadsheetRowsPage {
  rows: Record<string, string | null>[];
  rowNumbers: number[];
  number: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
