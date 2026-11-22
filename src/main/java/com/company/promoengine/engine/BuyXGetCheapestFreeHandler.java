package com.company.promoengine.engine;

import com.company.promoengine.domain.PromotionRule;
import com.company.promoengine.domain.RuleType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code BUY_X_GET_CHEAPEST_FREE}: every complete group of {@code buyQuantity +
 * freeQuantity} eligible units frees {@code freeQuantity} of them.
 *
 * <p>The pool is units, not lines, so one rule can mix and match across several SKUs and
 * free units from different lines. Units are freed at their <em>current</em> price, which
 * is why price lives on {@link UnitState}: a unit already reduced by an earlier rule is
 * cheaper now and may sort into the free positions where it would not have before.
 */
@Component
class BuyXGetCheapestFreeHandler implements RuleHandler {

    /** Cheapest first, then request order, then unit order. Fully determined. */
    private static final Comparator<UnitState> CHEAPEST_FIRST =
            Comparator.comparing(UnitState::currentPrice)
                    .thenComparingInt(UnitState::lineIndex)
                    .thenComparingInt(UnitState::unitIndex);

    @Override
    public RuleType type() {
        return RuleType.BUY_X_GET_CHEAPEST_FREE;
    }

    @Override
    public RuleScope scope() {
        return RuleScope.ITEM;
    }

    @Override
    public List<String> validate(PromotionRule rule) {
        var violations = new ArrayList<String>();
        List<String> skus = rule.conditions() == null ? null : rule.conditions().skus();
        if (skus == null || skus.isEmpty()) {
            violations.add("conditions.skus is required");
        } else if (skus.stream().anyMatch(sku -> sku == null || sku.isBlank())) {
            violations.add("conditions.skus must not contain blank entries");
        }
        Validations.atLeastOne(buyQuantity(rule), "buyQuantity", violations);
        Validations.atLeastOne(freeQuantity(rule), "freeQuantity", violations);
        return violations;
    }

    @Override
    public Decision decide(PromotionRule rule, CartState state) {
        List<String> skus = rule.conditions().skus();
        // Case-sensitive SKU match, bundled units excluded.
        List<UnitState> pool = state.lines().stream()
                .filter(line -> skus.contains(line.item().sku()))
                .flatMap(line -> line.nonBundledUnits().stream())
                .sorted(CHEAPEST_FIRST)
                .toList();
        if (pool.isEmpty()) {
            return Decision.skip("Skipped: no eligible units for SKUs " + String.join(", ", skus));
        }

        int buy = buyQuantity(rule);
        int free = freeQuantity(rule);
        int groupSize = buy + free;
        int freeUnits = (pool.size() / groupSize) * free;
        if (freeUnits == 0) {
            return Decision.skip("Skipped: " + pool.size() + " eligible unit(s), "
                    + groupSize + " needed for one buy " + buy + " get " + free + " group");
        }

        var reductions = new ArrayList<DiscountPlan.LineReduction>(freeUnits);
        for (UnitState unit : pool.subList(0, freeUnits)) {
            reductions.add(DiscountPlan.LineReduction.ofUnit(unit, unit.currentPrice()));
        }
        return Decision.apply(DiscountPlan.of(reductions, "Applied: buy " + buy + " get " + free
                + " cheapest free (" + freeUnits + " unit" + (freeUnits == 1 ? "" : "s") + " free)"));
    }

    private static Integer buyQuantity(PromotionRule rule) {
        return rule.discountConfig() == null ? null : rule.discountConfig().buyQuantity();
    }

    private static Integer freeQuantity(PromotionRule rule) {
        return rule.discountConfig() == null ? null : rule.discountConfig().freeQuantity();
    }
}
