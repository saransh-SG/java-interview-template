package com.company.promoengine.engine;

import com.company.promoengine.domain.Money;
import com.company.promoengine.domain.PromotionRule;
import com.company.promoengine.domain.RuleType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code PERCENTAGE_OFF_CATEGORY}: a percentage off every line in one category.
 *
 * <p>Each matching line is computed and rounded on its own. The README is explicit that a
 * category percentage is never computed on several lines summed together: two {@code 1.25}
 * lines at 15% round independently to {@code 0.19} each, not to {@code 0.38} from
 * {@code 2.50}. Pooling would also change the answer in the other direction, so the
 * per-line loop below is load-bearing rather than incidental.
 *
 * <p>{@code maxDiscountAmount} is the one case where this rule allocates: the rule total
 * is capped and then split across the matching lines by each line's uncapped discount.
 */
@Component
class PercentageOffCategoryHandler implements RuleHandler {

    @Override
    public RuleType type() {
        return RuleType.PERCENTAGE_OFF_CATEGORY;
    }

    @Override
    public RuleScope scope() {
        return RuleScope.ITEM;
    }

    @Override
    public List<String> validate(PromotionRule rule) {
        var violations = new ArrayList<String>();
        Validations.nonBlank(rule.conditions() == null ? null : rule.conditions().category(),
                "conditions.category", violations);
        Validations.percentageInRange(config(rule), violations);
        Validations.positiveMoneyIfPresent(maxDiscount(rule), "maxDiscountAmount", violations);
        return violations;
    }

    @Override
    public Decision decide(PromotionRule rule, CartState state) {
        String category = rule.conditions().category();
        // Case-sensitive category match, and only lines with units a bundle has not taken.
        List<LineState> matching = state.lines().stream()
                .filter(line -> category.equals(line.item().category()))
                .filter(line -> !line.nonBundledUnits().isEmpty())
                .toList();
        if (matching.isEmpty()) {
            return Decision.skip("Skipped: no items in category " + category);
        }

        BigDecimal percentage = config(rule);
        var uncapped = new ArrayList<BigDecimal>(matching.size());
        BigDecimal total = Money.ZERO;
        for (LineState line : matching) {
            BigDecimal eligibleBase = line.nonBundledPrice();
            // Rounded once, here, then capped at what the line can actually give up.
            BigDecimal lineDiscount =
                    Discounts.cappedAt(Discounts.percentageOf(eligibleBase, percentage), eligibleBase);
            uncapped.add(lineDiscount);
            total = total.add(lineDiscount);
        }

        BigDecimal cap = maxDiscount(rule);
        boolean capped = cap != null && total.compareTo(cap) > 0;
        List<BigDecimal> perLine = capped ? PennyAllocator.allocate(cap, uncapped) : uncapped;

        var reductions = new ArrayList<DiscountPlan.LineReduction>(matching.size());
        for (int i = 0; i < matching.size(); i++) {
            LineState line = matching.get(i);
            reductions.add(DiscountPlan.LineReduction.of(line, line.nonBundledUnits(), perLine.get(i)));
        }
        return Decision.apply(DiscountPlan.of(reductions, reason(percentage, category, capped, cap)));
    }

    private static String reason(BigDecimal percentage, String category, boolean capped, BigDecimal cap) {
        String base = "Applied: " + Validations.plain(percentage) + "% off category " + category;
        return capped ? base + ", capped at " + Money.format(cap) : base;
    }

    private static BigDecimal config(PromotionRule rule) {
        return rule.discountConfig() == null ? null : rule.discountConfig().percentage();
    }

    private static BigDecimal maxDiscount(PromotionRule rule) {
        return rule.discountConfig() == null ? null : rule.discountConfig().maxDiscountAmount();
    }
}
