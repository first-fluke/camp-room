# Independent OMA DB review

Reviewed by the root `oma-db` role, independently of the backend schema author. Source: `apps/api/app/migrations/001_normalized.sql`; executable evidence: `scripts/review-database.py`. Review date: 2026-10-25.

## Decision and scope

The revised schema is approved for additive application to the supplied Turso database. It has 32 normalized, STRICT tables. The independent in-memory review passes 37 integrity and transaction checks on Python 3.14. Runtime concurrency and remote Hrana behavior require the separate API tests and `apps/api/scripts/verify_turso.py --run-remote`; this document does not substitute for those results.

The earlier single JSON state record is no longer canonical application storage. The old `camp_room_state` table, if present, is neither read nor dropped. Existing production data is not implicitly migrated or deleted. This initial additive migration targets the new Camp Room entities; adopting unrelated legacy data requires an explicit mapping.

## Functional dependencies and third normal form

Each relation stores facts at one declared key. Multi-valued facilities, rules, zone settings, forecasts, cart lines and order lines use child or join tables. There is no generic EAV or JSON business-state column.

| Relations | Determinant and dependent facts | Review |
| --- | --- | --- |
| Scopes, profiles | Scope; scope + profile | Profile identity is distinct from trips and orders. Composite foreign keys retain scope boundaries. |
| Providers, provider runs | Scope + provider | Provider name/kind are stored once. The latest run has its own status, cache variant and expiry. |
| Campsites | Scope + campsite | Current provider identity and catalog facts depend on the site. Facility codes and repeated rules are separate relations. |
| Receiving policies | Scope + campsite | One current parcel policy, with provider/admin provenance; catalog refresh must preserve an admin override. |
| Availability overview | Scope + campsite | General demo/coverage information only. This is not a dated live inventory claim. |
| Availability queries | Scope + campsite + arrival + departure + guests + slot type | Remaining quantity depends on the complete booking query. Unknown quantity remains NULL. |
| Trips | Scope + profile | One current trip per profile is the current product rule. The site name/address remain in the site relation. |
| Saved scenes and zones | Scope + profile + scene; additionally zone for zone settings | Repeatable zone settings do not appear as arrays or duplicate scene facts. |
| Vehicle state and zones | Scope + profile; additionally zone | Server vehicle state is explicitly simulator-only. Actual Pleos state comes from the local SDK. |
| Products and inventory | Scope + product | Catalog price and available quantity have separate lifetimes. Monetary values and quantities are strict integers. |
| Carts and cart items | Scope + profile; additionally product for items | One quantity per product; current product names/prices are joined. |
| Orders and items | Scope + order; additionally line number | Order-level facts are separate from line facts. Product name and price snapshots represent the facts at purchase time. |
| Delivery, receiving conditions, fulfillment | Scope + order; additionally position for repeated conditions | Shipping destination at purchase and later operational fulfillment have separate meanings. |
| Idempotency keys | Scope + profile + request key | The request fingerprint maps to exactly one order in that same profile. |
| Weather queries and forecasts | Scope + site + stay; additionally forecast time | Provider labels are joined. A cache variant changes the retained response for that same site/stay, not its identity. |
| Refresh leases and runs | Scope + job; scope + run | A lease is current ownership. Run history is append-only evidence of attempts, including failures. |

Historical order names, prices and delivery addresses are not copies of the *current* catalog facts: the same product can have a different name or price in a later order. Their determinant is the order line or delivery, so keeping them is consistent with the modeled dependencies. Current order subtotal and total are derived from line facts and shipping; they are not stored redundantly.

Coordinates, region/address and published check-in times are supplier attributes; this schema does not infer a functional dependency from arbitrary free-form address text. Weather summaries and overnight low values are provider response facts for the query, not assumed to be exact aggregates of the bounded visible forecast sample.

## Integrity and transaction review

- Composite foreign keys prevent a trip, cart, idempotency key or order from claiming an entity in a different scope/profile. Every connection must enable foreign keys; schema declarations alone are insufficient.
- ISO calendar dates are validated with canonical SQLite date conversion; trips and dated availability require 1–30 nights. Campsite delivery ETA must satisfy arrival ≤ ETA < departure.
- STRICT typing plus checks reject fractional or negative KRW values, negative inventory and invalid quantities. The first market uses KRW; adding UI languages does not imply currency conversion.
- Checkout must acquire one write transaction, re-read current inventory and idempotency state, validate, decrement inventory, insert order/lines/delivery/key and clear the cart, then commit together. The independent SQL check verifies rollback semantics; API concurrent-request and remote tests verify the actual implementation.
- The Turso transport must retain the same Hrana stream through `BEGIN IMMEDIATE`, reads, writes, explicit COMMIT or ROLLBACK. It must not enqueue an unconditional commit after unchecked errors.
- Product deletion is blocked while an order references it. Catalog deactivation is preferable to deleting ordered sites/products. Probe cleanup uses an isolated scope and child-first deletes without disabling foreign keys.
- A refresh lease has a bounded expiry and an owner. Cache publication and lease completion must check ownership in the write transaction, so an expired worker cannot overwrite the newer owner's result.
- Provider failures must retain prior successful cache/run history. Expired data may be retained for provenance, but must not be reported as fresh live availability.

The saved-scene 16–30°C range is an existing simulator/app recipe constraint, confirmed by the author. It is not a claim about real vehicle capability bounds. Native control checks the capability/range actually reported by the SDK.

## Query shape and operations

Composite primary keys serve scoped point lookups and child joins. Explicit indexes serve order history by profile/time, provider catalog filtering, weather expiry and refresh history. Current repository writes compare relational rows and update changed facts inside a transaction; transient API dictionaries are serialization views, not stored JSON.

The initial repository reconstructs a scoped application view for compatibility with existing service logic. This is acceptable for the bounded demo catalog and single active trip, but broad catalog ingestion must replace full-scope reads with paged/filter-specific queries before scaling. No unmeasured latency or throughput claim is made.

Scope IDs are storage partitions, not an authentication boundary by themselves. The operations API requires a server bearer token and the admin browser uses its server-side session/BFF. Full consumer account authorization is a separate deployment concern; do not expose this development API as a multi-tenant production service without it.

No retention deletion or backup schedule is silently installed. Refresh history and historical orders remain available; operational retention, backup restoration and production migration rollout must be defined for the selected deployment. Additive DDL is repeatable, but `CREATE TABLE IF NOT EXISTS` does not upgrade an already-created incompatible table; future changes require numbered migrations.

## Evidence

```bash
.venv/bin/python scripts/review-database.py
.venv/bin/python -m pytest apps/api/tests -q
.venv/bin/python apps/api/scripts/verify_turso.py --run-remote
```

The independent report records the exact schema SHA-256, Python/SQLite versions and each passed check in `artifacts/qa/db-review.json`. A failed rerun replaces the old report with failure status. Runtime test/remote probe results are recorded separately so schema approval cannot be mistaken for end-to-end verification.

## Runtime verification completed

The separately authored API suite passed 33 tests. The root reviewer inspected the successful real Turso probe report dated 2026-10-25T05:50:45Z: all 20 checks passed, including competing final-stock orders, concurrent same-key requests, a deliberately lost COMMIT response followed by idempotent recovery, foreign-key rollback, persisted admin changes and durable refresh history. Probe rows were cleaned with foreign keys enabled; unrelated scopes and the legacy prototype table were preserved.

The actual worker CLI was also run against the isolated Turso QA API. It persisted three weather query rows and a refresh history row. With external provider credentials absent, it correctly returned exit 1 and `provider_unavailable`, while the database refresh operation itself completed. This verifies degraded operation without claiming live campsite or weather data.
