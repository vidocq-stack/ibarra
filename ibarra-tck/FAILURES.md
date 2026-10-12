# Official Pages failure inventory

Date: 2026-10-12, Pages 4.0.0; tracked by IBARRA-098 in [BUG.md](../BUG.md).
This inventory records the remaining 194 method errors and no setup errors
after legacy WAR library and JspIdConsumer generation. All 682 official methods now execute. Each class keeps its previous stable id.
Resolved method entries remain as a trace; other causes still need investigation.
Append new ids when discovering new failing classes, without renumbering records.

Reproduce a method with `./run-official-tck-pages-4.0.sh -Dtest=<class>#<method>`;
for deployment errors select the class alone. Add `--no-install --no-prepare`
only after installing the current reactor and preparing official artifacts.

## TCK-001 — ee.jakarta.tck.pages.api.jakarta_el.beanelresolver.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.api.jakarta_el.beanelresolver.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

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
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.api.jakarta_el.methodinfo.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

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

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.bodytagsupport.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

- `bodyTagSupportEvalBodyBufferedTest`
- `bodyTagSupportVariableSynchronizationTest`
- `bodyTagSupportSkipBodyTest`
- `bodyTagSupportEvalBodyIncludeTest`
- `bodyTagSupportEmptyTagTest`

## TCK-006 — ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.functioninfo.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.functioninfo.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

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

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.pagedata.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

- `pageDataTest`
- `pageDataTagFileTest`

## TCK-009 — ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.simpletagsupport.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.simpletagsupport.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

- `simpleTagSupportVariableSynchronizationTest`
- `simpleTagSupportSkipPageExceptionTest`
- `jspFragmentGetJspContextTest`

## TCK-010 — ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.tagattributeinfo.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.tagattributeinfo.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

- `tagAttributeInfoIsFragmentTest`
- `tagAttributeInfoIsRequiredTest`
- `tagAttributeInfoToStringTest`
- `tagAttributeInfoGetIdAttribute`
- `tagAttributeInfoGetTypeNameTest`
- `tagAttributeInfoCanBeRequestTimeTest`
- `tagAttributeInfoGetNameTest`

## TCK-011 — ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.tagdata.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.tagdata.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

- `tagDataSetAttributeTest`
- `tagDataGetAttributesTest`
- `tagDataGetAttributeTest`
- `tagDataGetAttributeStringTest`
- `tagDataConstructorTest`

## TCK-012 — ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.tagextrainfo.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.tagextrainfo.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

- `tagExtraInfoTest`

## TCK-013 — ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.tagfileinfo.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.tagfileinfo.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

- `tagFileInfoGetTagInfoTest`
- `tagFileInfoGetPathTest`
- `tagFileInfoGetNameTest`

## TCK-014 — ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.taginfo.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.taginfo.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

- `tagInfoGetTagNameTest`
- `tagInfoGetTagLibraryTest`
- `tagInfoGetTagClassNameTest`
- `tagInfoHasDynamicAttributesTest`
- `tagInfoGetTagVariableInfosTest`
- `tagInfoSetTagExtraInfoTest`
- `tagInfoGetDisplayNameTest`
- `tagInfoGetAttributesTest`
- `tagInfoGetTagExtraInfoTest`
- `tagInfoGetSmallIconTest`
- `tagInfoGetBodyContentTest`
- `tagInfoGetLargeIconTest`
- `tagInfoSetTagLibraryTest`
- `tagInfoGetInfoStringTest`

## TCK-015 — ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.taglibraryinfo.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.taglibraryinfo.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

- `tagLibraryInfoGetFunctionTest`
- `tagLibraryInfoGetTagTest`
- `tagLibraryInfoGetPrefixStringTest`
- `tagLibraryInfoGetURITest`
- `tagLibraryInfoGetShortNameTest`
- `tagLibraryInfoGetTagFilesTest`
- `tagLibraryInfoGetInfoStringTest`
- `tagLibraryInfoGetRequiredVersionTest`
- `tagLibraryInfoGetReliableURNTest`
- `tagLibraryInfoGetFunctionsTest`
- `tagLibraryInfoGetTagLibraryInfosTest`
- `tagLibraryInfoGetTagFileTest`
- `tagLibraryInfoGetTagsTest`

## TCK-016 — ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.taglibraryvalidator.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.taglibraryvalidator.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

- `tagLibraryValidatorAPITest`
- `tagLibraryValidatorTranslationFailureTest`

## TCK-017 — ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.tagsupport.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.tagsupport.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

- `tagSupportDoEndTagSkipPageTest`
- `tagSupportDoStartEvalBodyIncludeTest`
- `tagSupportDoEndTagEvalPageTest`
- `tagSupportDoStartSkipBodyTest`
- `tagSupportDoAfterBodySkipBodyTest`
- `tagSupportVariableSynchronizationTest`
- `tagSupportDoAfterBodyEvalBodyAgainTest`

## TCK-018 — ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.tagvariableinfo.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.tagvariableinfo.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

- `tagVariableInfoGetClassNameTest`
- `tagVariableInfoGetNameFromAttributeTest`
- `tagVariableInfoGetDeclareTest`
- `tagVariableInfoGetScopeTest`
- `tagVariableInfoGetNameGivenTest`

## TCK-019 — ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.trycatchfinally.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.api.jakarta_servlet.jsp.tagext.trycatchfinally.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

- `tryCatchFinallyTest`

## TCK-020 — ee.jakarta.tck.pages.signaturetest.jsp.JSPSigTestIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.signaturetest.jsp.JSPSigTestIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

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
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.spec.configuration.includes.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

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
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.spec.core_syntax.actions.forward.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

- `unbufferedWriteForwardTest`

## TCK-031 — ee.jakarta.tck.pages.spec.core_syntax.actions.include.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.spec.core_syntax.actions.include.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

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
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.spec.core_syntax.actions.output.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

- `doctypeSystemPublicTest`
- `jspOutputOmitDeclValidValuesTest`
- `simpleDefaultTest`
- `doctypeSystemTest`
- `jspOutputJspRootOmitDeclDefaultTest`
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
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.spec.core_syntax.actions.setproperty.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

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
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.spec.core_syntax.scripting.el.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.
- The previous TLD setup blocker was resolved by IBARRA-093/094 on 2026-10-12.

Failing methods:

- `elInvocationAndUsageTest`
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

- `positiveELDeferredMethodValueTest`
- `implicitImportTest`
- `allowedDynamicAttributeValueTypesTest`

Resolved methods (2026-10-12, ordinary setter binding):

- `positiveELDeferredValueValueTest`

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

- `jspRootPrefixTest`
- `negativeJspRootTest`

## TCK-046 — ee.jakarta.tck.pages.spec.jspdocument.general.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.spec.jspdocument.general.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.
- The previous TLD setup blocker was resolved by IBARRA-093/094 on 2026-10-12.

Failing methods:

- `invalidPlainURITest`
- `identifyByExtensionTest`
- `identifyByJspRootTest`
- `tagDependentTest`
- `identifyByConfigTest`

## TCK-047 — ee.jakarta.tck.pages.spec.misc.precompilation.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.spec.misc.precompilation.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

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
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.spec.tagfiles.implicitobjects.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

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

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the official HTTP/signature assertion reported an error.
- Minimal repro: select `ee.jakarta.tck.pages.spec.tagfiles.semantics.URLClientIT` and any method below.
- Hypothesis: an unsupported page/tag/API scenario or a response mismatch; inspect translation-errors.txt and server.log before assigning a root cause.

Failing methods:

- `jspTagSemanticsAttributeNotSpecifiedTest`
- `semanticsInvokeSimpleTagTest`
- `semanticsInvokeClassicTagTest`
- `jspTagSemanticsDynamicAttributesTest`
- `jspTagSemanticsJspContextWrapperScopesTest`
- `jspTagSemanticsDeclaredAttributesTest`
- `semanticsJspForwardTest`
- `jspTagSemanticsJspContextWrapperTest`

## TCK-057 — ee.jakarta.tck.pages.spec.tldres.URLClientIT

- Date: 2026-10-12.
- Status: OPEN.
- Symptom: the newly runnable official HTTP assertions report errors.
- Minimal repro: select `ee.jakarta.tck.pages.spec.tldres.URLClientIT` and any method below.
- Hypothesis: the missing absolute tag-library URI translation/error response is incorrect; inspect retained page diagnostics and HTTP responses before assigning the cause.

Resolved setup error (2026-10-12, IBARRA-102): legacy WAR library bytecode now
assembles into the existing private application module, preserving physical
JAR references and separate build TLD discovery.

Failing methods:

- `tldResPathAbsUriNotFoundTest`
