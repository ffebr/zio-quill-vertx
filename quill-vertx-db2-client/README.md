# Vert.x DB2 client corrected binary repack

This module packages the upstream Vert.x DB2 client 4.5.36 with one
corrected CCSID charset constant. It exists to fix native DB2 password
authentication in the Vert.x client. It does not change the DB2 server,
credentials, or authentication configuration.

## Cause and correction

Upstream `CCSIDConstants` advertises EBCDIC CCSID 500 while resolving its
charset as `CP1047`. CCSID 500 is IBM EBCDIC International; the charset name
for it is `CP500`. The mismatch made password authentication fail for a
password containing non-alphanumeric characters. A test account with an
alphanumeric-only password worked, while `!` in the password failed. The same
credentials worked over CLP/TCP, isolating the failure to the native driver's
charset handling rather than a DB2 password or server change.

The replacement source changes only that charset name to `CP500`; the
upstream Apache copyright and license header remain on the patched class.
Upstream Vert.x DB2 client 5.2.3 sources still use `CP1047`, so moving to that
version alone does not resolve this issue.

## How the build packages it

`project/VertxDB2Patch.scala` resolves and verifies the upstream
`io.vertx:vertx-db2-client:4.5.36` jar by SHA-256:

```text
0e42c8d46602908fb2269ebdcc9ffd5cd1496cc39666cbde24ba66460261760e
```

It unpacks the upstream jar's resources and classes, omitting the original
`CCSIDConstants.class`, manifest, and signature files, then packages those
entries together with the replacement class from this module. The result is a
full repack of the upstream client with only that class replaced; it is not a
thin overlay that relies on a second copy of the upstream client at runtime.
Other upstream resources are preserved. The upstream artifact is a provided
build input, while the repacked module is the DB2 client dependency for the
native `quill-vertx-zio` module at runtime. The Java artifact has no dependency
on a Scala version. Its automatic module name is preserved, and it includes
Apache license text and an attribution/change notice.

The local build produces the corrected runtime artifact; nothing has been published.
Keep the upstream pin and checksum aligned: an upstream version change must
review the source fix and jar assembly. Remove this repack and depend directly
on upstream once an upstream release contains the corrected CCSID mapping.

## Verification status

The source correction and pinned jar repack enable the Vert.x DB2 client to
connect using the affected credentials. Full DB2 integration-suite status is
tracked separately and must not be inferred from the authentication fix alone.
The current DB2 LUW 11.5.8.0 corpus passes 21 tests on both Scala 2.12.20 and
2.13.16; see [MULTI-SQL-VALIDATION.md](../docs/vertx/MULTI-SQL-VALIDATION.md).
