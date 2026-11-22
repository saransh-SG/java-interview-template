package com.company.promoengine.engine;

import static com.company.promoengine.engine.TestRules.amountTier;
import static com.company.promoengine.engine.TestRules.cart;
import static com.company.promoengine.engine.TestRules.component;
import static com.company.promoengine.engine.TestRules.engine;
import static com.company.promoengine.engine.TestRules.item;
import static com.company.promoengine.engine.TestRules.promo;
import static org.assertj.core.api.Assertions.assertThat;

import com.company.promoengine.domain.AuditEntry;
import com.company.promoengine.domain.Cart;
import com.company.promoengine.domain.EvaluationResult;
import com.company.promoengine.domain.PromotionRule;
import com.company.promoengine.domain.RuleStatus;
import com.company.promoengine.domain.RuleType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How rules behave in each other's company. Every case also asserts the full accounting
 * contract through {@link EvaluationInvariants}, so a reconciliation error anywhere fails
 * the test that caused it.
 */
class PromotionInteractionTest {

    @Test
    @DisplayName("README example: two bundles form, and a later category rule hits only the leftover unit")
    void bundleLeavesLeftoverUnitsEligible() {
        var result = evaluate(
                cart(item("SKU-LAPTOP", "LAPTOP", "800.00", 3),
                        item("SKU-MOUSE", "ACC", "150.00", 2),
                        item("SKU-BAG", "ACC", "50.00", 2)),
                promo("PROMO_BUNDLE", RuleType.BUNDLE_FIXED_PRICE).priority(1)
                        .components(component("SKU-LAPTOP", 1), component("SKU-MOUSE", 1),
                                component("SKU-BAG", 1))
                        .bundlePrice("800.00").build(),
                promo("PROMO_LAPTOP", RuleType.PERCENTAGE_OFF_CATEGORY).priority(2)
                        .category("LAPTOP").percentage("10").build());

        // 2 bundles save 400.00, split 320/60/20 by consumed value.
        assertThat(result.auditTrail().get(0).discountAmount()).isEqualByComparingTo("400.00");
        assertThat(result.auditTrail().get(0).reason()).contains("2 x bundle");
        // 10% of the one leftover laptop unit at 800.00, not of the whole 2400.00 line.
        assertThat(result.auditTrail().get(1).discountAmount()).isEqualByComparingTo("80.00");
        assertThat(result.itemizedBreakdown()).extracting(line -> line.discount().toPlainString())
                .containsExactly("400.00", "60.00", "20.00");
        assertThat(result.finalTotal()).isEqualByComparingTo("2320.00");
    }

    @Test
    @DisplayName("A bundle makes its lines item-discounted, so a non-stackable cart rule skips them")
    void bundleThenNonStackableCartRule() {
        var result = evaluate(
                cart(item("SKU-LAPTOP", "LAPTOP", "800.00", 1),
                        item("SKU-MOUSE", "ACC", "150.00", 1),
                        item("SKU-BAG", "ACC", "50.00", 1),
                        item("SKU-SOCKS", "ACC", "100.00", 1)),
                promo("PROMO_BUNDLE", RuleType.BUNDLE_FIXED_PRICE).priority(1)
                        .components(component("SKU-LAPTOP", 1), component("SKU-MOUSE", 1),
                                component("SKU-BAG", 1))
                        .bundlePrice("800.00").build(),
                promo("PROMO_CART", RuleType.CART_PERCENTAGE_OFF).priority(2)
                        .stackable(false).percentage("10").build());

        assertThat(result.auditTrail().get(0).discountAmount()).isEqualByComparingTo("200.00");
        // Only the socks line escaped the bundle, so the cart rule sees 100.00.
        assertThat(result.auditTrail().get(1).discountAmount()).isEqualByComparingTo("10.00");
        assertThat(result.itemizedBreakdown().get(3).discount()).isEqualByComparingTo("10.00");
        assertThat(result.finalTotal()).isEqualByComparingTo("890.00");
    }

    @Test
    @DisplayName("A capped line allocated 0.00 is not item-discounted and stays eligible")
    void cappedCategoryLineReceivingNoCentsStaysEligible() {
        var result = evaluate(
                cart(item("SKU-BIG", "TOPS", "100.00", 1), item("SKU-SMALL", "TOPS", "0.05", 1)),
                promo("PROMO_CAP", RuleType.PERCENTAGE_OFF_CATEGORY).priority(1)
                        .category("TOPS").percentage("10").maxDiscount("0.01").build(),
                promo("PROMO_CART", RuleType.CART_PERCENTAGE_OFF).priority(2)
                        .stackable(false).percentage("10").build());

        assertThat(result.auditTrail().get(0).discountAmount()).isEqualByComparingTo("0.01");
        // SKU-BIG took the capped cent and is excluded; SKU-SMALL took nothing and is not.
        assertThat(result.auditTrail().get(1).discountAmount()).isEqualByComparingTo("0.01");
        assertThat(result.itemizedBreakdown()).extracting(line -> line.discount().toPlainString())
                .containsExactly("0.01", "0.01");
        assertThat(result.finalTotal()).isEqualByComparingTo("100.03");
    }

    @Test
    @DisplayName("A fixed cart amount above the base floors the cart at 0.00 and the next rule skips")
    void fixedCartAmountIsCappedAtTheBase() {
        var result = evaluate(cart(item("SKU-A", "TOPS", "50.00", 1)),
                promo("PROMO_COUPON", RuleType.COUPON_PAYMENT_OFF).priority(1)
                        .coupon("SAVE10").amount("500.00").build(),
                promo("PROMO_CART", RuleType.CART_PERCENTAGE_OFF).priority(2).percentage("10").build());

        assertThat(result.auditTrail().get(0).discountAmount()).isEqualByComparingTo("50.00");
        assertThat(result.finalTotal()).isEqualByComparingTo("0.00");
        assertThat(result.auditTrail().get(1).status()).isEqualTo(RuleStatus.SKIPPED);
        assertThat(result.auditTrail().get(1).reason()).contains("no eligible subtotal");
    }

    @Test
    @DisplayName("A bundle after a category discount is clamped, and the audit reports what it really took")
    void bundleAfterCategoryDiscountIsClampedToWhatTheLineHolds() {
        var result = evaluate(
                cart(item("SKU-LAPTOP", "LAPTOP", "800.00", 1),
                        item("SKU-MOUSE", "ACC", "150.00", 1),
                        item("SKU-BAG", "ACC", "50.00", 1)),
                promo("PROMO_LAPTOP", RuleType.PERCENTAGE_OFF_CATEGORY).priority(1)
                        .category("LAPTOP").percentage("95").build(),
                promo("PROMO_BUNDLE", RuleType.BUNDLE_FIXED_PRICE).priority(2)
                        .components(component("SKU-LAPTOP", 1), component("SKU-MOUSE", 1),
                                component("SKU-BAG", 1))
                        .bundlePrice("800.00").build());

        assertThat(result.auditTrail().get(0).discountAmount()).isEqualByComparingTo("760.00");
        // Savings are computed on input prices (160/30/10), but the laptop only holds 40.00
        // left, so the realized total is 80.00 rather than the planned 200.00.
        assertThat(result.auditTrail().get(1).discountAmount()).isEqualByComparingTo("80.00");
        assertThat(result.itemizedBreakdown()).extracting(line -> line.finalExtendedPrice().toPlainString())
                .containsExactly("0.00", "120.00", "40.00");
        assertThat(result.finalTotal()).isEqualByComparingTo("160.00");
    }

    @Test
    @DisplayName("Two bundle rules sharing a SKU never consume the same unit twice")
    void twoBundleRulesShareASkuWithoutDoubleConsumption() {
        var result = evaluate(
                cart(item("SKU-LAPTOP", "LAPTOP", "800.00", 2),
                        item("SKU-MOUSE", "ACC", "150.00", 1),
                        item("SKU-BAG", "ACC", "50.00", 1)),
                promo("PROMO_B1", RuleType.BUNDLE_FIXED_PRICE).priority(1)
                        .components(component("SKU-LAPTOP", 1), component("SKU-MOUSE", 1))
                        .bundlePrice("900.00").build(),
                promo("PROMO_B2", RuleType.BUNDLE_FIXED_PRICE).priority(2)
                        .components(component("SKU-LAPTOP", 1), component("SKU-BAG", 1))
                        .bundlePrice("800.00").build(),
                promo("PROMO_B3", RuleType.BUNDLE_FIXED_PRICE).priority(3)
                        .components(component("SKU-LAPTOP", 1), component("SKU-MOUSE", 1))
                        .bundlePrice("900.00").build());

        assertThat(result.auditTrail().get(0).discountAmount()).isEqualByComparingTo("50.00");
        assertThat(result.auditTrail().get(1).discountAmount()).isEqualByComparingTo("50.00");
        // Both laptop units are now bundled, so the third rule can form nothing.
        assertThat(result.auditTrail().get(2).status()).isEqualTo(RuleStatus.SKIPPED);
        assertThat(result.auditTrail().get(2).reason()).contains("incomplete bundle");
        assertThat(result.totalDiscount()).isEqualByComparingTo("100.00");
        assertThat(result.itemizedBreakdown().get(0).discount()).isEqualByComparingTo("89.17");
        assertThat(result.finalTotal()).isEqualByComparingTo("1700.00");
    }

    @Test
    @DisplayName("One SKU spread over two cart lines still forms a bundle")
    void formsABundleFromASkuSplitAcrossLines() {
        var result = evaluate(
                cart(item("SKU-X", "GEN", "100.00", 1),
                        item("SKU-X", "GEN", "100.00", 1),
                        item("SKU-Y", "GEN", "50.00", 1)),
                promo("PROMO_B", RuleType.BUNDLE_FIXED_PRICE)
                        .components(component("SKU-X", 2), component("SKU-Y", 1))
                        .bundlePrice("200.00").build());

        assertThat(result.totalDiscount()).isEqualByComparingTo("50.00");
        assertThat(result.itemizedBreakdown()).extracting(line -> line.discount().toPlainString())
                .containsExactly("20.00", "20.00", "10.00");
        assertThat(result.finalTotal()).isEqualByComparingTo("200.00");
    }

    @Test
    @DisplayName("An item discount can drop the cart into a lower spend tier")
    void spendTierDowngradesOnTheRunningSubtotal() {
        var result = evaluate(cart(item("SKU-ORDER", "GENERAL", "2500.00", 1)),
                promo("PROMO_ITEM", RuleType.PERCENTAGE_OFF_CATEGORY).priority(1)
                        .category("GENERAL").percentage("20").build(),
                promo("PROMO_TIER", RuleType.TIERED_SPEND).priority(2).tiers(
                        amountTier("2500.00", "500.00"),
                        amountTier("2000.00", "150.00")).build());

        assertThat(result.auditTrail().get(0).discountAmount()).isEqualByComparingTo("500.00");
        // The base is now 2000.00, so the 2500.00 tier no longer qualifies.
        assertThat(result.auditTrail().get(1).discountAmount()).isEqualByComparingTo("150.00");
        assertThat(result.finalTotal()).isEqualByComparingTo("1850.00");
    }

    @Test
    @DisplayName("Two category rules compound on the running line price: 10.00 then 9.00")
    void categoryRulesCompoundOnTheRunningLinePrice() {
        var result = evaluate(cart(item("SKU-A", "TOPS", "100.00", 1)),
                promo("PROMO_A", RuleType.PERCENTAGE_OFF_CATEGORY).priority(1)
                        .category("TOPS").percentage("10").build(),
                promo("PROMO_B", RuleType.PERCENTAGE_OFF_CATEGORY).priority(2)
                        .category("TOPS").percentage("10").build());

        assertThat(result.auditTrail()).extracting(entry -> entry.discountAmount().toPlainString())
                .containsExactly("10.00", "9.00");
        assertThat(result.finalTotal()).isEqualByComparingTo("81.00");
    }

    @Test
    @DisplayName("A rule skipped on its predicate leaves its exclusive group open")
    void aSkippedRuleDoesNotClaimItsExclusiveGroup() {
        var result = evaluate(cart(item("SKU-A", "TOPS", "100.00", 1)),
                promo("PROMO_MISS", RuleType.PERCENTAGE_OFF_CATEGORY).priority(1)
                        .group("SUMMER").category("NOPE").percentage("50").build(),
                promo("PROMO_HIT", RuleType.PERCENTAGE_OFF_CATEGORY).priority(2)
                        .group("SUMMER").category("TOPS").percentage("10").build());

        assertThat(result.auditTrail().get(0).status()).isEqualTo(RuleStatus.SKIPPED);
        assertThat(result.auditTrail().get(1).status()).isEqualTo(RuleStatus.APPLIED);
        assertThat(result.totalDiscount()).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("An applied rule does claim its group, blocking the rest")
    void anAppliedRuleClaimsItsExclusiveGroup() {
        var result = evaluate(cart(item("SKU-A", "TOPS", "100.00", 1)),
                promo("PROMO_FIRST", RuleType.PERCENTAGE_OFF_CATEGORY).priority(1)
                        .group("SUMMER").category("TOPS").percentage("10").build(),
                promo("PROMO_SECOND", RuleType.PERCENTAGE_OFF_CATEGORY).priority(2)
                        .group("SUMMER").category("TOPS").percentage("50").build());

        assertThat(result.auditTrail().get(1).reason()).contains("exclusive group SUMMER");
        assertThat(result.totalDiscount()).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("Identical priority and identical id fall back to request order")
    void equalPriorityAndIdFallBackToRequestIndex() {
        var result = evaluate(cart(item("SKU-A", "TOPS", "100.00", 1)),
                promo("PROMO_SAME", RuleType.CART_PERCENTAGE_OFF).named("first")
                        .priority(1).percentage("10").build(),
                promo("PROMO_SAME", RuleType.CART_PERCENTAGE_OFF).named("second")
                        .priority(1).percentage("50").build());

        assertThat(result.auditTrail()).extracting(AuditEntry::ruleName).containsExactly("first", "second");
        assertThat(result.auditTrail()).extracting(entry -> entry.discountAmount().toPlainString())
                .containsExactly("10.00", "45.00");
    }

    @Test
    @DisplayName("The same request evaluated twice produces the identical response")
    void isDeterministicAcrossRepeatedEvaluations() {
        Cart cart = cart(item("SKU-LAPTOP", "LAPTOP", "800.00", 2),
                item("SKU-MOUSE", "ACC", "150.00", 1),
                item("SKU-BAG", "ACC", "50.00", 1));
        List<PromotionRule> rules = List.of(
                promo("PROMO_B1", RuleType.BUNDLE_FIXED_PRICE).priority(1)
                        .components(component("SKU-LAPTOP", 1), component("SKU-MOUSE", 1))
                        .bundlePrice("900.00").build(),
                promo("PROMO_CART", RuleType.CART_PERCENTAGE_OFF).priority(2).percentage("7").build(),
                promo("PROMO_FREE", RuleType.BUY_X_GET_CHEAPEST_FREE).priority(3)
                        .skus("SKU-LAPTOP", "SKU-BAG").buy(1).free(1).build());

        var first = engine().evaluate(cart, rules);
        var second = engine().evaluate(cart, rules);

        assertThat(second).isEqualTo(first);
        EvaluationInvariants.assertHolds(first, rules.size());
        EvaluationInvariants.assertHolds(second, rules.size());
    }

    @Test
    @DisplayName("An empty cart with cart-level rules skips them rather than failing")
    void handlesAnEmptyCart() {
        var result = evaluate(cart(),
                promo("PROMO_CART", RuleType.CART_PERCENTAGE_OFF).percentage("10").build());

        assertThat(result.originalSubtotal()).isEqualByComparingTo("0.00");
        assertThat(result.finalTotal()).isEqualByComparingTo("0.00");
        assertThat(result.auditTrail().get(0).status()).isEqualTo(RuleStatus.SKIPPED);
    }

    @Test
    @DisplayName("A line already free is skipped rather than applied for 0.00")
    void skipsARuleWorthNothing() {
        var result = evaluate(cart(item("SKU-A", "TOPS", "100.00", 1)),
                promo("PROMO_ALL", RuleType.PERCENTAGE_OFF_CATEGORY).priority(1)
                        .category("TOPS").percentage("100").build(),
                promo("PROMO_MORE", RuleType.PERCENTAGE_OFF_CATEGORY).priority(2)
                        .category("TOPS").percentage("10").build());

        assertThat(result.auditTrail().get(1).status()).isEqualTo(RuleStatus.SKIPPED);
        assertThat(result.auditTrail().get(1).reason()).contains("computed discount is 0.00");
        assertThat(result.finalTotal()).isEqualByComparingTo("0.00");
    }

    private static EvaluationResult evaluate(Cart cart, PromotionRule... rules) {
        return EvaluationInvariants.assertHolds(engine().evaluate(cart, List.of(rules)), rules.length);
    }
}
