# Publishing

All modules use the Maven group `io.github.ai-agentcore`.

The SDK, collaboration, and Memory plugin are separate release units:

| Package | Release tag | Published artifacts |
| --- | --- | --- |
| SDK | `sdk-v0.1.0` | Parent POM, core, server, Spring AI, LangChain4j and AgentScope |
| Collaboration | `collaboration-v0.1.0` | `agentcore-collaboration` only |
| Memory plugin | `memory-plugin-v0.1.0` | `agentcore-memory-plugin` only |

Installing the SDK does not install collaboration or the Memory plugin. Both
packages depend on a compatible SDK version, but their versions advance independently.

CI verifies the entire repository. Publishing verifies only the selected release
unit. Examples and integration tests are never published. The old combined `v*`
tags no longer trigger publishing.

## Credentials

Configure these repository Actions secrets:

- `MAVEN_CENTRAL_USERNAME`: Central Portal token username.
- `MAVEN_CENTRAL_PASSWORD`: Central Portal token password.
- `GPG_PRIVATE_KEY`: ASCII-armored private signing key.
- `GPG_PASSPHRASE`: signing key passphrase.

Verify the namespace in Central Portal and publish the signing public key to a
supported keyserver. Never commit credentials or private keys.

## Snapshots

Enable SNAPSHOTs for the namespace in Central Portal. Set the selected package's
version to a development version, for example `0.1.1-SNAPSHOT`, following the
version rules below. Then run **Publish packages** manually from `main` in GitHub
Actions and select `sdk`, `collaboration`, or `memory-plugin` in the `package` input.
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

### SDK

Update the root POM version, SDK module parent versions, and the examples and
integration-tests parent versions. Update public documentation examples to match.
Keep collaboration's and the Memory plugin's project versions, parent versions,
and SDK dependencies unchanged when releasing the SDK.

Commit and push a matching tag, for example `sdk-v0.1.0`. This release excludes
collaboration and the Memory plugin and does not build or upload their JARs.

### Collaboration

Update the explicit project version in `collaboration/pom.xml`, and update
`collaboration.version` in the root POM so examples and integration tests use that
version. Leave the root SDK version unchanged.

Keep collaboration's parent POM and explicit `agentcore-sdk` dependency pinned to
compatible, already-published versions. Only update those dependencies when the
collaboration code requires a newer SDK. For example, collaboration `0.1.1` may
continue to use parent and SDK `0.1.0`.

Commit and push a matching tag, for example `collaboration-v0.1.1`. The workflow
builds from `collaboration/pom.xml`, resolves its SDK dependency from Maven, and
uploads only the collaboration artifact. It does not use `-am`, rebuild the SDK,
or republish the parent POM. For the first release, publish the SDK and verify it
is downloadable from Maven Central before publishing collaboration.

In a whole-repository build, Maven uses matching reactor dependencies; if a pinned
collaboration dependency differs from the current SDK version, Maven resolves the
pinned release instead. This also checks compatibility with the declared SDK
baseline rather than silently testing only against unreleased SDK code.

### Memory plugin

Update the explicit project version in `memory-plugin/pom.xml`. Leave the root
SDK version unchanged. Keep the plugin's parent version pinned to a compatible,
already-published SDK release. Its `agentcore-sdk` dependency uses that parent
version, so update the parent only when the plugin requires a newer SDK.

Commit and push a matching tag, for example `memory-plugin-v0.1.0`. The workflow
builds from `memory-plugin/pom.xml` and uploads only `agentcore-memory-plugin`.
It does not use `-am`, rebuild the SDK, or republish the parent POM. Before the
first plugin release, publish SDK `0.1.6` and confirm that its parent POM and
`agentcore-sdk` artifact are downloadable from Maven Central.

### Central validation

The workflow uploads signed artifacts to Central Portal for validation. It does
not automatically publish a final release: review the validated deployment and
publish it in Central Portal. Published release versions cannot be overwritten.

For local packaging checks without signing or uploading:

```bash
# SDK: parent plus five JARs, no collaboration or Memory plugin artifacts.
mvn -B -ntp -Ppublish -Dgpg.skip -pl '!collaboration,!memory-plugin,!integration-tests,!examples' verify

# Collaboration only; requires its declared SDK dependency to be available.
mvn -B -ntp -f collaboration/pom.xml -Ppublish -Dgpg.skip verify

# Memory plugin only; requires its declared SDK dependency to be available.
mvn -B -ntp -f memory-plugin/pom.xml -Ppublish -Dgpg.skip verify
```

For a local first-release check before the SDK is published, install the SDK with
`mvn -B -ntp -pl '!collaboration,!memory-plugin,!integration-tests,!examples' install`, then run the
standalone collaboration or Memory plugin check. The publishing workflow does
not install the SDK locally. It resolves the SDK version consumers receive.
