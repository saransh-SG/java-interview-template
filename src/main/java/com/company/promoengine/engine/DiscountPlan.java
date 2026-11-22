package com.company.promoengine.engine;

import com.company.promoengine.domain.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * What a handler worked out but has not applied. Handlers compute; the orchestrator
 * commits. Keeping the two apart is what lets gatekeeper step 6 (a {@code 0.00} discount
 * is a skip, not an application) live in one place instead of in six handlers.
 *
 * @param reductions    amounts to take off, each against an explicit set of units
 * @param unitsToBundle units an applied bundle consumes; marked only after the
 *                      reductions land, since the reductions target these same units
 * @param reason        the audit reason for the APPLIED entry, never blank
 */
record DiscountPlan(List<LineReduction> reductions, List<UnitState> unitsToBundle, String reason) {

    DiscountPlan {
        reductions = List.copyOf(reductions);
        unitsToBundle = List.copyOf(unitsToBundle);
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a discount plan needs a non-blank audit reason");
        }
    }

    static DiscountPlan of(List<LineReduction> reductions, String reason) {
        return new DiscountPlan(reductions, List.of(), reason);
    }

    /**
     * The discount as computed, before the {@code 0.00} floor. This is what gatekeeper
     * step 6 tests; what the audit entry reports is what the commit actually took off.
     */
    BigDecimal plannedTotal() {
        return reductions.stream().map(LineReduction::amount).reduce(Money.ZERO, BigDecimal::add);
    }

    /**
     * One amount to take off one line, spread across the units named in {@code targets}
     * by the same largest-remainder method used across lines.
     *
     * <p>Naming the targets explicitly is what keeps the four mechanics honest with a
     * single reduction type: a category rule passes the line's non-bundled units, a
     * cart rule passes every unit, a bundle passes exactly the units it consumes, and
     * {@code BUY_X_GET_CHEAPEST_FREE} passes one freed unit at its own current price.
     */
    record LineReduction(int lineIndex, List<UnitState> targets, BigDecimal amount) {

        LineReduction {
            targets = List.copyOf(targets);
            Objects.requireNonNull(amount, "amount");
        }

        static LineReduction of(LineState line, List<UnitState> targets, BigDecimal amount) {
            return new LineReduction(line.index(), targets, Money.of(amount));
        }

        static LineReduction ofUnit(UnitState unit, BigDecimal amount) {
            return new LineReduction(unit.lineIndex(), List.of(unit), Money.of(amount));
        }
    }
}
