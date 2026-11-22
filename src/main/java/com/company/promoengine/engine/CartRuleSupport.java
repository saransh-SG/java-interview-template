package com.company.promoengine.engine;

import com.company.promoengine.domain.Money;
import com.company.promoengine.domain.PromotionRule;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The flow all three cart-level rules share: pick the eligible lines, sum their current
 * prices into a base, and spread a grant back across them.
 *
 * <p>Only the grant itself differs between a spend tier, a flat cart percentage and a
 * coupon, so only that is left to each handler.
 */
final class CartRuleSupport {

    private CartRuleSupport() {
    }

    /**
     * Eligible lines and their summed current price.
     *
     * <p>{@code stackableWithItemPromos} of {@code true} or {@code null} means every line.
     * {@code false} limits the base to lines no item-level rule has reduced.
     */
    static EligibleBase eligibleBase(PromotionRule rule, CartState state) {
        boolean stacks = rule.stackableWithItemPromos() == null || rule.stackableWithItemPromos();
        List<LineState> eligible = stacks
                ? state.lines()
                : state.lines().stream().filter(line -> !line.itemDiscounted()).toList();
        BigDecimal base = eligible.stream().map(LineState::currentPrice).reduce(Money.ZERO, BigDecimal::add);
        return new EligibleBase(eligible, base, stacks);
    }

    /**
     * Spreads {@code grant} across the eligible lines by current price. A cart-level grant
     * reduces the whole line, bundled units included, because the base it was computed
     * from is the line's full current price.
     */
    static DiscountPlan plan(EligibleBase eligible, BigDecimal grant, String reason) {
        List<BigDecimal> shares = PennyAllocator.allocate(
                grant, eligible.lines().stream().map(LineState::currentPrice).toList());
        List<DiscountPlan.LineReduction> reductions = new ArrayList<>();
        for (int i = 0; i < eligible.lines().size(); i++) {
            LineState line = eligible.lines().get(i);
            reductions.add(DiscountPlan.LineReduction.of(line, line.units(), shares.get(i)));
        }
        return DiscountPlan.of(reductions, reason);
    }

    /** Describes the base in an audit reason, naming the restriction when there is one. */
    static String baseDescription(EligibleBase eligible) {
        if (eligible.stacks()) {
            return "running subtotal " + Money.format(eligible.base());
        }
        return "running subtotal " + Money.format(eligible.base()) + " over "
                + eligible.lines().size() + " line(s) with no item promotion";
    }

    record EligibleBase(List<LineState> lines, BigDecimal base, boolean stacks) {
    }
}
