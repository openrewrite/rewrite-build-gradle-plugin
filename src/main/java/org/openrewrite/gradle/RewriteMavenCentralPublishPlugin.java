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

import io.github.gradlenexus.publishplugin.NexusPublishExtension;
import io.github.gradlenexus.publishplugin.NexusPublishPlugin;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

import java.net.URI;

/**
 * Stages and releases artifacts to Maven Central through Sonatype's OSSRH.
 *
 * @deprecated Recipes and language libraries publish only to the Code Genome Project. This plugin is
 * no longer applied by {@link RewriteRootProjectPlugin} and remains only for modules that still have
 * to reach Central while they transition; it will be removed.
 */
@Deprecated
public class RewriteMavenCentralPublishPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getLogger().warn("org.openrewrite.build.publish-maven-central is deprecated. " +
                                 "Publish to the Code Genome Project with org.openrewrite.build.publish-cgp instead.");

        project.getPlugins().apply(NexusPublishPlugin.class);

        project.getExtensions().configure(NexusPublishExtension.class, ext ->
                ext.getRepositories().sonatype(nexusRepository -> {
                    nexusRepository.getNexusUrl().set(URI.create("https://ossrh-staging-api.central.sonatype.com/service/local/"));
                    nexusRepository.getSnapshotRepositoryUrl().set(URI.create("https://central.sonatype.com/repository/maven-snapshots/"));
                }));
    }
}
