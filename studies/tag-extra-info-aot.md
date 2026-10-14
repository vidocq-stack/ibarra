# TagExtraInfo and ahead-of-time Pages translation

Date: 2026-10-13. Updated 2026-10-14 with the first generated validation path
and targeted TCK results. This records observed official Pages 4.0.0 fixture
behaviour, not complete metadata support or a conformance claim.

## Observed contract

The TagAttributeInfo tests declare TagAttributeInfoTEI in their TLD. Its parent,
ee.jakarta.tck.pages.common.util.BaseTCKExtraInfo, sets a shared static flag in
validate(TagData). Each JSP request checks teiWasCalled() and then resets that
flag. The same helper is used by other translation-metadata API fixtures.

The classic BodyTagSupport synchronization fixture's `getVariableInfo` result is a
literal array of `VariableInfo` values; its diagnostic log call does not affect that
result. Ibarra now evaluates that bounded bytecode shape with the Class-File API,
without loading the TEI class, and synchronizes generated locals at the classic
handler lifecycle boundaries. Generated page initialization also invokes
`TagExtraInfo.validate` for TEI-bearing tags, passing the available `TagInfo` and
static `TagData` values; request-time values use `TagData.REQUEST_TIME_VALUE`, and
nonempty validation messages fail initialization.

The generated `TagInfo` now links a `TagLibraryInfo` exposing the TLD URI,
prefix and function catalogue as `FunctionInfo` values. Other tag, tag-file and
nested-library catalogues are not populated. `TagLibraryValidator` and complete
TagInfo/TagLibraryInfo context remain unsupported.

The runner precompiles WARs in the build-side process and serves their unchanged
class bytes in a separate compiler-free named-module process. A static field changed
by build-side validation is not shared with the server. For the tested fixtures,
generated servlet initialization invokes the actual TEI validator for each
TEI-bearing tag occurrence in the server process, before `jspInit`, so its state is
available to that page's requests without replaying validation on every request or
fabricating a marker. This is an Ibarra lifecycle adaptation; the official
specification's translation-time validation contract still needs review before
claiming full conformance.

The targeted `tagattributeinfo.URLClientIT` (7/7), `tagdata.URLClientIT` (5/5),
`tagextrainfo.URLClientIT` (1/1) and `functioninfo.URLClientIT` (3/3) runs pass
with this path. FunctionInfo now reaches the TEI through `TagInfo.getTagLibrary()`;
the full suite was not rerun.

## Required follow-up

1. Retain complete TLD metadata and provide the remaining TagInfo/TagLibraryInfo/
   TagData context expected by TEI and TLV APIs, including tag and tag-file catalogues.
2. Verify the servlet-initialization validation adaptation against the specification
   and TCK portability rules; preserve one-time lifecycle semantics and meaningful
   diagnostics without reflective access to private application types.
3. Extend static variable metadata analysis only where its compile-time behavior can
   be established safely.
4. File a challenge only if comparison with the specification shows an actual TCK
   portability issue and that path is pursued.

No per-request TEI replay, fixture-byte transformation, extra opens/exports or
fabricated result has been introduced. The TCK-005 variable synchronization method
and targeted TCK-006/-010/-011/-012 classes now pass. Function metadata is available
to TEI; complete TagInfo/TagLibraryInfo, TagLibraryValidator and tag-file failures
remain in the official inventory. The full suite was not rerun for these targeted
fixes.
