package com.company.promoengine.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.promoengine.domain.RuleType;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Handler lookup, and the two ways a wiring mistake must fail loudly. */
class RuleHandlerRegistryTest {

    @Test
    @DisplayName("Resolves each rule type to its own handler")
    void resolvesHandlerByType() {
        var category = FakeRuleHandler.skipping(RuleType.PERCENTAGE_OFF_CATEGORY, "no");
        var coupon = FakeRuleHandler.skipping(RuleType.COUPON_PAYMENT_OFF, "no");

        var registry = new RuleHandlerRegistry(List.of(category, coupon));

        assertThat(registry.handlerFor(RuleType.PERCENTAGE_OFF_CATEGORY)).isSameAs(category);
        assertThat(registry.handlerFor(RuleType.COUPON_PAYMENT_OFF)).isSameAs(coupon);
    }

    @Test
    @DisplayName("Two handlers for one type fail at construction rather than one winning silently")
    void rejectsDuplicateRegistration() {
        var first = FakeRuleHandler.skipping(RuleType.TIERED_SPEND, "no");
        var second = FakeRuleHandler.skipping(RuleType.TIERED_SPEND, "no");

        assertThatThrownBy(() -> new RuleHandlerRegistry(List.of(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("two handlers")
                .hasMessageContaining("TIERED_SPEND");
    }

    @Test
    @DisplayName("Looking up an unhandled type names the type it could not resolve")
    void failsClearlyWhenATypeHasNoHandler() {
        var registry = new RuleHandlerRegistry(List.of());

        assertThatThrownBy(() -> registry.handlerFor(RuleType.BUNDLE_FIXED_PRICE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BUNDLE_FIXED_PRICE");
    }

    @Test
    @DisplayName("Completeness check names every rule type still missing a handler")
    void requireCompleteCoverageNamesMissingTypes() {
        var registry = new RuleHandlerRegistry(
                List.of(FakeRuleHandler.skipping(RuleType.CART_PERCENTAGE_OFF, "no")));

        assertThatThrownBy(registry::requireCompleteCoverage)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BUNDLE_FIXED_PRICE")
                .hasMessageContaining("TIERED_SPEND")
                .matches(thrown -> !thrown.getMessage().contains("CART_PERCENTAGE_OFF"));
    }

    @Test
    @DisplayName("A handler for every rule type passes the completeness check")
    void requireCompleteCoverageAcceptsAFullSet() {
        var handlers = Arrays.stream(RuleType.values())
                .map(type -> (RuleHandler) FakeRuleHandler.skipping(type, "no"))
                .toList();

        var registry = new RuleHandlerRegistry(handlers);

        assertThat(registry.requireCompleteCoverage()).isSameAs(registry);
        for (RuleType type : RuleType.values()) {
            assertThat(registry.handlerFor(type).type()).isEqualTo(type);
        }
    }
}
