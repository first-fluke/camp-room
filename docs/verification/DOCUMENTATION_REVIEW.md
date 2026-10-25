# Documentation and Korean review

Status: completed. Reviewed on 2026-10-25.

## Scope

Fourteen current Korean Camp Room documents were synchronized with the implemented native app, generated Ktor API client, Python 3.14 server, normalized Turso storage, administrator BFF, worker, optional observability and Pleos design adaptation. The provided SDK JSONL and application DOCX were preserved. Unrelated inherited starter translations remain reference material; the Korean entry document identifies the current product documentation.

Factual synchronization happened before stylistic review. The style-change percentages below measure that corrected baseline against the reviewed prose, not the earlier architecture replacement.

## Korean review method and results

The user-selected [im-not-ai humanize-korean skill](https://github.com/epoko77-ai/im-not-ai/tree/main/codex/skills/humanize-korean) was read in full. Its preparation script selected **light** independently for all 14 documents. A separate monolith-role agent reviewed the prose conservatively. Each output retained exactly one review-summary block in the review artifacts.

The original `verify_gates.py` ran for every document with genre `report`. All returned exit 0. Summary percentages were then filled from those gate outputs. Code fences, inline code and link destinations were also compared with the baseline and preserved exactly. The reviewer reported self-validation 6/6 and grade B; these are reviewer assessments, while change rates and exit codes are tool results. No warning, rejection or finalizer escalation remained.

| Document | Route | Gate change rate | Gate exit | Code and links |
| --- | --- | ---: | ---: | --- |
| [README.md](../../README.md) | light | 0.03% | 0 | preserved |
| [docs/PRD.md](../../docs/PRD.md) | light | 0.31% | 0 | preserved |
| [docs/FUNCTIONAL_SPEC.md](../../docs/FUNCTIONAL_SPEC.md) | light | 0.16% | 0 | preserved |
| [docs/INTEGRATIONS.md](../../docs/INTEGRATIONS.md) | light | 0.00% | 0 | preserved |
| [docs/product/PLEOS_SDK_REVIEW.md](../../docs/product/PLEOS_SDK_REVIEW.md) | light | 0.01% | 0 | preserved |
| [docs/product/SDK_CAPABILITY_MATRIX.md](../../docs/product/SDK_CAPABILITY_MATRIX.md) | light | 0.08% | 0 | preserved |
| [docs/README.ko.md](../../docs/README.ko.md) | light | 0.09% | 0 | preserved |
| [docs/USAGE.ko.md](../../docs/USAGE.ko.md) | light | 0.02% | 0 | preserved |
| [docs/AUTH.ko.md](../../docs/AUTH.ko.md) | light | 0.00% | 0 | preserved |
| [docs/WHY.ko.md](../../docs/WHY.ko.md) | light | 0.04% | 0 | preserved |
| [docs/architecture/SERVER_ARCHITECTURE.md](../../docs/architecture/SERVER_ARCHITECTURE.md) | light | 0.04% | 0 | preserved |
| [docs/database/ERD.md](../../docs/database/ERD.md) | light | 0.00% | 0 | preserved |
| [docs/OBSERVABILITY.md](../../docs/OBSERVABILITY.md) | light | 0.08% | 0 | preserved |
| [docs/DESIGN_SYSTEM.md](../../docs/DESIGN_SYSTEM.md) | light | 0.11% | 0 | preserved |

All changes were below 5%; the documents already needed only limited wording corrections. Changes included removing unnecessary commas after Korean connective endings and replacing awkward nominal expressions without changing technical claims. Review-summary metadata remains in the preserved review outputs rather than the product-document body.

## Structure and references

- The server architecture document contains two flowcharts and one refresh sequence diagram.
- The ERD contains 11 domain diagrams, at most five entities each, plus an inventory of all 32 STRICT tables.
- PK, FK, uniqueness and nullable relationships were derived from the actual migration applied to an in-memory SQLite database.
- Mermaid 12.2.0 successfully parsed all 14 diagram blocks. This is syntax verification, not a claim of visual rendering certification.
- OMA reference verification checked the 14 product documents and this report: 15 documents, 83 references and no broken references. External URLs were not exhaustively scanned; the cited official Pleos design and Mermaid pages were inspected directly.

## Factual update after review

The latest user instruction requires the existing local Aside MCP and rejects a new device-authentication flow. The integration status was therefore corrected to `LOCAL_MCP_NOT_EXPOSED`; no external provider keys have been issued. This small factual synchronization in INTEGRATIONS and PRD is tracked separately from the style-change rates above. The documents do not instruct a new device login.

A final source update also added fixed telemetry provenance fields: `runtime=demo|pleos` and `order_mode=demo` for checkout/order creation. OBSERVABILITY now documents these fields and distinguishes the Pleos build flavor from physical-vehicle verification. This is a source-fact update after stylistic review; it is not included in the earlier style-change percentages.

## Evidence and reproducibility

The repository [machine-readable gate results](humanize-results.json) preserve the exact per-document tool findings and hashes. The local review archive preserves each input, metrics, monolith output, gate stdout and updated summary. The archive manifest is `humanize-manifest.json`; aggregate gate and application records are `humanize-gate-report.json` and `humanize-applied-report.json`. These contain no credentials. The OMA documentation task is `documentation-humanize` in session `20261025-060338`, run `c8d58c67-f471-4ec5-b6fd-bb8c22dbdf02`.

Product references: [server architecture](../architecture/SERVER_ARCHITECTURE.md), [ERD](../database/ERD.md), [independent DB review](../database/SCHEMA_REVIEW.md).
