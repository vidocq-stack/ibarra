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

## IBARRA-004 · 2026-10-10 · PR validation cannot find Ibarra's dependency graph

- Symptom: `pr-validate` fails after a successful producer build and all unit tests.
- Minimal repro: PR #2, action run #7, downstream rebuild step: fetching
  `Vidocq_ibarra.json` from `Vidocq/dep-graph` returns HTTP 404.
- Cause: the new repository's dependency graph has not been bootstrapped; the
  automatic updater only runs for POM changes on the default branch.
- Status: fixed by dispatching the existing `update-dep-graph.yml` workflow on
  `main` (successful action run #8). `data/Vidocq_ibarra.json` now exists in
  `Vidocq/dep-graph`; the PR validation is rerun on the updated branch.

## IBARRA-005 · 2026-10-10 · JSP markers inside EL strings confuse the lexer

- Symptom: `${'<%'}` is treated as an unclosed scripting element; directive
  text inside an EL string can also introduce a fake taglib binding.
- Minimal repro: `PageParserTest.scriptingAndDirectiveMarkersInsideElStringsStayInsideTheExpression`.
- Cause: the structural lexer scans JSP delimiters before respecting EL boundaries.
- Status: fixed by skipping balanced EL expressions, including quoted strings,
  in both the directive prepass and structural scan.
  Unbalanced candidate expressions do not hide later `isELIgnored` directives;
  covered by `ignoredElDoesNotRequireBalancedExpressionDelimitersBeforeTheDirective`.

## IBARRA-006 · 2026-10-10 · Root detection scans commented-out JSP text

- Symptom: a `jsp:root` mentioned only in a JSP comment switches parsing to XML.
- Minimal repro: `PageParserTest.xmlRootTextInsideJspCommentsDoesNotSelectXmlSyntax`.
- Cause: syntax inference searches the complete source instead of the actual
  document root.
- Status: fixed by checking only the initial document element after whitespace,
  XML comments and processing instructions.
  Root inference also handles a default JSP namespace without a prefix; covered
  by `defaultNamespaceRootAlsoSelectsXmlSyntax`.

## IBARRA-007 · 2026-10-10 · XML formatting filtering removes meaningful Unicode spaces

- Symptom: U+2003 template content is discarded in JSPX.
- Minimal repro: `PageParserTest.xmlRetainsCharactersThatAreNotXmlFormattingWhitespace`.
- Cause: `String.isBlank()` accepts characters beyond XML formatting whitespace.
- Status: fixed by filtering only space, tab, CR and LF outside `jsp:text`.

## IBARRA-008 · 2026-10-10 · Escaped EL in directive attributes is rejected

- Symptom: a literal `\${...}` directive attribute is classified as dynamic.
- Minimal repro: `PageParserTest.escapedElInDirectiveAttributesIsLiteral`.
- Cause: a substring search ignores the Pages escaping rules.
- Status: fixed by examining quoting before recognizing a dynamic delimiter.
