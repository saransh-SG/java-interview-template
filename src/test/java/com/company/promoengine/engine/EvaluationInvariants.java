package com.company.promoengine.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.promoengine.domain.AuditEntry;
import com.company.promoengine.domain.EvaluationResult;
import com.company.promoengine.domain.ItemizedLine;
import com.company.promoengine.domain.Money;
import com.company.promoengine.domain.RuleStatus;
import java.math.BigDecimal;

/**
 * The accounting contract, asserted on every evaluation in the interaction tests rather
 * than spot-checked. Comparisons use {@code compareTo}, so a scale difference is not
 * mistaken for a value difference.
 */
final class EvaluationInvariants {

    private EvaluationInvariants() {
    }

    static EvaluationResult assertHolds(EvaluationResult result, int expectedRuleCount) {
        assertThat(result.originalSubtotal()).as("originalSubtotal >= 0.00")
                .isGreaterThanOrEqualTo(Money.ZERO);
        assertThat(result.totalDiscount()).as("totalDiscount >= 0.00")
                .isGreaterThanOrEqualTo(Money.ZERO);
        assertThat(result.finalTotal()).as("finalTotal >= 0.00").isGreaterThanOrEqualTo(Money.ZERO);

        assertThat(result.originalSubtotal().subtract(result.totalDiscount()))
                .as("originalSubtotal - totalDiscount == finalTotal")
                .isEqualByComparingTo(result.finalTotal());
        assertThat(sum(result, ItemizedLine::originalExtendedPrice))
                .as("sum(originalExtendedPrice) == originalSubtotal")
                .isEqualByComparingTo(result.originalSubtotal());
        assertThat(sum(result, ItemizedLine::discount))
                .as("sum(line.discount) == totalDiscount")
                .isEqualByComparingTo(result.totalDiscount());
        assertThat(sum(result, ItemizedLine::finalExtendedPrice))
                .as("sum(finalExtendedPrice) == finalTotal")
                .isEqualByComparingTo(result.finalTotal());

        BigDecimal applied = result.auditTrail().stream()
                .filter(entry -> entry.status() == RuleStatus.APPLIED)
                .map(AuditEntry::discountAmount)
                .reduce(Money.ZERO, BigDecimal::add);
        assertThat(applied).as("sum(APPLIED audit.discountAmount) == totalDiscount")
                .isEqualByComparingTo(result.totalDiscount());

        for (ItemizedLine line : result.itemizedBreakdown()) {
            assertThat(line.finalExtendedPrice()).as("line %s floors at 0.00", line.sku())
                    .isGreaterThanOrEqualTo(Money.ZERO);
            assertThat(line.discount()).as("line %s discount >= 0.00", line.sku())
                    .isGreaterThanOrEqualTo(Money.ZERO);
            assertThat(line.originalExtendedPrice().subtract(line.discount()))
                    .as("line %s reconciles", line.sku())
                    .isEqualByComparingTo(line.finalExtendedPrice());
        }

        assertThat(result.auditTrail()).as("one audit entry per input rule").hasSize(expectedRuleCount);
        for (AuditEntry entry : result.auditTrail()) {
            assertThat(entry.reason()).as("audit reason for %s", entry.ruleId()).isNotBlank();
            assertThat(entry.reason()).isNotEqualTo("Skipped: promotion engine not implemented");
            if (entry.status() == RuleStatus.SKIPPED) {
                assertThat(entry.discountAmount()).as("a skipped rule grants 0.00")
                        .isEqualByComparingTo(Money.ZERO);
            }
        }
        return result;
    }

    private static BigDecimal sum(EvaluationResult result,
            java.util.function.Function<ItemizedLine, BigDecimal> field) {
        return result.itemizedBreakdown().stream().map(field).reduce(Money.ZERO, BigDecimal::add);
    }
}
