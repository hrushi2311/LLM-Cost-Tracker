package dev.llmcosttracker.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.llmcosttracker.provider.Usage;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Test-only prices (made-up numbers). */
class PricingTest {

    private static ModelPrice price(String provider, String model, String from, String in, String out,
            String cacheRead, String cacheWrite) {
        return new ModelPrice(provider, model, LocalDate.parse(from), new BigDecimal(in), new BigDecimal(out),
                cacheRead == null ? null : new BigDecimal(cacheRead),
                cacheWrite == null ? null : new BigDecimal(cacheWrite));
    }

    private final PriceTable table = new PriceTable(List.of(
            price("openai", "gpt-4o", "2020-01-01", "100", "100", null, null),
            price("openai", "gpt-4o", "2024-01-01", "2", "8", "0.5", null),
            price("openai", "gpt-4o-mini", "2024-01-01", "0.1", "0.4", null, null),
            price("openai", "gpt-4o-2024-08-06", "2024-01-01", "3", "9", null, null),
            price("anthropic", "gpt-4o", "2024-01-01", "999", "999", null, null)));

    private static final LocalDate TODAY = LocalDate.parse("2026-11-24");

    @Test
    void exactMatchBeatsPrefix() {
        assertThat(table.find("openai", "gpt-4o-2024-08-06", TODAY)).get()
                .extracting(ModelPrice::model).isEqualTo("gpt-4o-2024-08-06");
    }

    @Test
    void datedSnapshotFallsBackToLongestPrefix() {
        assertThat(table.find("openai", "gpt-4o-2024-11-20", TODAY)).get()
                .extracting(ModelPrice::model).isEqualTo("gpt-4o");
        assertThat(table.find("openai", "gpt-4o-mini-2024-07-18", TODAY)).get()
                .extracting(ModelPrice::model).isEqualTo("gpt-4o-mini");
    }

    @Test
    void latestRowInEffectOnTheEventDateWins() {
        assertThat(table.find("openai", "gpt-4o", TODAY)).get()
                .extracting(ModelPrice::inputPerMtok).isEqualTo(new BigDecimal("2"));
        assertThat(table.find("openai", "gpt-4o", LocalDate.parse("2023-06-01"))).get()
                .extracting(ModelPrice::inputPerMtok).isEqualTo(new BigDecimal("100"));
        assertThat(table.find("openai", "gpt-4o", LocalDate.parse("2019-12-31"))).isEmpty();
    }

    @Test
    void providerMustMatchAndUnknownModelsAreUnpriced() {
        assertThat(table.find("openai", "o3", TODAY)).isEmpty();
        assertThat(table.find("custom", "gpt-4o", TODAY)).isEmpty();
        assertThat(table.find("openai", null, TODAY)).isEmpty();
    }

    @Test
    void costMatchesHandComputedValue() {
        // 93 uncached in, 46 out, 1024 cache read at $2 / $8 / $0.50 per Mtok:
        // (93*2 + 46*8 + 1024*0.5) / 1e6 = (186 + 368 + 512) / 1e6 = 0.001066
        Usage usage = new Usage("gpt-4o", 93, 46, 1024, 0, 0);
        BigDecimal cost = CostCalculator.cost(usage, price("openai", "gpt-4o", "2024-01-01", "2", "8", "0.5", null));

        assertThat(cost).isEqualTo(new BigDecimal("0.00106600"));
    }

    @Test
    void missingCachePricesFallBackToInputPrice() {
        Usage usage = new Usage("m", 0, 0, 1_000_000, 2_000_000, 0);
        BigDecimal cost = CostCalculator.cost(usage, price("openai", "m", "2024-01-01", "3", "10", null, null));

        assertThat(cost).isEqualTo(new BigDecimal("9.00000000"));
    }

    @Test
    void reasoningTokensAreNeverCosted() {
        Usage withReasoning = new Usage("m", 512, 1200, 0, 0, 1024);
        Usage without = new Usage("m", 512, 1200, 0, 0, 0);
        ModelPrice p = price("openai", "m", "2024-01-01", "1", "4", null, null);

        assertThat(CostCalculator.cost(withReasoning, p))
                .isEqualTo(CostCalculator.cost(without, p))
                .isEqualTo(new BigDecimal("0.00531200"));
    }
}
