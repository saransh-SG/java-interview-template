package com.company.promoengine.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static com.company.promoengine.engine.TestRules.rule;

import com.company.promoengine.domain.PromotionRule;
import com.company.promoengine.domain.RuleType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Priority, then id by String.compareTo, then original request index. */
class RuleOrderingTest {

    @Test
    @DisplayName("Lower priority runs first")
    void ordersByPriorityAscending() {
        var ordered = RuleOrdering.sorted(List.of(
                rule("PROMO_C", RuleType.CART_PERCENTAGE_OFF, 10),
                rule("PROMO_A", RuleType.CART_PERCENTAGE_OFF, 1),
                rule("PROMO_B", RuleType.CART_PERCENTAGE_OFF, 5)));

        assertThat(ids(ordered)).containsExactly("PROMO_A", "PROMO_B", "PROMO_C");
    }

    @Test
    @DisplayName("Equal priority falls back to id, compared as a string and not as a number")
    void breaksPriorityTiesByIdLexicographically() {
        var ordered = RuleOrdering.sorted(List.of(
                rule("PROMO_Z", RuleType.CART_PERCENTAGE_OFF, 1),
                rule("PROMO_9", RuleType.CART_PERCENTAGE_OFF, 1),
                rule("PROMO_10", RuleType.CART_PERCENTAGE_OFF, 1),
                rule("PROMO_A", RuleType.CART_PERCENTAGE_OFF, 1)));

        // "PROMO_10" < "PROMO_9" under String.compareTo, which is what the README specifies.
        assertThat(ids(ordered)).containsExactly("PROMO_10", "PROMO_9", "PROMO_A", "PROMO_Z");
    }

    @Test
    @DisplayName("Identical priority and id keep their original request order")
    void keepsRequestOrderWhenPriorityAndIdTie() {
        var first = rule("PROMO_SAME", RuleType.CART_PERCENTAGE_OFF, 1);
        var second = rule("PROMO_SAME", RuleType.TIERED_SPEND, 1);
        var third = rule("PROMO_SAME", RuleType.COUPON_PAYMENT_OFF, 1);

        var ordered = RuleOrdering.sorted(List.of(first, second, third));

        assertThat(ordered).containsExactly(first, second, third);
    }

    @Test
    @DisplayName("Sorting leaves the caller's list alone")
    void doesNotMutateTheInput() {
        var input = List.of(
                rule("PROMO_B", RuleType.CART_PERCENTAGE_OFF, 9),
                rule("PROMO_A", RuleType.CART_PERCENTAGE_OFF, 1));

        RuleOrdering.sorted(input);

        assertThat(ids(input)).containsExactly("PROMO_B", "PROMO_A");
    }

    private static List<String> ids(List<PromotionRule> rules) {
        return rules.stream().map(PromotionRule::id).toList();
    }
}
