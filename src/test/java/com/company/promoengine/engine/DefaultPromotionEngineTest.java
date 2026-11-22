package com.company.promoengine.engine;

import static com.company.promoengine.engine.TestRules.cart;
import static com.company.promoengine.engine.TestRules.item;
import static com.company.promoengine.engine.TestRules.rule;
import static org.assertj.core.api.Assertions.assertThat;

import com.company.promoengine.domain.AuditEntry;
import com.company.promoengine.domain.RuleStatus;
import com.company.promoengine.domain.RuleType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The gatekeeper sequence, with handlers that have no promotion semantics. Each case
 * proves an earlier gate short-circuits the later ones, which is what keeps the six
 * handlers free of gatekeeper logic.
 */
class DefaultPromotionEngineTest {

    private static final RuleType ITEM_TYPE = RuleType.PERCENTAGE_OFF_CATEGORY;
    private static final RuleType OTHER_TYPE = RuleType.CART_PERCENTAGE_OFF;

    @Test
    @DisplayName("An applied rule reports the realized discount and the totals reconcile")
    void appliesARuleAndReconcilesTotals() {
        var engine = engineWith(FakeRuleHandler.reducingLineZeroBy(ITEM_TYPE, RuleScope.ITEM, "10.00"));

        var result = engine.evaluate(cart(item("SKU-A", "TOPS", "100.00", 1)),
                List.of(rule("PROMO_A", ITEM_TYPE, 1)));

        assertThat(result.auditTrail()).singleElement()
                .extracting(AuditEntry::status, AuditEntry::discountAmount)
                .containsExactly(RuleStatus.APPLIED, new java.math.BigDecimal("10.00"));
        assertThat(result.originalSubtotal()).isEqualByComparingTo("100.00");
        assertThat(result.totalDiscount()).isEqualByComparingTo("10.00");
        assertThat(result.finalTotal()).isEqualByComparingTo("90.00");
        assertThat(result.itemizedBreakdown()).singleElement()
                .extracting(line -> line.discount().toPlainString())
                .isEqualTo("10.00");
    }

    @Test
    @DisplayName("Gate 1: an inverted window is reported before the window check, handler untouched")
    void invertedWindowIsCheckedFirst() {
        var handler = FakeRuleHandler.reducingLineZeroBy(ITEM_TYPE, RuleScope.ITEM, "10.00");
        var engine = engineWith(handler);
        var inverted = rule("PROMO_A", ITEM_TYPE, 1, null,
                Instant.parse("2026-12-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));

        var result = engine.evaluate(cart(item("SKU-A", "TOPS", "100.00", 1)), List.of(inverted));

        assertSkippedBecause(result.auditTrail().get(0), "inverted");
        assertThat(handler.validateCalled()).isFalse();
        assertThat(result.finalTotal()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("Gate 2: a rule outside its window never reaches configuration validation")
    void windowIsCheckedBeforeConfiguration() {
        var handler = FakeRuleHandler.withConfigViolations(ITEM_TYPE, "percentage must be > 0");
        var engine = engineWith(handler);
        var expired = rule("PROMO_A", ITEM_TYPE, 1, null,
                Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2025-12-31T00:00:00Z"));

        var result = engine.evaluate(cart(item("SKU-A", "TOPS", "100.00", 1)), List.of(expired));

        assertSkippedBecause(result.auditTrail().get(0), "outside the validity window");
        assertThat(handler.validateCalled()).isFalse();
    }

    @Test
    @DisplayName("Gate 3: a claimed exclusive group skips before configuration validation")
    void claimedGroupIsCheckedBeforeConfiguration() {
        var applying = FakeRuleHandler.reducingLineZeroBy(ITEM_TYPE, RuleScope.ITEM, "10.00");
        var blocked = FakeRuleHandler.withConfigViolations(OTHER_TYPE, "percentage must be > 0");
        var engine = engineWith(applying, blocked);

        var result = engine.evaluate(cart(item("SKU-A", "TOPS", "100.00", 1)), List.of(
                rule("PROMO_A", ITEM_TYPE, 1, "SUMMER", TestRules.OPEN_FROM, TestRules.OPEN_TO),
                rule("PROMO_B", OTHER_TYPE, 2, "SUMMER", TestRules.OPEN_FROM, TestRules.OPEN_TO)));

        assertThat(result.auditTrail().get(0).status()).isEqualTo(RuleStatus.APPLIED);
        assertSkippedBecause(result.auditTrail().get(1), "exclusive group SUMMER");
        assertThat(blocked.validateCalled()).isFalse();
    }

    @Test
    @DisplayName("Gate 4: configuration violations skip before the predicate and name the problem")
    void configurationIsCheckedBeforeThePredicate() {
        var handler = FakeRuleHandler.withConfigViolations(ITEM_TYPE, "percentage must be > 0 and <= 100");
        var engine = engineWith(handler);

        var result = engine.evaluate(cart(item("SKU-A", "TOPS", "100.00", 1)),
                List.of(rule("PROMO_A", ITEM_TYPE, 1)));

        assertSkippedBecause(result.auditTrail().get(0), "percentage must be > 0 and <= 100");
        assertThat(handler.decideCalled()).isFalse();
    }

    @Test
    @DisplayName("Gate 5: a failed predicate reports the handler's own reason verbatim")
    void predicateSkipKeepsTheHandlerReason() {
        var engine = engineWith(FakeRuleHandler.skipping(ITEM_TYPE, "Skipped: no matching items in category TOPS"));

        var result = engine.evaluate(cart(item("SKU-A", "TOPS", "100.00", 1)),
                List.of(rule("PROMO_A", ITEM_TYPE, 1)));

        assertSkippedBecause(result.auditTrail().get(0), "no matching items in category TOPS");
    }

    @Test
    @DisplayName("Gate 6: a plan worth 0.00 is skipped centrally, leaves the cart alone and claims no group")
    void zeroDiscountIsSkippedAndClaimsNothing() {
        var engine = engineWith(
                FakeRuleHandler.planningNothing(ITEM_TYPE),
                FakeRuleHandler.reducingLineZeroBy(OTHER_TYPE, RuleScope.CART, "5.00"));

        var result = engine.evaluate(cart(item("SKU-A", "TOPS", "100.00", 1)), List.of(
                rule("PROMO_A", ITEM_TYPE, 1, "SUMMER", TestRules.OPEN_FROM, TestRules.OPEN_TO),
                rule("PROMO_B", OTHER_TYPE, 2, "SUMMER", TestRules.OPEN_FROM, TestRules.OPEN_TO)));

        assertSkippedBecause(result.auditTrail().get(0), "computed discount is 0.00");
        assertThat(result.auditTrail().get(0).discountAmount()).isEqualByComparingTo("0.00");
        // The skipped rule did not claim SUMMER, so the later rule in that group still runs.
        assertThat(result.auditTrail().get(1).status()).isEqualTo(RuleStatus.APPLIED);
        assertThat(result.finalTotal()).isEqualByComparingTo("95.00");
    }

    @Test
    @DisplayName("Only an item-scoped rule marks the lines it reduced as item-discounted")
    void itemScopeAloneSetsTheItemDiscountedFlag() {
        boolean[] seenAfterItemRule = {false};
        boolean[] seenAfterCartRule = {false};

        engineWith(FakeRuleHandler.reducingLineZeroBy(ITEM_TYPE, RuleScope.ITEM, "10.00"),
                FakeRuleHandler.observingItemDiscounted(OTHER_TYPE, seenAfterItemRule))
                .evaluate(cart(item("SKU-A", "TOPS", "100.00", 1)), List.of(
                        rule("PROMO_A", ITEM_TYPE, 1), rule("PROMO_B", OTHER_TYPE, 2)));

        engineWith(FakeRuleHandler.reducingLineZeroBy(ITEM_TYPE, RuleScope.CART, "10.00"),
                FakeRuleHandler.observingItemDiscounted(OTHER_TYPE, seenAfterCartRule))
                .evaluate(cart(item("SKU-A", "TOPS", "100.00", 1)), List.of(
                        rule("PROMO_A", ITEM_TYPE, 1), rule("PROMO_B", OTHER_TYPE, 2)));

        assertThat(seenAfterItemRule[0]).as("item-scoped rule marks the line").isTrue();
        assertThat(seenAfterCartRule[0]).as("cart-scoped rule must not mark the line").isFalse();
    }

    @Test
    @DisplayName("One audit entry per input rule, in evaluation order rather than request order")
    void auditTrailFollowsEvaluationOrder() {
        var engine = engineWith(FakeRuleHandler.skipping(ITEM_TYPE, "Skipped: not today"));

        var result = engine.evaluate(cart(item("SKU-A", "TOPS", "100.00", 1)), List.of(
                rule("PROMO_C", ITEM_TYPE, 3),
                rule("PROMO_A", ITEM_TYPE, 1),
                rule("PROMO_B", ITEM_TYPE, 2)));

        assertThat(result.auditTrail()).extracting(AuditEntry::ruleId)
                .containsExactly("PROMO_A", "PROMO_B", "PROMO_C");
    }

    @Test
    @DisplayName("The itemized breakdown stays in request order whatever order the rules ran in")
    void itemizedBreakdownKeepsRequestOrder() {
        var engine = engineWith(FakeRuleHandler.reducingLineZeroBy(ITEM_TYPE, RuleScope.ITEM, "10.00"));

        var result = engine.evaluate(
                cart(item("SKU-Z", "TOPS", "100.00", 1), item("SKU-A", "BAGS", "50.00", 1)),
                List.of(rule("PROMO_A", ITEM_TYPE, 1)));

        assertThat(result.itemizedBreakdown()).extracting(line -> line.sku())
                .containsExactly("SKU-Z", "SKU-A");
    }

    @Test
    @DisplayName("No promotions means original prices and an empty audit trail")
    void emptyPromotionsChangeNothing() {
        var engine = engineWith();

        var result = engine.evaluate(cart(item("SKU-A", "TOPS", "100.00", 2)), List.of());

        assertThat(result.auditTrail()).isEmpty();
        assertThat(result.originalSubtotal()).isEqualByComparingTo("200.00");
        assertThat(result.totalDiscount()).isEqualByComparingTo("0.00");
        assertThat(result.finalTotal()).isEqualByComparingTo("200.00");
    }

    private static DefaultPromotionEngine engineWith(RuleHandler... handlers) {
        return new DefaultPromotionEngine(List.of(handlers));
    }

    private static void assertSkippedBecause(AuditEntry entry, String fragment) {
        assertThat(entry.status()).isEqualTo(RuleStatus.SKIPPED);
        assertThat(entry.discountAmount()).isEqualByComparingTo("0.00");
        assertThat(entry.reason()).isNotBlank().contains(fragment);
    }
}
