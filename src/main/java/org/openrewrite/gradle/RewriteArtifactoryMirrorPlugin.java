/*
 * Copyright 2026 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

/**
 * Routes whatever a project would resolve from Maven Central through {@link ArtifactoryMirror} instead, when
 * credentials for it are available. Every other plugin here applies this one, so that holds wherever any of
 * them is applied, and for the repositories a build script declares itself as much as for the ones
 * {@link RewriteDependencyRepositoriesPlugin} adds.
 * <p>
 * It has no say over the classpath of the plugins themselves, which is resolved from the repositories settings
 * declares before any project exists. {@code org.openrewrite.build.settings} covers that.
 */
public class RewriteArtifactoryMirrorPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        ArtifactoryMirror mirror = ArtifactoryMirror.ifAvailable(project.getProviders());
        if (mirror != null) {
            mirror.redirect(project.getRepositories());
        }
    }
}
