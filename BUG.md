# BUG.md — Ibarra

## IBARRA-055 · 2026-10-11 · Implicit URI precedence and explicit aliases are mixed

- Symptom: loose WEB-INF TLDs override library META-INF URIs, and an explicit
  external resource unexpectedly contributes its internal URI to the implicit map.
- Minimal repro: `TagCatalogTest.libraryImplicitUrisPrecedeLooseTldsButExplicitAliasesRemainAuthoritative`.
- Cause: one discovery list supplies both explicit and implicit entries in the
  wrong precedence order.
- Status: FIXED; keep explicit aliases separate, prefer library entries over
  loose TLDs, and retain the union for listener discovery.

## IBARRA-054 · 2026-10-11 · Later relative taglib declarations redirect earlier actions

- Symptom: equal relative URI strings declared in different included directories
  use the last declaration's TLD for earlier actions.
- Minimal repro: `TagGenerationTest.relativeTaglibBindingsRetainTheirDeclarationOriginAcrossIncludes`.
- Cause: a directive pre-pass overwrites origin metadata before any action emits.
- Status: FIXED; bind taglib origins in expanded source order and resolve each
  action against its active declaration.

## IBARRA-051 · 2026-10-11 · Tag attribute defaults and static setter coercion are wrong

- Symptom: runtime attributes default to String instead of Object, and literal
  attributes use the TLD type instead of the actual setter property type.
- Minimal repro: `TagMetadataTest.handlerAndAttributeMetadataAreImmutableAndHaveSpecificationDefaults`
  and `TagGenerationTest.staticValuesUseSetterTypesAndRuntimeValuesUseObjectDefaults`.
- Cause: one fixed type policy is applied to static and request-time contracts;
  choosing by invocation value also mis-selects overloaded dynamic setters.
- Status: FIXED; follow Pages 4.0 section 2.3 defaults and use class-file setter
  metadata for static-contract coercion. Dynamic contracts select their declared
  setter even when the supplied invocation value is literal.

## IBARRA-052 · 2026-10-11 · Attribute names beginning with xmlns disappear

- Symptom: a valid xmlnsLabel attribute passes required checks but its setter
  is never invoked.
- Minimal repro: `TagGenerationTest.ordinaryAttributesBeginningWithXmlnsAreNotNamespaceDeclarations`.
- Cause: broad startsWith("xmlns") filtering.
- Status: FIXED; filter only xmlns and xmlns: namespace declarations.

## IBARRA-053 · 2026-10-11 · Normal SKIP_PAGE triggers the returning handler's catch

- Symptom: a normal doEndTag return of SKIP_PAGE invokes that handler's own
  doCatch, potentially turning page termination into an error.
- Minimal repro: `TagGenerationTest.aNormalSkipPageReturnDoesNotInvokeTheReturningTagsOwnCatch`.
- Cause: synthetic SkipPageException is thrown inside its own action try block.
- Status: FIXED; run the handler's finally, then propagate skip-page to enclosing
  tags while retaining release cleanup.

## IBARRA-049 · 2026-10-11 · Setter failures enter TryCatchFinally callbacks

- Symptom: setBodyContent failure is swallowed by doCatch and invokes doFinally.
- Minimal repro: `TagGenerationTest.bodyContentSetterFailuresBypassCatchFinallyButStillRelease`.
- Cause: the buffered-body setter is inside the action exception scope.
- Status: FIXED; track setter failure separately, restore the writer, bypass
  doCatch/doFinally for that failure and still release the classic handler.

## IBARRA-050 · 2026-10-11 · Nested skip-page omits catch callbacks or resumes rendering

- Symptom: a classic SKIP_PAGE bypasses enclosing doCatch; a simple skip can
  be swallowed by doCatch and render the remainder of the page.
- Minimal repro: `TagGenerationTest.skipPageStopsRenderingWithoutInvokingErrorHandling`.
- Cause: direct service return for classic tags and swallowed SkipPageException.
- Status: FIXED; propagate skip-page through enclosing catch/finally/release,
  then handle it as normal termination at the service boundary.

## IBARRA-048 · 2026-10-11 · Typed skip-page catches reject valid generated methods

- Symptom: javac rejects a generated service method when a TryCatchFinally
  handler consumes all checked tag exceptions, or the page contains literals only.
- Minimal repro: `TagGenerationTest.catchFinallyAndReleaseRunWithTheRestoredWriter`.
- Cause: a standalone checked SkipPageException catch requires a reachable
  throwing statement in the generated try scope.
- Status: FIXED; distinguish normal skip-page termination inside the existing
  Throwable handler, preserving literal-only page compilation.

## IBARRA-047 · 2026-10-11 · Consumer merge can precede prerequisite snapshot publication

- Symptom: the post-merge build cannot compile `IbarraJspEngine` because the
  downloaded Foy API has no `JspEngine.Listener`.
- Minimal repro: merge Ibarra #11 before Foy #29 finishes publishing; Ibarra
  CI run 36359 fails with the old 0.4.0-SNAPSHOT API despite green PR validation.
- Cause: PR validation used an immutable local prerequisite; the main build
  resolves published snapshots and ran before the upstream publication completed.
- Status: RECOVERED; Foy run 36350 published successfully, then Ibarra main
  workflow run 36381 passed all 183 tests and published its snapshot. Cleanup
  PR #12 validates the published dependency without a source bootstrap. The
  ordering constraint remains: wait for upstream publication before merging
  a consumer that requires its new API. The workflow dispatch endpoint provides
  recovery without changing application code.

## IBARRA-045 · 2026-10-11 · Checked listener constructors cannot use plain suppliers

- Symptom: a valid public no-argument listener constructor declaring a checked
  exception makes generated index compilation fail.
- Minimal repro: `PagePrecompilerTest.namedLibraryTldsAreDiscoveredOnlyInsideTheApplicationDependencyGraph`
  uses a constructor declaring a checked Throwable (previously IOException).
- Cause: constructor method references cannot propagate checked exceptions
  through Supplier.get.
- Status: FIXED; direct constructor factories preserve RuntimeException/Error
  and wrap checked Throwable as the cause of a deployment failure. No listener
  is instantiated at build time.

## IBARRA-046 · 2026-10-11 · Relative taglib mappings use the wrong base

- Symptom: `tlds/example.tld` loads `/tlds/example.tld` instead of
  `/WEB-INF/tlds/example.tld`, or incorrectly reports a missing location.
- Minimal repro: `PagePrecompilerTest.relativeTaglibLocationsResolveAgainstWebInfWebXml`.
- Cause: prepending a slash instead of resolving against `/WEB-INF/web.xml`.
- Status: FIXED; resolve as required by Pages 4.0 section 7.3.6.1, then validate
  the normalized context-relative resource path.

## IBARRA-044 · 2026-10-11 · TLD discovery rejects recoverable generated outputs

- Symptom: incremental precompilation fails if an owned generated directory
  has been removed while its previous provider remains in module-info.class.
- Minimal repro: `PagePrecompilerTest.missingOwnedDirectoriesDoNotPreventRemovingPages`
  and `missingOwnedDirectoriesCanBeRebuiltWithoutChangingApplicationMetadata`.
- Cause: TLD discovery uses ModuleFinder on the stale application output,
  which validates service provider packages before repair.
- Status: FIXED; scan the application's META-INF directly and use ModuleFinder
  only for dependency modules.

## IBARRA-042 · 2026-10-11 · Missing page requests grow the router cache

- Symptom: distinct missing JSP URLs retain one cache entry each.
- Minimal repro: `RouterLifecycleTest` sends 100 distinct missing page lookups
  after concurrent requests initialize one real page.
- Cause: caching negative catalogue lookups in the servlet task map.
- Status: fixed by checking the immutable catalogue before allocating a task;
  cache entries are bounded by the finite set of precompiled pages.

## IBARRA-043 · 2026-10-11 · One failing page destruction skips other pages

- Symptom: an exception from one page's `destroy()` prevents later cleanup.
- Minimal repro: `RouterLifecycleTest` installs two initialized pages, one of
  which throws during destruction, and checks both are destroyed once.
- Cause: router cleanup stopped on the first unchecked exception.
- Status: fixed by logging individual failures and continuing destruction.

Reproducible bugs (internal issue, regression, incorrect behavior not yet fixed).
Entry format: `short id · date · symptom · minimal repro · cause hypothesis · status`.

## IBARRA-025 · 2026-10-10 · Source encodings are treated as unit-wide settings

- Symptom: included encodings conflict with the root or change its response
  encoding; repeated identical declarations and unknown encodings evade validation.
- Minimal repro: `pageEncodingIsFileLocalAndOnlyTheRequestedPageSetsResponseEncoding`,
  `duplicateAndUnknownEncodingsFailAtTheOwningDirective` and
  `defaultJspNamespacesStillValidateFileLocalEncodings` in `PageGeneratorTest`.
- Cause: generation merges file-local `pageEncoding` with global page settings.
- Status: fixed by validating encodings before expansion, including unprefixed
  XML directives, and using only the requested page's encoding for the response.

## IBARRA-026 · 2026-10-10 · XML response defaults use standard-page settings

- Symptom: XML pages default to `text/html` and use their source encoding instead
  of `text/xml` and UTF-8 as required by Pages 4.0 section 4.2.
- Minimal repro: `PageGeneratorTest.xmlResponseDefaultsIgnorePageSourceEncoding`.
- Cause: a single default content-type/encoding branch handles both syntaxes.
- Status: fixed with syntax-specific response defaults.

## IBARRA-027 · 2026-10-10 · Mixed includes lose source syntax during generation

- Symptom: XML markup included in standard JSP is rejected as a custom tag;
  standard custom tags included in XML can become literal output; XML attribute
  escapes and locally declared include namespaces are processed incorrectly.
- Minimal repro: `mixedIncludesDistinguishXmlMarkupFromStandardCustomTags`,
  `xmlIncludeMayDeclareItsJspNamespaceLocally` and
  `xmlAttributeEscapesAreNotInterpretedAsStandardSyntaxEscapes` in `PageGeneratorTest`.
- Cause: expanded nodes inherit root syntax instead of retaining file syntax;
  XML namespace declarations are mistaken for directive attributes.
- Status: fixed by preserving source syntax and separating XML namespaces from
  directive attributes when structuring each resource.

## IBARRA-028 · 2026-10-10 · Included taglibs can overwrite an enclosing XML prefix

- Symptom: a standard taglib in an XML include can bind an enclosing prefix
  to a different URI without a translation error.
- Minimal repro: `PageGeneratorTest.standardTaglibCannotRedefineAnEnclosingXmlNamespace`.
- Cause: translation-unit binding collection ignores enclosing XML namespaces.
- Status: fixed by checking declarations against the enclosing XML scope.

## IBARRA-029 · 2026-10-10 · Configuration errors lose source or are ignored

- Symptom: invalid included buffer directives report the main page's first
  line; misspelled property-group keys are silently ignored.
- Minimal repro: `PageGeneratorTest.invalidBufferAndUnknownGroupKeysHaveTranslationDiagnostics`.
- Cause: setting origins are discarded and group property names are unchecked.
- Status: fixed by preserving setting locations and rejecting unknown group keys.

## IBARRA-030 · 2026-10-10 · Source-aware XML attributes lose literal whitespace

- Symptom: an ordinary XML attribute `title='  '` becomes `title=''`.
- Minimal repro: `PageGeneratorTest.ordinaryXmlAttributeValuesKeepWhitespace`.
- Cause: already-decoded attribute values are structurally normalized as XML
  template text, which strips document indentation.
- Status: fixed by applying only expression/escape normalization to attributes.

## IBARRA-031 · 2026-10-10 · XML byte decoding inherits deployment encoding

- Symptom: XML without a named prolog encoding decodes as the configured
  charset instead of its derived UTF-8 default.
- Minimal repro: `SourceDecodingTest.xmlDefaultEncodingDoesNotComeFromThePropertyGroup`.
- Cause: configuration overwrites the provisional XML encoding before syntax detection.
- Status: fixed by keeping byte-derived XML encoding separate from named settings.

## IBARRA-032 · 2026-10-10 · Encoding conflicts lose directive-free source names

- Symptom: a configuration/prolog conflict reports an empty resource name
  if no page directive exists.
- Minimal repro: `SourceDecodingTest.configurationConflictsRetainTheRequestedResourceName`.
- Cause: error locations are inferred from the first directive instead of the resource.
- Status: fixed by supplying the resource location to consistency checks.

## IBARRA-033 · 2026-10-10 · Encoding scanner ignores EL literal policies

- Symptom: real pageEncoding directives inside literal EL delimiters are missed,
  producing corrupt output from valid UTF-8 source bytes.
- Minimal repro: `SourceDecodingTest.encodingDirectiveScanningRespectsLiteralElPolicies`.
- Cause: the detection scanner always uses default EL options and does not
  update deferred-literal policy from directives.
- Status: fixed by using configured options and tracking directive policy changes.

## IBARRA-034 · 2026-10-10 · Byte source snapshots break option value equality

- Symptom: reconstructing unchanged options or copying the same startup flag
  creates unequal records when byte sources are present.
- Minimal repro: `PageGeneratorTest.byteSourceSnapshotsCannotBeChangedByCallers`.
- Cause: record equality compares defensive byte-array copies by identity.
- Status: fixed with content-based equality and hash codes while retaining snapshots.

## IBARRA-035 · 2026-10-10 · EBCDIC probe rejects valid declared variants

- Symptom: valid IBM500 XML fails with an encoding/signature conflict.
- Minimal repro: `SourceDecodingTest.ebcdicSignatureIsAProbeAndDoesNotOverrideTheNamedVariant`.
- Cause: provisional IBM037 detection is treated as the definitive EBCDIC charset.
- Status: fixed by using IBM037 only to read the declaration and honoring the
  named variant for final strict decoding.

## IBARRA-036 · 2026-10-10 · Fixture module path omits Peano's API module

- Symptom: generated-page fixture JVMs fail boot resolution after Peano core
  requires its internal API module.
- Minimal repro: `PageGeneratorTest.rendersCachedPeanoExpressionsAgainstEachRequestContext`.
- Cause: fixture module-path filtering includes only Peano core.
- Status: fixed by including the required Peano API module in test JVM paths.

## IBARRA-037 · 2026-10-10 · Invalid resource paths fail only during discovery

- Symptom: a page filename containing a fragment/query marker compiles, but
  runtime page-index initialization fails when constructing its descriptor.
- Minimal repro: `PagePrecompilerTest.invalidPagePathsFailBeforePublishingTheIndex`.
- Cause: build packaging does not apply the descriptor's path validation.
- Status: fixed by sharing path validation through the internal API before publishing.

## IBARRA-038 · 2026-10-10 · Incremental ownership can delete replacement classes

- Symptom: a generated file replaced by application compilation can subsequently
  be overwritten/deleted by the precompiler's filename-only ownership manifest.
- Minimal repro: `PagePrecompilerTest.replacedOwnedClassesAreNeitherOverwrittenNorDeleted`.
- Cause: ownership does not verify the content of an existing file.
- Status: fixed by recording/checking SHA-256 class digests before replacement or cleanup.

## IBARRA-039 · 2026-10-10 · Removed pages leave stale module package metadata

- Symptom: an existing ModulePackages attribute retains the generated package
  after its last page is removed or the generation package changes.
- Minimal repro: `PagePrecompilerTest.existingModulePackageMetadataDropsRemovedGeneratedPackages`.
- Cause: module rewriting only appends package names.
- Status: fixed by rebuilding package metadata from surviving application/new classes.

## IBARRA-040 · 2026-10-10 · Partial output refresh prevents page cleanup

- Symptom: removing a page after its generated directory disappeared fails
  while resolving the stale provider or deleting an absent package directory.
- Minimal repro: `PagePrecompilerTest.missingOwnedDirectoriesDoNotPreventRemovingPages`.
- Cause: build validation resolves the application's stale generated provider
  and cleanup assumes every owned directory still exists.
- Status: fixed with invocation-local descriptor repair and tolerant directory cleanup.

## IBARRA-041 · 2026-10-10 · Foreign XML elements become JSP configuration

- Symptom: foreign-namespace elements named jsp-config/property-group are
  interpreted as official deployment settings.
- Minimal repro: `PrecompileMojoTest.foreignNamespacesCannotPretendToBeJspConfiguration`.
- Cause: descriptor extraction compares only element local names.
- Status: fixed by checking supported descriptor namespaces and namespace consistency.

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
- Status: initially fixed by rejecting unsupported flags. Strict EL and namespace
  policies are now implemented and covered by `strictGroupPolicyRejectsMissingIdentifiersAtEvaluation`
  and `undeclaredNamespacesFailOnlyWhenConfiguredAndKeepTheirSourceLocation`.

## IBARRA-023 · 2026-10-10 · Included taglib declarations do not bind the main page

- Symptom: a taglib declared in a static include is not recognized in the main
  page; custom actions silently become literal markup. Late declarations are
  accepted despite Pages 4.0 section 1.10.2 requiring declaration before use.
- Minimal repro: `PageGeneratorTest.taglibDeclarationsInIncludesBindLaterTagsAcrossTheTranslationUnit`.
- Cause: per-file parsing binds prefixes without validating the expanded unit.
- Status: fixed by collecting translation-unit bindings before the final parse
  and checking taglib declaration order over expanded includes/preludes/codas.

## IBARRA-024 · 2026-10-10 · Invalid boolean policies evade translation validation

- Symptom: malformed group booleans leak generic `IllegalArgumentException`,
  and some malformed page booleans are silently accepted on literal pages.
- Minimal repro: `PageGeneratorTest.invalidBooleanConfigurationFailsWithPageDiagnosticsEvenWithoutAffectedNodes`.
- Cause: boolean validation occurs only when an affected node uses the setting.
- Status: fixed by validating group/page booleans eagerly with source diagnostics.

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
