package com.company.promoengine.engine;

import com.company.promoengine.domain.Money;
import java.math.BigDecimal;

/**
 * The two arithmetic steps every percentage rule shares.
 *
 * <p>A percentage is rounded exactly once, at the level the README names: the end of a
 * line calculation for a category rule, the end of a cart calculation for a cart rule.
 * Nothing upstream of that is rounded, and a category percentage is never computed on
 * several lines summed together.
 */
final class Discounts {

    private Discounts() {
    }

    /** {@code percentage%} of {@code base}, rounded once to scale 2 HALF_UP. */
    static BigDecimal percentageOf(BigDecimal base, BigDecimal percentage) {
        // Dividing a decimal by 100 is an exact shift, so only the final setScale rounds.
        return Money.of(base.multiply(percentage).divide(Money.ONE_HUNDRED));
    }

    /** Never grant more than the eligible base: the README's zero floor. */
    static BigDecimal cappedAt(BigDecimal amount, BigDecimal limit) {
        return amount.min(limit).max(Money.ZERO);
    }
}
