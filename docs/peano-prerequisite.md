# Temporary Peano source prerequisite

TLD functions require the compatible compiled-function API from
[Peano PR #47](https://codefloe.com/Vidocq/peano/pulls/47), commit
`2268c8c50581fd390b8adca3c21f247a6c1db0d3`.
The root POM pins `peano.version` to `0.4.0-PR47.2268c8c5`.
PR artifacts are installed only in CI's local Maven repository; they are not
published to a remote registry. Both Ibarra workflows therefore check out that
exact source commit and install its reactor at the pinned version before building
Ibarra. No moving branch or unpublished snapshot is used for this prerequisite.

For a local build, clone Peano at that commit into a separate disposable checkout,
then run with Java 25:

```sh
./mvnw -ntp versions:set -DnewVersion=0.4.0-PR47.2268c8c5 \
  -DprocessAllModules=true -DgenerateBackupPoms=false
./mvnw -ntp install -DskipTests
```

Return to the Ibarra checkout and follow its normal build commands.
Peano's own PR validates its full unit suite and official EL TCK; the consumer
bootstrap skips those repeated tests and validates Ibarra instead.

Merge Peano #47 first and wait for its main artifacts to be published. Then merge
the Ibarra function PR. A follow-up PR must restore `peano.version` to the published
main version, remove both bootstrap step pairs and remove this document/link.
Until that cleanup, consumers of an Ibarra artifact also need this local Peano
installation: the pinned PR coordinates are not available remotely.
