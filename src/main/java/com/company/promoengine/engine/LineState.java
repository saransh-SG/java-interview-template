package com.company.promoengine.engine;

import com.company.promoengine.domain.CartItem;
import com.company.promoengine.domain.ItemizedLine;
import com.company.promoengine.domain.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * One cart line's running state: its units, whether an item-level rule has already
 * reduced it, and the response row it becomes.
 *
 * <p>{@code discount} is derived as {@code originalExtendedPrice - currentPrice} rather
 * than accumulated separately, so the per-line accounting invariant cannot drift away
 * from the unit prices that produced it.
 */
final class LineState {

    private final int index;
    private final CartItem item;
    private final List<UnitState> units;
    private final BigDecimal originalExtendedPrice;
    private boolean itemDiscounted;

    LineState(int index, CartItem item) {
        this.index = index;
        this.item = item;
        this.originalExtendedPrice = Money.extended(item.unitPrice(), item.quantity());
        // unitPrice arrives at scale 2 from EvaluationMapper, so quantity units at that
        // price sum exactly to originalExtendedPrice.
        var built = new ArrayList<UnitState>(Math.max(item.quantity(), 0));
        for (int unitIndex = 0; unitIndex < item.quantity(); unitIndex++) {
            built.add(new UnitState(index, unitIndex, item.unitPrice()));
        }
        this.units = built;
    }

    /** Request order, which drives both response order and allocation tie-breaks. */
    int index() {
        return index;
    }

    CartItem item() {
        return item;
    }

    List<UnitState> units() {
        return List.copyOf(units);
    }

    /** Units still eligible for item-level rules, in unit order. */
    List<UnitState> nonBundledUnits() {
        return units.stream().filter(unit -> !unit.bundled()).toList();
    }

    BigDecimal originalExtendedPrice() {
        return originalExtendedPrice;
    }

    /** Sum of every unit, bundled or not. The base cart-level rules see. */
    BigDecimal currentPrice() {
        return sum(units);
    }

    /** Sum of non-bundled units. The eligible base item-level rules see. */
    BigDecimal nonBundledPrice() {
        return sum(nonBundledUnits());
    }

    BigDecimal discount() {
        return originalExtendedPrice.subtract(currentPrice());
    }

    /**
     * True once an item-level rule has actually lowered this line. Cart-level rules with
     * {@code stackableWithItemPromos: false} exclude these lines; cart-level rules never
     * set the flag themselves.
     */
    boolean itemDiscounted() {
        return itemDiscounted;
    }

    void markItemDiscounted() {
        itemDiscounted = true;
    }

    /**
     * Pushes a line-level amount down to the units a rule named, with the same
     * largest-remainder method
     * used across lines, weighted by each unit's current price. The spec mandates
     * Hamilton allocation across lines; applying it within a line too keeps unit prices
     * consistent with the line total, which matters once a later
     * {@code BUY_X_GET_CHEAPEST_FREE} frees a unit from this line.
     *
     * @return what was actually taken off the line, after the 0.00 floor
     */
    BigDecimal reduceAcross(List<UnitState> targets, BigDecimal amount) {
        var shares = PennyAllocator.allocate(amount, targets.stream().map(UnitState::currentPrice).toList());
        BigDecimal taken = Money.ZERO;
        for (int i = 0; i < targets.size(); i++) {
            taken = taken.add(targets.get(i).reduceBy(shares.get(i)));
        }
        return taken;
    }

    /**
     * {@code quantity} and {@code unitPrice} stay the input values even when units were
     * bundled or freed, per the response contract.
     */
    ItemizedLine toItemizedLine() {
        BigDecimal current = currentPrice();
        return new ItemizedLine(
                item.sku(),
                item.category(),
                item.quantity(),
                Money.of(item.unitPrice()),
                originalExtendedPrice,
                originalExtendedPrice.subtract(current),
                current);
    }

    private static BigDecimal sum(List<UnitState> of) {
        return of.stream().map(UnitState::currentPrice).reduce(Money.ZERO, BigDecimal::add);
    }
}
