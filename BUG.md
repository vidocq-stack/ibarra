# BUG.md — Ibarra

Reproducible bugs (internal issue, regression, incorrect behavior not yet fixed).
Entry format: `short id · date · symptom · minimal repro · cause hypothesis · status`.

## IBARRA-001 · 2026-10-10 · Included output bypasses the page writer

- Symptom: `include(path, false)` renders included output before pending page
  output and cannot capture included output in a tag body.
- Minimal repro: `PageRuntimeTest.includedOutputKeepsOrderAndCanBeCapturedInsideATagBody`;
  write `before`, include `included`, write `after`, then flush.
- Cause: the dispatcher receives the original response instead of a response
  whose character writer targets the current `JspWriter`.
- Status: fixed by routing included character output through the current page
  writer; covered by the ordering and body capture regression test.

## IBARRA-002 · 2026-10-10 · Reentrant EL listener recreates the page context

- Symptom: an EL context listener calling the page's `getELContext()` can recurse
  until `StackOverflowError`.
- Minimal repro: `PageRuntimeTest.applicationListenersObserveEachPageContextAndResolversFreezeOnFirstUse`;
  retrieve `PageContext` from the event context and call `getELContext()`.
- Cause: listener notification runs before the page caches its EL context.
- Status: fixed by publishing the context before listener notification; the
  regression test verifies callback-visible context identity.

## IBARRA-003 · 2026-10-10 · EL assignment shadows a scoped attribute

- Symptom: assigning an existing page attribute through EL leaves the original
  attribute unchanged.
- Minimal repro: `PageRuntimeTest.applicationListenersObserveEachPageContextAndResolversFreezeOnFirstUse`;
  set a page attribute, resolve it, then assign through the resolver chain.
- Cause: `StandardELContext`'s bean-name resolver handles assignment before the
  scoped-attribute resolver.
- Status: fixed by providing an explicit resolver chain with scoped attributes
  as the final top-level resolver, without the standalone bean-name resolver.
