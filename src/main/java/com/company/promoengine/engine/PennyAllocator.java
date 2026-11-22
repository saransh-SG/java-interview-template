package com.company.promoengine.engine;

import com.company.promoengine.domain.Money;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The single place in the engine where one monetary total is split across several
 * recipients. Every split goes through here: cart-level grants, bundle savings,
 * capped category discounts, and the line-to-unit push-down.
 *
 * <p>Largest-remainder (Hamilton) method, in integer cents so the parts always sum
 * back to the whole:
 * <ol>
 *   <li>each recipient takes its integer-cent floor share of {@code total},
 *       proportional to its weight;</li>
 *   <li>leftover cents go out one at a time, largest fractional remainder first,
 *       skipping any recipient already at its weight, ties to the lower index
 *       (that is request order for lines, unit order within a line);</li>
 *   <li>{@code total} above the summed weights is clamped to them, so no recipient
 *       is ever allocated more than it is worth and nothing can go below 0.00.</li>
 * </ol>
 *
 * <p>The step-2 cap is a literal transcription of the spec that is, given the step-3
 * clamp, unreachable on every path. Proof in two cases. When {@code total < sum(weights)}
 * each floor share is strictly below its own weight, and leftover cents number at most
 * {@code recipients - 1}, so the first pass hands out every leftover cent and no
 * recipient is visited twice. When {@code total == sum(weights)} each share divides
 * exactly into its own weight, so there is no leftover cent to hand out at all. The
 * guard is kept because it costs nothing and keeps {@code allocate} correct for a caller
 * that one day bypasses the clamp; it is not load-bearing today.
 */
final class PennyAllocator {

    private PennyAllocator() {
    }

    /**
     * @param total   amount to split, {@code >= 0.00}
     * @param weights per-recipient weight, each {@code >= 0.00}, in recipient order
     * @return one scale-2 share per weight, in the same order, summing to
     *         {@code min(total, sum(weights))}; all zeros when either side is zero
     */
    static List<BigDecimal> allocate(BigDecimal total, List<BigDecimal> weights) {
        Objects.requireNonNull(total, "total");
        Objects.requireNonNull(weights, "weights");

        int size = weights.size();
        long[] cap = new long[size];
        long weightSum = 0;
        for (int i = 0; i < size; i++) {
            long weight = cents(weights.get(i), "weight at index " + i);
            cap[i] = weight;
            weightSum += weight;
        }

        long remaining = cents(total, "total");
        if (size == 0 || weightSum == 0 || remaining == 0) {
            return Collections.nCopies(size, Money.ZERO);
        }
        remaining = Math.min(remaining, weightSum);

        var divisor = BigInteger.valueOf(weightSum);
        var dividend = BigInteger.valueOf(remaining);
        long[] share = new long[size];
        var remainder = new BigInteger[size];
        long floorTotal = 0;
        for (int i = 0; i < size; i++) {
            // Exact integer arithmetic: no double, and no overflow on a large cart.
            BigInteger[] quotientAndRemainder =
                    dividend.multiply(BigInteger.valueOf(cap[i])).divideAndRemainder(divisor);
            share[i] = quotientAndRemainder[0].longValueExact();
            remainder[i] = quotientAndRemainder[1];
            floorTotal += share[i];
        }

        // Remainders share the divisor, so their numerators compare directly.
        var byRemainderThenIndex = new ArrayList<Integer>(size);
        for (int i = 0; i < size; i++) {
            byRemainderThenIndex.add(i);
        }
        byRemainderThenIndex.sort(Comparator.<Integer, BigInteger>comparing(i -> remainder[i])
                .reversed()
                .thenComparing(Comparator.naturalOrder()));

        long leftover = remaining - floorTotal;
        while (leftover > 0) {
            boolean handedOut = false;
            for (int i : byRemainderThenIndex) {
                if (leftover == 0) {
                    break;
                }
                if (share[i] < cap[i]) {
                    share[i]++;
                    leftover--;
                    handedOut = true;
                }
            }
            if (!handedOut) {
                break;
            }
        }

        var allocation = new ArrayList<BigDecimal>(size);
        for (long cents : share) {
            allocation.add(BigDecimal.valueOf(cents, Money.SCALE));
        }
        return List.copyOf(allocation);
    }

    private static long cents(BigDecimal amount, String what) {
        Objects.requireNonNull(amount, what);
        long value = amount.setScale(Money.SCALE, Money.ROUNDING).unscaledValue().longValueExact();
        if (value < 0) {
            throw new IllegalArgumentException("negative " + what + ": " + amount.toPlainString());
        }
        return value;
    }
}
