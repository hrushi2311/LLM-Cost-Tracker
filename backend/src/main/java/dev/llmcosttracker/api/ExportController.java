package dev.llmcosttracker.api;

import dev.llmcosttracker.auth.ProjectRepository;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Streams a project's events in a range as CSV, oldest first. Rows are read with a server-side cursor (fetch size
 * 1,000) and written as they are read, so memory stays flat for any export size and a client that stops reading
 * stops the query.
 */
@RestController
public class ExportController {

    static final String HEADER = "created_at,agent,agent_source,run_id,provider,endpoint,model,input_tokens,"
            + "output_tokens,cache_read_tokens,cache_write_tokens,reasoning_tokens,cost_usd,streamed,incomplete,"
            + "status_code,latency_ms,tags\r\n";
    static final int ROWS_PER_CHUNK = 500;

    private final DataSource dataSource;
    private final ProjectRepository projects;
    private final DashboardController dashboard;

    public ExportController(DataSource dataSource, ProjectRepository projects, DashboardController dashboard) {
        this.dataSource = dataSource;
        this.projects = projects;
        this.dashboard = dashboard;
    }

    @GetMapping("/api/projects/{projectId}/events.csv")
    public Mono<ResponseEntity<Flux<DataBuffer>>> export(@PathVariable UUID projectId,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        TimeRange r = dashboard.range(from, to);
        return Blocking.call(() -> projects.find(projectId).orElseThrow(() -> ApiException.notFound("Unknown project")))
                .map(project -> {
                    String filename = "events-" + slug(project.name()) + "-"
                            + r.from().atOffset(ZoneOffset.UTC).toLocalDate() + "-"
                            + r.to().atOffset(ZoneOffset.UTC).toLocalDate() + ".csv";
                    return ResponseEntity.ok()
                            .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                    ContentDisposition.attachment().filename(filename).build().toString())
                            .body(rows(projectId, r));
                });
    }

    private record Cursor(Connection connection, PreparedStatement statement, ResultSet rows) {}

    private Flux<DataBuffer> rows(UUID projectId, TimeRange r) {
        Flux<DataBuffer> body = Flux.<DataBuffer, Cursor>generate(() -> open(projectId, r), (cursor, sink) -> {
            try {
                StringBuilder chunk = new StringBuilder(64 * ROWS_PER_CHUNK);
                int n = 0;
                while (n < ROWS_PER_CHUNK && cursor.rows().next()) {
                    appendRow(chunk, cursor.rows());
                    n++;
                }
                if (chunk.length() > 0) {
                    sink.next(buffer(chunk.toString()));
                }
                if (n < ROWS_PER_CHUNK) {
                    sink.complete();
                }
            } catch (SQLException e) {
                sink.error(e);
            }
            return cursor;
        }, ExportController::close);
        return Flux.concat(Mono.fromSupplier(() -> buffer(HEADER)), body)
                .subscribeOn(Schedulers.boundedElastic()); // JDBC stays off the event loop, requests included
    }

    private Cursor open(UUID projectId, TimeRange r) throws SQLException {
        Connection c = dataSource.getConnection();
        try {
            c.setAutoCommit(false); // Postgres only streams with a cursor inside a transaction
            c.setReadOnly(true);
            PreparedStatement ps = c.prepareStatement("""
                    SELECT e.created_at, a.name, a.source, e.run_id, e.provider, e.endpoint, e.model, e.input_tokens,
                           e.output_tokens, e.cache_read_tokens, e.cache_write_tokens, e.reasoning_tokens, e.cost_usd,
                           e.streamed, e.incomplete, e.status_code, e.latency_ms, e.tags::text
                    FROM usage_event e LEFT JOIN agent a ON a.id = e.agent_id
                    WHERE e.project_id = ? AND e.created_at >= ? AND e.created_at < ?
                    ORDER BY e.created_at, e.id
                    """);
            ps.setFetchSize(1_000);
            ps.setObject(1, projectId);
            ps.setObject(2, r.from().atOffset(ZoneOffset.UTC));
            ps.setObject(3, r.to().atOffset(ZoneOffset.UTC));
            return new Cursor(c, ps, ps.executeQuery());
        } catch (SQLException | RuntimeException e) {
            c.close();
            throw e;
        }
    }

    private static void close(Cursor cursor) {
        try (Connection c = cursor.connection(); PreparedStatement ps = cursor.statement(); ResultSet rs = cursor.rows()) {
            c.rollback(); // read-only: nothing to commit
        } catch (SQLException ignored) {
            // the connection goes back to the pool (or is discarded) either way
        }
    }

    static void appendRow(StringBuilder out, ResultSet rs) throws SQLException {
        out.append(rs.getObject(1, OffsetDateTime.class).toInstant()).append(',');
        field(out, rs.getString(2)).append(',');
        field(out, rs.getString(3)).append(',');
        field(out, rs.getString(4)).append(',');
        field(out, rs.getString(5)).append(',');
        field(out, rs.getString(6)).append(',');
        field(out, rs.getString(7)).append(',');
        for (int i = 8; i <= 12; i++) {
            out.append(rs.getLong(i)).append(',');
        }
        java.math.BigDecimal cost = rs.getBigDecimal(13);
        out.append(cost == null ? "" : cost.toPlainString()).append(',');
        out.append(rs.getBoolean(14)).append(',').append(rs.getBoolean(15)).append(',');
        out.append(rs.getInt(16)).append(',').append(rs.getInt(17)).append(',');
        field(out, rs.getString(18)).append("\r\n");
    }

    /**
     * RFC 4180 quoting, plus a guard against spreadsheet formula injection: run ids and tags come from request
     * headers, so a value starting with {@code = + - @}, tab or CR is prefixed with an apostrophe.
     */
    static StringBuilder field(StringBuilder out, String value) {
        if (value == null || value.isEmpty()) {
            return out;
        }
        String v = value;
        char first = v.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
            v = "'" + v;
        }
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
            return out.append('"').append(v.replace("\"", "\"\"")).append('"');
        }
        return out.append(v);
    }

    private static DataBuffer buffer(String s) {
        return DefaultDataBufferFactory.sharedInstance.wrap(s.getBytes(StandardCharsets.UTF_8));
    }

    static String slug(String name) {
        String s = name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "project" : s.substring(0, Math.min(40, s.length()));
    }
}
