package com.company.promoengine.engine;

import static com.company.promoengine.engine.TestRules.amountTier;
import static com.company.promoengine.engine.TestRules.cart;
import static com.company.promoengine.engine.TestRules.component;
import static com.company.promoengine.engine.TestRules.engine;
import static com.company.promoengine.engine.TestRules.item;
import static com.company.promoengine.engine.TestRules.percentageTier;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Per-rule behaviour and configuration boundaries, beyond what the six fixtures reach. */
class PromotionRuleTest {

    @Nested
    @DisplayName("PERCENTAGE_OFF_CATEGORY")
    class CategoryPercentage {

        @Test
        @DisplayName("Each line rounds on its own, which is not the same as rounding the sum")
        void roundsEachLineIndependently() {
            // Independently: 0.015 -> 0.02 twice, so 0.04. Pooled: 0.20 x 15% = 0.03.
            var result = evaluate(
                    cart(item("SKU-A", "TOPS", "0.10", 1), item("SKU-B", "TOPS", "0.10", 1)),
                    promo("PROMO_A", RuleType.PERCENTAGE_OFF_CATEGORY).category("TOPS")
                            .percentage("15").build());

            assertThat(result.totalDiscount()).isEqualByComparingTo("0.04");
            assertThat(result.itemizedBreakdown()).extracting(line -> line.discount().toPlainString())
                    .containsExactly("0.02", "0.02");
        }

        @Test
        @DisplayName("The README's two 1.25 lines at 15% give 0.19 each")
        void matchesTheReadmeRoundingExample() {
            var result = evaluate(
                    cart(item("SKU-A", "TOPS", "1.25", 1), item("SKU-B", "TOPS", "1.25", 1)),
                    promo("PROMO_A", RuleType.PERCENTAGE_OFF_CATEGORY).category("TOPS")
                            .percentage("15").build());

            assertThat(result.itemizedBreakdown()).extracting(line -> line.discount().toPlainString())
                    .containsExactly("0.19", "0.19");
        }

        @Test
        @DisplayName("maxDiscountAmount caps the rule total and splits it by uncapped weight")
        void capsTheRuleTotalAndAllocatesByUncappedDiscount() {
            var result = evaluate(
                    cart(item("SKU-BIG", "TOPS", "100.00", 1), item("SKU-SMALL", "TOPS", "0.05", 1)),
                    promo("PROMO_A", RuleType.PERCENTAGE_OFF_CATEGORY).category("TOPS")
                            .percentage("10").maxDiscount("0.01").build());

            // Uncapped would be 10.00 and 0.01; the single capped cent goes to the larger weight.
            assertThat(result.totalDiscount()).isEqualByComparingTo("0.01");
            assertThat(result.itemizedBreakdown()).extracting(line -> line.discount().toPlainString())
                    .containsExactly("0.01", "0.00");
            assertThat(applied(result).reason()).contains("capped at 0.01");
        }

        @Test
        @DisplayName("A 100% category rule takes the line to exactly 0.00")
        void acceptsTheHundredPercentBoundary() {
            var result = evaluate(cart(item("SKU-A", "TOPS", "100.00", 1)),
                    promo("PROMO_A", RuleType.PERCENTAGE_OFF_CATEGORY).category("TOPS")
                            .percentage("100").build());

            assertThat(result.finalTotal()).isEqualByComparingTo("0.00");
            assertThat(result.totalDiscount()).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("Category matching is case-sensitive")
        void matchesCategoryCaseSensitively() {
            var result = evaluate(cart(item("SKU-A", "TOPS", "100.00", 1)),
                    promo("PROMO_A", RuleType.PERCENTAGE_OFF_CATEGORY).category("tops")
                            .percentage("10").build());

            assertSkipped(result, "no items in category tops");
        }

        @Test
        @DisplayName("Illegal percentages and caps are skips, not HTTP 400s")
        void skipsIllegalConfiguration() {
            assertSkipped(evaluate(cart(item("SKU-A", "TOPS", "100.00", 1)),
                    promo("P", RuleType.PERCENTAGE_OFF_CATEGORY).category("TOPS")
                            .percentage("0").build()), "percentage must be > 0 and <= 100");
            assertSkipped(evaluate(cart(item("SKU-A", "TOPS", "100.00", 1)),
                    promo("P", RuleType.PERCENTAGE_OFF_CATEGORY).category("TOPS")
                            .percentage("100.01").build()), "percentage must be > 0 and <= 100");
            assertSkipped(evaluate(cart(item("SKU-A", "TOPS", "100.00", 1)),
                    promo("P", RuleType.PERCENTAGE_OFF_CATEGORY).category("TOPS")
                            .percentage("10").maxDiscount("0").build()), "maxDiscountAmount must be > 0");
            assertSkipped(evaluate(cart(item("SKU-A", "TOPS", "100.00", 1)),
                    promo("P", RuleType.PERCENTAGE_OFF_CATEGORY).percentage("10").build()),
                    "conditions.category is required");
        }
    }

    @Nested
    @DisplayName("BUY_X_GET_CHEAPEST_FREE")
    class BuyXGetCheapestFree {

        @Test
        @DisplayName("Group thresholds: 1-2 free nothing, 3-5 free one, 6-8 free two")
        void freesOneUnitPerCompleteGroup() {
            assertSkipped(buyTwoGetOne(2), "2 eligible unit(s)");
            assertThat(buyTwoGetOne(3).totalDiscount()).isEqualByComparingTo("120.00");
            assertThat(buyTwoGetOne(5).totalDiscount()).isEqualByComparingTo("120.00");
            assertThat(buyTwoGetOne(6).totalDiscount()).isEqualByComparingTo("240.00");
            assertThat(buyTwoGetOne(8).totalDiscount()).isEqualByComparingTo("240.00");
        }

        @Test
        @DisplayName("A multi-SKU pool frees the cheapest unit, wherever it sits")
        void freesTheCheapestAcrossSkus() {
            var result = evaluate(
                    cart(item("SKU-A", "KITCHEN", "100.00", 1),
                            item("SKU-B", "KITCHEN", "60.00", 1),
                            item("SKU-C", "KITCHEN", "80.00", 1)),
                    promo("PROMO_MIX", RuleType.BUY_X_GET_CHEAPEST_FREE)
                            .skus("SKU-A", "SKU-B", "SKU-C").buy(2).free(1).build());

            assertThat(result.totalDiscount()).isEqualByComparingTo("60.00");
            assertThat(result.itemizedBreakdown()).extracting(line -> line.discount().toPlainString())
                    .containsExactly("0.00", "60.00", "0.00");
        }

        @Test
        @DisplayName("Missing or illegal quantities and SKUs are skips")
        void skipsIllegalConfiguration() {
            assertSkipped(evaluate(cart(item("SKU-A", "KITCHEN", "10.00", 3)),
                    promo("P", RuleType.BUY_X_GET_CHEAPEST_FREE).skus("SKU-A").buy(0).free(1).build()),
                    "buyQuantity must be >= 1");
            assertSkipped(evaluate(cart(item("SKU-A", "KITCHEN", "10.00", 3)),
                    promo("P", RuleType.BUY_X_GET_CHEAPEST_FREE).skus("SKU-A").buy(2).build()),
                    "freeQuantity is required");
            assertSkipped(evaluate(cart(item("SKU-A", "KITCHEN", "10.00", 3)),
                    promo("P", RuleType.BUY_X_GET_CHEAPEST_FREE).rawSkus(List.of()).buy(2).free(1).build()),
                    "conditions.skus is required");
            assertSkipped(evaluate(cart(item("SKU-A", "KITCHEN", "10.00", 3)),
                    promo("P", RuleType.BUY_X_GET_CHEAPEST_FREE).rawSkus(List.of("  ")).buy(2).free(1).build()),
                    "must not contain blank entries");
        }

        private EvaluationResult buyTwoGetOne(int quantity) {
            return evaluate(cart(item("SKU-MUG", "KITCHEN", "120.00", quantity)),
                    promo("PROMO_MUG", RuleType.BUY_X_GET_CHEAPEST_FREE)
                            .skus("SKU-MUG").buy(2).free(1).build());
        }
    }

    @Nested
    @DisplayName("BUNDLE_FIXED_PRICE")
    class BundleFixedPrice {

        @Test
        @DisplayName("A SKU missing from the cart forms no bundle")
        void skipsWhenAComponentIsAbsent() {
            var result = evaluate(cart(item("SKU-LAPTOP", "LAPTOP", "800.00", 1)),
                    promo("PROMO_B", RuleType.BUNDLE_FIXED_PRICE)
                            .components(component("SKU-LAPTOP", 1), component("SKU-GONE", 1))
                            .bundlePrice("700.00").build());

            assertSkipped(result, "incomplete bundle");
        }

        @Test
        @DisplayName("A bundle priced above component value is skipped and consumes nothing")
        void neverChargesASurchargeAndLeavesUnitsEligible() {
            var result = evaluate(
                    cart(item("SKU-LAPTOP", "LAPTOP", "800.00", 1), item("SKU-MOUSE", "ACC", "150.00", 1)),
                    promo("PROMO_B", RuleType.BUNDLE_FIXED_PRICE).priority(1)
                            .components(component("SKU-LAPTOP", 1), component("SKU-MOUSE", 1))
                            .bundlePrice("2000.00").build(),
                    promo("PROMO_C", RuleType.PERCENTAGE_OFF_CATEGORY).priority(2)
                            .category("LAPTOP").percentage("10").build());

            assertThat(result.auditTrail().get(0).status()).isEqualTo(RuleStatus.SKIPPED);
            assertThat(result.auditTrail().get(0).reason()).contains("is not below component value");
            // The laptop unit was never consumed, so the later category rule still sees it.
            assertThat(result.auditTrail().get(1).discountAmount()).isEqualByComparingTo("80.00");
        }

        @Test
        @DisplayName("A repeated component SKU is a configuration skip")
        void skipsDuplicateComponentSku() {
            var result = evaluate(cart(item("SKU-LAPTOP", "LAPTOP", "800.00", 2)),
                    promo("PROMO_B", RuleType.BUNDLE_FIXED_PRICE)
                            .components(component("SKU-LAPTOP", 1), component("SKU-LAPTOP", 1))
                            .bundlePrice("700.00").build());

            assertSkipped(result, "appears more than once");
        }

        @Test
        @DisplayName("A negative bundle price is a configuration skip")
        void skipsNegativeBundlePrice() {
            var result = evaluate(cart(item("SKU-LAPTOP", "LAPTOP", "800.00", 1)),
                    promo("PROMO_B", RuleType.BUNDLE_FIXED_PRICE)
                            .components(component("SKU-LAPTOP", 1)).bundlePrice("-1.00").build());

            assertSkipped(result, "bundlePrice must be >= 0");
        }
    }

    @Nested
    @DisplayName("TIERED_SPEND")
    class TieredSpend {

        @Test
        @DisplayName("The greatest qualifying tier wins whatever order the tiers were listed in")
        void selectsTheGreatestQualifyingTier() {
            var result = evaluate(cart(item("SKU-ORDER", "GENERAL", "2500.00", 1)),
                    promo("PROMO_T", RuleType.TIERED_SPEND).tiers(
                            amountTier("1000.00", "50.00"),
                            amountTier("2000.00", "150.00"),
                            amountTier("500.00", "10.00")).build());

            assertThat(result.totalDiscount()).isEqualByComparingTo("150.00");
            assertThat(applied(result).reason()).contains("spend tier >= 2000.00");
        }

        @Test
        @DisplayName("Two tiers sharing a minSubtotal resolve to the earlier one in the list")
        void prefersTheEarlierTierOnATie() {
            var result = evaluate(cart(item("SKU-ORDER", "GENERAL", "2500.00", 1)),
                    promo("PROMO_T", RuleType.TIERED_SPEND).tiers(
                            amountTier("2000.00", "150.00"),
                            amountTier("2000.00", "999.00")).build());

            assertThat(result.totalDiscount()).isEqualByComparingTo("150.00");
        }

        @Test
        @DisplayName("A percentage tier rounds once against the base")
        void grantsAPercentageTier() {
            var result = evaluate(cart(item("SKU-ORDER", "GENERAL", "2500.00", 1)),
                    promo("PROMO_T", RuleType.TIERED_SPEND)
                            .tiers(percentageTier("1000.00", "10")).build());

            assertThat(result.totalDiscount()).isEqualByComparingTo("250.00");
        }

        @Test
        @DisplayName("No qualifying tier is a skip")
        void skipsWhenNoTierQualifies() {
            var result = evaluate(cart(item("SKU-ORDER", "GENERAL", "2500.00", 1)),
                    promo("PROMO_T", RuleType.TIERED_SPEND)
                            .tiers(amountTier("5000.00", "100.00")).build());

            assertSkipped(result, "qualifies for no spend tier");
        }

        @Test
        @DisplayName("A tier needs exactly one of a percentage or an amount")
        void skipsAmbiguousOrEmptyTiers() {
            var both = new com.company.promoengine.domain.SpendTier(
                    new java.math.BigDecimal("1000.00"),
                    new java.math.BigDecimal("10"),
                    new java.math.BigDecimal("50.00"));
            assertSkipped(evaluate(cart(item("SKU-ORDER", "GENERAL", "2500.00", 1)),
                    promo("P", RuleType.TIERED_SPEND).tiers(both).build()),
                    "exactly one of a valid percentage or a valid amount");
            assertSkipped(evaluate(cart(item("SKU-ORDER", "GENERAL", "2500.00", 1)),
                    promo("P", RuleType.TIERED_SPEND).build()), "discountConfig.tiers is required");
        }
    }

    @Nested
    @DisplayName("CART_PERCENTAGE_OFF and COUPON_PAYMENT_OFF")
    class CartLevel {

        @Test
        @DisplayName("Two cart percentages compound on the running subtotal: 10.00 then 9.00")
        void compoundsOnTheRunningSubtotal() {
            var result = evaluate(cart(item("SKU-A", "TOPS", "100.00", 1)),
                    promo("PROMO_A", RuleType.CART_PERCENTAGE_OFF).priority(1).percentage("10").build(),
                    promo("PROMO_B", RuleType.CART_PERCENTAGE_OFF).priority(2).percentage("10").build());

            assertThat(result.auditTrail()).extracting(entry -> entry.discountAmount().toPlainString())
                    .containsExactly("10.00", "9.00");
            assertThat(result.finalTotal()).isEqualByComparingTo("81.00");
        }

        @Test
        @DisplayName("Coupon and payment method match case-insensitively")
        void matchesCouponAndPaymentCaseInsensitively() {
            var result = evaluate(cart(item("SKU-A", "TOPS", "100.00", 1)),
                    promo("PROMO_C", RuleType.COUPON_PAYMENT_OFF)
                            .coupon("save10").payment("upi").percentage("10").build());

            assertThat(result.totalDiscount()).isEqualByComparingTo("10.00");
        }

        @Test
        @DisplayName("A rule without a payment method ignores the cart's payment method")
        void ignoresPaymentMethodWhenTheRuleOmitsIt() {
            var result = evaluate(cart(item("SKU-A", "TOPS", "100.00", 1)),
                    promo("PROMO_C", RuleType.COUPON_PAYMENT_OFF).coupon("SAVE10").percentage("10").build());

            assertThat(result.totalDiscount()).isEqualByComparingTo("10.00");
        }

        @Test
        @DisplayName("A mismatched coupon or payment method is a skip")
        void skipsOnMismatch() {
            assertSkipped(evaluate(cart(item("SKU-A", "TOPS", "100.00", 1)),
                    promo("P", RuleType.COUPON_PAYMENT_OFF).coupon("OTHER").percentage("10").build()),
                    "does not match OTHER");
            assertSkipped(evaluate(cart(item("SKU-A", "TOPS", "100.00", 1)),
                    promo("P", RuleType.COUPON_PAYMENT_OFF).coupon("SAVE10").payment("CARD")
                            .percentage("10").build()), "payment method UPI does not match CARD");
        }

        @Test
        @DisplayName("A coupon carrying both a percentage and an amount uses the percentage")
        void prefersThePercentageWhenBothAreConfigured() {
            // The README states an "exactly one" rule for tiers only, so both present is
            // accepted here rather than rejected as a configuration error.
            var result = evaluate(cart(item("SKU-A", "TOPS", "100.00", 1)),
                    promo("PROMO_C", RuleType.COUPON_PAYMENT_OFF)
                            .coupon("SAVE10").percentage("10").amount("500.00").build());

            assertThat(result.totalDiscount()).isEqualByComparingTo("10.00");
        }

        @Test
        @DisplayName("A coupon with neither a percentage nor an amount is a configuration skip")
        void skipsWhenNoBenefitIsConfigured() {
            assertSkipped(evaluate(cart(item("SKU-A", "TOPS", "100.00", 1)),
                    promo("P", RuleType.COUPON_PAYMENT_OFF).coupon("SAVE10").build()),
                    "one of discountConfig.percentage or discountConfig.amount is required");
        }
    }

    private static EvaluationResult evaluate(Cart cart, PromotionRule... rules) {
        return EvaluationInvariants.assertHolds(engine().evaluate(cart, List.of(rules)), rules.length);
    }

    private static AuditEntry applied(EvaluationResult result) {
        return result.auditTrail().stream().filter(entry -> entry.status() == RuleStatus.APPLIED)
                .findFirst().orElseThrow(() -> new AssertionError("no applied rule"));
    }

    private static void assertSkipped(EvaluationResult result, String reasonFragment) {
        assertThat(result.auditTrail().get(0).status()).isEqualTo(RuleStatus.SKIPPED);
        assertThat(result.auditTrail().get(0).reason()).contains(reasonFragment);
        assertThat(result.totalDiscount()).isEqualByComparingTo("0.00");
    }
}
