package com.company.promoengine.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Largest-remainder allocation: the parts must always sum back to the whole. */
class PennyAllocatorTest {

    @Test
    @DisplayName("README example: 10.00 across three equal lines gives 3.34, 3.33, 3.33")
    void splitsTenAcrossThreeEqualLines() {
        assertAllocation("10.00", weights("100.00", "100.00", "100.00"), "3.34", "3.33", "3.33");
    }

    @Test
    @DisplayName("Fixture 03: bundle saving of 200.00 by consumed value 800/150/50")
    void allocatesBundleSavingByConsumedValue() {
        assertAllocation("200.00", weights("800.00", "150.00", "50.00"), "160.00", "30.00", "10.00");
    }

    @Test
    @DisplayName("A single leftover cent goes to the earlier line on a remainder tie")
    void breaksRemainderTiesTowardTheEarlierLine() {
        assertAllocation("0.01", weights("1.00", "1.00"), "0.01", "0.00");
    }

    @Test
    @DisplayName("A zero-weight line never receives a cent")
    void skipsZeroWeightLines() {
        assertAllocation("0.03", weights("0.00", "1.00"), "0.00", "0.03");
    }

    @Test
    @DisplayName("A total above the summed weights is clamped to them, never over-allocated")
    void clampsTotalToSummedWeights() {
        assertAllocation("10.00", weights("0.01", "0.02"), "0.01", "0.02");
    }

    @Test
    @DisplayName("At total == sum(weights) every recipient takes exactly its weight, with no leftover")
    void allocatesExactlyTheWeightsAtTheBoundary() {
        assertAllocation("3.00", weights("1.00", "1.00", "1.00"), "1.00", "1.00", "1.00");
        assertAllocation("1.63", weights("0.33", "1.00", "0.30"), "0.33", "1.00", "0.30");
    }

    @Test
    @DisplayName("Awkward cent counts still reconcile exactly")
    void reconcilesAwkwardCentCounts() {
        assertAllocation("0.07", weights("1.00", "1.00", "1.00"), "0.03", "0.02", "0.02");
        assertAllocation("1.00", weights("1.00", "1.00", "1.00"), "0.34", "0.33", "0.33");
        assertAllocation("0.01", weights("0.33", "0.33", "0.34"), "0.00", "0.00", "0.01");
    }

    @Test
    @DisplayName("One recipient takes the whole total")
    void givesEverythingToASingleRecipient() {
        assertAllocation("5.00", weights("9.99"), "5.00");
    }

    @Test
    @DisplayName("Nothing to split, or nothing to split across, allocates zeros")
    void allocatesZerosWhenEitherSideIsZero() {
        assertAllocation("0.00", weights("1.00", "2.00"), "0.00", "0.00");
        assertAllocation("5.00", weights("0.00", "0.00"), "0.00", "0.00");
        assertThat(PennyAllocator.allocate(new BigDecimal("5.00"), List.of())).isEmpty();
    }

    @Test
    @DisplayName("Negative inputs are a programming error, not a silent credit")
    void rejectsNegativeInputs() {
        assertThatThrownBy(() -> PennyAllocator.allocate(new BigDecimal("-0.01"), weights("1.00")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PennyAllocator.allocate(new BigDecimal("1.00"), weights("-1.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static List<BigDecimal> weights(String... amounts) {
        return List.of(amounts).stream().map(BigDecimal::new).toList();
    }

    private static void assertAllocation(String total, List<BigDecimal> weights, String... expected) {
        var allocation = PennyAllocator.allocate(new BigDecimal(total), weights);
        assertThat(allocation).hasSize(expected.length);
        for (int i = 0; i < expected.length; i++) {
            assertThat(allocation.get(i))
                    .as("share %d of %s", i, total)
                    .isEqualByComparingTo(expected[i]);
        }
        assertThat(allocation.stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .as("allocation of %s must sum back to the whole", total)
                .isEqualByComparingTo(sumOf(expected));
    }

    private static BigDecimal sumOf(String... amounts) {
        return List.of(amounts).stream().map(BigDecimal::new).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
