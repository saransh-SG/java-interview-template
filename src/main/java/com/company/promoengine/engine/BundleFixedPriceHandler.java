package com.company.promoengine.engine;

import com.company.promoengine.domain.BundleComponent;
import com.company.promoengine.domain.Money;
import com.company.promoengine.domain.PromotionRule;
import com.company.promoengine.domain.RuleType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code BUNDLE_FIXED_PRICE}: every complete set of component SKUs costs {@code bundlePrice}.
 *
 * <p>Two readings of the README had to be reconciled. The rule's own text forms sets from
 * {@code min(floor(lineQuantity / requiredQuantity))}, while the unit-tracking mechanic says
 * a later item-level rule skips bundled units — and this rule is itself item-level. Taken
 * only literally, a second bundle rule would re-count units the first already consumed and
 * discount them twice. Sets are therefore formed from units still unbundled, which honours
 * both statements and cannot over-discount a line.
 *
 * <p>Savings use the <em>input</em> {@code unitPrice}, exactly as specified, not the running
 * price. A bundle that runs after a category discount can therefore plan more than the line
 * still holds; the commit's per-unit floor clamps it and the audit reports what was really
 * taken off.
 *
 * <p>Collecting eligible units per SKU rather than per line also means one SKU spread over
 * two cart lines is handled without a special case.
 */
@Component
class BundleFixedPriceHandler implements RuleHandler {

    private static final Comparator<UnitState> REQUEST_ORDER =
            Comparator.comparingInt(UnitState::lineIndex).thenComparingInt(UnitState::unitIndex);

    @Override
    public RuleType type() {
        return RuleType.BUNDLE_FIXED_PRICE;
    }

    @Override
    public RuleScope scope() {
        return RuleScope.ITEM;
    }

    @Override
    public List<String> validate(PromotionRule rule) {
        var violations = new ArrayList<String>();
        List<BundleComponent> components = rule.conditions() == null ? null : rule.conditions().components();
        if (components == null || components.isEmpty()) {
            violations.add("conditions.components is required");
        } else {
            var seen = new HashSet<String>();
            for (BundleComponent component : components) {
                if (component.sku() == null || component.sku().isBlank()) {
                    violations.add("every bundle component needs a non-blank sku");
                } else if (!seen.add(component.sku())) {
                    violations.add("bundle component sku " + component.sku() + " appears more than once");
                }
                if (component.quantity() < 1) {
                    violations.add("bundle component quantity must be >= 1 but was " + component.quantity());
                }
            }
        }
        Validations.nonNegativeMoney(bundlePrice(rule), "bundlePrice", violations);
        return violations;
    }

    @Override
    public Decision decide(PromotionRule rule, CartState state) {
        List<BundleComponent> components = rule.conditions().components();

        // Eligible units per component SKU, in request order, across every line.
        Map<String, List<UnitState>> available = new LinkedHashMap<>();
        for (BundleComponent component : components) {
            available.put(component.sku(), state.lines().stream()
                    .filter(line -> component.sku().equals(line.item().sku()))
                    .flatMap(line -> line.nonBundledUnits().stream())
                    .sorted(REQUEST_ORDER)
                    .toList());
        }

        int bundles = Integer.MAX_VALUE;
        for (BundleComponent component : components) {
            // A SKU absent from the cart has no units, so it forms no bundles.
            bundles = Math.min(bundles, available.get(component.sku()).size() / component.quantity());
        }
        if (bundles == 0) {
            return Decision.skip("Skipped: incomplete bundle, no complete set of "
                    + components.size() + " component(s) available");
        }

        // Consume the lowest-index units first, which keeps the choice deterministic.
        var consumed = new ArrayList<UnitState>();
        var consumedValueByLine = new LinkedHashMap<Integer, BigDecimal>();
        var consumedUnitsByLine = new LinkedHashMap<Integer, List<UnitState>>();
        BigDecimal componentValue = Money.ZERO;
        for (BundleComponent component : components) {
            List<UnitState> take = available.get(component.sku()).subList(0, component.quantity() * bundles);
            for (UnitState unit : take) {
                LineState line = state.lines().get(unit.lineIndex());
                // Input unitPrice, per the README, not the unit's running price.
                BigDecimal unitPrice = Money.of(line.item().unitPrice());
                componentValue = componentValue.add(unitPrice);
                consumedValueByLine.merge(unit.lineIndex(), unitPrice, BigDecimal::add);
                consumedUnitsByLine.computeIfAbsent(unit.lineIndex(), key -> new ArrayList<>()).add(unit);
                consumed.add(unit);
            }
        }

        BigDecimal bundleTotal = Money.of(bundlePrice(rule).multiply(BigDecimal.valueOf(bundles)));
        BigDecimal saving = componentValue.subtract(bundleTotal);
        if (saving.signum() <= 0) {
            // Never a surcharge, and nothing is consumed: a skipped rule leaves no trace.
            return Decision.skip("Skipped: bundle price " + Money.format(bundleTotal)
                    + " is not below component value " + Money.format(componentValue));
        }

        // Split the saving across component lines by the value each one contributed.
        List<Integer> lineIndexes = List.copyOf(consumedValueByLine.keySet());
        List<BigDecimal> shares = PennyAllocator.allocate(
                saving, lineIndexes.stream().map(consumedValueByLine::get).toList());

        var reductions = new ArrayList<DiscountPlan.LineReduction>(lineIndexes.size());
        for (int i = 0; i < lineIndexes.size(); i++) {
            int lineIndex = lineIndexes.get(i);
            reductions.add(new DiscountPlan.LineReduction(
                    lineIndex, consumedUnitsByLine.get(lineIndex), shares.get(i)));
        }
        return Decision.apply(new DiscountPlan(reductions, consumed,
                "Applied: " + bundles + " x bundle at " + Money.format(bundlePrice(rule))
                        + " saving " + Money.format(saving)
                        + " on component value " + Money.format(componentValue)));
    }

    private static BigDecimal bundlePrice(PromotionRule rule) {
        return rule.discountConfig() == null ? null : rule.discountConfig().bundlePrice();
    }
}
