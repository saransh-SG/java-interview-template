package com.company.promoengine.engine;

import com.company.promoengine.domain.Money;
import com.company.promoengine.domain.PromotionRule;
import com.company.promoengine.domain.RuleType;
import com.company.promoengine.domain.SpendTier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code TIERED_SPEND}: one tier, chosen by the running cart base.
 *
 * <p>The tier with the greatest {@code minSubtotal} at or below the base wins, whatever
 * order the tiers were listed in, and the earlier of two tiers sharing a {@code minSubtotal}
 * wins. Because the base is the running subtotal, an item discount applied by an earlier
 * rule can drop the cart into a lower tier, or out of every tier.
 */
@Component
class TieredSpendHandler implements RuleHandler {

    @Override
    public RuleType type() {
        return RuleType.TIERED_SPEND;
    }

    @Override
    public RuleScope scope() {
        return RuleScope.CART;
    }

    @Override
    public List<String> validate(PromotionRule rule) {
        var violations = new ArrayList<String>();
        List<SpendTier> tiers = rule.discountConfig() == null ? null : rule.discountConfig().tiers();
        if (tiers == null || tiers.isEmpty()) {
            violations.add("discountConfig.tiers is required");
            return violations;
        }
        for (SpendTier tier : tiers) {
            Validations.nonNegativeMoney(tier.minSubtotal(), "tier minSubtotal", violations);
            // README: every tier has exactly one of a valid percentage or a valid amount.
            boolean validPercentage = tier.percentage() != null
                    && tier.percentage().signum() > 0
                    && tier.percentage().compareTo(Money.ONE_HUNDRED) <= 0;
            boolean validAmount = tier.amount() != null && tier.amount().signum() > 0;
            if (validPercentage == validAmount) {
                violations.add("every tier needs exactly one of a valid percentage or a valid amount");
            }
        }
        return violations;
    }

    @Override
    public Decision decide(PromotionRule rule, CartState state) {
        var eligible = CartRuleSupport.eligibleBase(rule, state);
        if (eligible.base().signum() == 0) {
            return Decision.skip("Skipped: no eligible subtotal remains for a spend tier");
        }

        SpendTier selected = null;
        for (SpendTier tier : rule.discountConfig().tiers()) {
            if (tier.minSubtotal().compareTo(eligible.base()) > 0) {
                continue;
            }
            // Strictly greater, so the earlier tier wins a minSubtotal tie.
            if (selected == null || tier.minSubtotal().compareTo(selected.minSubtotal()) > 0) {
                selected = tier;
            }
        }
        if (selected == null) {
            return Decision.skip("Skipped: running subtotal " + Money.format(eligible.base())
                    + " qualifies for no spend tier");
        }

        BigDecimal grant = selected.percentage() != null
                ? Discounts.percentageOf(eligible.base(), selected.percentage())
                : selected.amount();
        grant = Discounts.cappedAt(grant, eligible.base());

        String benefit = selected.percentage() != null
                ? Validations.plain(selected.percentage()) + "% off cart"
                : Money.format(grant) + " off cart";
        return Decision.apply(CartRuleSupport.plan(eligible, grant,
                "Applied: spend tier >= " + Money.format(selected.minSubtotal()) + " grants "
                        + benefit + " (" + CartRuleSupport.baseDescription(eligible) + ")"));
    }
}
