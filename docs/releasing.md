# Candidate artifacts and publication

The prepared release version is `0.5.0`; it has not yet been published.
On 2026-09-22 the maintainer deferred independent developer trials and authorized
release after maintainer validation. The trial remains unmeasured, not completed.
A Git tag, a GitHub Release, local
Maven installation, and public Maven repository availability are separate states.

## Local candidate checks

Run `scripts/verify-adoption.ps1` from PowerShell. It uses the repository's Maven
Wrapper, a fresh temporary Maven repository by default, and a separate copy of the
consumer. `-MavenRepository <absolute-path>` may reuse downloads for subsequent runs.

The `candidate` Maven profile attaches sources and Javadoc. The script clean-builds
and installs the parent POM plus the ten reusable library modules, excluding
Playground, then requires 41 files: one parent POM and four files for each library
(POM, main JAR, sources JAR, Javadoc JAR). It copies them to `target/candidate/<version>/`
and records their SHA-256 hashes in `SHA256SUMS`. The clean SDK build removes previous
candidate output before a new bundle is assembled.
The dependency-only starter's source JAR contains its POM; its documentation JAR
explains its dependency and wiring contract because it contains no Java classes.

The standalone consumer has no parent POM or Playground dependency. Its online run
downloads third-party dependencies; its following offline run executes the same
deterministic tests and demonstration. These checks establish local artifact
consumption, not public publication or independent developer adoption. The consumer
exercises Spring AI method registration; it does not constitute a separate external
adoption test of every storage or Spring Boot module.

The configured CI jobs run full SDK verification and a disposable Redis 7.4.2 acceptance service.
A separate adoption job builds the candidate and uploads its unsigned files as a
short-lived workflow artifact. It has read-only repository permissions and no
publishing credentials. Default Maven tests still require no Redis.

The real Redis test class calls `FLUSHDB` before each test. Run it only against
a disposable instance, never a shared or application Redis database.

## Before public publication

1. Review the [adoption record](adoption/2026-09-first-integration.md), unresolved
   limitations, and [changelog](../CHANGELOG.md). Assign one release version to
   the root, all module parents, and documented consumer coordinates in a reviewed change.
2. Run the full Maven Wrapper verify, the real Redis suite from the
   [integration reference](integration-reference.md#redis-result-idempotency),
   and the isolated consumer checks against those exact candidate artifacts.
3. Reconfirm namespace ownership for `io.github.agentpermit4j` (Portal showed Verified
   on 2026-09-22 under the `mat973252-coder` GitHub sign-in), configure a Central
   Portal token in the maintainer's Maven `settings.xml` server named `central`,
   and make the maintainer's signing key available through GPG/agent facilities.
   Never put credentials or signing material in repository files or command output.
4. Verify the security report channel and supported version statement in
   [SECURITY.md](../SECURITY.md). Private reporting was enabled and verified on 2026-09-22.
5. Use both profiles for an intentional publication invocation:

   ```powershell
   .\mvnw.cmd -B -ntp '-Pcandidate,central-publish' -pl '!agent-permit-playground' deploy
   ```

   This command signs and uploads to Central; it is not a local verification command.
   The publisher uses server `central`, excludes Playground and leaves
   `autoPublish=false` so the validated deployment is reviewed in the Portal.
6. After publishing, resolve the released coordinates into a new Maven local
   repository without source installation and run the copied consumer. Record the
   public resolution result and links before marking distribution complete.

Signing and Central upload need the actual maintainer credentials and have not
been validated by the unsigned local candidate checks. No release, tag or public
artifact is created by the adoption script or ordinary CI build.

The configuration follows the official [Central artifact requirements](https://central.sonatype.org/publish/requirements/)
and [Central Maven publisher documentation](https://central.sonatype.org/publish/publish-portal-maven/).
