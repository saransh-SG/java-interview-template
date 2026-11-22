package com.company.promoengine.engine;

import com.company.promoengine.domain.PromotionRule;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The one deterministic evaluation order: {@code priority} ascending, then {@code id}
 * ascending by {@link String#compareTo}, then original request index.
 *
 * <p>The request index needs no comparator of its own. {@link List#sort} is required to
 * be stable, so rules that tie on priority and id come out in the order they arrived.
 * {@code RuleOrderingTest} pins that rather than trusting the comment.
 */
final class RuleOrdering {

    private static final Comparator<PromotionRule> BY_PRIORITY_THEN_ID =
            Comparator.comparingInt(PromotionRule::priority)
                    .thenComparing(PromotionRule::id, Comparator.nullsFirst(Comparator.naturalOrder()));

    private RuleOrdering() {
    }

    static List<PromotionRule> sorted(List<PromotionRule> promotions) {
        var ordered = new ArrayList<>(promotions);
        ordered.sort(BY_PRIORITY_THEN_ID);
        return List.copyOf(ordered);
    }
}
