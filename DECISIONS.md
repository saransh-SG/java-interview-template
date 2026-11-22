# Decisions

## 1. Rule engine pattern and extensibility

Each rule type is one `RuleHandler` implementation declaring its `RuleType`, its scope
(item-level or cart-level), its configuration checks and its discount calculation.
`RuleHandlerRegistry` resolves a type to its handler, and `DefaultPromotionEngine` owns only
what the README specifies once for every rule: ordering, the seven gatekeeper steps,
committing a plan, claiming an exclusive group and assembling the response. There is no
`switch` over rule types anywhere.

Adding a seventh type is a new constant in `RuleType` plus a new `@Component` handler class.
No existing class is edited: the orchestrator never names a rule type, and the registry
discovers handlers by constructor injection. The registry fails at startup if a type has no
handler or if two handlers claim the same type, so a half-wired seventh type cannot reach a
live request.

Handlers compute but never mutate. `decide` returns a `DiscountPlan` of reductions, each
naming the units the money comes off, and the orchestrator decides whether to commit it.
That is what keeps the zero-discount rule, the exclusive-group claim and the item-discounted
flag in one place instead of repeated in six handlers.

## 2. Precision and rounding choices

Every amount is `BigDecimal` at scale 2, `HALF_UP`; there is no `double` or `float` anywhere
in the engine. A percentage is rounded exactly once, at the level the README names: the end of
a line calculation for a category rule, the end of a cart calculation for a cart rule. A
category percentage is never computed on several lines summed together, which matters because
the two differ: two `0.10` lines at 15% give `0.02` each independently, but `0.03` pooled.

Splitting one total across several recipients happens only in `PennyAllocator`, in integer
cents, by the largest-remainder (Hamilton) method, with ties going to the earlier line in
request order. Because it allocates whole cents and clamps the total to the summed weights,
the parts always sum back to the whole and no recipient is allocated more than it is worth.

Nothing can go below zero at three levels. The allocator caps a grant at the eligible base,
each unit's reduction is clamped at its own current price, and a line's price is the sum of
its units — so `finalExtendedPrice >= 0.00` and `finalTotal >= 0.00` hold by construction. A
line's `discount` is derived as `originalExtendedPrice - currentPrice` rather than
accumulated separately, so there is no second counter that can drift. Each audit entry reports
the discount actually taken off after clamping, not the amount planned, which keeps
`totalDiscount == sum(APPLIED audit.discountAmount)` true even when a clamp fires.

## 3. Edge cases handled vs deferred

Handled, with tests: bundle leftovers staying eligible for later item rules; two bundle rules
sharing a SKU without consuming the same unit twice; one SKU spread across two cart lines;
a bundle running after a category discount, where savings computed on input prices exceed what
the line still holds and the commit clamps them; a capped category discount where a matching
line is allocated zero cents and therefore is not item-discounted; non-stackable cart rules
excluding item-discounted lines; spend-tier downgrades on the running subtotal; a fixed cart
amount larger than the base; mix-and-match buy-X pools across SKUs with cheapest-first unit
selection; per-line independent rounding; one-cent remainders; skipped rules leaving their
exclusive group open; zero-discount rules changing nothing; inverted and out-of-window rules;
illegal configuration of every rule type as a skip rather than an HTTP 400; empty carts; and
identical requests producing identical responses.

Two readings of the specification needed a decision. Bundle sets are formed from units still
unbundled rather than from the raw `lineQuantity`: `BUNDLE_FIXED_PRICE` is itself an item-level
rule, and the unit-tracking mechanic says later item-level rules skip bundled units, so the
purely literal reading would let a second bundle rule discount the same unit twice. A coupon
carrying both a `percentage` and an `amount` is accepted and the percentage used, rather than
rejected as a configuration error: the README states an "exactly one" rule for spend tiers and
not for coupons, and inventing the stricter rule would skip a promotion the caller expected to
apply.

Deferred, as out of scope: persistence, authentication, tax, shipping, currency conversion,
per-user redemption limits, rejecting duplicate rule IDs, and any search for an optimal or
cheaper subset of rules. The engine runs the single deterministic pass the README specifies.

## 4. AI usage note

I used an AI assistant throughout, and the design and code are mine in the sense that I
reviewed, challenged and own every line. It was most useful on the mechanical parts: laying out
the handler scaffolding once the seam was agreed, and drafting the allocator and its tests from
the README's rules, which saved a lot of typing on code whose shape I had already decided.

Three places I overrode it. It first proposed putting the handlers in an `engine.handler`
subpackage while keeping them package-private, which cannot compile — package scope is per
package, not per package tree — so the engine is one flat package. It proposed treating a
coupon with both a percentage and an amount as a configuration error, which I rejected for the
reason given above: the README withholds that rule for coupons deliberately. And it justified
the allocator's weight cap by claiming the cap binds when the total exceeds the summed
weights; working it through, the clamp makes every share equal its weight with no leftover
cents, so the guard is in fact unreachable on all paths. I kept it as a literal transcription
of the specification and documented why it is not load-bearing, rather than leaving a comment
that was wrong.
