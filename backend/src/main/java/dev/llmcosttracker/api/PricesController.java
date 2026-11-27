package dev.llmcosttracker.api;

import dev.llmcosttracker.config.TrackerProperties;
import dev.llmcosttracker.pricing.PriceRecompute;
import dev.llmcosttracker.proxy.ProviderRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * The price table (USD per million tokens) and re-pricing. A price change is a new row with a new
 * {@code effective_from}, so existing costs never change unless a recompute is asked for.
 */
@RestController
@RequestMapping(path = "/api/prices", produces = MediaType.APPLICATION_JSON_VALUE)
public class PricesController {

    static final BigDecimal MAX_PRICE = new BigDecimal("999999.999999"); // NUMERIC(12,6)

    public record PriceView(String provider, String model, LocalDate effectiveFrom, String inputPerMtok,
            String outputPerMtok, String cacheReadPerMtok, String cacheWritePerMtok, String source) {}

    /** A model seen in events without a matching price. */
    public record UnpricedModel(String provider, String model, long events) {}

    public record Prices(List<PriceView> prices, List<UnpricedModel> unpriced) {}

    public record PriceRequest(String provider, String model, LocalDate effectiveFrom, BigDecimal inputPerMtok,
            BigDecimal outputPerMtok, BigDecimal cacheReadPerMtok, BigDecimal cacheWritePerMtok) {}

    public record RecomputeResult(long examined, long updated, long stillUnpriced) {}

    private final JdbcTemplate jdbc;
    private final ProviderRegistry providers;
    private final PriceRecompute recompute;
    private final Clock clock;
    private final TrackerProperties props;

    public PricesController(JdbcTemplate jdbc, ProviderRegistry providers, PriceRecompute recompute, Clock clock,
            TrackerProperties props) {
        this.jdbc = jdbc;
        this.providers = providers;
        this.recompute = recompute;
        this.clock = clock;
        this.props = props;
    }

    @GetMapping
    public Mono<Prices> list() {
        return Blocking.call(() -> new Prices(
                jdbc.query("SELECT provider, model, effective_from, input_per_mtok, output_per_mtok, "
                                + "cache_read_per_mtok, cache_write_per_mtok, source FROM model_price "
                                + "ORDER BY provider, model, effective_from DESC",
                        (rs, i) -> new PriceView(rs.getString(1), rs.getString(2), rs.getObject(3, LocalDate.class),
                                Money.price(rs.getBigDecimal(4)), Money.price(rs.getBigDecimal(5)),
                                Money.price(rs.getBigDecimal(6)), Money.price(rs.getBigDecimal(7)), rs.getString(8))),
                // Uses the ue_unpriced partial index. Events without a model have nothing to price.
                jdbc.query("SELECT provider, model, count(*) FROM usage_event "
                                + "WHERE cost_usd IS NULL AND model IS NOT NULL GROUP BY provider, model "
                                + "ORDER BY 3 DESC, provider, model",
                        (rs, i) -> new UnpricedModel(rs.getString(1), rs.getString(2), rs.getLong(3)))));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<PriceView> add(@RequestBody PriceRequest body) {
        String provider = Validation.name("provider", body.provider(), 64);
        if (!providers.keys().contains(provider)) {
            throw ApiException.validation("provider must be one of " + providers.keys().stream().sorted().toList());
        }
        String model = Validation.name("model", body.model(), 200);
        if (model.chars().anyMatch(Character::isWhitespace)) {
            throw ApiException.validation("model must not contain spaces");
        }
        if (body.effectiveFrom() == null) {
            throw ApiException.validation("effectiveFrom is required");
        }
        BigDecimal in = Validation.decimal("inputPerMtok", body.inputPerMtok(), 6, BigDecimal.ZERO, false, MAX_PRICE);
        BigDecimal out = Validation.decimal("outputPerMtok", body.outputPerMtok(), 6, BigDecimal.ZERO, false, MAX_PRICE);
        BigDecimal cr = body.cacheReadPerMtok() == null ? null
                : Validation.decimal("cacheReadPerMtok", body.cacheReadPerMtok(), 6, BigDecimal.ZERO, false, MAX_PRICE);
        BigDecimal cw = body.cacheWritePerMtok() == null ? null
                : Validation.decimal("cacheWritePerMtok", body.cacheWritePerMtok(), 6, BigDecimal.ZERO, false, MAX_PRICE);
        return Blocking.call(() -> {
            try {
                jdbc.update("INSERT INTO model_price (provider, model, effective_from, input_per_mtok, output_per_mtok, "
                                + "cache_read_per_mtok, cache_write_per_mtok, source) VALUES (?, ?, ?, ?, ?, ?, ?, 'user')",
                        provider, model, body.effectiveFrom(), in, out, cr, cw);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("A price for this provider, model and date already exists");
            }
            return new PriceView(provider, model, body.effectiveFrom(), Money.price(in), Money.price(out),
                    Money.price(cr), Money.price(cw), "user");
        });
    }

    /**
     * Re-prices events with no cost; with {@code all=true}, every event in {@code from}/{@code to} (default: the
     * last 14 days). 409 if a recompute is already running.
     */
    @PostMapping("/recompute")
    public Mono<RecomputeResult> recompute(@RequestParam(defaultValue = "false") boolean all,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        TimeRange r = TimeRange.parse(from, to, clock.instant(), props.timezone());
        return Blocking.call(() -> recompute.run(all, r.from(), r.to())
                .map(x -> new RecomputeResult(x.examined(), x.updated(), x.stillUnpriced()))
                .orElseThrow(() -> ApiException.conflict("A recompute is already running")));
    }
}
