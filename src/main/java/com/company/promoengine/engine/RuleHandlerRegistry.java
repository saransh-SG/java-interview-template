package com.company.promoengine.engine;

import com.company.promoengine.domain.RuleType;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a {@link RuleType} to its {@link RuleHandler}. This is the one place that
 * knows the set of handlers, which is what keeps a {@code switch} over rule types out of
 * the orchestrator and makes a seventh type additive.
 */
final class RuleHandlerRegistry {

    private final Map<RuleType, RuleHandler> byType;

    RuleHandlerRegistry(List<RuleHandler> handlers) {
        var resolved = new EnumMap<RuleType, RuleHandler>(RuleType.class);
        for (RuleHandler handler : handlers) {
            RuleHandler previous = resolved.put(handler.type(), handler);
            if (previous != null) {
                // Two handlers for one type is ambiguous; silently keeping either one
                // would be a pricing bug that only shows up in production.
                throw new IllegalStateException("two handlers registered for rule type "
                        + handler.type() + ": " + previous.getClass().getName()
                        + " and " + handler.getClass().getName());
            }
        }
        this.byType = Collections.unmodifiableMap(resolved);
    }

    /**
     * Asserts every {@link RuleType} has a handler, naming the ones that do not.
     * Called from the orchestrator's constructor once all six handlers exist, so a new
     * enum constant without a handler fails at startup rather than on a live request.
     */
    RuleHandlerRegistry requireCompleteCoverage() {
        var missing = Arrays.stream(RuleType.values())
                .filter(type -> !byType.containsKey(type))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("no RuleHandler registered for rule types " + missing);
        }
        return this;
    }

    RuleHandler handlerFor(RuleType type) {
        RuleHandler handler = byType.get(type);
        if (handler == null) {
            throw new IllegalStateException("no RuleHandler registered for rule type " + type);
        }
        return handler;
    }
}
