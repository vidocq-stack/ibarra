# Official Pages failure inventory

Date: 2026-10-15, Pages 4.0.0; tracked by IBARRA-098 in [BUG.md](../BUG.md).
The latest full suite exercised all 682 official methods across 92 classes: 600
pass and 82 report method-level errors, with no class-level setup errors,
assertion failures or skipped methods. TCK-013 passes 3/3 after IBARRA-144,
TCK-014 passes 14/14 after IBARRA-145, TCK-015 passes 13/13 after IBARRA-146,
and TCK-018 passes 5/5 after IBARRA-143;
TCK-054 passes 7/7 after IBARRA-140, TCK-056 passes 8/8 after IBARRA-141, and
TCK-057 passes 12/12 after IBARRA-142. Twenty-five classes still have method-level
errors, so certification remains pending.
See [BASELINE.md](BASELINE.md) for current counts and historical snapshots. Each
class keeps its previous stable id.
The parallel compiler batch restores literal editor conversion, opaque tagdependent
bodies and invalid precompile handling. The unsafe include-forward prototype was
withheld; the typed dispatch replacement now restores positiveIncludeForwardTest
without bypassing application wrappers (IBARRA-119/130).
The signature/envelope follow-up restores eight more methods without a regression;
static XML prolog/DOCTYPE generation restores four of the six pre-existing output
errors. The XML output class now passes its six previously failing methods; TCK-033 is
resolved. Other causes still need investigation.
Resolved method entries remain as a trace; other causes still need investigation.
Append new ids when discovering new failing classes, without renumbering records.

Reproduce a method with `./run-official-tck-pages-4.0.sh -Dtest=<class>#<method>`;
for deployment errors select the class alone. Add `--no-install --no-prepare`
only after installing the current reactor and preparing official artifacts.

## TCK-001 — ee.jakarta.tck.pages.api.jakarta_el.beanelresolver.URLClientIT

- Date: 2026-10-12.
- Status: RESOLVED (2026-10-14).
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.api.jakarta_el.beanelresolver.URLClientIT#beanElResolverTest`.
- Cause: the Jakarta EL `BeanELResolver` performs reflective JavaBeans access and
  cannot inspect bean classes in the private synthetic application module unless
  their packages are exported to `jakarta.el`. Peano's generated accessor tables
  do not participate in this API resolver.
- Resolution: the adapter now qualified-exports packages with generated bean
  tables only to `jakarta.el`, without unqualified exports or opens, and retains
  the exports when collection providers rewrite the module descriptor.
- Verification: `beanElResolverTest` passes in the official Pages 4.0 runner and
  the direct-resolver `PagesContainerTest` regression passes.

Resolved method:

- `beanElResolverTest`

## TCK-002 — ee.jakarta.tck.pages.api.jakarta_el.compelresolver.URLClientIT

- Date: 2026-10-12.
- Status: RESOLVED; the official class now passes.
- Symptom: the previous class-level deployment failure is removed.
- Minimal repro: select `ee.jakarta.tck.pages.api.jakarta_el.compelresolver.URLClientIT`.
- Cause: descriptor Servlet components lacked generated constructor factories.

Resolved setup error (2026-10-12, IBARRA-100): direct Foy factories are generated
for explicit root web.xml components without application exports/opens.


## TCK-003 — ee.jakarta.tck.pages.api.jakarta_el.methodinfo.URLClientIT

- Date: 2026-10-12.
- Status: RESOLVED; the official class now passes.
- Symptom: the previous official HTTP/signature assertion error is resolved.
- Minimal repro: select `ee.jakarta.tck.pages.api.jakarta_el.methodinfo.URLClientIT`.
- Cause: unqualified Object prevented signature translation (IBARRA-113), then
  Vector lacked direct method metadata (IBARRA-114).

Resolved methods (2026-10-13, direct collection method tables):

- `methodInfoTest`

## TCK-004 — ee.jakarta.tck.pages.api.jakarta_servlet.jsp.jspapplicationcontext.URLClientIT

- Date: 2026-10-12.
- Status: RESOLVED; the official class now passes.
- Symptom: the previous class-level deployment failure is removed.
- Minimal repro: select `ee.jakarta.tck.pages.api.jakarta_servlet.jsp.jspapplicationcontext.URLClientIT`.
- Cause: descriptor Servlet components lacked generated constructor factories.

Resolved setup error (2026-10-12, IBARRA-100): direct Foy factories are generated
for explicit root web.xml components without application exports/opens.


## TCK-005 — ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.bodytagsupport.URLClientIT

- Date: 2026-10-14.
- Status: RESOLVED; the official class now passes.
- Symptom: translation rejected the tag's `TagExtraInfo` scripting-variable metadata.
- Minimal repro: select `ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.bodytagsupport.URLClientIT#bodyTagSupportVariableSynchronizationTest`.
- Cause: TLD parsing discarded the TEI class, and generated classic-tag code did not declare or synchronize `VariableInfo` locals.
- Resolution: Class-File analysis reads literal `VariableInfo` arrays without loading TEI classes; generated classic-tag code synchronizes declared variables at their lifecycle boundaries.
- Verification: the targeted method passes, and all 11 methods in `URLClientIT` pass.

Resolved methods (2026-10-13, direct WAR bean property access; 2026-10-14, TEI variable synchronization):

- `bodyTagSupportEvalBodyBufferedTest`
- `bodyTagSupportSkipBodyTest`
- `bodyTagSupportEvalBodyIncludeTest`
- `bodyTagSupportEmptyTagTest`
- `bodyTagSupportVariableSynchronizationTest`


## TCK-006 — ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.functioninfo.URLClientIT

- Date: 2026-10-14.
- Status: RESOLVED in the targeted 3-method class run; the full aggregate has not been rerun.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.functioninfo.URLClientIT` and any method below.
- Cause: generated `TagInfo` did not link to a `TagLibraryInfo` exposing the TLD's function catalogue, so the TEI could not find the declared `FunctionInfo`.
- Resolution: generated validation metadata now includes the tag library URI, prefix and `FunctionInfo` entries. The targeted class passes 3/3 methods.

Previously failing methods:

- `functionInfoGetFunctionClassTest`
- `functionInfoGetNameTest`
- `functionInfoGetFunctionSignatureTest`

## TCK-007 — ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.jspidconsumer.URLClientIT

- Date: 2026-10-12.
- Status: RESOLVED; all three official methods now pass.
- Symptom: generated tag instances previously had no compiler-provided JSP ID.
- Minimal repro: select `ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.jspidconsumer.URLClientIT`.
- Cause: JspIdConsumer.setJspId was absent from tag property initialization.
- Resolution: IBARRA-086, direct stable per-occurrence identifiers on fresh handlers;
  reserved setter overwrite protection is tracked by IBARRA-104.

Resolved methods (2026-10-12, JspIdConsumer generation):

- `sameJspIdTest`
- `setJspIdTest`
- `multipleJspIdTest`


## TCK-008 — ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.pagedata.URLClientIT

- Date: 2026-10-12; resolved 2026-10-14.
- Status: RESOLVED.
- Symptom: both PageData tests failed because standard-page validators were not invoked with a complete XML view, and standard tag-file attributes/validators were unsupported.
- Minimal repro: select `ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.pagedata.URLClientIT` and any method below.
- Cause: generated page initialization had no TagLibraryValidator invocation or XML view serializer. Standard `.tag` files did not expose `attribute` directives or validate their own PageData, and nested actions rejected declared attributes.
- Fix: TLD validators now receive generated PageData XML for standard JSP pages and standard tag files; `.tag` supports scalar attribute declarations and nested declared tag attributes. The final official targeted run passes 2/2 methods.

Methods failing in the original baseline:

- `pageDataTest`
- `pageDataTagFileTest`

Resolved methods (2026-10-14): both methods above pass.

## TCK-009 — ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.simpletagsupport.URLClientIT

- Date: 2026-10-14 (updated; original baseline 2026-10-12).
- Status: RESOLVED in the targeted 11-method class run; the 682-method aggregate has not been rerun.
- Symptom: the unchanged official class had three method errors. The
  `jspFragmentGetJspContextTest` page failed translation because the fragment
  handler's setter accepts `Object`.
- Cause for the resolved method: the compiler required exact equality between
  the TLD fragment input type `jakarta.servlet.jsp.tagext.JspFragment` and the
  handler setter parameter, although `Object` accepts that value.
- Resolution: fragment setters now accept assignable reference supertypes based
  on build-time class-file metadata. The generated call uses the selected setter
  parameter type. Deferred attribute setters remain exact-type validated.
- Simple `.tag` files now support declared variables and empty nested simple or
  classic custom tags. Variable attributes are isolated around invocation, with
  `NESTED` page-scope values restored after the tag returns. Nested actions with
  attributes or bodies, classic body/exception callbacks, EL, fragments and full
  tag-file metadata remain unsupported.
- Verification: the official class passed 11/11 methods, including
  `simpleTagSupportVariableSynchronizationTest` and
  `simpleTagSupportSkipPageExceptionTest`. Its two wrapper pages now compile and
  propagate both simple `SkipPageException` and classic `SKIP_PAGE` behavior.
  The 2026-10-14 `all` run reports this class at 11/11, within 655 entries across
  92 classes. The prior aggregate had 682 entries; the 27-entry discrepancy and
  comparison are recorded in `ibarra-tck/BASELINE.md`. The fragment setter fix
  remains tracked by `IBARRA-138` in the root `BUG.md`.

Resolved methods:

- `simpleTagSupportVariableSynchronizationTest`
- `simpleTagSupportSkipPageExceptionTest`

## TCK-010 — ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.tagattributeinfo.URLClientIT

- Date: 2026-10-14.
- Status: RESOLVED.
- Cause: generated page initialization did not invoke the tag's `TagExtraInfo.validate(TagData)` method, so the TCK's validation state was never set.
- Fix: generated page initialization now constructs the available `TagInfo` and `TagData` metadata and invokes the TEI validator. The targeted class passes 7/7 methods.

Previously failing methods:

- `tagAttributeInfoIsFragmentTest`
- `tagAttributeInfoIsRequiredTest`
- `tagAttributeInfoToStringTest`
- `tagAttributeInfoGetIdAttribute`
- `tagAttributeInfoGetTypeNameTest`
- `tagAttributeInfoCanBeRequestTimeTest`
- `tagAttributeInfoGetNameTest`

## TCK-011 — ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.tagdata.URLClientIT

- Date: 2026-10-14.
- Status: RESOLVED.
- Cause: generated page initialization did not invoke the tag's `TagExtraInfo.validate(TagData)` method, leaving the TCK's `TagData` validation state unset.
- Fix: generated page initialization now invokes the TEI validator with the tag's static attribute values. The targeted class passes 5/5 methods.

Previously failing methods:

- `tagDataSetAttributeTest`
- `tagDataGetAttributesTest`
- `tagDataGetAttributeTest`
- `tagDataGetAttributeStringTest`
- `tagDataConstructorTest`

## TCK-012 — ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.tagextrainfo.URLClientIT

- Date: 2026-10-14.
- Status: RESOLVED.
- Cause: generated page initialization did not invoke `TagExtraInfo.validate(TagData)`, so the TCK's `TagExtraInfo` validation state was never set.
- Fix: generated page initialization now calls the TEI validator with the available tag metadata. The targeted class passes 1/1 method.

Previously failing methods:

- `tagExtraInfoTest`

## TCK-013 — ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.tagfileinfo.URLClientIT

- Date: 2026-10-12.
- Status: RESOLVED (2026-10-15, IBARRA-144).
- Symptom: the official HTTP/signature assertion reported an error for each method.
- Minimal repro: run
  `./run-official-tck-pages-4.0.sh --no-install --no-prepare
  -Dtest=ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.tagfileinfo.URLClientIT`.
- Cause: the TLD parser discarded each `<tag-file>` path, and the generated
  `TagLibraryInfo` left `tagFiles` unset. No `TagInfo` was created from the
  referenced tag-file directives.
- Fix: retain tag-file paths, then emit `TagFileInfo` and its directive-derived
  `TagInfo` metadata while generating `TagLibraryInfo` at build time.
- Evidence: all three methods pass in the focused class and the full official suite.

Failing methods:

- `tagFileInfoGetTagInfoTest`
- `tagFileInfoGetPathTest`
- `tagFileInfoGetNameTest`

## TCK-014 — ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.taginfo.URLClientIT

- Date: 2026-10-12.
- Status: RESOLVED on 2026-10-15 by IBARRA-145.
- Symptom: the tag's `TagExtraInfo` could not inspect the ordinary TLD tag catalogue,
  and the invocation `TagInfo` omitted descriptive metadata.
- Minimal repro: run `./run-official-tck-pages-4.0.sh --no-install --no-prepare
  -Dtest=ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.taginfo.URLClientIT`.
- Cause: generated `TagLibraryInfo` left its ordinary `tags` array unset; TLD tag
  descriptions, display names and icons were discarded, and invocation metadata
  passed null for those fields.
- Fix: retain TLD tag metadata and generate the declaration-ordered `TagInfo[]`
  with attributes, variables, dynamic-attribute flags and linked TEIs. Pass the
  same descriptive fields to the invocation's `TagInfo`.
- Verification: the focused class passes all 14 methods; the full official run
  includes the fix and passes 14/14.

Previously failing methods:

- `tagInfoGetTagNameTest`
- `tagInfoGetTagLibraryTest`
- `tagInfoGetTagClassNameTest`
- `tagInfoHasDynamicAttributesTest`
- `tagInfoGetTagVariableInfosTest`
- `tagInfoGetDisplayNameTest`
- `tagInfoGetAttributesTest`
- `tagInfoGetTagExtraInfoTest`
- `tagInfoGetSmallIconTest`
- `tagInfoGetBodyContentTest`
- `tagInfoGetLargeIconTest`
- `tagInfoGetInfoStringTest`

## TCK-015 — ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.taglibraryinfo.URLClientIT

- Date: 2026-10-12; fixed 2026-10-15 by IBARRA-146.
- Status: RESOLVED; the focused official class passes 13/13.
- Symptom: generated `TagLibraryInfo` objects omitted TLD header metadata and the
  other libraries imported by the same page translation unit.
- Cause: TLD parsing discarded header fields, and generated instances did not
  initialize the corresponding properties or imported-library catalogue.
- Fix: retain TLD metadata and generate the complete `TagLibraryInfo` set for
  libraries imported by page directives.

Methods still failing after IBARRA-145 and resolved by this fix:

- `tagLibraryInfoGetShortNameTest`
- `tagLibraryInfoGetInfoStringTest`
- `tagLibraryInfoGetRequiredVersionTest`
- `tagLibraryInfoGetReliableURNTest`
- `tagLibraryInfoGetTagLibraryInfosTest`

## TCK-016 — ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.taglibraryvalidator.URLClientIT

- Date: 2026-10-12; resolved 2026-10-14.
- Status: RESOLVED.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.taglibraryvalidator.URLClientIT` and any method below.
- Fix: generated servlet initialization invokes each referenced TLD validator once with PageData XML and fails initialization on validation messages. The final official targeted run passes 2/2 methods.

Methods failing in the original baseline:

- `tagLibraryValidatorAPITest`
- `tagLibraryValidatorTranslationFailureTest`

## TCK-017 — ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.tagsupport.URLClientIT

- Date: 2026-10-12.
- Status: RESOLVED (2026-10-14).
- Symptom: the official `tagsupport.tld` used `<declare>yes</declare>`, and
  precompilation rejected the WAR before any method ran.
- Minimal repro: `./run-official-tck-pages-4.0.sh --no-install --no-prepare
  -Dtest=ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.tagsupport.URLClientIT`.
- Cause: TLD tag-variable `declare` was parsed with a strict `true`/`false`
  boolean helper, although this unchanged official fixture uses the `yes` alias.
- Fix: accept `yes`/`no` only for TLD tag-variable declarations. Other TLD boolean
  fields remain strict.
- Evidence: the unchanged official class passes all 15 methods; the full-suite
  rerun also deploys it successfully.


## TCK-018 — ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.tagvariableinfo.URLClientIT

- Date: 2026-10-12.
- Status: RESOLVED on 2026-10-15; the unchanged class passes 5/5 methods.
- Symptom: all five official HTTP tests errored during translation because the TLD
  declares a variable with `<name-from-attribute>`.
- Minimal repro: select `ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.tagvariableinfo.URLClientIT` and any method below.
- Cause: the TLD parser discarded name-from-attribute variables as unsupported.
  After restoring the descriptor, generated `TagInfo` code also assumed that every
  variable had a non-null `<name-given>` field.
- Fix: retain both TLD name fields, validate the referenced required static string
  attribute, derive the page variable name from its literal invocation value, and
  pass the absent name field as `null` when generating `TagVariableInfo`.
- Verification: `./run-official-tck-pages-4.0.sh --no-install --no-prepare
  -Dtest=ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.tagvariableinfo.URLClientIT`
  passes all five methods.

Previously failing methods:

- `tagVariableInfoGetClassNameTest`
- `tagVariableInfoGetNameFromAttributeTest`
- `tagVariableInfoGetDeclareTest`
- `tagVariableInfoGetScopeTest`
- `tagVariableInfoGetNameGivenTest`

## TCK-019 — ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.trycatchfinally.URLClientIT

- Date: 2026-10-12.
- Status: RESOLVED; the official class now passes.
- Symptom: the verifier's private bean previously failed with reflection fallback denied.
- Minimal repro: select `ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.trycatchfinally.URLClientIT` and any method below.
- Cause: private WAR bean properties had no direct accessor provider (IBARRA-106); handler lifecycle generation remains unchanged.

Resolved methods (2026-10-13, direct WAR bean property access):

- `tryCatchFinallyTest`


## TCK-020 — ee.jakarta.tck.pages.signaturetest.jsp.JSPSigTestIT

- Date: 2026-10-12.
- Status: RESOLVED; the unchanged official signature method passes.
- Symptom: the previous signature driver error is resolved.
- Minimal repro: select `ee.jakarta.tck.pages.signaturetest.jsp.JSPSigTestIT`.
- Cause: an empty API path (IBARRA-092). The official profile now supplies staged
  exact spec APIs and the driver's java.base image, without changing expectations.

Resolved methods (2026-10-13, official signature configuration):

- `signatureTest`

## TCK-021 — ee.jakarta.tck.pages.spec.configuration.general.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the newly runnable official HTTP assertions report errors.
- Minimal repro: select `ee.jakarta.tck.pages.spec.configuration.general.URLClientIT` and any method below.
- Hypothesis: JSP property-group URL mapping/runtime routing remains incomplete; inspect actual HTTP responses before assigning the cause.

Resolved setup error (2026-10-12, IBARRA-100): direct Foy factories are generated
for explicit root web.xml components without application exports/opens.

Failing methods:

- `identicalMappingTest`
- `mostSpecificMappingTest`
- `moreSpecificMappingTest`


## TCK-022 — ee.jakarta.tck.pages.spec.configuration.includes.URLClientIT

- Date: 2026-10-12.
- Status: RESOLVED; the official class now passes.
- Symptom: the previous XML/prelude/coda page assertion error is resolved.
- Minimal repro: select `ee.jakarta.tck.pages.spec.configuration.includes.URLClientIT`.
- Cause: valid XML output omission aliases were rejected (IBARRA-135).

Resolved methods (2026-10-13, XML output omission aliases):

- `jspConfigurationIncludes3Test`

## TCK-023 — ee.jakarta.tck.pages.spec.configuration.scripting.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.spec.configuration.scripting.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

- `jspConfigurationScriptingTrueTest`

## TCK-024 — ee.jakarta.tck.pages.spec.configuration.xml.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.spec.configuration.xml.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

- `jspConfigurationIsXmlTrueTest`

## TCK-025 — ee.jakarta.tck.pages.spec.core_syntax.actions.attribute.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.spec.core_syntax.actions.attribute.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.
- The previous TLD setup blocker was resolved by IBARRA-093/094 on 2026-10-12.

Failing methods:

- `jspAttributeFragmentTest`

## TCK-026 — ee.jakarta.tck.pages.spec.core_syntax.actions.declaration.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.spec.core_syntax.actions.declaration.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

- `jspDeclarationUsageContextTest`

## TCK-027 — ee.jakarta.tck.pages.spec.core_syntax.actions.dobody.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.spec.core_syntax.actions.dobody.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

- `jspDoBodyScopeTest`
- `jspDoBodyJspAttributeTest`
- `jspDoBodyVarReaderTest`
- `jspDoBodyVarTest`
- `positiveJspDoBodyUsageContextTest`

## TCK-028 — ee.jakarta.tck.pages.spec.core_syntax.actions.element.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.spec.core_syntax.actions.element.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

- `jspElementDynamicAttributeTest`
- `jspElementTest`
- `jspElementJspBodyTest`
- `jspElementJspAttributeTest`

## TCK-029 — ee.jakarta.tck.pages.spec.core_syntax.actions.expression.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.spec.core_syntax.actions.expression.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

- `jspExpressionUsageContextTest`

## TCK-030 — ee.jakarta.tck.pages.spec.core_syntax.actions.forward.URLClientIT

- Date: 2026-10-12.
- Status: RESOLVED; all seven official methods now pass.
- Symptom: the previous unbuffered-write forward assertion error is resolved.
- Minimal repro: select `ee.jakarta.tck.pages.spec.core_syntax.actions.forward.URLClientIT`.
- Cause: Ibarra checked Servlet commitment without consulting JSP output state
  (IBARRA-089). Forwarding now rejects unbuffered writes/JSP flushes; the error
  page is included after emitted output, preserving its original exception.

Resolved methods (2026-10-13, JSP forwarding output-state checks):

- `unbufferedWriteForwardTest`

## TCK-031 — ee.jakarta.tck.pages.spec.core_syntax.actions.include.URLClientIT

- Date: 2026-10-12.
- Status: RESOLVED; all twelve official methods now pass.
- Symptom: pending ancestor JSP output previously survived an included page's forward.
- Minimal repro: select `ee.jakarta.tck.pages.spec.core_syntax.actions.include.URLClientIT#positiveIncludeForwardTest`.
- Cause: inclusion wrappers hid ancestor buffers and kept container INCLUDE policy.
  Typed forwarding clears validated JSP buffers, preserves application wrappers and
  propagates completion to generated including pages (IBARRA-119/130).

Resolved methods (2026-10-14, typed include forwarding):

- `positiveIncludeForwardTest`

## TCK-032 — ee.jakarta.tck.pages.spec.core_syntax.actions.invoke.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.spec.core_syntax.actions.invoke.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

- `jspFragmentNullTest`
- `jspInvokeScopeTest`
- `jspInvokeNotInSessionTest`
- `jspInvokeVarTest`
- `jspInvokeVarReaderTest`
- `jspInvokeUsageContextTest`
- `jspInvokeJspAttributeTest`

## TCK-033 — ee.jakarta.tck.pages.spec.core_syntax.actions.output.URLClientIT

- Date: 2026-10-12.
- Status: RESOLVED (2026-10-14); all six previously failing methods pass.
- Symptom: XML tag-file output scenarios reported errors before the static `.tagx`
  subset was added; the pure JSP-document declaration/default/DOCTYPE methods already
  passed.
- Minimal repro: select `ee.jakarta.tck.pages.spec.core_syntax.actions.output.URLClientIT` and any method below.
- Cause: the two remaining methods use XML tag files with static markup and
  `jsp:output` omission metadata (IBARRA-136).

Resolved methods (2026-10-14, static XML output prolog, DOCTYPE and tag-file output):

- `doctypeSystemPublicTest`
- `simpleDefaultTest`
- `doctypeSystemTest`
- `jspOutputJspRootOmitDeclDefaultTest`
- `jspOutputOmitDeclValidValuesTest`
- `jspOutputUsageContextTest`

## TCK-034 — ee.jakarta.tck.pages.spec.core_syntax.actions.param.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.spec.core_syntax.actions.param.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

- `jspParamAttributesTest`

## TCK-035 — ee.jakarta.tck.pages.spec.core_syntax.actions.root.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.spec.core_syntax.actions.root.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

- `jspRootJspBodyTest`
- `jspRootUsageContextTest`
- `jspRootVersionAttrInvalidValueTest`

## TCK-036 — ee.jakarta.tck.pages.spec.core_syntax.actions.scriptlet.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.spec.core_syntax.actions.scriptlet.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

- `jspScriptletUsageContextTest`

## TCK-037 — ee.jakarta.tck.pages.spec.core_syntax.actions.setproperty.URLClientIT

- Date: 2026-10-12.
- Status: RESOLVED; all 22 official methods now pass.
- Symptom: the previous custom property-editor assertion error is resolved.
- Minimal repro: select `ee.jakarta.tck.pages.spec.core_syntax.actions.setproperty.URLClientIT`.
- Cause: literal BeanInfo editor metadata was ignored. Verified class-file
  metadata now selects direct editor construction/conversion (IBARRA-090/125–127).

Resolved methods (2026-10-13, direct literal BeanInfo editors):

- `positiveBeanPropertyEditorTest`

## TCK-038 — ee.jakarta.tck.pages.spec.core_syntax.actions.text.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.spec.core_syntax.actions.text.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

- `jspTextUsageContextTest`

## TCK-039 — ee.jakarta.tck.pages.spec.core_syntax.actions.usebean.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.spec.core_syntax.actions.usebean.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

- `requestTimeBeanNameTest`
- `serBeanNameTest`
- `positiveBeanNameTypeCastTest`

## TCK-040 — ee.jakarta.tck.pages.spec.core_syntax.actions.usebean2.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.spec.core_syntax.actions.usebean2.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

- `inScriptlessTest`

## TCK-041 — ee.jakarta.tck.pages.spec.core_syntax.directives.page.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.spec.core_syntax.directives.page.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

- `positiveExtendsTest`
- `positiveDuplicateExtendsTest`
- `positiveMultipleImportTest`

## TCK-042 — ee.jakarta.tck.pages.spec.core_syntax.scripting.el.URLClientIT

- Date: 2026-10-12.
- Status: RESOLVED; all six official methods now pass.
- Symptom: the previous tagdependent-body translation error is resolved.
- Minimal repro: select `ee.jakarta.tck.pages.spec.core_syntax.scripting.el.URLClientIT`.
- Cause: raw tagdependent EL text was interpreted as JSP. Early TLD contracts
  preserve opaque payload through parsing, encoding and generation (IBARRA-120).
- The previous TLD setup blocker was resolved by IBARRA-093/094 on 2026-10-12.

Resolved methods (2026-10-13, opaque tagdependent bodies):

- `elInvocationAndUsageTest`

Resolved methods (2026-10-13, direct WAR bean property access):

- `elPrimitiveBoxedTypeConversionTest`
- `elBooleanCoercionTest`
- `elNumberCoercionTest`
- `elCharacterCoercionTest`
- `elStringCoercionTest`


## TCK-043 — ee.jakarta.tck.pages.spec.el.jsp.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.spec.el.jsp.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

- `implicitImportTest`
- `allowedDynamicAttributeValueTypesTest`

Resolved methods (2026-10-12, ordinary setter binding):

- `positiveELDeferredValueValueTest`

Resolved methods (2026-10-13, direct collection method tables):

- `positiveELDeferredMethodValueTest`

## TCK-044 — ee.jakarta.tck.pages.spec.el.language.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.spec.el.language.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

- `dotAndIndexOperatorsSameTest`

Resolved methods (2026-10-12, ordinary setter binding):

- `compositeExprEval1Test`
- `compositeExprEval2Test`
- `poundDollarSameMeaningTest`

## TCK-045 — ee.jakarta.tck.pages.spec.jspdocument.elements.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.spec.jspdocument.elements.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

- `negativeJspRootTest`

Resolved methods (2026-10-13, XML output omission aliases):

- `jspRootPrefixTest`

## TCK-046 — ee.jakarta.tck.pages.spec.jspdocument.general.URLClientIT

- Date: 2026-10-12.
- Status: RESOLVED; all official methods in the class now pass.
- Symptom: previous XML output/envelope translation errors are resolved.
- Minimal repro: select `ee.jakarta.tck.pages.spec.jspdocument.general.URLClientIT`.
- Cause: named raw-body envelopes and specified yes/no output values were not
  supported. Attribute/body separation and narrow output validation fix them.
- The previous TLD setup blocker was resolved by IBARRA-093/094 on 2026-10-12.

Resolved methods (2026-10-13, tagdependent envelopes and XML output aliases):

- `invalidPlainURITest`
- `identifyByExtensionTest`
- `identifyByJspRootTest`
- `tagDependentTest`
- `identifyByConfigTest`

## TCK-047 — ee.jakarta.tck.pages.spec.misc.precompilation.URLClientIT

- Date: 2026-10-12.
- Status: RESOLVED; all four official methods now pass.
- Symptom: the previous invalid jsp_precompile response error is resolved.
- Minimal repro: select `ee.jakarta.tck.pages.spec.misc.precompilation.URLClientIT`.
- Cause: generated service did not implement the request protocol (IBARRA-091/118).
  All decoded valid values suppress the body; invalid values send HTTP 500.

Resolved methods (2026-10-13, generated precompile service gate):

- `precompileNegativeTest`

## TCK-048 — ee.jakarta.tck.pages.spec.tagfiles.directives.attribute20.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.spec.tagfiles.directives.attribute20.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

- `defaultRequiredTest`
- `defaultTypeTest`
- `defaultRtexprvalueTest`
- `defaultFragmentTest`

## TCK-049 — ee.jakarta.tck.pages.spec.tagfiles.directives.attribute21.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.spec.tagfiles.directives.attribute21.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

- `defaultDeferredValue1Test`
- `deferredValueTypeNotSpecifiedTest`
- `defaultDeferredMethod1Test`
- `defaultDeferredMethodSignatureTest`

## TCK-050 — ee.jakarta.tck.pages.spec.tagfiles.directives.general.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.spec.tagfiles.directives.general.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

- `positiveIncludePageRelativeTest`
- `positiveTaglibTest`
- `positiveIncludeContextRelativeTest`
- `errorOnELNotFoundFalseTest`

## TCK-051 — ee.jakarta.tck.pages.spec.tagfiles.directives.tag20.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.spec.tagfiles.directives.tag20.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

- `implicitImportHttpTest`
- `positiveImportTest`
- `positiveMultipleImportTest`
- `implicitImportJspTest`
- `dynamicAttributesNoUriTest`
- `positiveDuplicateAttributesTest`
- `implicitImportLangTest`
- `positiveLangTest`
- `implicitImportServletTest`

## TCK-052 — ee.jakarta.tck.pages.spec.tagfiles.directives.tag21.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.spec.tagfiles.directives.tag21.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

- `deferredSyntaxAllowedAsLiteralTrueTemplateTextTest`
- `deferredSyntaxAllowedAsLiteralTrueActionTest`

## TCK-053 — ee.jakarta.tck.pages.spec.tagfiles.directives.variable.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.spec.tagfiles.directives.variable.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

- `syncRemoveAtEndTest`
- `syncRemoveNestedTest`
- `positiveVariableClassTest`
- `syncAtBeginNameFromAttributeTest`
- `syncAtEndTest`
- `positiveDeclareTest`
- `syncRemoveAtBeginTest`
- `syncAtBeginTest`
- `falseDeclareTest`
- `syncNestedTest`

## TCK-054 — ee.jakarta.tck.pages.spec.tagfiles.implicitobjects.URLClientIT

- Date: 2026-10-12.
- Status: FIXED (IBARRA-140).
- Symptom: the class fails during WAR precompilation. Generated tag-file sources
  reference unresolved `application`, `config`, `request`, `response` and
  `session` identifiers.
- Minimal repro: `./run-official-tck-pages-4.0.sh --no-install --no-prepare
  -Dtest=ee.jakarta.tck.pages.spec.tagfiles.implicitobjects.URLClientIT`.
- Cause: the generated standard tag-file class does not bind JSP tag-file
  implicit objects from its JSP context.
- Resolution: generated tag-file methods now obtain `application`, `config`,
  `request`, `response` and `session` from the `PageContext`; HTTP request and
  response are exposed with their HTTP types.
- Latest focused run (2026-10-14): WAR deployment succeeds and all 7 methods
  pass; the latest complete official run confirms 7/7.

Methods passed (7/7):

- `checkApplicationTest`
- `checkSessionTest`
- `checkResponseTest`
- `checkOutTest`
- `checkRequestTest`
- `checkJspContextTest`
- `checkConfigTest`

## TCK-055 — ee.jakarta.tck.pages.spec.tagfiles.packaging.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the newly runnable official HTTP assertions report errors.
- Minimal repro: select `ee.jakarta.tck.pages.spec.tagfiles.packaging.URLClientIT` and any method below.
- Hypothesis: tag-file translation and implicit TLD metadata remain incomplete; inspect retained page diagnostics and HTTP responses before assigning the cause.

Resolved setup error (2026-10-12, IBARRA-102): legacy WAR library bytecode now
assembles into the existing private application module, preserving physical
JAR references and separate build TLD discovery.

Failing methods:

- `tldImplicitTldJspVersionNotMatchTest`
- `implicitTldDefaultJspVersionTest`
- `jspTagFilePackagedWarTldTest`
- `implicitTldReservedName20Test`
- `jspTagFilePackagedWarTest`
- `jspTagFilePackagedJarTest`


## TCK-056 — ee.jakarta.tck.pages.spec.tagfiles.semantics.URLClientIT

- Date: 2026-10-14.
- Status: RESOLVED (IBARRA-141; focused official run).
- Symptom: after the former deployment error for unresolved `request` was fixed,
  five methods still reported HTTP assertion errors for tag-file semantics.
- Minimal repro: `./run-official-tck-pages-4.0.sh --no-install --no-prepare
  -Dtest=ee.jakarta.tck.pages.spec.tagfiles.semantics.URLClientIT`.
- Cause: generated tag files reused the invoking page scope, did not expose
  declared/dynamic attributes through their JSP context and did not handle
  `jsp:forward`; IBARRA-140 separately fixed implicit-object binding.
- Fix: use an isolated `PageContext` wrapper, publish declared and dynamic
  attributes into its page scope, synchronize tag-file variables and stop after
  literal-path forwarding.
- Latest focused run (2026-10-14): all 8 methods pass. The full-suite aggregate
  recorded above predates IBARRA-141 and has not been rerun.

Methods passed (8/8):

- `jspTagSemanticsAttributeNotSpecifiedTest`
- `semanticsInvokeSimpleTagTest`
- `semanticsInvokeClassicTagTest`
- `jspTagSemanticsDynamicAttributesTest`
- `jspTagSemanticsJspContextWrapperScopesTest`
- `jspTagSemanticsDeclaredAttributesTest`
- `semanticsJspForwardTest`
- `jspTagSemanticsJspContextWrapperTest`

## TCK-057 — ee.jakarta.tck.pages.spec.tldres.URLClientIT

- Date: 2026-10-14.
- Status: RESOLVED (IBARRA-142; focused official run).
- Symptom: an absolute URI missing from the tag-library map did not raise a translation
  error when the page did not use its declared prefix.
- Minimal repro: run
  `./run-official-tck-pages-4.0.sh --no-install --no-prepare
  -Dtest=ee.jakarta.tck.pages.spec.tldres.URLClientIT`.
- Cause: `TldReferences.discover` skipped every unforced URI containing a scheme, so
  unused absolute-URI directives escaped validation.
- Fix: resolve absolute URIs against the discovered alias map and report an unmapped
  URI as a page translation error; keep `urn:jsptagdir:` directory references separate.

Resolved setup error (2026-10-12, IBARRA-102): legacy WAR library bytecode now
assembles into the existing private application module, preserving physical
JAR references and separate build TLD discovery.

The focused official run passes all 12 methods, including the formerly failing method:

- `tldResPathAbsUriNotFoundTest`
