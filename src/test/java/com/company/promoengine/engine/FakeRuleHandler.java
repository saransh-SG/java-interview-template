package com.company.promoengine.engine;

import com.company.promoengine.domain.Money;
import com.company.promoengine.domain.PromotionRule;
import com.company.promoengine.domain.RuleType;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.BiFunction;

/**
 * A handler with no promotion semantics, so orchestrator tests exercise the gatekeeper
 * rather than any rule's arithmetic. It records whether it was consulted, which is how
 * the tests prove an earlier gate short-circuited the later ones.
 */
final class FakeRuleHandler implements RuleHandler {

    private final RuleType type;
    private final RuleScope scope;
    private final List<String> violations;
    private final BiFunction<PromotionRule, CartState, Decision> decision;

    private boolean validateCalled;
    private boolean decideCalled;

    private FakeRuleHandler(RuleType type, RuleScope scope, List<String> violations,
            BiFunction<PromotionRule, CartState, Decision> decision) {
        this.type = type;
        this.scope = scope;
        this.violations = violations;
        this.decision = decision;
    }

    /** Takes {@code amount} off the whole of line 0 whenever it is consulted. */
    static FakeRuleHandler reducingLineZeroBy(RuleType type, RuleScope scope, String amount) {
        return new FakeRuleHandler(type, scope, List.of(), (rule, state) -> {
            LineState line = state.lines().get(0);
            return Decision.apply(DiscountPlan.of(
                    List.of(DiscountPlan.LineReduction.of(line, line.units(), new BigDecimal(amount))),
                    "Applied: fake handler took " + Money.format(new BigDecimal(amount))));
        });
    }

    static FakeRuleHandler withConfigViolations(RuleType type, String... violations) {
        return new FakeRuleHandler(type, RuleScope.ITEM, List.of(violations),
                (rule, state) -> Decision.apply(DiscountPlan.of(List.of(), "unreachable")));
    }

    static FakeRuleHandler skipping(RuleType type, String reason) {
        return new FakeRuleHandler(type, RuleScope.ITEM, List.of(), (rule, state) -> Decision.skip(reason));
    }

    static FakeRuleHandler planningNothing(RuleType type) {
        return new FakeRuleHandler(type, RuleScope.ITEM, List.of(),
                (rule, state) -> Decision.apply(DiscountPlan.of(List.of(), "Applied: a plan worth 0.00")));
    }

    /** Records whether line 0 was already item-discounted when this rule was reached. */
    static FakeRuleHandler observingItemDiscounted(RuleType type, boolean[] sink) {
        return new FakeRuleHandler(type, RuleScope.CART, List.of(), (rule, state) -> {
            sink[0] = state.lines().get(0).itemDiscounted();
            return Decision.skip("Skipped: observer only, no discount of its own");
        });
    }

    @Override
    public RuleType type() {
        return type;
    }

    @Override
    public RuleScope scope() {
        return scope;
    }

    @Override
    public List<String> validate(PromotionRule rule) {
        validateCalled = true;
        return violations;
    }

    @Override
    public Decision decide(PromotionRule rule, CartState state) {
        decideCalled = true;
        return decision.apply(rule, state);
    }

    boolean validateCalled() {
        return validateCalled;
    }

    boolean decideCalled() {
        return decideCalled;
    }
}
