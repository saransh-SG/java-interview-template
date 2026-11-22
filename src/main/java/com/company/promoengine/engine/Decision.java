package com.company.promoengine.engine;

/**
 * A handler's answer for one rule: either a skip with its reason (gatekeeper step 5,
 * the rule's own predicate) or a computed plan the orchestrator may commit.
 */
sealed interface Decision {

    record Skip(String reason) implements Decision {
    }

    record Apply(DiscountPlan plan) implements Decision {
    }

    static Decision skip(String reason) {
        return new Skip(reason);
    }

    static Decision apply(DiscountPlan plan) {
        return new Apply(plan);
    }
}
