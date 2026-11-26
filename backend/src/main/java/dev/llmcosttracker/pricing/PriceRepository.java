package dev.llmcosttracker.pricing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PriceRepository {

    private final JdbcTemplate jdbc;

    public PriceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The whole price table; it is small (one row per model per price change). */
    public PriceTable load() {
        return new PriceTable(jdbc.query(
                "SELECT provider, model, effective_from, input_per_mtok, output_per_mtok, "
                        + "cache_read_per_mtok, cache_write_per_mtok FROM model_price",
                (rs, i) -> new ModelPrice(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getObject(3, java.time.LocalDate.class),
                        rs.getBigDecimal(4),
                        rs.getBigDecimal(5),
                        rs.getBigDecimal(6),
                        rs.getBigDecimal(7))));
    }
}
