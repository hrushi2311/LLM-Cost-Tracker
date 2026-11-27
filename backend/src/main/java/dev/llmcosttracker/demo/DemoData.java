package dev.llmcosttracker.demo;

import dev.llmcosttracker.pricing.CostCalculator;
import dev.llmcosttracker.pricing.ModelPrice;
import dev.llmcosttracker.pricing.PriceTable;
import dev.llmcosttracker.provider.Usage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Generates realistic-looking demo traffic for one project: four agents (two discovered by URL path, one by header,
 * one by system-prompt fingerprint that appears a week in), four models, multi-call runs, standalone calls, a few
 * upstream errors and incomplete streams, a weekday/daytime rhythm and gentle growth. Deterministic for a seed.
 *
 * <p>Costs come from the price table when it has a row for the model; otherwise from {@link #ILLUSTRATIVE_RATES},
 * which are round placeholder numbers for screenshots and UI work, not provider prices.
 */
final class DemoData {

    /** One generated call, already normalised and priced. {@code agent} indexes {@link #AGENTS}. */
    record Event(int agent, String runId, String provider, String endpoint, String model, int inputTokens,
            int outputTokens, int cacheReadTokens, int cacheWriteTokens, BigDecimal costUsd, boolean streamed,
            boolean incomplete, int statusCode, int latencyMs, Instant createdAt, String tagsJson) {}

    record AgentSpec(String name, String source, String fingerprint, double weight, String[] models,
            double[] modelWeights, int typicalInput, int typicalOutput) {}

    static final String FINGERPRINT = sha256("You are a helpful assistant that drafts follow-up emails for support "
            + "tickets. Be concise and friendly.");

    static final List<AgentSpec> AGENTS = List.of(
            new AgentSpec("summary-agent", "path", null, 0.27,
                    new String[] {"gpt-4o", "claude-sonnet-5-5"}, new double[] {0.55, 0.45}, 4200, 600),
            new AgentSpec("refund-agent", "header", null, 0.23,
                    new String[] {"claude-sonnet-5-5", "gpt-4o", "claude-haiku-5-5"}, new double[] {0.6, 0.3, 0.1},
                    4500, 400),
            new AgentSpec("triage-agent", "path", null, 0.40,
                    new String[] {"gpt-4o-mini", "claude-haiku-5-5"}, new double[] {0.7, 0.3}, 1800, 120),
            new AgentSpec("agent-" + FINGERPRINT.substring(0, 4), "fingerprint", FINGERPRINT, 0.10,
                    new String[] {"gpt-4o"}, new double[] {1.0}, 900, 350));

    /** Index of the fingerprint agent; it first appears {@link #FINGERPRINT_FIRST_DAY} days into the demo. */
    static final int FINGERPRINT_AGENT = 3;
    static final int FINGERPRINT_FIRST_DAY = 7;

    /** Placeholder USD per million tokens (input, output, cache read, cache write). Not provider prices. */
    static final Map<String, BigDecimal[]> ILLUSTRATIVE_RATES = Map.of(
            "gpt-4o", rates("2.50", "10.00", "1.25", "2.50"),
            "gpt-4o-mini", rates("0.15", "0.60", "0.075", "0.15"),
            "claude-sonnet-5-5", rates("3.00", "15.00", "0.30", "3.75"),
            "claude-haiku-5-5", rates("1.00", "5.00", "0.10", "1.25"));

    private final Random random;
    private final Instant start;
    private final Instant end;
    private final int days;
    private final ZoneId zone;
    private final PriceTable prices;
    private final double[] dayWeights;
    private long runCounter;

    DemoData(long seed, Instant end, int days, ZoneId zone, PriceTable prices) {
        this.random = new Random(seed);
        this.end = end;
        this.days = days;
        this.start = end.minus(Duration.ofDays(days));
        this.zone = zone;
        this.prices = prices;
        // One weight per calendar day the window touches (the first and last are partial days).
        this.dayWeights = new double[days + 1];
        LocalDate first = start.atZone(zone).toLocalDate();
        for (int d = 0; d <= days; d++) {
            int dow = first.plusDays(d).getDayOfWeek().getValue();
            double weekend = dow >= 6 ? 0.55 : 1.0;
            double growth = 1.0 + 0.45 * d / Math.max(1, days - 1);
            dayWeights[d] = weekend * growth * (0.9 + 0.2 * random.nextDouble());
        }
        this.runCounter = 0x7d1000L + random.nextInt(0x1000);
    }

    /** Emits about {@code count} events (runs are never split, so it may overshoot by a few). */
    void generate(int count, Consumer<Event> sink) {
        int produced = 0;
        while (produced < count) {
            Instant t = randomTime();
            int day = (int) Duration.between(start, t).toDays();
            if (random.nextDouble() < 0.72) {
                produced += run(t, day, sink);
            } else {
                int agent = pickAgent(day, true);
                Event e = call(agent, null, t, day);
                if (!e.createdAt().isAfter(end)) {
                    sink.accept(e);
                    produced++;
                }
            }
        }
    }

    private int run(Instant t, int day, Consumer<Event> sink) {
        String runId = "run_" + Long.toHexString(runCounter++);
        int calls = 2 + (int) Math.min(9, Math.abs(random.nextGaussian() * 3.2));
        Instant cursor = t;
        int emitted = 0;
        for (int i = 0; i < calls; i++) {
            int agent = i == 0 ? 2 : pickAgent(day, false); // triage first, then the workers
            Event e = call(agent, runId, cursor, day);
            if (e.createdAt().isAfter(end)) {
                break;
            }
            sink.accept(e);
            emitted++;
            cursor = e.createdAt().plusMillis(40 + random.nextInt(400));
        }
        return emitted;
    }

    private Event call(int agentIndex, String runId, Instant startedAt, int day) {
        AgentSpec a = AGENTS.get(agentIndex);
        String model = a.models()[pick(a.modelWeights())];
        boolean anthropic = model.startsWith("claude");
        String provider = anthropic ? "anthropic" : "openai";
        String endpoint = anthropic ? "messages" : (random.nextDouble() < 0.2 ? "responses" : "chat.completions");
        boolean streamed = random.nextDouble() < 0.4;

        int status = 200;
        double roll = random.nextDouble();
        if (roll < 0.004) {
            status = anthropic ? (random.nextBoolean() ? 529 : 429) : (random.nextBoolean() ? 429 : 500);
        } else if (roll < 0.005) {
            status = 400;
        }
        boolean incomplete = status == 200 && streamed && random.nextDouble() < 0.025;

        int input = 0;
        int output = 0;
        int cacheRead = 0;
        int cacheWrite = 0;
        if (status == 200) {
            int totalInput = around(a.typicalInput(), 0.45);
            output = around(a.typicalOutput(), 0.6);
            // Long shared system prompts get cached: some of the input is read from cache.
            if (random.nextDouble() < 0.55) {
                cacheRead = (int) (totalInput * (0.3 + 0.5 * random.nextDouble()));
            } else if (anthropic && random.nextDouble() < 0.15) {
                cacheWrite = (int) (totalInput * (0.3 + 0.4 * random.nextDouble()));
            }
            input = Math.max(1, totalInput - cacheRead - cacheWrite);
            if (incomplete) {
                output = Math.max(1, (int) (output * random.nextDouble() * 0.6)); // client gave up part-way
            }
        }
        int latency = status == 200
                ? (int) (250 + output * (model.endsWith("mini") || model.contains("haiku") ? 6 : 14)
                        * (0.7 + 0.6 * random.nextDouble()))
                : 80 + random.nextInt(600);
        Instant createdAt = startedAt.plusMillis(latency);
        BigDecimal cost = cost(provider, model, new Usage(model, input, output, cacheRead, cacheWrite, 0), createdAt);
        String tags = random.nextDouble() < 0.3 ? "{\"env\":\"prod\",\"tier\":\"" + (random.nextBoolean() ? "pro" : "free")
                + "\"}" : null;
        return new Event(agentIndex, runId, provider, endpoint, model, input, output, cacheRead, cacheWrite, cost,
                streamed, incomplete, status, latency, createdAt, tags);
    }

    BigDecimal cost(String provider, String model, Usage usage, Instant at) {
        LocalDate date = LocalDate.ofInstant(at, ZoneOffset.UTC);
        return prices.find(provider, model, date)
                .or(() -> {
                    BigDecimal[] r = ILLUSTRATIVE_RATES.get(model);
                    return r == null ? java.util.Optional.empty()
                            : java.util.Optional.of(new ModelPrice(provider, model, date, r[0], r[1], r[2], r[3]));
                })
                .map(p -> CostCalculator.cost(usage, p))
                .orElse(null);
    }

    private int pickAgent(int day, boolean standalone) {
        double[] w = new double[AGENTS.size()];
        for (int i = 0; i < w.length; i++) {
            w[i] = AGENTS.get(i).weight();
        }
        if (day < FINGERPRINT_FIRST_DAY || !standalone) {
            w[FINGERPRINT_AGENT] = 0; // the fingerprint agent shows up a week in, outside runs
        } else {
            w[FINGERPRINT_AGENT] *= 3;
        }
        if (!standalone) {
            w[2] *= 0.35; // triage mostly opens a run
        }
        return pick(w);
    }

    private Instant randomTime() {
        while (true) {
            int day = pick(dayWeights);
            // Busy from 08:00 to 20:00 in the zone, quiet at night.
            int hour;
            do {
                hour = random.nextInt(24);
            } while (random.nextDouble() > (hour >= 8 && hour < 20 ? 1.0 : 0.18));
            ZonedDateTime base = start.atZone(zone).toLocalDate().plusDays(day).atStartOfDay(zone);
            Instant t = base.plusHours(hour).plusSeconds(random.nextInt(3600)).toInstant();
            if (!t.isBefore(start) && t.isBefore(end)) {
                return t;
            }
        }
    }

    private int around(int typical, double spread) {
        double v = typical * Math.exp(random.nextGaussian() * spread);
        return Math.max(1, (int) Math.round(v));
    }

    private int pick(double[] weights) {
        double total = 0;
        for (double w : weights) {
            total += w;
        }
        double x = random.nextDouble() * total;
        for (int i = 0; i < weights.length; i++) {
            x -= weights[i];
            if (x < 0) {
                return i;
            }
        }
        return weights.length - 1;
    }

    Instant start() {
        return start;
    }

    int days() {
        return days;
    }

    /** Rounds a spend figure up to a "budget-looking" number (e.g. 412.86 → 450). */
    static BigDecimal roundBudget(BigDecimal amount) {
        BigDecimal step = amount.compareTo(BigDecimal.valueOf(100)) >= 0 ? BigDecimal.valueOf(50)
                : amount.compareTo(BigDecimal.TEN) >= 0 ? BigDecimal.TEN : BigDecimal.ONE;
        BigDecimal r = amount.divide(step, 0, RoundingMode.CEILING).multiply(step);
        return r.signum() == 0 ? step : r.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal[] rates(String in, String out, String cacheRead, String cacheWrite) {
        return new BigDecimal[] {new BigDecimal(in), new BigDecimal(out), new BigDecimal(cacheRead),
                new BigDecimal(cacheWrite)};
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static List<UUID> newIds(int n) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(UUID.randomUUID());
        }
        return ids;
    }
}
