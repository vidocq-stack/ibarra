# TagExtraInfo and ahead-of-time Pages translation

Date: 2026-10-13. This records observed official Pages 4.0.0 fixture behaviour,
not an implemented metadata-validation pipeline or a filed TCK challenge.

## Observed contract

The TagAttributeInfo tests declare TagAttributeInfoTEI in their TLD. Its parent,
ee.jakarta.tck.pages.common.util.BaseTCKExtraInfo, sets a shared static flag in
validate(TagData). Each JSP request checks teiWasCalled() and then resets that
flag. The same helper is used by other translation-metadata API fixtures.

The classic BodyTagSupport synchronization fixture's `getVariableInfo` result is a
literal array of `VariableInfo` values; its diagnostic log call does not affect that
result. Ibarra now evaluates that bounded bytecode shape with the Class-File API,
without loading the TEI class, and synchronizes generated locals at the classic
handler lifecycle boundaries.
Other TEI behavior remains incomplete: TagData-dependent metadata, `validate`,
complete TagInfo/TagLibraryInfo context, TagLibraryValidator behavior and TLD
`<variable>` declarations are not supported.

The runner precompiles WARs in the build-side process and serves their unchanged
class bytes in a separate compiler-free named-module process. A static field changed
by build-side validation is not shared with the server; resetting it after requests
makes a single startup replay insufficient as well. This is a concrete fixture
assumption to resolve alongside metadata semantics, not permission to invent a
passing marker or rewrite the official classes/pages.

## Required follow-up

1. Retain complete TLD metadata and provide the complete TagInfo/TagLibraryInfo/
   TagData context expected by TEI and TLV APIs.
2. Extend static variable metadata analysis only where its compile-time behavior can
   be established safely; support validation with diagnostics affecting real
   compilation and without reflective access to private application types.
3. Determine a legitimate official-runner lifecycle/porting solution for translation
   side effects. Keep production ahead-of-time semantics and compiler-free serving.
4. Compare that solution with the specification and TCK portability rules before
   claiming metadata API conformance. File a challenge only if actually pursued.

No request-time TEI replay, fixture-byte transformation, extra opens/exports or
fabricated result has been introduced. The TCK-005 variable synchronization method
now passes; TagInfo/TagAttributeInfo/TagLibraryInfo, validation, dynamic TEI/TLV and
tag-file failures remain in the stable official inventory.
