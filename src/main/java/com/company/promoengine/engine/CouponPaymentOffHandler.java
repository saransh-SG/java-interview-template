package com.company.promoengine.engine;

import com.company.promoengine.domain.Money;
import com.company.promoengine.domain.PromotionRule;
import com.company.promoengine.domain.RuleType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code COUPON_PAYMENT_OFF}: a cart discount gated on a coupon code and, optionally, a
 * payment method. Both are matched case-insensitively, unlike category and SKU.
 *
 * <p>The README requires {@code percentage} <em>or</em> {@code amount} here, and states an
 * "exactly one" rule only for spend tiers. That contrast is taken as deliberate: both
 * present is accepted rather than rejected as a configuration error, and the percentage is
 * used. Inventing a validation the specification withholds would skip a rule the caller
 * expected to apply.
 */
@Component
class CouponPaymentOffHandler implements RuleHandler {

    @Override
    public RuleType type() {
        return RuleType.COUPON_PAYMENT_OFF;
    }

    @Override
    public RuleScope scope() {
        return RuleScope.CART;
    }

    @Override
    public List<String> validate(PromotionRule rule) {
        var violations = new ArrayList<String>();
        Validations.nonBlank(rule.conditions() == null ? null : rule.conditions().couponCode(),
                "conditions.couponCode", violations);
        BigDecimal percentage = percentage(rule);
        BigDecimal amount = amount(rule);
        if (percentage == null && amount == null) {
            violations.add("one of discountConfig.percentage or discountConfig.amount is required");
        }
        Validations.percentageInRangeIfPresent(percentage, violations);
        Validations.positiveMoneyIfPresent(amount, "amount", violations);
        return violations;
    }

    @Override
    public Decision decide(PromotionRule rule, CartState state) {
        String required = rule.conditions().couponCode();
        String presented = state.cart().couponCode();
        if (presented == null || !presented.equalsIgnoreCase(required)) {
            return Decision.skip("Skipped: cart coupon " + (presented == null ? "absent" : presented)
                    + " does not match " + required);
        }

        String requiredPayment = rule.conditions().paymentMethod();
        if (requiredPayment != null && !requiredPayment.isBlank()) {
            String presentedPayment = state.cart().paymentMethod();
            if (presentedPayment == null || !presentedPayment.equalsIgnoreCase(requiredPayment)) {
                return Decision.skip("Skipped: cart payment method "
                        + (presentedPayment == null ? "absent" : presentedPayment)
                        + " does not match " + requiredPayment);
            }
        }

        var eligible = CartRuleSupport.eligibleBase(rule, state);
        if (eligible.base().signum() == 0) {
            return Decision.skip("Skipped: no eligible subtotal remains for coupon " + required);
        }

        BigDecimal percentage = percentage(rule);
        BigDecimal grant = Discounts.cappedAt(
                percentage != null ? Discounts.percentageOf(eligible.base(), percentage) : amount(rule),
                eligible.base());

        String benefit = percentage != null
                ? Validations.plain(percentage) + "% coupon " + required
                : Money.format(grant) + " coupon " + required;
        String via = requiredPayment == null || requiredPayment.isBlank() ? "" : " via " + requiredPayment;
        return Decision.apply(CartRuleSupport.plan(eligible, grant, "Applied: " + benefit + via
                + " off cart (" + CartRuleSupport.baseDescription(eligible) + ")"));
    }

    private static BigDecimal percentage(PromotionRule rule) {
        return rule.discountConfig() == null ? null : rule.discountConfig().percentage();
    }

    private static BigDecimal amount(PromotionRule rule) {
        return rule.discountConfig() == null ? null : rule.discountConfig().amount();
    }
}
