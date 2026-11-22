package com.company.promoengine.engine;

import com.company.promoengine.domain.PromotionRule;
import com.company.promoengine.domain.RuleType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code CART_PERCENTAGE_OFF}: a percentage off the running cart base.
 *
 * <p>Rounded once against that base, then split across the eligible lines. Two 10% cart
 * rules on {@code 100.00} therefore grant {@code 10.00} and then {@code 9.00}, because the
 * second sees the subtotal the first left behind.
 */
@Component
class CartPercentageOffHandler implements RuleHandler {

    @Override
    public RuleType type() {
        return RuleType.CART_PERCENTAGE_OFF;
    }

    @Override
    public RuleScope scope() {
        return RuleScope.CART;
    }

    @Override
    public List<String> validate(PromotionRule rule) {
        var violations = new ArrayList<String>();
        Validations.percentageInRange(
                rule.discountConfig() == null ? null : rule.discountConfig().percentage(), violations);
        return violations;
    }

    @Override
    public Decision decide(PromotionRule rule, CartState state) {
        var eligible = CartRuleSupport.eligibleBase(rule, state);
        if (eligible.base().signum() == 0) {
            return Decision.skip("Skipped: no eligible subtotal remains for a cart discount");
        }

        BigDecimal percentage = rule.discountConfig().percentage();
        BigDecimal grant = Discounts.cappedAt(
                Discounts.percentageOf(eligible.base(), percentage), eligible.base());
        return Decision.apply(CartRuleSupport.plan(eligible, grant,
                "Applied: " + Validations.plain(percentage) + "% off cart ("
                        + CartRuleSupport.baseDescription(eligible) + ")"));
    }
}
