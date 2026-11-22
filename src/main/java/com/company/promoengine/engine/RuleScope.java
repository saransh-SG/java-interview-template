package com.company.promoengine.engine;

/**
 * Whether a rule prices individual items or the cart as a whole.
 *
 * <p>The distinction is not cosmetic. Only an {@link #ITEM} rule marks the lines it
 * reduces as item-discounted, and that flag is what a later cart-level rule with
 * {@code stackableWithItemPromos: false} uses to exclude lines. All three item-level
 * rule types set it; no cart-level rule ever does.
 */
enum RuleScope {
    ITEM,
    CART
}
