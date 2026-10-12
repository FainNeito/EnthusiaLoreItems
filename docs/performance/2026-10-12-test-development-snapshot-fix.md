# TEST development snapshot fix

This document supersedes the deployment-blocker and candidate-specific evidence
in 2026-10-11-live-inventory-references.md for this TEST-only branch. That document
records the original canonical PR #70; its test counts and timings belong to
that candidate, not automatically to this development integration.

## Source proof / spec

- Current canonical main inspected: ec7b77cbbca75ee52256d6bc05a38f975e0832f7.
- Authoritative installed development source: FainNeito/EnthusiaLoreItems main,
  d9e15ae6aca0d1383848e6bd82f6d233ea828ecb.
- A clean rebuild with repository LF migration resources reproduced the installed
  TEST JAR byte for byte: e9109b4c0d750d9c2c0cc5be26b23cfe651ddd4d2cfebb734185b2f3af690e9d.
- Windows checkout CRLF conversion initially changed only eleven SQL resource
  entries; all class bytes were identical. .gitattributes pins these resources
  to repository LF so the canonical Gradle build remains reproducible on Windows.
- Preserve storage lock, creative storage lock, swap duplicate fence, holder
  announcements, and signature preservation exactly as this development base.
- Apply only canonical PR #70's non-snapshot holder/block reference lookup fix,
  its focused regressions and isolated runtime probe. No scan policy, item data,
  definitions, migrations, persistence or permission changes.
- Never restart/reload TEST, hot-swap the plugin, or change production. Stage only
  /plugins/update/EnthusiaLoreItems.jar for a future normal startup.

## SPEAR evidence

Spec: source and staging boundaries above. Prove: new reference regressions were
red on canonical base and green with the fix; development-head checks are pending.
Engine: four lookup calls use getHolder(false) / getState(false), preserving fresh
loaded-inventory resolution. Architecture: Paper adapter only; no async live data
retention. Refine: full development tests, class/resource parity, exact-source
artifact hash, runtime probe and TEST download-back verification remain required.
No project-local EARS/state executable was found; this is the manual evidence record.

Delivery is an unmerged TEST artifact with reviewable development-source PR.
Staging is not activation or live-player acceptance. Production stays unchanged.
