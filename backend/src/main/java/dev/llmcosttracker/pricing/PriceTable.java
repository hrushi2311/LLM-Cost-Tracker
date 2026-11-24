package dev.llmcosttracker.pricing;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Price lookup: same provider, {@code effective_from} on or before the event date, latest such row; exact model
 * match first, otherwise the longest stored model name that is a prefix of the event's model.
 */
public final class PriceTable {

    private final List<ModelPrice> prices;

    public PriceTable(List<ModelPrice> prices) {
        this.prices = List.copyOf(prices);
    }

    public Optional<ModelPrice> find(String provider, String model, LocalDate date) {
        if (provider == null || model == null || model.isEmpty()) {
            return Optional.empty();
        }
        List<ModelPrice> inEffect = prices.stream()
                .filter(p -> p.provider().equals(provider) && !p.effectiveFrom().isAfter(date))
                .toList();
        Optional<ModelPrice> exact = inEffect.stream()
                .filter(p -> p.model().equals(model))
                .max(Comparator.comparing(ModelPrice::effectiveFrom));
        if (exact.isPresent()) {
            return exact;
        }
        return inEffect.stream()
                .filter(p -> model.startsWith(p.model()))
                .max(Comparator.<ModelPrice>comparingInt(p -> p.model().length())
                        .thenComparing(ModelPrice::effectiveFrom));
    }
}
