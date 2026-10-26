<p align="center">
  <a href="https://docs.openrewrite.org">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://github.com/openrewrite/rewrite/raw/main/doc/logo-oss-dark.svg">
      <source media="(prefers-color-scheme: light)" srcset="https://github.com/openrewrite/rewrite/raw/main/doc/logo-oss-light.svg">
      <img alt="OpenRewrite Logo" src="https://github.com/openrewrite/rewrite/raw/main/doc/logo-oss-light.svg" width='600px'>
    </picture>
  </a>
</p>

<div align="center">
  <h1>rewrite-build-gradle-plugin</h1>
</div>

<div align="center">

<!-- Keep the gap above this line, otherwise they won't render correctly! -->
[![ci](https://github.com/openrewrite/rewrite-build-gradle-plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/openrewrite/rewrite-build-gradle-plugin/actions/workflows/ci.yml)
[![Apache 2.0](https://img.shields.io/github/license/openrewrite/rewrite-build-gradle-plugin.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Contributing Guide](https://img.shields.io/badge/Contributing-Guide-informational)](https://github.com/openrewrite/.github/blob/main/CONTRIBUTING.md)
</div>

## What is this?

This project provides a Gradle plugin that provides common build opinions to repositories in the openrewrite GitHub organization.organization's source code.

## Code Genome Project artifacts

`org.openrewrite.build.recipe-repositories` (applied by the `recipe-library` and `language-library` plugins) adds
https://artifacts.codegenomeproject.org/maven as a dependency repository for the `org.openrewrite` and `io.moderne`
groups, and excludes those same groups from Maven Central, to avoid downloading older versions.

Set credentials as Gradle properties in `~/.gradle/gradle.properties` — never in a file under source control:

```properties
codegenomeUsername=you@example.com
codegenomePassword=cgp_...
```

`codeGenomeUsername` and `codeGenomeToken`, the names docs.openrewrite.org and the Code Genome Project's own setup
instructions give, work too.

In CI, expose the same values as the `ORG_GRADLE_PROJECT_codegenomeUsername` and `ORG_GRADLE_PROJECT_codegenomePassword`
environment variables.

There is no fallback to Maven Central, which stopped receiving new `org.openrewrite` releases: without credentials,
resolving a dynamic version such as `latest.release` fails with a 401 from the Code Genome Project. This project's own
build resolves `org.openrewrite` artifacts the same way.

### Settings

`recipe-repositories` covers a project's own dependencies. It cannot cover the plugins' dependencies: a plugin's
classpath is resolved from the repositories *settings* declares, before any project exists. Those default to the
Gradle Plugin Portal, which proxies Maven Central, so once recipe and language libraries stopped publishing there,
applying any of these plugins started failing on the `org.openrewrite` artifacts they are built on:

```
> Could not find org.openrewrite:rewrite-core:8.91.4.
    Searched in the following locations:
      - https://plugins.gradle.org/m2/org/openrewrite/rewrite-core/8.91.4/rewrite-core-8.91.4.pom
```

These plugins are published to the Code Genome Project, so declaring it in `pluginManagement` resolves the plugins and
everything they are built on:

```kotlin
pluginManagement {
    repositories {
        maven {
            name = "codegenome"
            url = uri("https://artifacts.codegenomeproject.org/maven")
            credentials {
                username = providers.gradleProperty("codegenomeUsername").get()
                password = providers.gradleProperty("codegenomePassword").get()
            }
            content {
                includeGroupAndSubgroups("org.openrewrite")
                includeGroupAndSubgroups("io.moderne")
            }
        }
        gradlePluginPortal()
    }
}
```

## Artifactory mirror

Maven Central rate limits by address, so builds that resolve from it directly start failing with HTTP 429 once enough
of them have done so from behind the same address. With credentials for Moderne's Artifactory cache, whatever these
plugins have a hand in resolves through https://artifactory.moderne.ninja/artifactory/moderne-cache-3/ instead:

```properties
artifactoryUsername=you@example.com
artifactoryPassword=...
```

CI exports the same thing as the `MAVEN_CENTRAL_MIRROR_URL`, `MAVEN_CENTRAL_MIRROR_USERNAME` and
`MAVEN_CENTRAL_MIRROR_PASSWORD` environment variables, which are read when the properties are absent. With neither —
or with the variables set to secrets that come out empty, as in a fork's pull request — every repository stays where
it was declared.

What gets redirected is any repository pointing at Maven Central (`mavenCentral()`, or `repo.maven.apache.org` and
`repo1.maven.org` spelled out) or at the Gradle Plugin Portal, which answers for anything it does not host itself with
a redirect to Maven Central. Each is repointed where it stands, so it keeps its place in the order and its content
filters, including the Code Genome Project exclusions above. It is also limited to releases, because the mirror
carries snapshots that neither of the two ever does.

- Every `org.openrewrite.build.*` project plugin does this for the repositories of the project it is applied to,
  whether `recipe-repositories` added them or the build script did.
- `org.openrewrite.build.java-base` (applied by the `recipe-library` and `language-library` plugins) hands every `Test`
  task the three environment variables, so that what tests resolve is mirrored too, along with any build they launch
  that applies these plugins.

Anything resolved before a project exists is out of reach: the classpath the plugins themselves are resolved onto,
which comes from the `pluginManagement` repositories and by default off the plugin portal, and `buildSrc` or included
builds, which are builds of their own. To have the plugin classpath mirrored too, redirect the plugin portal in
`pluginManagement` the way this project's own `settings.gradle.kts` does.

The mirror takes Maven Central's place rather than being tried ahead of it. A stale password fails the build with a
401, and an artifact the mirror cannot serve is reported as not found, instead of either falling back.

## Test retries

`org.openrewrite.build.java-base` (applied by the `recipe-library` and `language-library` plugins) applies Gradle's
[test-retry plugin](https://github.com/gradle/test-retry-gradle-plugin) to every `Test` task. On CI, wherever the `CI`
environment variable is set, as it is on the Code Genome Project, a test that fails runs once more in a new test JVM,
and fails the task only if it fails again. When ten or more of a task's tests fail, none of them runs again: that is a
break, not a flaky test. On a developer's machine nothing runs again, so a flaky test shows itself to whoever is
working on it.

A test that failed and then passed appears twice in the JUnit XML report, as two `testcase`s with the same name, the
failed one first. The retries run once the task's other tests are done, so they come after the rest of their class. A
parameterized test is retried whole: all of its invocations run again, the ones that passed too.

A build can change either number for a task, or turn retries off:

```kotlin
tasks.named<Test>("test") {
    retry {
        maxRetries = 0
    }
}
```

## Publishing

Recipe and language libraries publish only to the Code Genome Project. `org.openrewrite.build.publish-cgp` (applied by
the `recipe-library` and `language-library` plugins) adds the CGP bucket as a publishing repository, and stays inert
unless `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` are set, so only CI publishes.

This project's own plugins, releases and snapshots alike, are published only to the Code Genome Project. Releases up
to 2.27.4 remain on the Gradle Plugin Portal, but nothing newer is published there.
