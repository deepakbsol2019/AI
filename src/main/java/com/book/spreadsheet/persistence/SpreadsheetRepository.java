package com.book.spreadsheet.persistence;

import java.sql.*;
import java.time.*;
import java.util.*;
import com.book.entity.BookUploadStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** JDBC batches preserve generated row IDs independently of source positions. */
@Repository
public class SpreadsheetRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();
    private final String jsonParameter;

    public SpreadsheetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.jsonParameter = jdbc.execute((java.sql.Connection connection) ->
                connection.getMetaData().getDatabaseProductName().equals("H2")) ? "? FORMAT JSON" : "?";
    }

    public record FileRecord(Long fileId, Long uploadId, long tenantId, String fileName, int sheetCount,
            Instant uploadedAt, long createdBy, String createdByName, String originalUploadPath, String format) { }
    public record SheetRecord(Long sheetId, Long fileId, int sheetIndex, String sheetName, int columnCount,
            long dataRowCount, Map<String,String> header, String uploadPath, Instant uploadedAt, long createdBy) { }
    public record RowRecord(long rowNumber, Map<String,String> payload) { }
    public record FilePage(List<FileRecord> content, long totalElements, int number, int size, int totalPages) { }
    public record Reservation(BookUploadStatus upload, FileRecord file) { }

    /** Receipt and unique reservation commit together; duplicate losers create neither. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reservation reserve(long tenant, long user, String fileName, String canonicalName, String format) {
        if (tenant <= 0 || user <= 0 || canonicalName == null || canonicalName.isBlank() || canonicalName.length() > 255)
            throw invalid("Choose a valid spreadsheet filename.");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM pdf_file_details WHERE tenant_id=? AND LOWER(TRIM(book_name))=LOWER(TRIM(?))", Long.class, tenant, canonicalName) > 0
                || jdbc.queryForObject("SELECT COUNT(*) FROM spreadsheet_file_details WHERE tenant_id=? AND canonical_name=LOWER(TRIM(?))", Long.class, tenant, canonicalName) > 0)
            throw duplicate();
        Instant now = Instant.now();
        String message = "Your file is being processed in the background. Check My Uploads for its outcome.";
        try {
            long upload = insert("INSERT INTO pdf_file_status(tenant_id,uploader_user_id,file_name,created_at,updated_at,processing_state,status,message) VALUES(?,?,?,?,?,'PENDING','inprogress',?)",
                    tenant, user, fileName, utc(now), utc(now), message);
            long file = insert("INSERT INTO spreadsheet_file_details(upload_id,tenant_id,file_name,canonical_name,original_upload_path,sheet_count,uploaded_at,created_by,original_format) VALUES(?,?,?,?,?,0,?,?,?)",
                    upload, tenant, fileName, canonicalName.toLowerCase(Locale.ROOT).strip(), "", utc(now), user, format);
            BookUploadStatus receipt = new BookUploadStatus();
            receipt.setBookUploadId(upload); receipt.setTenantId(tenant); receipt.setUploadedBy(user);
            receipt.setFileName(fileName); receipt.setCreatedAt(now); receipt.setUpdatedAt(now);
            receipt.setStatus(BookUploadStatus.IN_PROGRESS); receipt.setState("PENDING"); receipt.setMessage(message);
            return new Reservation(receipt, new FileRecord(file, upload, tenant, fileName, 0, now, user, "", "", format));
        } catch (DuplicateKeyException conflict) {
            throw duplicate();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void original(long tenant, long user, long file, String path, int sheetCount) {
        if (path == null || path.isBlank() || sheetCount < 1) throw invalid("The original file and sheets are required.");
        requireProcessing(tenant, user, file);
        if (jdbc.update("UPDATE spreadsheet_file_details SET original_upload_path=?,sheet_count=? WHERE file_id=? AND tenant_id=? AND created_by=?", path, sheetCount, file, tenant, user) != 1) throw missing();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SheetRecord createSheet(long tenant, long user, long file, int index, String name, int columns, Map<String,String> header) {
        requireProcessing(tenant, user, file);
        if (index < 1 || columns < 0 || name == null) throw invalid("Invalid worksheet metadata.");
        Instant now = Instant.now();
        long id = insert("INSERT INTO spreadsheet_sheet_details(file_id,sheet_index,sheet_name,header_row_number,`header`,column_count,data_row_count,upload_path,uploaded_at,created_by) VALUES(?,?,?,1," + jsonParameter + ",?,0,'',?,?)",
                file, index, name, encode(header), columns, utc(now), user);
        return new SheetRecord(id, file, index, name, columns, 0, freeze(header), "", now, user);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sheetArtifact(long tenant, long user, long file, long sheet, String path) {
        requireProcessing(tenant, user, file);
        if (path == null || path.isBlank() || jdbc.update("UPDATE spreadsheet_sheet_details SET upload_path=? WHERE file_id=? AND sheet_id=? AND created_by=?", path, file, sheet, user) != 1) throw missing();
    }

    /** Called through the separate worker bean, in that worker's independent transaction. */
    public int insertRows(long tenant, long user, long file, long sheet, List<Map<String,String>> rows, List<Long> numbers) {
        requireProcessing(tenant, user, file);
        if (rows.isEmpty() || rows.size() > 500 || rows.size() != numbers.size()) throw invalid("A row batch must contain 1 to 500 data rows.");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM spreadsheet_sheet_details WHERE sheet_id=? AND file_id=? AND created_by=?", Long.class, sheet, file, user) != 1) throw missing();
        List<Object[]> values = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            if (numbers.get(i) == null || numbers.get(i) < 2) throw invalid("A data source row number starts at 2.");
            values.add(new Object[] {file, sheet, numbers.get(i), encode(rows.get(i))});
        }
        int[] changes = jdbc.batchUpdate("INSERT INTO spreadsheet_sheet_rows(file_id,sheet_id,`row_number`,payload) VALUES(?,?,?," + jsonParameter + ")", values);
        for (int changed : changes) if (changed == Statement.EXECUTE_FAILED || changed == 0) throw new IllegalStateException("A spreadsheet row batch was not persisted completely.");
        return rows.size();
    }

    /** Final publication is one short transaction after every worker and artifact has succeeded. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(long tenant, long user, long file, Map<Long,Long> acknowledgedCounts) {
        FileRecord source = requireProcessing(tenant, user, file);
        List<SheetRecord> sheets = sheets(tenant, file);
        if (source.originalUploadPath().isBlank() || source.sheetCount() != sheets.size() || sheets.size() != acknowledgedCounts.size()) throw new IllegalStateException("Spreadsheet metadata is incomplete.");
        for (SheetRecord sheet : sheets) {
            long actual = jdbc.queryForObject("SELECT COUNT(*) FROM spreadsheet_sheet_rows WHERE file_id=? AND sheet_id=?", Long.class, file, sheet.sheetId());
            if (sheet.uploadPath().isBlank() || !Objects.equals(acknowledgedCounts.get(sheet.sheetId()), actual)) throw new IllegalStateException("Worksheet counts or artifacts are incomplete.");
            jdbc.update("UPDATE spreadsheet_sheet_details SET data_row_count=? WHERE file_id=? AND sheet_id=?", actual, file, sheet.sheetId());
        }
        if (jdbc.update("UPDATE pdf_file_status SET status='Success',processing_state='COMPLETED',message=?,book_name=?,updated_at=? WHERE book_upload_id=? AND tenant_id=? AND uploader_user_id=? AND status='inprogress'",
                "Uploaded " + source.fileName() + " successfully.", source.fileName(), utc(Instant.now()), source.uploadId(), tenant, user) != 1) throw missing();
    }

    /** Only this failed reservation is removed, after workers have quiesced; receipt survives. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void removeAttempt(long tenant, long user, long file) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM spreadsheet_file_details f JOIN pdf_file_status u ON u.book_upload_id=f.upload_id AND u.tenant_id=f.tenant_id WHERE f.file_id=? AND f.tenant_id=? AND f.created_by=? AND u.status='inprogress'", Long.class, file, tenant, user) == 1)
            jdbc.update("DELETE FROM spreadsheet_file_details WHERE file_id=? AND tenant_id=? AND created_by=?", file, tenant, user);
    }

    public FilePage listCompleted(long tenant, int page, int size) { return listCompleted(tenant, page, size, ""); }
    public FilePage listCompleted(long tenant, int page, int size, String search) {
        if (tenant <= 0 || page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) throw invalid("Choose a valid spreadsheet page and size (1–100).");
        String needle = "%" + (search == null ? "" : search.strip()).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        String where = " FROM spreadsheet_file_details f JOIN pdf_file_status u ON u.book_upload_id=f.upload_id AND u.tenant_id=f.tenant_id LEFT JOIN users owner ON owner.user_id=f.created_by WHERE f.tenant_id=? AND u.status='Success' AND u.processing_state='COMPLETED' AND LOWER(f.file_name) LIKE LOWER(?) ESCAPE '!'";
        long count = jdbc.queryForObject("SELECT COUNT(*)" + where, Long.class, tenant, needle);
        List<FileRecord> content = jdbc.query("SELECT f.*,COALESCE(owner.display_name,'Former user') created_by_name" + where + " ORDER BY f.uploaded_at DESC,f.file_id DESC LIMIT ? OFFSET ?", this::file, tenant, needle, size, (long) page * size);
        return new FilePage(content, count, page, size, (int) Math.ceil((double) count / size));
    }

    public FileRecord requireCompleted(long tenant, Long id) {
        if (id == null || id <= 0 || tenant <= 0) throw invalid("A positive file ID is required.");
        List<FileRecord> rows = jdbc.query("SELECT f.*,COALESCE(owner.display_name,'Former user') created_by_name FROM spreadsheet_file_details f JOIN pdf_file_status u ON u.book_upload_id=f.upload_id AND u.tenant_id=f.tenant_id LEFT JOIN users owner ON owner.user_id=f.created_by WHERE f.tenant_id=? AND f.file_id=? AND u.status='Success' AND u.processing_state='COMPLETED'", this::file, tenant, id);
        if (rows.size() != 1) throw missing();
        return rows.getFirst();
    }

    public List<SheetRecord> sheets(long tenant, long file) {
        return jdbc.query("SELECT s.* FROM spreadsheet_sheet_details s JOIN spreadsheet_file_details f ON f.file_id=s.file_id WHERE f.tenant_id=? AND f.file_id=? ORDER BY s.sheet_index", this::sheet, tenant, file);
    }

    public List<RowRecord> rows(long tenant, long file, long sheet, long afterRowNumber, int limit) {
        if (afterRowNumber < 0 || limit < 1 || limit > 500) throw invalid("Choose a valid row cursor and size (1–500).");
        return jdbc.query("SELECT r.`row_number`,r.payload FROM spreadsheet_sheet_rows r JOIN spreadsheet_sheet_details s ON s.file_id=r.file_id AND s.sheet_id=r.sheet_id JOIN spreadsheet_file_details f ON f.file_id=r.file_id JOIN pdf_file_status u ON u.book_upload_id=f.upload_id AND u.tenant_id=f.tenant_id WHERE f.tenant_id=? AND f.file_id=? AND s.sheet_id=? AND u.status='Success' AND u.processing_state='COMPLETED' AND r.`row_number`>? ORDER BY r.`row_number` LIMIT ?",
                (rs, index) -> new RowRecord(rs.getLong(1), decode(rs.getString(2))), tenant, file, sheet, afterRowNumber, limit);
    }

    public List<RowRecord> rowsPage(long tenant,long file,long sheet,int page,int size) {
        if(page<0 || size<1 || size>500 || (long)page*size>Integer.MAX_VALUE)throw invalid("Choose a valid spreadsheet page and size (1–500).");
        return jdbc.query("SELECT r.`row_number`,r.payload FROM spreadsheet_sheet_rows r JOIN spreadsheet_sheet_details s ON s.file_id=r.file_id AND s.sheet_id=r.sheet_id JOIN spreadsheet_file_details f ON f.file_id=r.file_id JOIN pdf_file_status u ON u.book_upload_id=f.upload_id AND u.tenant_id=f.tenant_id WHERE f.tenant_id=? AND f.file_id=? AND s.sheet_id=? AND u.status='Success' AND u.processing_state='COMPLETED' ORDER BY r.`row_number` LIMIT ? OFFSET ?",
                (rs,index)->new RowRecord(rs.getLong(1),decode(rs.getString(2))),tenant,file,sheet,size,(long)page*size);
    }

    private FileRecord requireProcessing(long tenant, long user, long id) {
        List<FileRecord> rows = jdbc.query("SELECT f.*,'' created_by_name FROM spreadsheet_file_details f JOIN pdf_file_status u ON u.book_upload_id=f.upload_id AND u.tenant_id=f.tenant_id WHERE f.file_id=? AND f.tenant_id=? AND f.created_by=? AND u.uploader_user_id=? AND u.status='inprogress'", this::file, id, tenant, user, user);
        if (rows.size() != 1) throw missing();
        return rows.getFirst();
    }
    private FileRecord file(ResultSet r, int index) throws SQLException {
        return new FileRecord(r.getLong("file_id"), r.getLong("upload_id"), r.getLong("tenant_id"), r.getString("file_name"), r.getInt("sheet_count"), instant(r,"uploaded_at"), r.getLong("created_by"), r.getString("created_by_name"), r.getString("original_upload_path"), r.getString("original_format"));
    }
    private SheetRecord sheet(ResultSet r, int index) throws SQLException {
        return new SheetRecord(r.getLong("sheet_id"), r.getLong("file_id"), r.getInt("sheet_index"), r.getString("sheet_name"), r.getInt("column_count"), r.getLong("data_row_count"), decode(r.getString("header")), r.getString("upload_path"), instant(r,"uploaded_at"), r.getLong("created_by"));
    }
    private long insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> { var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS); for (int i=0;i<args.length;i++) statement.setObject(i+1,args[i]); return statement; }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
    private String encode(Map<String,String> value) { return json.writeValueAsString(freeze(value)); }
    private Map<String,String> decode(String value) {
        var node = json.readTree(value);
        if (!node.isObject()) throw new IllegalStateException("Stored spreadsheet payload is not a JSON object.");
        var result = new LinkedHashMap<String,String>();
        // MySQL binary JSON can reorder object properties lexically (column_10 before
        // column_2). Positional keys, not serialized object order, define sheet columns.
        var fields = new ArrayList<>(node.properties());
        fields.sort(Comparator.comparingInt(entry -> columnPosition(entry.getKey())));
        fields.forEach(entry -> { if (!entry.getValue().isString()) throw new IllegalStateException("Stored spreadsheet cells must be strings."); result.put(entry.getKey(),entry.getValue().asString()); });
        return Collections.unmodifiableMap(result);
    }
    private static int columnPosition(String key) {
        if(key.startsWith("column_"))try {int position=Integer.parseInt(key.substring(7));if(position>0)return position;}catch(NumberFormatException ignored){ }
        return Integer.MAX_VALUE;
    }
    public static Map<String,String> freeze(Map<String,String> value) {
        if (value == null || ((Map<?,?>)value).entrySet().stream().anyMatch(entry -> !(entry.getKey() instanceof String) || !(entry.getValue() instanceof String))) throw invalid("Spreadsheet cell maps require string keys and values, including empty strings for blanks.");
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
    private static LocalDateTime utc(Instant instant) { return LocalDateTime.ofInstant(instant,ZoneOffset.UTC); }
    private static Instant instant(ResultSet row,String field) throws SQLException { return row.getObject(field,LocalDateTime.class).toInstant(ZoneOffset.UTC); }
    private static ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"The spreadsheet is unavailable."); }
    private static ResponseStatusException duplicate() { return new ResponseStatusException(HttpStatus.CONFLICT,"A file/book with this name already exists for your tenant."); }
}
