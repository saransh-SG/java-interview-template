package com.company.promoengine.engine;

import com.company.promoengine.domain.Money;
import java.math.BigDecimal;

/**
 * One purchasable unit of a cart line, and the engine's source of truth for price.
 * A line's current price is the sum of its units, so the README's "a line's current
 * price is the sum of its units' current prices" holds by construction rather than
 * by convention.
 *
 * <p>Units exist because two rules need them. {@code BUY_X_GET_CHEAPEST_FREE} sorts by
 * current unit price and frees whole units at that price, and {@code BUNDLE_FIXED_PRICE}
 * marks individual units bundled while leftovers on the same line stay eligible.
 */
final class UnitState {

    private final int lineIndex;
    private final int unitIndex;
    private BigDecimal currentPrice;
    private boolean bundled;

    UnitState(int lineIndex, int unitIndex, BigDecimal unitPrice) {
        this.lineIndex = lineIndex;
        this.unitIndex = unitIndex;
        this.currentPrice = Money.of(unitPrice);
    }

    /** Request order of the owning line; part of the deterministic unit ordering. */
    int lineIndex() {
        return lineIndex;
    }

    /** Position within the owning line; the final tie-breaker when prices and lines tie. */
    int unitIndex() {
        return unitIndex;
    }

    BigDecimal currentPrice() {
        return currentPrice;
    }

    /** Consumed by an applied bundle. Later item-level rules skip these units. */
    boolean bundled() {
        return bundled;
    }

    void markBundled() {
        bundled = true;
    }

    /**
     * Takes {@code amount} off this unit, never below {@code 0.00}.
     *
     * @return what was actually taken off, which is what the audit entry must report
     */
    BigDecimal reduceBy(BigDecimal amount) {
        BigDecimal taken = Money.of(amount).min(currentPrice).max(Money.ZERO);
        currentPrice = currentPrice.subtract(taken);
        return taken;
    }
}
