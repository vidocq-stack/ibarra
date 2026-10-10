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

## IBARRA-009 · 2026-10-10 · Generated fixture cannot discover the core Java module

- Symptom: javac reports `module not found: io.vidocq.ibarra.core` in generator tests.
- Minimal repro: `PageGeneratorTest.rendersTemplateAndScriptingWithRequestImplicitObjects`.
- Cause: Surefire does not resolve a test-only core dependency into the compiler
  test's boot module configuration.
- Status: fixed by discovering modular artifacts with `ModuleFinder` from the
  available test dependency paths, then compiling/running fixtures on the module path.

## IBARRA-010 · 2026-10-10 · Typed EL values and array bean parameters are converted to text

- Symptom: a standalone EL object value loses its type; an array property receives
  only the first request parameter and conversion fails.
- Minimal repro: `PageGeneratorTest.pureElBeanValuesKeepTheirObjectTypeAndArrayParametersUseAllValues`.
- Cause: attribute generation always creates a string and uses `getParameter`.
- Status: fixed by preserving standalone EL values and selecting `getParameterValues`
  for array setters discovered from class files.

## IBARRA-011 · 2026-10-10 · Property-group EL defaults do not reach action attributes

- Symptom: a configured literal `${...}` attribute is evaluated despite `el-ignored=true`.
- Minimal repro: `PageGeneratorTest.propertyGroupElSettingsAlsoApplyToAttributes`.
- Cause: configuration is passed to text normalization but not attribute generation.
- Status: fixed by propagating the resolved EL defaults to attribute handling.

## IBARRA-012 · 2026-10-10 · Quoted EL strings are mistaken for function calls

- Symptom: `${'x:y()'}` is rejected as an unsupported tag-library function.
- Minimal repro: `PageGeneratorTest.elStringsThatResembleFunctionsAreNotRejected`.
- Cause: function detection scans quoted string contents.
- Status: fixed by excluding quoted contents before inspecting function syntax.

## IBARRA-013 · 2026-10-10 · Generator duplicates XML syntax inference incorrectly

- Symptom: an alternate JSP namespace prefix is emitted as literal markup.
- Minimal repro: `PageGeneratorTest.alternateXmlRootPrefixesReuseParserSyntaxDetection`.
- Cause: translation-unit loading checks only a `jsp:root` spelling.
- Status: fixed by sharing `PageParser.detectSyntax` with translation-unit loading.

## IBARRA-014 · 2026-10-10 · Generated scripting omits default Jakarta imports

- Symptom: valid unqualified `PageContext` and `HttpServletRequest` scripting types
  fail to compile without an explicit import directive.
- Minimal repro: `PageGeneratorTest.defaultJakartaImportsAreAvailableToScripting`.
- Cause: only explicit page imports are emitted.
- Status: fixed by emitting the default Servlet, HTTP and Pages API imports.

## IBARRA-015 · 2026-10-10 · Unsupported page options are accepted without an effect

- Symptom: `errorOnELNotFound=true` silently retains the default missing-value
  behaviour; session-scoped beans on `session=false` pages fail only at runtime.
- Minimal repro: `PageGeneratorTest.rejectsUnsupportedMissingElPolicyAndSessionlessSessionBeans`.
- Cause: accepted directives and scopes are not fully validated during generation.
- Status: fixed by rejecting the unimplemented strict EL policy and invalid
  session scope during translation. Strict missing-EL policy remains I3 work.

## IBARRA-016 · 2026-10-10 · Declaration fragments can bypass generated lifecycle setup

- Symptom: a declaration overriding `init(ServletConfig)` or generic `service`
  can skip generated expression initialization or page dispatch.
- Minimal repro: `PageGeneratorTest.declarationsCannotOverrideTheGeneratedLifecycleEntryPoints`.
- Cause: only no-argument init and typed HTTP service are final.
- Status: fixed by finalizing both public Servlet entry points as well; user
  `jspInit`/`jspDestroy` hooks remain supported.

## IBARRA-017 · 2026-10-10 · EL header maps ignore Servlet case-insensitive lookup

- Symptom: `${header['x-name']}` returns empty when the header was enumerated as `X-Name`.
- Minimal repro: `PageElIntegrationTest.headersCookiesAndApplicationInitParametersUseTheirServletSemantics`.
- Cause: header implicit objects use case-sensitive snapshot maps.
- Status: fixed with case-insensitive maps while retaining read-only header views.

## IBARRA-018 · 2026-10-10 · XML include directives survive into source generation

- Symptom: `jsp:directive.include` is rejected as an unsupported directive.
- Minimal repro: `PageGeneratorTest.xmlDirectiveIncludesAreExpandedBeforeNormalization`.
- Cause: include expansion recognizes only standard-syntax directive nodes,
  before XML elements are normalized into directives.
- Status: fixed by expanding namespace-aware XML include elements before normalization.

## IBARRA-019 · 2026-10-10 · Include cycles are reported as missing root resources

- Symptom: a cycle returning to the main page reports a missing include if the
  caller did not repeat the main source in the auxiliary source map.
- Minimal repro: `PageGeneratorTest.cyclicIncludeIsDetectedWithoutRequiringTheRootInTheSourceMap`.
- Cause: resource lookup runs before checking the current inclusion stack.
- Status: fixed by checking for cycles before looking up the included resource.

## IBARRA-020 · 2026-10-10 · Strict property-group policies are ignored

- Symptom: `error-on-el-not-found=true` in a matching group is ignored even
  though the equivalent page directive is rejected as unsupported.
- Minimal repro: `PageGeneratorTest.unsupportedGroupPoliciesAreNotSilentlyIgnored`.
- Cause: strict configuration flags are not propagated into generation settings.
- Status: fixed by applying the same explicit unsupported-policy checks to
  page directives and property-group settings.

## IBARRA-021 · 2026-10-10 · Named attribute bodies lose meaningful whitespace

- Symptom: XML `jsp:attribute trim=false` loses a whitespace-only body; default
  trimming also removes U+2003 beyond XML whitespace.
- Minimal repro: `PageGeneratorTest.xmlNamedAttributeWhitespaceIsPreservedWhenTrimIsDisabled`
  and `namedAttributeTrimmingOnlyRemovesXmlWhitespace`.
- Cause: XML normalization filters the body too early and generation uses Java
  Unicode stripping instead of XML whitespace rules.
- Status: fixed by retaining named-attribute body text until its trim policy is
  applied, and trimming only space, tab, CR and LF.

## IBARRA-022 · 2026-10-10 · Property actions keep using a replaced bean instance

- Symptom: after a scoped attribute is replaced, get/setProperty still operate
  on the original local variable created by useBean.
- Minimal repro: `PageGeneratorTest.propertyActionsResolveTheCurrentScopedBeanAfterReplacement`.
- Cause: property generation targets the Java local rather than the current
  named attribute in the page context.
- Status: fixed by resolving the current scoped bean, casting to its declared
  type, then calling the getter/setter directly without reflection.
