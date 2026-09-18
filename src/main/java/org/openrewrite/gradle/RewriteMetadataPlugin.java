/*
 * Copyright 2022 the original author or authors.
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

import nebula.plugin.contacts.Contact;
import nebula.plugin.contacts.ContactsExtension;
import nebula.plugin.contacts.ContactsPlugin;
import nebula.plugin.info.InfoPlugin;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.jvm.tasks.Jar;

public class RewriteMetadataPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getPlugins().apply(ContactsPlugin.class);
        project.getPlugins().apply(InfoPlugin.class);

        // InfoPlugin fills the manifest and META-INF/<module>.properties from a Jar doFirst, after Gradle has
        // decided whether to execute, so none of it reaches the cache key. Gradle's own Jar is
        // @DisableCachingByDefault("Not worth caching"); ShadowJar opts back in, so a cache hit would
        // republish the storing build's provenance.
        project.getTasks().withType(Jar.class).configureEach(task ->
                task.getOutputs().doNotCacheIf(
                        "provenance is injected after the caching decision", t -> true));

        project.getExtensions().configure(ContactsExtension.class, ext -> {
            Contact j = new Contact("team@moderne.io");
            j.moniker("Moderne");
            ext.getPeople().put("team@moderne.io", j);
        });
    }
}
