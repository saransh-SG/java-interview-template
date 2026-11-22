package com.company.promoengine.engine;

import com.company.promoengine.domain.PromotionRule;
import com.company.promoengine.domain.RuleType;
import java.util.List;

/**
 * The engine's extension seam: one implementation per {@link RuleType}.
 *
 * <p>Adding a seventh rule type means adding a constant to {@link RuleType} and one class
 * implementing this interface. {@link DefaultPromotionEngine} is not edited, because it
 * resolves handlers through {@link RuleHandlerRegistry} and owns the gatekeeper sequence
 * rather than any rule's semantics.
 */
interface RuleHandler {

    /** The single rule type this handler prices. */
    RuleType type();

    /** Whether applying this rule marks the lines it touches item-discounted. */
    RuleScope scope();

    /**
     * Gatekeeper step 4. Checks this rule type's required and legal configuration.
     *
     * @return one message per violation, empty when the configuration is usable
     */
    List<String> validate(PromotionRule rule);

    /**
     * Gatekeeper step 5. Evaluates this rule's predicate against the running state and
     * computes the discount, without mutating anything.
     *
     * <p>Implementations must leave {@code state} untouched: the orchestrator decides
     * whether a plan is committed at all, and a plan that is skipped must leave no trace.
     */
    Decision decide(PromotionRule rule, CartState state);
}
