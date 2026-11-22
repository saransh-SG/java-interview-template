package com.company.promoengine.engine;

import com.company.promoengine.domain.Cart;
import com.company.promoengine.domain.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Mutable running state for a single evaluation: the cart's lines in request order and
 * the exclusive groups claimed so far. Created per call and never shared, which is what
 * keeps the engine bean stateless and safe under concurrent requests.
 */
final class CartState {

    private final Cart cart;
    private final List<LineState> lines;
    private final Set<String> claimedGroups = new LinkedHashSet<>();

    CartState(Cart cart) {
        this.cart = cart;
        var built = new ArrayList<LineState>(cart.items().size());
        for (int index = 0; index < cart.items().size(); index++) {
            built.add(new LineState(index, cart.items().get(index)));
        }
        this.lines = List.copyOf(built);
    }

    Cart cart() {
        return cart;
    }

    /** Lines in original request order, which the response must preserve. */
    List<LineState> lines() {
        return lines;
    }

    BigDecimal originalSubtotal() {
        return lines.stream().map(LineState::originalExtendedPrice).reduce(Money.ZERO, BigDecimal::add);
    }

    /** The running subtotal every rule after the first one evaluates against. */
    BigDecimal currentSubtotal() {
        return lines.stream().map(LineState::currentPrice).reduce(Money.ZERO, BigDecimal::add);
    }

    /** A blank or absent group never blocks anything. */
    boolean isGroupClaimed(String exclusiveGroup) {
        return exclusiveGroup != null && !exclusiveGroup.isBlank() && claimedGroups.contains(exclusiveGroup);
    }

    /** Called only on APPLIED, so a skipped rule leaves its group open for later rules. */
    void claimGroup(String exclusiveGroup) {
        if (exclusiveGroup != null && !exclusiveGroup.isBlank()) {
            claimedGroups.add(exclusiveGroup);
        }
    }
}
