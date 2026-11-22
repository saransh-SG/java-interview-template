package com.company.promoengine.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.promoengine.domain.Cart;
import com.company.promoengine.domain.CartItem;
import com.company.promoengine.domain.UserTier;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit-level running state: prices, bundling, the item-discounted flag, and the 0.00 floor. */
class CartStateTest {

    @Test
    @DisplayName("A line starts as quantity units at the input unit price")
    void buildsOneUnitPerQuantity() {
        var line = lineOf("SKU-A", "TOPS", "10.00", 3);

        assertThat(line.units()).hasSize(3);
        assertThat(line.currentPrice()).isEqualByComparingTo("30.00");
        assertThat(line.originalExtendedPrice()).isEqualByComparingTo("30.00");
        assertThat(line.discount()).isEqualByComparingTo("0.00");
        assertThat(line.itemDiscounted()).isFalse();
    }

    @Test
    @DisplayName("A fractional line discount pushes down to units and still sums to the line")
    void pushesFractionalLineDiscountDownToUnits() {
        var line = lineOf("SKU-A", "TOPS", "1.00", 3);

        BigDecimal taken = line.reduceAcross(line.nonBundledUnits(), new BigDecimal("0.10"));

        assertThat(taken).isEqualByComparingTo("0.10");
        assertThat(line.units()).extracting(UnitState::currentPrice)
                .containsExactly(bd("0.96"), bd("0.97"), bd("0.97"));
        assertThat(line.currentPrice()).isEqualByComparingTo("2.90");
        assertThat(line.discount()).isEqualByComparingTo("0.10");
    }

    @Test
    @DisplayName("Bundled units leave the item-level base but stay in the cart-level base")
    void separatesBundledUnitsFromTheEligibleBase() {
        var line = lineOf("SKU-A", "TOPS", "10.00", 3);
        line.units().get(0).markBundled();

        assertThat(line.nonBundledUnits()).hasSize(2);
        assertThat(line.nonBundledPrice()).isEqualByComparingTo("20.00");
        assertThat(line.currentPrice()).isEqualByComparingTo("30.00");
    }

    @Test
    @DisplayName("An item-level reduction skips bundled units entirely")
    void reducesOnlyNonBundledUnits() {
        var line = lineOf("SKU-A", "TOPS", "10.00", 3);
        line.units().get(0).markBundled();

        line.reduceAcross(line.nonBundledUnits(), new BigDecimal("6.00"));

        assertThat(line.units()).extracting(UnitState::currentPrice)
                .containsExactly(bd("10.00"), bd("7.00"), bd("7.00"));
    }

    @Test
    @DisplayName("A cart-level reduction spreads over every unit, bundled included")
    void reducesAcrossAllUnitsForCartRules() {
        var line = lineOf("SKU-A", "TOPS", "10.00", 2);
        line.units().get(0).markBundled();

        line.reduceAcross(line.units(), new BigDecimal("4.00"));

        assertThat(line.units()).extracting(UnitState::currentPrice)
                .containsExactly(bd("8.00"), bd("8.00"));
    }

    @Test
    @DisplayName("A grant larger than the line is clamped, and the line floors at 0.00")
    void clampsAGrantAtTheLineValue() {
        var line = lineOf("SKU-A", "TOPS", "5.00", 1);

        BigDecimal taken = line.reduceAcross(line.units(), new BigDecimal("9.99"));

        assertThat(taken).isEqualByComparingTo("5.00");
        assertThat(line.currentPrice()).isEqualByComparingTo("0.00");
        assertThat(line.discount()).isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("The response row keeps the input quantity and unit price after a unit is freed")
    void keepsInputQuantityAndUnitPriceOnTheResponse() {
        var line = lineOf("SKU-A", "TOPS", "10.00", 2);
        line.units().get(0).reduceBy(new BigDecimal("10.00"));

        var itemized = line.toItemizedLine();

        assertThat(itemized.quantity()).isEqualTo(2);
        assertThat(itemized.unitPrice()).isEqualByComparingTo("10.00");
        assertThat(itemized.originalExtendedPrice()).isEqualByComparingTo("20.00");
        assertThat(itemized.discount()).isEqualByComparingTo("10.00");
        assertThat(itemized.finalExtendedPrice()).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("Cart subtotals track the running state, not the original prices")
    void tracksRunningSubtotal() {
        var state = cartOf(
                new CartItem("SKU-A", "TOPS", bd("10.00"), 2),
                new CartItem("SKU-B", "BAGS", bd("5.00"), 1));

        assertThat(state.originalSubtotal()).isEqualByComparingTo("25.00");
        assertThat(state.currentSubtotal()).isEqualByComparingTo("25.00");

        state.lines().get(0).reduceAcross(state.lines().get(0).nonBundledUnits(), new BigDecimal("2.00"));

        assertThat(state.originalSubtotal()).isEqualByComparingTo("25.00");
        assertThat(state.currentSubtotal()).isEqualByComparingTo("23.00");
    }

    @Test
    @DisplayName("Only a non-blank exclusive group can be claimed, and only once")
    void claimsOnlyNonBlankExclusiveGroups() {
        var state = cartOf(new CartItem("SKU-A", "TOPS", bd("10.00"), 1));

        assertThat(state.isGroupClaimed("SUMMER")).isFalse();
        state.claimGroup("SUMMER");
        assertThat(state.isGroupClaimed("SUMMER")).isTrue();
        assertThat(state.isGroupClaimed("WINTER")).isFalse();

        state.claimGroup(null);
        state.claimGroup("   ");
        assertThat(state.isGroupClaimed(null)).isFalse();
        assertThat(state.isGroupClaimed("   ")).isFalse();
    }

    @Test
    @DisplayName("An empty cart has zero subtotals rather than failing")
    void handlesAnEmptyCart() {
        var state = cartOf();

        assertThat(state.lines()).isEmpty();
        assertThat(state.originalSubtotal()).isEqualByComparingTo("0.00");
        assertThat(state.currentSubtotal()).isEqualByComparingTo("0.00");
    }

    private static LineState lineOf(String sku, String category, String unitPrice, int quantity) {
        return new LineState(0, new CartItem(sku, category, bd(unitPrice), quantity));
    }

    private static CartState cartOf(CartItem... items) {
        return new CartState(new Cart(
                "cart-test",
                "INR",
                Instant.parse("2026-03-15T10:00:00Z"),
                UserTier.STANDARD,
                List.of(items),
                null,
                null));
    }

    private static BigDecimal bd(String amount) {
        return new BigDecimal(amount);
    }
}
