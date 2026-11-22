package com.company.promoengine.engine;

import com.company.promoengine.domain.AuditEntry;
import com.company.promoengine.domain.Cart;
import com.company.promoengine.domain.EvaluationResult;
import com.company.promoengine.domain.ItemizedLine;
import com.company.promoengine.domain.Money;
import com.company.promoengine.domain.PromotionRule;
import com.company.promoengine.domain.RuleStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Orchestrates one evaluation. It owns the parts the README specifies once for every
 * rule — ordering, the seven gatekeeper steps, committing a plan, claiming an exclusive
 * group, and assembling the response — and none of the six rule semantics, which live
 * behind {@link RuleHandler}.
 *
 * <p>Stateless as a bean: all mutable state is the per-call {@link CartState}, so
 * concurrent requests cannot see each other.
 */
@Component
public class DefaultPromotionEngine implements PromotionEngine {

    private final RuleHandlerRegistry registry;

    /**
     * {@link ObjectProvider} rather than {@code List<RuleHandler>} so the context still
     * starts while handlers are being added one at a time; Spring treats an injected
     * {@code List} of a type with no beans as an unsatisfied dependency. Annotated
     * because the class has a second constructor for tests and Spring must be told which
     * one to use.
     */
    @Autowired
    DefaultPromotionEngine(ObjectProvider<RuleHandler> handlers) {
        this.registry = complete(handlers.stream().toList());
    }

    DefaultPromotionEngine(List<RuleHandler> handlers) {
        this.registry = new RuleHandlerRegistry(handlers);
    }

    /**
     * Spring's constructor also asserts every {@link com.company.promoengine.domain.RuleType}
     * has a handler, so a seventh type added without one fails at startup rather than on a
     * live request. Tests use the plain list constructor with the handlers they need.
     */
    private static RuleHandlerRegistry complete(List<RuleHandler> handlers) {
        return new RuleHandlerRegistry(handlers).requireCompleteCoverage();
    }

    @Override
    public EvaluationResult evaluate(Cart cart, List<PromotionRule> promotions) {
        Objects.requireNonNull(cart, "cart");
        Objects.requireNonNull(promotions, "promotions");

        var state = new CartState(cart);
        var auditTrail = new ArrayList<AuditEntry>(promotions.size());
        for (PromotionRule rule : RuleOrdering.sorted(promotions)) {
            auditTrail.add(evaluateRule(rule, state));
        }

        List<ItemizedLine> itemized = state.lines().stream().map(LineState::toItemizedLine).toList();
        BigDecimal originalSubtotal = state.originalSubtotal();
        BigDecimal finalTotal = state.currentSubtotal();
        return new EvaluationResult(
                cart.cartId(),
                cart.currency(),
                originalSubtotal,
                originalSubtotal.subtract(finalTotal),
                finalTotal,
                itemized,
                auditTrail);
    }

    /** The gatekeeper, in README order. The first matching outcome wins. */
    private AuditEntry evaluateRule(PromotionRule rule, CartState state) {
        Instant from = rule.validFrom();
        Instant to = rule.validTo();

        // 1. Inverted window, checked before the window itself so the reason is specific.
        if (from != null && to != null && from.isAfter(to)) {
            return skipped(rule, "Skipped: validity window is inverted, validFrom " + from
                    + " is after validTo " + to);
        }

        // 2. Validity window, inclusive on both ends; an absent bound is unbounded.
        Instant at = state.cart().evaluationTimestamp();
        if ((from != null && at.isBefore(from)) || (to != null && at.isAfter(to))) {
            return skipped(rule, "Skipped: evaluation timestamp " + at
                    + " is outside the validity window [" + from + ", " + to + "]");
        }

        // 3. Exclusive group, claimed only by an earlier APPLIED rule.
        if (state.isGroupClaimed(rule.exclusiveGroup())) {
            return skipped(rule, "Skipped: exclusive group " + rule.exclusiveGroup()
                    + " was already claimed by an earlier applied rule");
        }

        RuleHandler handler = registry.handlerFor(rule.type());

        // 4. Configuration, a skip rather than an HTTP 400.
        List<String> violations = handler.validate(rule);
        if (!violations.isEmpty()) {
            return skipped(rule, "Skipped: invalid configuration, " + String.join("; ", violations));
        }

        // 5. Predicate, which is the handler's own business. Asked exactly once.
        Decision decision = handler.decide(rule, state);
        if (decision instanceof Decision.Skip(String reason)) {
            return skipped(rule, reason);
        }
        DiscountPlan plan = ((Decision.Apply) decision).plan();

        // 6. Zero discount, enforced here so no handler has to remember it.
        if (plan.plannedTotal().signum() == 0) {
            return skipped(rule, "Skipped: computed discount is " + Money.format(Money.ZERO));
        }

        // 7. Apply. The audit reports what was actually taken off, not what was planned.
        BigDecimal realized = commit(plan, handler.scope(), state);
        if (realized.signum() == 0) {
            return skipped(rule, "Skipped: no discount remained once the 0.00 floor was applied");
        }
        state.claimGroup(rule.exclusiveGroup());
        return new AuditEntry(rule.id(), rule.name(), rule.type(), RuleStatus.APPLIED, realized,
                plan.reason());
    }

    /**
     * Applies a plan and reports what it actually took off after the per-unit {@code 0.00}
     * floor. Units are marked bundled only after the reductions land, because the
     * reductions target those same units and an item-level reduction skips bundled ones.
     */
    private static BigDecimal commit(DiscountPlan plan, RuleScope scope, CartState state) {
        BigDecimal realized = Money.ZERO;
        var reduced = new LinkedHashSet<LineState>();
        for (DiscountPlan.LineReduction reduction : plan.reductions()) {
            LineState line = state.lines().get(reduction.lineIndex());
            BigDecimal taken = line.reduceAcross(reduction.targets(), reduction.amount());
            if (taken.signum() > 0) {
                reduced.add(line);
            }
            realized = realized.add(taken);
        }
        if (realized.signum() == 0) {
            // Nothing moved, so leave bundling and the item-discounted flag untouched.
            return Money.ZERO;
        }
        plan.unitsToBundle().forEach(UnitState::markBundled);
        if (scope == RuleScope.ITEM) {
            // Only lines that actually came down count as item-discounted: a capped
            // allocation can hand a matching line 0.00.
            reduced.forEach(LineState::markItemDiscounted);
        }
        return realized;
    }

    private static AuditEntry skipped(PromotionRule rule, String reason) {
        return new AuditEntry(rule.id(), rule.name(), rule.type(), RuleStatus.SKIPPED, Money.ZERO, reason);
    }
}
