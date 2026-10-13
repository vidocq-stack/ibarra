# BUG.md — Ibarra

## IBARRA-137 · 2026-10-13 · Output omission aliases expose missing body validation

- Symptom: accepting the legal yes/no aliases makes unchanged
  `actions.output.URLClientIT#jspOutputBodyTest` fail; its invalid nonempty
  `jsp:output` now translates instead of failing as required by section 5.13.
- Minimal repro: `TagGenerationTest.xmlOutputRejectsNonemptyBodiesBeforeWritingADeclaration`
  and original `JspOutputBodyTest1.jspx` containing Test FAILED in the action body.
- Cause: the old omission-value rejection accidentally masked missing empty-body
  validation; generation ignored the action's children.
- Status: FIXED. Validate XML-only context and empty bodies before normalization
  can discard whitespace, retaining action-source diagnostics. Empty paired and
  self-closing forms remain accepted; legal omission aliases and tag-dependent
  payload envelopes remain supported. The genuine official body method passes.

## IBARRA-135 · 2026-10-13 · XML output rejects specified yes/no omission values

- Symptom: unchanged `jspdocument.general.URLClientIT#tagDependentTest` cannot
  render its envelope fixture because `jsp:output omit-xml-declaration='yes'`
  fails translation with Expected true or false.
- Minimal repro: `TagGenerationTest.xmlOutputOmissionAcceptsItsSpecifiedStringAliasesWithoutChangingPageBooleans`.
- Cause: the String-valued Pages 4.0 section 5.13 attribute uses the ordinary
  Boolean-directive parser instead of its four specified spellings.
- Status: FIXED. Only this attribute accepts yes/no/true/false; other spellings
  and yes/no on ordinary Boolean directives still fail. The unchanged official
  tag-dependent fixture and three nearby XML-identification methods pass.

## IBARRA-136 · 2026-10-13 · Broader XML output prolog semantics remain incomplete

- Symptom: the neighboring official actions.output class still reports six
  method errors after omission aliases are accepted.
- Minimal repro: `actions.output.URLClientIT` methods `simpleDefaultTest`,
  `jspOutputJspRootOmitDeclDefaultTest`, `jspOutputOmitDeclValidValuesTest`,
  `jspOutputUsageContextTest`, `doctypeSystemTest` and
  `doctypeSystemPublicTest` in the unchanged official archive.
- Cause: the initial generator emits explicit declarations at action position;
  full translation-unit prolog defaults, context/body validation and DOCTYPE
  output are not yet implemented.
- Status: OPEN. The alias fix is intentionally bounded; full section 5.13 support
  needs separate prolog collection and validation. No official fixtures are rewritten.

## IBARRA-118 · 2026-10-13 · Valid precompile queries still execute the JSP body

- Symptom: bare, empty, true and false jsp_precompile parameters execute user code;
  arbitrary values also return 200. Three official positive tests appeared to pass
  because their unexpected-response text uses Request but the page writes request.
- Minimal repro: PagesContainerTest.precompileRequestsValidateDecodedValuesAndNeverExecuteThePageThroughRoutesOrAliases;
  the initial bare query returned executed-1 rather than an empty response.
- Cause: generated servlet service dispatches every request to _jspService without
  implementing the Pages 4.0 section 11.4.2 precompilation protocol.
- Status: FIXED by validating all decoded parameter values before dispatch;
  valid requests suppress the JSP body and invalid values send HTTP 500. Both
  generic JSP routing and explicit jsp-file aliases use the generated service.

## IBARRA-119 · 2026-10-13 · A forward from an included JSP retains ancestor output

- Symptom: the unchanged official `actions.include.URLClientIT#positiveIncludeForwardTest`
  returns the including JSP's unexpected text around the forward target instead of
  returning only the target. Including pages also continue executing after the forward.
- Minimal repro: a buffered JSP writes `before`, includes a JSP that forwards to a
  static `target` resource, then writes `after`. The HTTP body is `beforetargetafter`.
- Cause: JSP inclusion redirects output to the ancestor writer through a response
  wrapper. Forward clears only the child buffer and forwards through the container's
  include response; ancestor buffers and generated-page continuation remain active.
- Status: OPEN. The signed prototype in the fix-include-forward worktree restores
  the official scenario, but bypasses application wrappers added during inclusion
  (IBARRA-130). It is withheld from the publication branch pending a typed container
  dispatch boundary that preserves those wrappers. See
  [studies/include-forward-wrapper-dispatch.md](studies/include-forward-wrapper-dispatch.md).
- Validation: original official method reproduced red, plus two focused core failures
  and HTTP `beforetargetafter`. Under Temurin 25, `./mvnw -o -ntp -Ptck -pl ibarra-tck
  -am test` passes all selected reactor and adapter tests. The unchanged official
  include and forward classes pass 19 methods with `-Ptck,tck-official -pl ibarra-tck
  -am -Dtest=ee.jakarta.tck.pages.spec.core_syntax.actions.include.URLClientIT,ee.jakarta.tck.pages.spec.core_syntax.actions.forward.URLClientIT
  -Dsurefire.failIfNoSpecifiedTests=false test`. Additional regressions cover nested
  includes, parent scriptlet suppression, committed/flushed ancestor rejection,
  application wrapper preservation, failed target dispatch and context reuse.

## IBARRA-130 · 2026-10-13 · Include-forward prototype bypasses child response wrappers

- Symptom: an application response wrapper added during included dispatch is not
  passed to the forward target; its uppercase transformation changes from TARGET
  to target. The outer original application wrapper is retained.
- Minimal repro: the isolated core test and exact command recorded in
  [studies/include-forward-wrapper-dispatch.md](studies/include-forward-wrapper-dispatch.md),
  against signed prototype 8d9c54ac98a99b1df1042bbacc9e6702ceac79b3.
- Cause: forwarding through the saved outer response bypasses application and
  container include wrappers together; core cannot safely distinguish opaque
  container wrappers from application wrappers through ServletResponseWrapper alone.
- Status: OPEN; prototype withheld from this branch. The original include/forward
  scenario remains failing. Follow-up requires container-aware typed dispatch,
  preserving wrapper identity/delegation and include/forward response semantics.

## IBARRA-120 · 2026-10-13 · Tag-dependent body content is parsed as JSP

- Symptom: the official `URLClientIT#elInvocationAndUsageTest` receives a translation
  failure; EL/actions/directives or unfinished scripting inside tag-dependent bodies
  are interpreted before the generator rejects the handler body contract.
- Minimal repro: `TagGenerationTest.tagDependentBodiesPreserveElActionsDirectivesAndUnfinishedScriptingAsLiteral`
  and the unchanged official HTTP scenario.
- Cause: page parsing and directive/encoding probes have no early TLD body contract.
- Status: FIXED. Early build-only TLD resolution preserves opaque standard body text,
  XML raw markup/decoded character data and binding origins across includes/preludes.
  The first exact standard closing delimiter matches Jasper's unparsed-body rule;
  XML structural nesting remains governed by XML. Compiler/core reactor tests and
  the genuine official HTTP scenario pass without runtime parsing or reflection.

## IBARRA-121 · 2026-10-13 · Tag-dependent explicit envelopes lack translation support

- Symptom: accepting initial `jsp:attribute`/`jsp:body` wrappers as raw content would
  ignore setter/body semantics and silently render the wrapper markup.
- Minimal repro: `TagGenerationTest.tagDependentExplicitEnvelopesEvaluateNamedAttributesAndEchoTheBodyVerbatim`,
  for both standard syntax and JSPX.
- Cause: the bounded raw-body implementation does not yet split those envelopes
  from the handler-dependent body payload.
- Status: FIXED. Outer named attributes use ordinary scalar/fragment/deferred
  contracts; explicit-body payload is opaque before directive/encoding probes,
  include expansion and EL normalization. Standard closing delimiters, XML aliases,
  whitespace, empty-body invocations and malformed envelope diagnostics are covered.
  The unchanged official `jspdocument.general.URLClientIT#tagDependentTest` passes.

## IBARRA-116 · 2026-10-13 · Partial unbuffered writes remain eligible for forwarding

- Symptom: after a response writer sends partial output and throws, forwarding
  succeeds while the Servlet response still reports uncommitted.
- Minimal repro: PageRuntimeTest.partialUnbufferedWriteFailuresStillPreventForwarding,
  exercising single-character, character-array and String writes.
- Cause: unbuffered writes record the flushed state only after the writer returns.
- Status: FIXED by recording possible output before invoking each nonempty write,
  after acquiring the destination. Forwarding consults the page writer's state.

## IBARRA-117 · 2026-10-13 · Error-page dispatch discards already emitted JSP output

- Symptom: an unbuffered page exception forwards to its error page and discards
  output already passed to a Servlet response whose own buffer is uncommitted.
- Minimal repro: PageRuntimeTest.errorPagesIncludeAfterUnbufferedOutputWhileRetainingTheOriginalException
  and PagesContainerTest.compiledForwardsDistinguishPendingBufferedOutputFromUnbufferedWritesAndRouteErrors.
- Cause: error-page selection considers only Servlet commitment, ignoring JSP
  output state; forwarding cannot preserve output already emitted by the page.
- Status: FIXED by including the error page after JSP output is flushed/written,
  preserving original exception attributes and leaving pending buffered forwards intact.

## IBARRA-115 · 2026-10-13 · Collection property pass-through accepts a null ELContext

- Symptom: generated method-only tables silently accept null context in their
  property operations, contrary to the ELResolver contract.
- Minimal repro: CollectionAccessorsTest.privateModuleTablesPreserveResolutionMetadataCoercionAndFailureContracts;
  the isolated application JVM failed with Null context accepted before the fix.
- Cause: unresolved property stubs return without validating the supplied context.
- Status: FIXED by validating context in all five pass-through operations while
  preserving unresolved property flags and native collection resolvers.

## IBARRA-113 · 2026-10-13 · Deferred signatures reject simple java.lang names

- Symptom: valid TLD types such as Object, Integer and String[][] fail translation
  with Bean class is absent from the build module path.
- Minimal repro: PagePrecompilerTest.deferredSignaturesResolveSimpleJavaLangNamesInLiteralAndCompiledExpressions,
  deferredObjectParametersResolveForMappedMethodExpressionsWithoutRuntimeCompilation
  and deferredJavaLangArrayAliasesKeepTheirDimensionsAndExpectedClass.
- Cause: expression type resolution treats simple names as binary names without
  the implicit java.lang qualification used by Java source signatures.
- Status: FIXED by qualifying simple reference names after peeling array dimensions;
  primitive and explicitly qualified names retain their existing resolution.

## IBARRA-114 · 2026-10-13 · Deferred Vector methods need direct EL metadata

- Symptom: after resolving Object in a deferred signature, getMethodInfo for
  #{vect.add} fails with Reflection fallback denied for java.util.Vector.
- Minimal repro: official api.jakarta_el.methodinfo.URLClientIT#methodInfoTest
  and spec.el.jsp.URLClientIT#positiveELDeferredMethodValueTest; a compiled page
  creating java.util.Vector and passing #{vect.add} with boolean add(Object)
  reproduces the metadata failure with peano.reflection=deny.
- Cause: the application has no registered direct method table for Vector;
  Peano CompiledMethodExpression.metadata reaches its denied reflection fallback.
- Status: FIXED in the unpublished runner by generating method-only tables for
  public java.base collections allocated by compiled WAR/page bytecode. Metadata,
  overload selection and invocation use Peano's existing SPI with direct calls;
  native collection property resolution remains untouched. Both official methods
  pass. Production applications still supply their own accessor providers.
  Simple-name translation is fixed separately in IBARRA-113; no reflection fallback
  or fixture rewrite is enabled.

## IBARRA-108 · 2026-10-13 · Interface-first bean lookup widens a covariant getter

- Symptom: a parent String getter becomes Object from an interface, making an
  incompatible Integer setter writable instead of read-only.
- Minimal repro: the private WAR bean fixture's interface/parent readOnly property;
  HTTP metadata was false|false before the fix, instead of true|true.
- Cause: getter selection keeps the first interface descriptor rather than the
  most-specific covariant return type across the hierarchy.
- Status: FIXED by covariant getter selection with primitive boolean is-getter
  precedence before compatible most-specific setter selection.

## IBARRA-109 · 2026-10-13 · Automatic bean tables shadow native map key resolution

- Symptom: a Map subclass's getName returns bean even though its name key is map.
- Minimal repro: PagesContainerTest.mapResolutionIsPreservedAndUnusedBeansAreNotInitializedByDiscovery.
- Cause: an automatic provider before the standard Map resolver claims a getter
  on a native container type extracted from legacy WAR classes.
- Status: FIXED by excluding Map, List, ResourceBundle and Record hierarchies;
  native resolvers retain their semantics. The Map assertion was red first.

## IBARRA-110 · 2026-10-13 · Accessor targets include unreadable or unexported hierarchy types

- Symptom: an available but inaccessible dependency yields an unusable table,
  whose eager target class lookup can fail during service discovery.
- Minimal repro: BeanAccessorsTest.unavailableAndUnexportedHierarchiesDoNotProduceUnusableTables.
- Cause: the original reader scans available artifacts without application
  module reads/exports and signature visibility constraints.
- Status: FIXED by non-static direct/transitive reads, qualified export checks
  and signature accessibility. Unadapted targets have bean-accessor-errors.txt
  diagnostics; real page errors remain visible. Positive qualified exports pass.

## IBARRA-111 · 2026-10-13 · Bean bytecode identity differs from its archive path

- Symptom: app/Wrong.class declaring app.Bean yields an unloadable accessor target.
- Minimal repro: BeanAccessorsTest.mismatchedBinaryPathsAreRejectedBeforeProviderRegistration.
- Cause: generation trusts thisClass without comparing the extracted path.
- Status: FIXED by binary identity validation before emission; mismatches abort
  adaptation without changing original application class bytes.

## IBARRA-112 · 2026-10-13 · Unreadable package owner masks a readable dependency class

- Symptom: support.Other in a.unread masks support.Parent in z.support,
  producing no provider for a valid bean.
- Minimal repro: BeanAccessorsTest.unrelatedUnreadableModuleCannotMaskTheReadableClassInTheSamePackage.
- Cause: package ownership is rejected before checking the exact class entry.
- Status: FIXED by exact resource lookup, continued readable candidates and
  inaccessible-hit diagnostics only after exhausting the search. Red/green verified.

## IBARRA-105 · 2026-10-13 · Official TEI fixtures rely on runtime-visible translation state

- Symptom: TagAttributeInfo and related metadata scenarios remain blocked by
  unsupported TEI metadata; their JSPs also require the TEI helper's shared static
  validation flag and reset it after serving each request.
- Minimal repro: official api.jakarta_servlet.jsp.tagext.tagattributeinfo.URLClientIT;
  inspect BaseTCKExtraInfo.validate and GetNameTest.jsp in the unchanged 4.0.0 binary.
- Hypothesis: build-side TEI execution alone cannot reproduce this cross-phase
  fixture state in the runner's separate compiler-free server process.
- Status: OPEN; the evidence and required design follow-up are recorded in
  [studies/tag-extra-info-aot.md](studies/tag-extra-info-aot.md). No challenge is filed
  and no fixture rewrite, fake marker or request-time validation replay is used.

## IBARRA-106 · 2026-10-13 · Private WAR bean properties have no direct EL accessors

- Symptom: TryCatchFinally's valid page fails with Reflection fallback denied for
  ResultVerifierBean; ordinary private WAR bean property reads/writes likewise fail.
- Minimal repro: official api.jakarta_servlet.jsp.tagext.trycatchfinally.URLClientIT
  and PagesContainerTest.privateWarBeanPropertiesUseDirectAccessorServicesWithReflectionDenied.
- Cause: original WAR bytecode has no Peano-generated property provider, while the
  server deliberately denies reflection and keeps application packages private.
- Status: FIXED; build-only Class-File property table/service generation
  uses the existing Peano SPI in the same module, preserving both runtime boundaries.

## IBARRA-107 · 2026-10-13 · Generated bean provider uses a class reference for List.of

- Symptom: accessor discovery throws IncompatibleClassChangeError and closes HTTP
  requests before a response because List.of requires an InterfaceMethodref.
- Minimal repro: the new private bean HTTP adapter fixture before the bytecode fix.
- Cause: invokestatic emission uses the default class-member constant for java.util.List.
- Status: FIXED by explicitly marking the static interface invocation; the complete
  adapter suite then passes, including private property reads and primitive writes.

## IBARRA-104 · 2026-10-12 · Caller attributes overwrite compiler-provided JSP identifiers

- Symptom: an ordinary/named jspId attribute, including the JspId capitalization
  alias, binds setJspId(String) after the generated constant and replaces it.
- Minimal repro: TagGenerationTest.ordinaryAndNamedAttributesCannotOverwriteTheCompilerIdentifierSetter.
- Cause: ordinary setter binding does not distinguish the reserved consumer setter
  from a caller-declared property. Pages 4.0 section 1.3.7 reserves jsp-prefixed names.
- Status: FIXED by rejecting caller scalar setters targeting the consumer's
  setJspId(String), plus the dynamic jspId spelling, during page generation.
  The reserved-container-property rule applies to ordinary and named attributes,
  including capitalization/prefix aliases, without rejecting unrelated TLD catalogues.

## IBARRA-102 · 2026-10-12 · WAR libraries prevent the last two official deployments

- Symptom: tag-file packaging and TLD resource classes fail setup on WEB-INF/lib.
- Minimal repro: official `spec.tagfiles.packaging.URLClientIT` and `spec.tldres.URLClientIT`.
- Cause: legacy WAR libraries are rejected before compilation, and their overlapping
  packages cannot be placed in independent named dependency modules.
- Status: FIXED for bounded legacy libraries by build-side assembly into the existing
  private application module. Original JARs remain unchanged for physical TLD references;
  library TLDs retain separate discovery paths. Conflicting bytecode/resources and
  unsupported module/version/fragment/service metadata fail explicitly. Both classes
  now execute 22 official methods (15 passing); tag-file and URI assertions remain open.

## IBARRA-103 · 2026-10-12 · Noncanonical library entries bypass metadata rejection

- Symptom: ./module-info.class or META-INF/./web-fragment.xml is accepted despite
  the runner's explicit rejection of those metadata capabilities.
- Minimal repro: WarLibrariesTest.unsafeLibraryPathsCannotEscapeTheApplicationRoot;
  the added dotted-name case was red before the fix.
- Cause: classification uses the raw ZIP entry while its destination is normalized.
- Status: FIXED by rejecting empty, dot and parent segments before classification
  or extraction, as well as absolute paths and backslash separators.

## IBARRA-101 · 2026-10-12 · Generated component constructors leak checked Throwable

- Symptom: a constructor declared `throws Throwable` propagates a checked failure
  out of WebComponent.newInstance, bypassing Foy's Exception/RuntimeException cleanup.
- Minimal repro: ComponentFactoriesTest.checkedThrowableConstructorsAreWrappedWhileRuntimeFailuresKeepTheirType.
- Cause: the first generated factory invokes the constructor without an exception boundary.
- Status: FIXED by generated exception handlers preserving RuntimeException/Error
  unchanged and wrapping all other Throwable in IllegalStateException with its cause.
  The generation test was red with an unexpected java.lang.Throwable before the fix.

## IBARRA-100 · 2026-10-12 · Official WAR descriptor components cannot be instantiated

- Symptom: CompositeELResolver, JspApplicationContext and general configuration
  classes fail deployment before their official test methods execute.
- Minimal repro: select `api.jakarta_el.compelresolver.URLClientIT`,
  `api.jakarta_servlet.jsp.jspapplicationcontext.URLClientIT` or
  `spec.configuration.general.URLClientIT` with the official runner.
  Adapter regression: descriptorComponentsUseDirectFactoriesInUnexportedApplicationPackages.
- Cause: the runner rejects descriptor components, and Foy's fallback constructors
  cannot access the private application packages without generated factories.
- Status: FIXED for classes in WEB-INF/classes explicitly declared in root web.xml.
  Build-only Class-File generation emits Foy WebComponent service providers with
  direct class literals and constructors in the application module. Metadata
  discovery never initializes components, invalid constructors/collisions fail
  before server launch, and application packages retain no exports/opens.
  WAR libraries, annotations and initializer providers remain separate boundaries.

## IBARRA-099 · 2026-10-12 · Ordinary tag getter guidance selects incompatible setters

- Symptom: getter-guided overload selection chooses Object instead of String or
  Parent[] instead of Child[], and accepts a broad setter for a narrow getter.
- Minimal repro: TagGenerationTest.tagGetterCompatibilityMatchesJavaBeansForUniqueAndOverloadedSetters
  and getterGuidanceAndNestedArrayCovarianceSelectTheCorrectOrdinarySetter.
- Cause: the initial tag-property binding uses reversed getter compatibility and
  prefers an exact getter-type setter before the most-specific writable method.
- Status: FIXED for ordinary tag properties by Class-File getter/write compatibility
  and unique most-specific setter selection, including single candidates, boolean
  getters and reference-array covariance. Bean actions and deferred protocols retain
  their existing paths; no runtime introspection is introduced.

## IBARRA-098 · 2026-10-12 · Remaining official Pages assertions after deployment recovery

- Symptom: the current full official run reports 164 method errors and no class-level
  setup/deployment errors after 518 passing methods; the previous baseline was 510 passes.
- Minimal repro: `./run-official-tck-pages-4.0.sh all`; individual classes and
  failing method selectors are recorded under stable TCK ids in
  [ibarra-tck/FAILURES.md](ibarra-tck/FAILURES.md).
- Hypotheses: remaining tag files/metadata, bean/API access, translation validation,
  response semantics and runner component/library adaptation. Established causes
  retain their specific IBARRA-085–094 records; other cases need isolated responses.
- Status: OPEN. The inventory tracks every currently failing class/method without
  treating deployment error entries as executed methods or claiming certification.

## IBARRA-095 · 2026-10-12 · Deployment translation mode masks invalid global properties

- Symptom: invalid global el-ignored/buffer values produce page error servlets instead
  of aborting deployment; a zero-page application ignores the configuration entirely.
- Minimal repro: deploymentCompilationDoesNotRetainInvalidGlobalConfigurationAsPageErrors.
- Cause: property validation originally runs inside each recoverable translation unit.
- Status: FIXED by deployment-wide key/boolean/encoding/buffer validation before per-page recovery, preserving
  the previous descriptor and manifest when configuration is invalid.

## IBARRA-096 · 2026-10-12 · Incremental deployment compilation resolves stale generated classes

- Symptom: a page compiles against a previous generated helper that another page
  loses when replaced by an error servlet, then fails linkage at runtime.
- Minimal repro: deploymentCompilationCannotResolveRemovedOwnedClassesFromPreviousRuns.
- Cause: isolated javac still includes previous owned outputs on its patch path.
- Status: FIXED by compiling against a clean original-application view and current
  successful/error classes only. Previous owned helpers cannot satisfy new references.

## IBARRA-097 · 2026-10-12 · Retained diagnostics split Unicode surrogate pairs

- Symptom: a long genuine page error becomes UnmappableCharacterException while
  writing its generated error servlet source in UTF-8.
- Minimal repro: retainedDiagnosticTruncationPreservesSupplementaryCharacters.
- Cause: bounded diagnostic text is cut after a high surrogate at UTF-16 unit 8192.
- Status: FIXED by retaining complete surrogate pairs at the bound; the public
  build report still preserves the complete original diagnostic.

## IBARRA-084 · 2026-10-12 · Eager TCK precompilation rejects mixed positive/negative WARs

- Symptom: a whole official test class fails deployment on an intentionally
  invalid page, before valid pages or negative translation assertions can run.
- Minimal repro: `spec.core_syntax.directives.page.URLClientIT`, whose WAR contains
  `negativeDuplicateAutoFlushFatalTranslationError.jsp`; run with the official script.
- Cause: PagePrecompiler translates every page atomically before starting Foy.
- Status: FIXED for individual parse/generation/javac failures by explicit deployment
  precompilation: valid pages compile, failed pages become direct error servlets
  preserving request-time errors. Global invalid deployment metadata still aborts.

## IBARRA-085 · 2026-10-12 · Official BeanELResolver fixture fails in the private application module

- Symptom: official `api.jakarta_el.beanelresolver.URLClientIT#beanElResolverTest`
  reports Test FAILED over HTTP.
- Minimal repro: select that fully qualified class with the official script.
- Hypothesis: the upstream reflective BeanELResolver cannot access a fixture bean
  in the unexported synthetic application module; accessor adaptation is incomplete.
- Status: OPEN, investigation required; do not attribute this to Peano's separate
  certified EL TCK without distinguishing the Pages deployment environment.

## IBARRA-086 · 2026-10-12 · Official JspIdConsumer tests fail

- Symptom: sameJspIdTest, setJspIdTest and multipleJspIdTest fail over HTTP in
  `api.jakarta_servlet.jsp.tagext.jspidconsumer.URLClientIT`.
- Minimal repro: select that fully qualified class with the official script.
- Cause: generated tag invocations omit JspIdConsumer.setJspId.
- Status: FIXED by Class-File hierarchy detection and a direct constant setter for
  each consumer occurrence during property initialization. Identifiers are unique
  within the page, stable across requests and valid for the API's character rules.
  Repeated source locations from static includes get distinct ids; fragment
  invocations retain their node ids while constructing fresh handler instances.
  Red/green compiled fixtures cover classic/simple inherited consumers and JSPX;
  official verification is recorded in the diagnostic baseline.

## IBARRA-087 · 2026-10-12 · Official TagLibraryValidator assertions fail

- Symptom: tagLibraryValidatorAPITest and tagLibraryValidatorTranslationFailureTest
  fail in `api.jakarta_servlet.jsp.tagext.taglibraryvalidator.URLClientIT`.
- Minimal repro: select that fully qualified class with the official script.
- Hypothesis: full TLV validation and XML-view handling remain incomplete.
- Status: OPEN, I6 translation/runner investigation; full metadata is not claimed complete.

## IBARRA-088 · 2026-10-12 · Official TryCatchFinally scenario fails

- Symptom: `api.jakarta_servlet.jsp.tagext.trycatchfinally.URLClientIT#tryCatchFinallyTest`
  reports Test FAILED despite the existing handler unit tests passing.
- Minimal repro: select that fully qualified class with the official script.
- Cause: the verifier's private bean has no generated EL property accessor;
  server.log reports Peano reflection fallback denial, rather than a callback fault.
- Status: FIXED by runner-owned direct property tables; the unmodified official
  TryCatchFinally scenario passes without changing handler lifecycle generation.

## IBARRA-089 · 2026-10-12 · Official unbuffered forward scenario fails

- Symptom: `spec.core_syntax.actions.forward.URLClientIT#unbufferedWriteForwardTest`
  reports Test FAILED; six other methods in that class pass.
- Minimal repro: select that fully qualified class with the official script.
- Cause: Ibarra PageContext.forward checks only Servlet response commitment,
  allowing forward after unbuffered JSP writes or a JSP flush whose response
  wrapper defers commitment. Pages 4.0 section 5.5 requires rejection.
- Status: FIXED by checking the base JSP writer's existing output state before
  clearing buffers or resolving the dispatcher. Unwritten unbuffered pages may
  still forward; zero-length writes do not acquire the response writer.

## IBARRA-090 · 2026-10-12 · Official bean PropertyEditor conversion fails

- Symptom: `spec.core_syntax.actions.setproperty.URLClientIT#positiveBeanPropertyEditorTest`
  reports Test FAILED.
- Minimal repro: select that fully qualified class with the official script.
- Cause: generated bean-property conversion always used EL coercion, ignoring
  the property-specific editor classes declared by the bean's BeanInfo.
- Status: FIXED (2026-10-13) for verified literal BeanInfo descriptors. Build-time
  Class-File metadata selects the editor; generated code directly constructs it
  and invokes `setAsText`/`getValue`. The unchanged official method passes with
  no failures, errors or skips. Dynamic BeanInfo metadata remains explicitly
  unsupported; no runtime introspection or editor-registration fallback is used.

## IBARRA-091 · 2026-10-12 · Official negative precompile query fails

- Symptom: `spec.misc.precompilation.URLClientIT#precompileNegativeTest` fails;
  the three other precompile query tests pass.
- Minimal repro: select that fully qualified class with the official script.
- Cause: generated servlet service does not validate jsp_precompile values.
- Status: FIXED with IBARRA-118: decoded invalid values send HTTP 500 and every
  valid precompilation request suppresses the body before PageContext acquisition.
  Existing translation-error servlets continue reporting their build failure.

## IBARRA-092 · 2026-10-12 · Official Pages signature test is not configured

- Symptom: `signaturetest.jsp.JSPSigTestIT#signatureTest` fails with signature differences.
- Minimal repro: select that fully qualified class with the official script.
- Cause: the upstream signature tool is invoked with an empty `-Classpath`.
- Status: FIXED in the official-only runner profile: maven-dependency-plugin
  copies the exact Pages/Servlet/EL API versions, and the unchanged signature
  driver receives their paths plus its extracted java.base image. The original
  signatureTest passes after clean without command-line path overrides; application
  and host deployment remain on the module path. No signature fixture is changed.

## IBARRA-093 · 2026-10-12 · TLD attribute names that are Java keywords are rejected

- Symptom: official action/EL fixture WARs fail translation with "Invalid tag
  attribute name: boolean", "byte" or "static".
- Minimal repro: `spec.core_syntax.actions.attribute.URLClientIT` loads `/WEB-INF/attribute.tld`.
- Cause: the parser additionally rejects Java keywords although the schema's
  identifier lexical constraint does not require these names to become Java variables.
- Status: FIXED by retaining identifier syntax validation and accepting keyword
  names. Direct generated setBoolean/setByte/setStatic calls are covered by rendering tests.

## IBARRA-094 · 2026-10-12 · Official fixtures fail inferred TLD setter validation

- Symptom: configuration/EL fixture WARs fail translation with "No setter matching
  TLD type java.lang.Object" for SimpleTag.echo or SetTag.value.
- Minimal repro: `spec.el.language.URLClientIT`, `/CompositeExprEval1.jsp`.
- Cause: ordinary tag setters are matched exactly against TLD EL metadata rather
  than the actual JavaBeans property. Omitted dynamic metadata correctly defaults
  to Object, but that does not mandate a setValue(Object) method.
- Status: FIXED by actual-property binding/coercion for ordinary attributes,
  including inherited methods and explicit metadata differences. The compatibility
  interpretation follows the official fixtures and Jasper's property-based generation;
  deferred/fragment setter protocols remain exact.

## IBARRA-083 · 2026-10-12 · Initial TCK adapter ignores JSP property groups

- Symptom: deployment succeeds although descriptor `el-ignored` and other JSP
  property-group settings never reach the precompiler.
- Minimal repro: PagesContainerTest.unsupportedDeploymentFeaturesFailExplicitlyAndLeaveTheAdapterReusable,
  a WAR declaring `<jsp-property-group><el-ignored>true</el-ignored>`.
- Cause: the initial adapter passes GenerationOptions.DEFAULT for every WAR.
- Status: FIXED by build-side property-group parsing and explicit compiler options,
  preserving pattern precedence, implicit include order and legacy EL defaults.
  Runtime ServletContext JSP descriptor exposure remains a separate Foy follow-up.

## IBARRA-082 · 2026-10-12 · Pages TCK adapter accepts only one active WAR

- Symptom: multi-deployment configuration tests fail with "A deployment is already active".
- Minimal repro: PagesContainerTest.multipleDeploymentsHaveIndependentPortsAndLifecycles;
  official `spec.configuration.elevaluation.URLClientIT` also declares multiple WARs.
- Cause: the initial adapter stores one process for the whole Arquillian container.
- Status: FIXED by per-archive processes, ports and work directories; undeploying one
  archive leaves the others running. Stop reaps every child with bounded waits and
  restores interruption; failed deployments clean up only their own process.

## IBARRA-080 · 2026-10-12 · TCK WAR resources are unavailable to compiled pages

- Symptom: a compiled page reading `/answer.txt` through ServletContext returns 500.
- Minimal repro: PagesContainerTest.extractedResourcesAreVisibleToThePageAndStopPreservesInterruption.
- Cause: the new runner extracts web resources but creates Foy DeployOptions without
  a resource provider for that directory.
- Status: FIXED by a contained filesystem provider supporting streams, URLs,
  directory paths and metadata. Application and server still run as named modules.

## IBARRA-081 · 2026-10-12 · Official Pages clients use a missing REST URL adapter

- Symptom: all three official JspFactory tests error before sending an HTTP request
  with a null TSURLInterface and ClassNotFoundException for the REST SunRIURL class.
- Minimal repro: `./mvnw -Ptck,tck-official -pl ibarra-tck test
  -Dtest=ee.jakarta.tck.pages.api.jakarta_servlet.jsp.jspfactory.URLClientIT`
  without the porting URL system property.
- Cause: the upstream common runtime defaults to the REST TCK URL implementation.
- Status: FIXED by setting `porting.ts.url.class.1` to the unmodified Pages TCK's
  `ee.jakarta.tck.pages.lib.implementation.sun.common.SunRIURL` in Surefire.

## IBARRA-079 · 2026-10-12 · Deferred private-bean methods fail in the Foy application layer

- Symptom: the deferred.jsp HTTP fixture returns 500 when invoking profile.greet
  in an unexported application package with reflection denied.
- Minimal repro: FoyHttpPagesTest deferred JSP/JSPX scenarios, expected A|Direct:call.
- Cause: Peano's API metadata lookup uses the caller thread's context class loader,
  which is the host loader; the child application's accessor provider is missed.
- Status: FIXED by an immutable generated method-expression adapter anchoring the
  page class and scoping metadata/invocation to its loader with finally restoration.
  The adapter retains no PageContext/request and keeps reflection denied.

## IBARRA-076 · 2026-10-12 · Deferred tag attributes are rejected as unsupported metadata

- Symptom: tags declaring deferred-value or deferred-method cannot be translated.
- Minimal repro: deferredValuesRemainLazyAndTypedForSimpleAndClassicTags and
  deferredMethodsUseTypedCompiledLiteralAndVariableTargets.
- Cause: TldParser marks deferred metadata unsupported and no typed expression
  construction path exists for handler setters.
- Status: FIXED by immutable expected type/signature metadata, compiled per-invocation
  expressions and exact ValueExpression/MethodExpression/Object setter selection.

## IBARRA-077 · 2026-10-12 · Mixed deferred values lose literal escaping and local function bindings

- Symptom: mixed attribute literals can double backslashes; named immediate EL
  loses local namespace bindings when it is reparsed through the generic value path.
- Minimal repro: mixedLiteralsPreserveBackslashesAndNamedImmediateFunctionsKeepLocalBindings.
- Cause: reconstructed EL text is passed back through JSP normalization rather than
  retaining normalized literal data and captured function metadata.
- Status: FIXED by direct literal conversion and invocation-owned compiled immediate
  values retaining their creation-time variable/function bindings.

## IBARRA-078 · 2026-10-12 · Empty method literals bypass translation-time return validation

- Symptom: a pure deferred method with String[] return type and an empty literal
  translates, then fails conversion at invocation.
- Minimal repro: deferredTranslationRejectsWrongSyntaxVoidLiteralsAndInvalidCoercion.
- Cause: return-type support is checked only for nonempty strings.
- Status: FIXED by validating supported literal return types before numeric conversion,
  including empty values; void and unsupported targets fail translation.

## IBARRA-075 · 2026-10-11 · Explicit tag bodies lose local namespace bindings

- Symptom: a function prefix declared on jsp:body disappears when its children
  are extracted, causing an unknown-prefix error or using the enclosing binding.
- Minimal repro: the explicit-body cases in
  namedScalarAndFragmentAttributesPreserveLocalNamespaceBindingsAndTrimming.
- Cause: tagInvocation flattens the body wrapper without retaining xmlns metadata.
- Status: FIXED by preserving the wrapper as an internal text container, retaining
  namespace push/pop for classic content and generated SimpleTag body fragments.

## IBARRA-074 · 2026-10-11 · Direct discovery rejects supplied relative resource metadata

- Symptom: a standalone generator with caller-supplied path metadata rejects a
  relative taglib declaration because no descriptor bytes were supplied.
- Minimal repro: TagGenerationTest.relativeTaglibBindingsRetainTheirDeclarationOriginAcrossIncludes.
- Cause: direct resource lookup incorrectly requires the metadata diagnostic path
  to match the supplied binding key, losing the existing resource-map contract.
- Status: FIXED by separating caller-supplied resource metadata from discovered
  aliases; standard relative declarations retain their metadata-only fallback.

## IBARRA-073 · 2026-10-11 · Named-attribute wrappers lose local EL namespace bindings

- Symptom: a function prefix declared on jsp:attribute fails as unknown; flattening
  jsp:text also loses its local namespace override during named-body generation.
- Minimal repro: namedScalarAndFragmentAttributesPreserveLocalNamespaceBindingsAndTrimming.
- Cause: named attributes bypass element namespace push/pop, and namedContent
  flattens jsp:text before evaluating its namespace-bearing wrapper.
- Status: FIXED by scoped wrapper bindings and retained jsp:text nodes with recursive
  source-boundary trimming. Scalar captures and repeatable fragments preserve bindings.

## IBARRA-071 · 2026-10-11 · Forced XML TLD paths are interpreted as URI aliases

- Symptom: `xmlns:f='urn:jsptld:/actual.tld'` resolves an alias for /actual.tld
  rather than reading that descriptor. Direct-path additions may also bind plain
  namespaces in unrelated pages through the shared alias map.
- Minimal repro: forcedXmlPathsBypassAliasesAndDoNotBindPlainNamespacesInOtherPages
  expects a direct sum function and mapped difference function (6|2), but gets 2|2.
- Cause: forced paths and ordinary aliases share one undifferentiated catalogue.
- Status: FIXED by separate immutable alias/resource maps and explicit standard,
  plain XML and forced XML resolution modes. Named XML URIs use plain namespaces;
  forced urn:jsptld URNs require web resource paths.

## IBARRA-072 · 2026-10-11 · Normalized XML nodes lose declaration-only TLD references

- Symptom: a forced TLD namespace on jsp:directive.page is lost before discovery,
  so its listener is missing from the generated application index.
- Minimal repro: forcedDeclarationsOnNormalizedDirectivesRetainExtensionlessResourcesAndListeners.
- Cause: scanning normalized Page nodes misses xmlns carriers removed by normalization.
- Status: FIXED by retaining immutable declaration references from the raw per-file
  parse, with original coordinates, before structural normalization and inclusion.

## IBARRA-069 · 2026-10-11 · Shared-scope useBean creation is rejected

- Symptom: valid session/application useBean declarations with a class fail translation.
- Minimal repro: `<jsp:useBean id='bean' class='example.Bean' scope='application'/>`.
- Cause: generation rejects creation until atomic shared-scope coordination exists;
  type-only lookup also lacks the required shared-namespace coordination.
- Status: FIXED by application-owned reentrant coordination of lookup, direct
  construction and publication. Creator-only bodies execute after unlocking.

## IBARRA-070 · 2026-10-11 · Missing type-only useBean raises the wrong cause

- Symptom: a missing type-only bean raises ServletException directly instead of
  preserving the InstantiationException required by Pages 4.0 section 5.1.
- Minimal repro: `<jsp:useBean id='bean' type='example.Bean'/>` with no scoped bean.
- Cause: the generated missing-bean branch throws the servlet wrapper itself.
- Status: FIXED by raising InstantiationException, which the page error boundary
  preserves as the ServletException cause.

## IBARRA-068 · 2026-10-11 · Direct TLD paths outside discovery need an explicit catalogue alias

- Symptom: a page-relative TLD under /one/lib.tld cannot resolve from its taglib
  declaration unless supplied through web.xml or the generation catalogue.
- Minimal repro: the relative function-binding fixture with /one and /two TLDs
  outside WEB-INF and no explicit web.xml mapping fails library lookup.
- Cause: implicit discovery covers WEB-INF and named-module META-INF resources;
  direct undeclared resources are not separately discovered from page declarations.
- Status: FIXED; directly referenced TLD/JAR resources are read from build-time
  snapshots and their listeners join the generated index. Include origins and
  explicit alias precedence are preserved; unused external TLDs are not scanned.

## IBARRA-067 · 2026-10-11 · Void function signatures escape class-file type conversion

- Symptom: a TLD function returning void throws StringIndexOutOfBoundsException
  while translating an otherwise valid expression.
- Minimal repro: `PagePrecompilerTest.tldFunctionsRenderThroughDirectCallsWithoutRuntimeExpressionCreation`.
- Cause: bean property conversion never needed void and assumes all unrecognized
  descriptors are reference types; nested function types also need binary names.
- Status: FIXED; recognize void and resolve function array/member type names from
  their class-file descriptors before generating direct Java calls.

## IBARRA-066 · 2026-10-11 · Standard ImportHandler rejects abstract/interface page types

- Symptom: a handwritten page's standard ImportHandler throws ELException when
  resolving HttpServletResponse or PageContext from mandatory default packages.
- Minimal repro: import jakarta.servlet.http with Jakarta EL 6.0.1 ImportHandler,
  then resolveClass("HttpServletResponse").
- Cause: the API implementation requires public concrete classes, while Pages
  default imports cover public types, including interfaces/abstract types.
- Status: OPEN upstream API limitation for handwritten-page fallback. Generated
  page imports use direct class literals and are not affected; no API fork is added.

## IBARRA-065 · 2026-10-11 · Handwritten pages omit standard Servlet and Pages EL imports

- Symptom: Cookie and ErrorData resolve to null in a handwritten
  page's EL ImportHandler, although Pages requires the default packages.
- Minimal repro: `PageElIntegrationTest.handwrittenPagesRetainTheStandardDefaultImports`.
- Cause: the fallback StandardELContext imports java.lang only.
- Status: FIXED; register the three Servlet/Pages packages for handwritten pages;
  generated pages retain their static build-time binding tables instead.

## IBARRA-064 · 2026-10-11 · Unavailable imported types escape translation diagnostics

- Symptom: an absent explicit type import throws StringIndexOutOfBoundsException
  instead of a diagnostic at the original directive.
- Minimal repro: `PageGeneratorTest.unavailableImportedTypesKeepDirectiveOrigins`.
- Cause: nested-type binary-name fallback removes every package dot, then read
  assumes a remaining package separator; import origins were not retained.
- Status: FIXED; stop unavailable-name lookup safely and validate explicit imports
  at their directive locations before generating the combined binding table.

## IBARRA-063 · 2026-10-11 · Import switches reference unused preview API types

- Symptom: ordinary generated pages fail javac because implicit java.lang package
  scanning includes StableValue, a preview API unavailable without preview flags.
- Minimal repro: `PageGeneratorTest.defaultExplicitWildcardAndNestedImportsAreVisibleToEl`.
- Cause: wildcard candidates include public preview-marked classes even though
  Ibarra compiles non-preview Java 25 applications.
- Status: FIXED; filter PreviewFeature-marked wildcard classes at build time;
  explicit preview references still follow javac's normal access validation.

## IBARRA-062 · 2026-10-11 · Java compilation can replace auxiliary expression classes

- Symptom: a nested Java declaration overwrites an auxiliary EL class with the
  same binary name, leaving inconsistent generated output.
- Minimal repro: `GeneratedClassTest.javacNestedClassesCannotReplaceAuxiliaryBytecode`.
- Cause: collision checks compare only top-level page names before javac emits
  additional nested or package-level classes.
- Status: FIXED; compare all auxiliary output bytes after javac and reject
  replacement/missing classes before staged publication.

## IBARRA-061 · 2026-10-11 · Child application accessor providers are invisible to EL setup

- Symptom: compiled HTTP EL cannot read a private application bean property;
  the Pages chain falls through to BeanELResolver instead of its direct table.
- Minimal repro: `FoyHttpPagesTest` compiled-el.jspx using app.Profile in a closed
  child module with an AccessorProvider service.
- Cause: provider resolver construction uses the request thread's parent loader.
- Status: FIXED; create the EL context under the page's defining loader and
  restore the caller loader in finally. HTTP passes with peano.reflection=deny;
  the unit test also proves restoration after provider failure.

## IBARRA-059 · 2026-10-11 · Pages EL chain discards provider resolvers

- Symptom: the ExpressionFactory provider's resolver is absent from the Pages
  chain, preventing generated accessor dispatch before BeanELResolver fallback.
- Minimal repro: `PageElIntegrationTest.providerResolversParticipateBeforeBeanFallback`
  returns null instead of the provider's direct value.
- Cause: overriding StandardELContext.getELResolver replaces its provider chain.
- Status: FIXED; retain the standard getStreamELResolver hook after application
  resolvers and before standard property resolvers, without a Peano core dependency.

## IBARRA-060 · 2026-10-11 · Build-time EL failures lose page origins

- Symptom: invalid compiled EL escapes as ELException without the original page
  source line in the translation diagnostic.
- Minimal repro: `PageGeneratorTest.invalidCompiledElKeepsTheOriginalPageLocation`.
- Cause: expression bytecode emission runs after the page traversal without origins.
- Status: FIXED; retain each expression's source location and report compiler
  failures as PageParseException before publishing outputs.

## IBARRA-058 · 2026-10-11 · Static XML named markup rejects nested jsp:text

- Symptom: static named attributes accept literal XML markup but reject jsp:text
  inside that markup even though its contents are template text.
- Minimal repro: `FoyHttpPagesTest` static-named-tags.jspx with
  `<b><jsp:text>label</jsp:text></b>` inside the named label.
- Cause: the template serializer flattens jsp:text only at the named body root.
- Status: FIXED; recursively consume jsp:text as template content, continuing
  to reject active expressions and custom actions.

## IBARRA-057 · 2026-10-11 · Adjacent jsp:text nodes retain named-attribute boundary spaces

- Symptom: a trimmed named attribute containing several jsp:text nodes and
  surrounding indentation retains spaces before and after its literal value.
- Minimal repro: `TagGenerationTest.namedStaticAttributesTrimOnlyTemplateWhitespaceAndAllowQualifiedNames`
  with whitespace-only jsp:text nodes around `hello`.
- Cause: trimming processes only the first/last text node after wrapper flattening.
- Status: FIXED; trim across adjacent boundary text nodes while preserving source
  locations and stopping at actions/expressions so runtime output is untouched.

## IBARRA-056 · 2026-10-11 · Scalar JSP string conversion rejects valid empty values

- Symptom: empty numeric tag attributes fail translation; character attributes
  reject multi-character strings instead of selecting the first character.
- Minimal repro: `TagGenerationTest.jspStringConversionsAcceptEmptyNumbersAndCharacterPrefixes`.
- Cause: Java parsing and an exact-length character check replace the JSP string
  conversions required by Pages 4.0 Table JSP.1-11.
- Status: FIXED; empty numeric/character strings convert to zero, nonempty
  character strings use their first character, for literals and captured bodies.

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

## IBARRA-125 · 2026-10-13 · Collection service registration drops application module reads

- Symptom: an editor-using WAR loses its `java.desktop` read when collection
  accessor services are added after page compilation.
- Minimal repro: `PagesContainerTest.propertyEditorsKeepTheDesktopReadAfterCollectionProviderRegistration`
  combines BeanInfo conversion and a Vector allocation, and inspects its named
  module descriptor. The original reconstruction reports a missing desktop read.
- Cause: the post-page descriptor reconstruction hardcoded three original reads
  instead of retaining the compiled application descriptor's requirements.
- Status: FIXED by preserving existing reads and their static/transitive modifiers
  while merging generated collection services. Application packages stay private.

## IBARRA-126 · 2026-10-13 · Literal BeanInfo metadata bypasses descriptor validity

- Symptom: initial static editor selection accepts inaccessible BeanInfo constructors
  and invalid unused descriptors that JavaBeans metadata construction would reject.
- Minimal repro: `PageGeneratorTest.beanInfoNeedsAnAccessibleConstructorAndValidUnusedDescriptors`.
- Cause: initial literal extraction retained editor bindings without checking
  BeanInfo eligibility and every constructed descriptor's conventional accessors.
- Status: FIXED with public concrete/no-argument BeanInfo validation and matching
  getter/setter checks for all literal descriptors. Unsupported metadata is diagnosed
  at the property action; application classes are never initialized.

## IBARRA-127 · 2026-10-13 · An absent child BeanInfo hides inherited editor metadata

- Symptom: a child bean inherits an edited property but lacks its own BeanInfo;
  generated setProperty silently uses EL coercion instead of applying or diagnosing
  the explicit parent BeanInfo's editor.
- Minimal repro: `PageGeneratorTest.inheritedExplicitBeanInfoIsDiagnosedInsteadOfSilentlyUsingElCoercion`.
- Cause: the missing child BeanInfo path cached an empty editor table without
  checking the bean's superclass chain.
- Status: FIXED by checking superclass class files for explicit BeanInfo before
  caching the ordinary conversion fallback. Inherited metadata remains explicitly
  unsupported and yields a page-location diagnostic; no metadata class is loaded
  or initialized and no runtime introspection fallback is added.
