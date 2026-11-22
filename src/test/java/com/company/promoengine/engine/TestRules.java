package com.company.promoengine.engine;

import com.company.promoengine.domain.BundleComponent;
import com.company.promoengine.domain.Cart;
import com.company.promoengine.domain.CartItem;
import com.company.promoengine.domain.DiscountConfig;
import com.company.promoengine.domain.PromotionRule;
import com.company.promoengine.domain.RuleConditions;
import com.company.promoengine.domain.RuleType;
import com.company.promoengine.domain.SpendTier;
import com.company.promoengine.domain.UserTier;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/** Builders for engine tests, so each test reads as its scenario and nothing else. */
final class TestRules {

    static final Instant NOW = Instant.parse("2026-03-15T10:00:00Z");
    static final Instant OPEN_FROM = Instant.parse("2026-01-01T00:00:00Z");
    static final Instant OPEN_TO = Instant.parse("2026-12-31T23:59:59Z");

    private TestRules() {
    }

    /** The real six handlers, wired exactly as Spring wires them. */
    static DefaultPromotionEngine engine() {
        return new DefaultPromotionEngine(List.<RuleHandler>of(
                new PercentageOffCategoryHandler(),
                new BuyXGetCheapestFreeHandler(),
                new BundleFixedPriceHandler(),
                new TieredSpendHandler(),
                new CartPercentageOffHandler(),
                new CouponPaymentOffHandler()));
    }

    static Builder promo(String id, RuleType type) {
        return new Builder(id, type);
    }

    static PromotionRule rule(String id, RuleType type, int priority) {
        return rule(id, type, priority, null, OPEN_FROM, OPEN_TO);
    }

    static PromotionRule rule(String id, RuleType type, int priority, String exclusiveGroup,
            Instant validFrom, Instant validTo) {
        return new PromotionRule(id, id + " name", type, priority, exclusiveGroup, null,
                validFrom, validTo, conditions(), config());
    }

    static RuleConditions conditions() {
        return new RuleConditions("TOPS", List.of("SKU-A"), "SAVE10", null, null);
    }

    static DiscountConfig config() {
        return new DiscountConfig(new BigDecimal("10"), null, null, null, null, null, null);
    }

    static Cart cart(CartItem... items) {
        return new Cart("cart-test", "INR", NOW, UserTier.STANDARD, List.of(items), "SAVE10", "UPI");
    }

    static CartItem item(String sku, String category, String unitPrice, int quantity) {
        return new CartItem(sku, category, new BigDecimal(unitPrice), quantity);
    }

    static BundleComponent component(String sku, int quantity) {
        return new BundleComponent(sku, quantity);
    }

    static SpendTier percentageTier(String minSubtotal, String percentage) {
        return new SpendTier(new BigDecimal(minSubtotal), new BigDecimal(percentage), null);
    }

    static SpendTier amountTier(String minSubtotal, String amount) {
        return new SpendTier(new BigDecimal(minSubtotal), null, new BigDecimal(amount));
    }

    static final class Builder {

        private final String id;
        private final RuleType type;
        private String name;
        private int priority = 1;
        private String exclusiveGroup;
        private Boolean stackable;
        private Instant from = OPEN_FROM;
        private Instant to = OPEN_TO;
        private String category;
        private List<String> skus;
        private String couponCode;
        private String paymentMethod;
        private List<BundleComponent> components;
        private BigDecimal percentage;
        private BigDecimal amount;
        private BigDecimal maxDiscountAmount;
        private BigDecimal bundlePrice;
        private Integer buyQuantity;
        private Integer freeQuantity;
        private List<SpendTier> tiers;

        private Builder(String id, RuleType type) {
            this.id = id;
            this.type = type;
            this.name = id + " name";
        }

        Builder named(String value) {
            this.name = value;
            return this;
        }

        Builder priority(int value) {
            this.priority = value;
            return this;
        }

        Builder group(String value) {
            this.exclusiveGroup = value;
            return this;
        }

        Builder stackable(Boolean value) {
            this.stackable = value;
            return this;
        }

        Builder window(Instant validFrom, Instant validTo) {
            this.from = validFrom;
            this.to = validTo;
            return this;
        }

        Builder category(String value) {
            this.category = value;
            return this;
        }

        Builder skus(String... values) {
            this.skus = List.of(values);
            return this;
        }

        Builder rawSkus(List<String> values) {
            this.skus = values;
            return this;
        }

        Builder coupon(String value) {
            this.couponCode = value;
            return this;
        }

        Builder payment(String value) {
            this.paymentMethod = value;
            return this;
        }

        Builder components(BundleComponent... values) {
            this.components = Arrays.asList(values);
            return this;
        }

        Builder percentage(String value) {
            this.percentage = new BigDecimal(value);
            return this;
        }

        Builder amount(String value) {
            this.amount = new BigDecimal(value);
            return this;
        }

        Builder maxDiscount(String value) {
            this.maxDiscountAmount = new BigDecimal(value);
            return this;
        }

        Builder bundlePrice(String value) {
            this.bundlePrice = new BigDecimal(value);
            return this;
        }

        Builder buy(int value) {
            this.buyQuantity = value;
            return this;
        }

        Builder free(int value) {
            this.freeQuantity = value;
            return this;
        }

        Builder tiers(SpendTier... values) {
            this.tiers = Arrays.asList(values);
            return this;
        }

        PromotionRule build() {
            return new PromotionRule(id, name, type, priority, exclusiveGroup, stackable, from, to,
                    new RuleConditions(category, skus, couponCode, paymentMethod, components),
                    new DiscountConfig(percentage, amount, buyQuantity, freeQuantity,
                            maxDiscountAmount, bundlePrice, tiers));
        }
    }
}
