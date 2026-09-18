# Publishing

All modules use the Maven group `io.github.ai-agentcore`.

The publish workflow tests every module, then publishes the parent POM, core,
server, Spring AI, LangChain4j, AgentScope and collaboration artifacts. Examples
and integration tests are not published.

## Credentials

Configure these repository Actions secrets:

- `MAVEN_CENTRAL_USERNAME`: Central Portal token username.
- `MAVEN_CENTRAL_PASSWORD`: Central Portal token password.
- `GPG_PRIVATE_KEY`: ASCII-armored private signing key.
- `GPG_PASSPHRASE`: signing key passphrase.

Verify the namespace in Central Portal and publish the signing public key to a
supported keyserver. Never commit credentials or private keys.

## Snapshots

Enable SNAPSHOTs for the namespace in Central Portal. Set the POM and intra-project
dependency versions to the next development version, for example `0.1.1-SNAPSHOT`,
then run **Publish packages** manually from `main` in GitHub Actions.
No Git tag is needed. Snapshots are publicly downloadable, mutable, and subject to
the repository's retention policy; they do not undergo release validation.

Consumers must add this repository to their Maven POM:

```xml
<repositories>
  <repository>
    <id>central-portal-snapshots</id>
    <url>https://central.sonatype.com/repository/maven-snapshots/</url>
    <releases><enabled>false</enabled></releases>
    <snapshots><enabled>true</enabled></snapshots>
  </repository>
</repositories>
```

Use the normal artifact coordinates with the published SNAPSHOT version.

## Releases

Update the parent/module versions and intra-project dependency versions, including
the collaboration module's explicit core dependency. Update documentation examples
to match, commit the changes and push a matching tag such as `v0.1.0`.

The workflow uploads signed artifacts to Central Portal for validation. It does
not automatically publish a final release: review the validated deployment and
publish it in Central Portal. Published release versions cannot be overwritten.

For a local packaging check without signing or uploading:

```bash
mvn -B -ntp -Ppublish -Dgpg.skip -pl '!integration-tests,!examples' verify
```
