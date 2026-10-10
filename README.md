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
[![Gradle Plugin Portal](https://img.shields.io/maven-metadata/v/https/plugins.gradle.org/m2/org.openrewrite/rewrite-build-gradle-plugin/maven-metadata.xml.svg?label=gradlePluginPortal)](https://plugins.gradle.org/plugin/org.openrewrite.build.root)
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

In CI, expose the same values as the `ORG_GRADLE_PROJECT_codegenomeUsername` and `ORG_GRADLE_PROJECT_codegenomePassword`
environment variables.

Ordinary builds still fall back to Maven Central when credentials are absent, so fork pull requests keep working, but
a release build (`-Preleasing`) fails at configuration time rather than releasing against whatever versions Maven
Central happens to carry. This project's own build draws `org.openrewrite` artifacts from the Code Genome Project, and
fails the same way when releasing without credentials.

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

Apply `org.openrewrite.build.settings` in `settings.gradle.kts` to add the Code Genome Project there too. It is
published as its own artifact and carries no `org.openrewrite` dependencies, which is what lets it resolve from the
plugin portal alone and then make everything else resolvable:

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

plugins {
    id("org.openrewrite.build.settings") version "latest.release"
}

rootProject.name = "..."
```

It reads the same two credential properties, and adds nothing when they are absent — a fork pull request has no way to
resolve these artifacts, and failing outright would only replace one unhelpful error with another. Nothing is excluded
from the repositories already declared: a plugin classpath is pinned to exact versions, so a hit anywhere is the same
artifact, and excluding `org.openrewrite` from the portal would take the plugin markers with it.

## Artifactory mirror

Maven Central rate limits by address, so builds that resolve from it directly start failing with HTTP 429 once enough
of them have done so from behind the same address. With credentials for Moderne's Artifactory cache, whatever these
plugins have a hand in resolves through https://artifactory.moderne.ninja/artifactory/moderne-cache-3/ instead:

```properties
artifactoryUsername=you@example.com
artifactoryPassword=...
```

CI exports the same thing as the `REWRITE_GRADLE_MIRROR_URL`, `REWRITE_GRADLE_MIRROR_USERNAME` and
`REWRITE_GRADLE_MIRROR_PASSWORD` environment variables, which are read when the properties are absent. With neither —
or with the variables set to secrets that come out empty, as in a fork's pull request — every repository stays where
it was declared.

What gets redirected is any repository pointing at Maven Central (`mavenCentral()`, or `repo.maven.apache.org` and
`repo1.maven.org` spelled out) or at the Gradle Plugin Portal, which answers for anything it does not host itself with
a redirect to Maven Central. Each is repointed where it stands, so it keeps its place in the order and its content
filters, including the Code Genome Project exclusions above. It is also limited to releases, because the mirror
carries snapshots that neither of the two ever does.

- Every `org.openrewrite.build.*` project plugin does this for the repositories of the project it is applied to,
  whether `recipe-repositories` added them or the build script did.
- `org.openrewrite.build.settings` does it for `pluginManagement` and `dependencyResolutionManagement`, and for the
  repositories of every project and of its `buildscript`. Only settings can reach the classpath the plugins themselves
  are resolved onto, which by default comes off the plugin portal.
- `org.openrewrite.build.java-base` (applied by the `recipe-library` and `language-library` plugins) hands every `Test`
  task the three environment variables, so that what tests resolve is mirrored too, along with any build they launch
  that applies these plugins.

Two things are out of reach: the `plugins {}` block of `settings.gradle.kts` itself, which is resolved before the
settings plugin can run, and `buildSrc` or included builds, which are builds of their own.

The mirror takes Maven Central's place rather than being tried ahead of it. A stale password fails the build with a
401, and an artifact the mirror cannot serve is reported as not found, instead of either falling back.

## Publishing

Recipe and language libraries publish only to the Code Genome Project. `org.openrewrite.build.publish-cgp` (applied by
the `recipe-library` and `language-library` plugins) adds the CGP bucket as a publishing repository, and stays inert
unless `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` are set, so only CI publishes.
