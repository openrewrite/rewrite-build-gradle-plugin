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
groups, and excludes those same groups from Maven Central, which is no longer a publish target for them. Both halves
only take effect when credentials are present, so builds without them resolve from Maven Central as before.

Set credentials as Gradle properties in `~/.gradle/gradle.properties` — never in a file under source control:

```properties
codegenomeUsername=you@example.com
codegenomePassword=cgp_...
```

In CI, expose the same values as the `ORG_GRADLE_PROJECT_codegenomeUsername` and `ORG_GRADLE_PROJECT_codegenomePassword`
environment variables.
