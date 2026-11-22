package com.company.promoengine.engine;

import com.company.promoengine.domain.Money;
import java.math.BigDecimal;
import java.util.List;

/**
 * Gatekeeper step 4 predicates, shared so each handler states only which fields its own
 * rule type needs. A violation is a skip with a readable reason, never an HTTP 400.
 */
final class Validations {

    private Validations() {
    }

    /** README: {@code percentage} must be {@code > 0} and {@code <= 100}. */
    static void percentageInRange(BigDecimal percentage, List<String> violations) {
        if (percentage == null) {
            violations.add("percentage is required");
        } else if (percentage.signum() <= 0 || percentage.compareTo(Money.ONE_HUNDRED) > 0) {
            violations.add("percentage must be > 0 and <= 100 but was " + plain(percentage));
        }
    }

    /** Used where a percentage is optional but must be legal when present. */
    static void percentageInRangeIfPresent(BigDecimal percentage, List<String> violations) {
        if (percentage != null) {
            percentageInRange(percentage, violations);
        }
    }

    /** README: {@code amount} and {@code maxDiscountAmount} must be {@code > 0}. */
    static void positiveMoney(BigDecimal amount, String field, List<String> violations) {
        if (amount == null) {
            violations.add(field + " is required");
        } else if (amount.signum() <= 0) {
            violations.add(field + " must be > 0 but was " + plain(amount));
        }
    }

    static void positiveMoneyIfPresent(BigDecimal amount, String field, List<String> violations) {
        if (amount != null) {
            positiveMoney(amount, field, violations);
        }
    }

    /** README: {@code bundlePrice} and tier {@code minSubtotal} must be {@code >= 0}. */
    static void nonNegativeMoney(BigDecimal amount, String field, List<String> violations) {
        if (amount == null) {
            violations.add(field + " is required");
        } else if (amount.signum() < 0) {
            violations.add(field + " must be >= 0 but was " + plain(amount));
        }
    }

    /** README: {@code buyQuantity} and {@code freeQuantity} must be integers {@code >= 1}. */
    static void atLeastOne(Integer quantity, String field, List<String> violations) {
        if (quantity == null) {
            violations.add(field + " is required");
        } else if (quantity < 1) {
            violations.add(field + " must be >= 1 but was " + quantity);
        }
    }

    static void nonBlank(String value, String field, List<String> violations) {
        if (value == null || value.isBlank()) {
            violations.add(field + " is required");
        }
    }

    static String plain(BigDecimal value) {
        return value == null ? "null" : value.stripTrailingZeros().toPlainString();
    }
}
