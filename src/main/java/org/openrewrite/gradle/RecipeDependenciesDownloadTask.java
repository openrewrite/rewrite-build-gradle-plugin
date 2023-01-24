/*
 * Copyright 2023 the original author or authors.
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

import org.gradle.api.DefaultTask;
import org.gradle.api.file.SourceDirectorySet;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.TaskAction;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipFile;

public class RecipeDependenciesDownloadTask extends DefaultTask {

    @TaskAction
    void download() throws IOException {
        SourceDirectorySet resources = getProject().getExtensions().getByType(JavaPluginExtension.class)
                .getSourceSets()
                .getByName("main")
                .getResources();

        for (File sourceDirectory : resources.getSourceDirectories()) {
            File parserClasspath = new File(sourceDirectory, "META-INF/rewrite/classpath");
            if (!parserClasspath.mkdirs()) {
                throw new IllegalStateException("Unable to create directory " + parserClasspath);
            }
            for (File dependency : getProject().getExtensions().getByType(RecipeDependenciesExtension.class).getResolved()) {
                Path destination = parserClasspath.toPath().resolve(dependency.toPath().getFileName());
                if (Files.exists(destination)) {
                    continue;
                }
                Files.copy(dependency.toPath(), destination);
            }
        }
    }
}
