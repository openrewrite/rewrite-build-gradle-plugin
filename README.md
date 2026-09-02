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

It reads the same two credential properties, and does nothing when they are absent — a fork pull request has no way to
resolve these artifacts, and failing outright would only replace one unhelpful error with another. Nothing is excluded
from the repositories already declared: a plugin classpath is pinned to exact versions, so a hit anywhere is the same
artifact, and excluding `org.openrewrite` from the portal would take the plugin markers with it.

## Publishing

Recipe and language libraries publish only to the Code Genome Project. `org.openrewrite.build.publish-cgp` (applied by
the `recipe-library` and `language-library` plugins) adds the CGP bucket as a publishing repository, and stays inert
unless `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` are set, so only CI publishes.

`org.openrewrite.build.root` no longer stages artifacts to Maven Central. Modules that still have to reach Central
during the transition can apply the deprecated `org.openrewrite.build.publish-maven-central` plugin, which restores the
Sonatype repository and its `closeAndReleaseSonatypeStagingRepository` task.
