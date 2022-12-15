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

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.compile.JavaCompile;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;

public class RewriteJava8TextBlocksPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getTasks().withType(JavaCompile.class).getByName("compileJava", task ->
                task.getOptions().getRelease().set((Integer) null));

        project.getTasks().withType(JavaCompile.class).getByName("compileJava", task -> task.doLast(task2 -> {
            for (File out : task2.getOutputs().getFiles()) {
                for (File clazz : project.fileTree(out, tree -> tree.include("**/*.class")).getFiles()) {
                    try(RandomAccessFile file = new RandomAccessFile(clazz, "rw")) {
                        file.seek(6);
                        file.writeShort(52);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            }
        }));
    }
}
