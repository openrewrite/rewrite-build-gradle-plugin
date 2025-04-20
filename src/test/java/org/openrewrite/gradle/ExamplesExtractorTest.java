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

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.test.RewriteTest.toRecipe;

class ExamplesExtractorTest implements RewriteTest {

    @Language("java")
    private static final String RECIPE_JAVA_FILE = """
      package org.openrewrite.staticanalysis;

      import org.openrewrite.ExecutionContext;
      import org.openrewrite.Recipe;
      import org.openrewrite.TreeVisitor;
      import org.openrewrite.java.JavaIsoVisitor;
      
      public class ChainStringBuilderAppendCalls extends Recipe {
          @Override
          public String getDisplayName() {
              return "Chain `StringBuilder.append()` calls";
          }

          @Override
          public String getDescription() {
              return "String concatenation within calls to `StringBuilder.append()` causes unnecessary memory allocation. Except for concatenations of String literals, which are joined together at compile time. Replaces inefficient concatenations with chained calls to `StringBuilder.append()`.";
          }

          @Override
          public TreeVisitor<?, ExecutionContext> getVisitor() {
              return new JavaIsoVisitor<>(){
              };
          }
      }
      """;

    @Test
    void extractJavaExampleWithDefault() {
        ExamplesExtractor examplesExtractor = new ExamplesExtractor();
        // language=java
        rewriteRun(
          spec -> spec.recipe(toRecipe(() -> examplesExtractor))
            .parser(JavaParser.fromJavaVersion()
              .classpath(JavaParser.runtimeClasspath())
            ),
          java(RECIPE_JAVA_FILE),
          java(
            """
              package org.openrewrite.staticanalysis;

              import org.junit.jupiter.api.Test;
              import org.openrewrite.Recipe;
              import org.openrewrite.DocumentExample;
              import org.openrewrite.test.RecipeSpec;
              import org.openrewrite.test.RewriteTest;

              import static org.openrewrite.java.Assertions.java;

              class ChainStringBuilderAppendCallsTest implements RewriteTest {
                  @Override
                  public void defaults(RecipeSpec spec) {
                      Recipe recipe = new ChainStringBuilderAppendCalls();
                      spec.recipe(recipe);
                  }

                  @DocumentExample(value = "Objects concatenation.")
                  @Test
                  void objectsConcatenation() {
                      rewriteRun(
                        java(
                          \"""
                            class A {
                                void method1() {
                                    StringBuilder sb = new StringBuilder();
                                    String op = "+";
                                    sb.append("A" + op + "B");
                                    sb.append(1 + op + 2);
                                }
                            }
                            \""",
                          \"""
                            class A {
                                void method1() {
                                    StringBuilder sb = new StringBuilder();
                                    String op = "+";
                                    sb.append("A").append(op).append("B");
                                    sb.append(1).append(op).append(2);
                                }
                            }
                            \"""
                        )
                      );
                  }
              }
              """
          )
        );

        String yaml = examplesExtractor.printRecipeExampleYaml();
        // language=yaml
        assertThat(yaml).isEqualTo(
          """
            type: specs.openrewrite.org/v1beta/example
            recipeName: org.openrewrite.staticanalysis.ChainStringBuilderAppendCalls
            examples:
            - description: Objects concatenation.
              sources:
              - before: |
                  class A {
                      void method1() {
                          StringBuilder sb = new StringBuilder();
                          String op = "+";
                          sb.append("A" + op + "B");
                          sb.append(1 + op + 2);
                      }
                  }
                after: |
                  class A {
                      void method1() {
                          StringBuilder sb = new StringBuilder();
                          String op = "+";
                          sb.append("A").append(op).append("B");
                          sb.append(1).append(op).append(2);
                      }
                  }
                path: A.java
                language: java
            """
        );
    }

    @Test
    void extractJavaExampleRecipeInSpec() {
        ExamplesExtractor examplesExtractor = new ExamplesExtractor();
        // language=java
        rewriteRun(
          spec -> spec.recipe(toRecipe(() -> examplesExtractor))
            .parser(JavaParser.fromJavaVersion()
              .classpath(JavaParser.runtimeClasspath())),
          java(RECIPE_JAVA_FILE),
          java(
            """
              package org.openrewrite.staticanalysis;

              import org.junit.jupiter.api.Test;
              import org.openrewrite.DocumentExample;
              import org.openrewrite.test.RecipeSpec;
              import org.openrewrite.test.RewriteTest;

              import static org.openrewrite.java.Assertions.java;

              class ChainStringBuilderAppendCallsTest implements RewriteTest {

                  @DocumentExample("Objects concatenation.")
                  @Test
                  void objectsConcatenation() {
                      rewriteRun(
                        spec -> spec.recipe(new ChainStringBuilderAppendCalls()),
                        java(
                          \"""
                            class A {
                                void method1() {
                                    StringBuilder sb = new StringBuilder();
                                    String op = "+";
                                    sb.append("A" + op + "B");
                                    sb.append(1 + op + 2);
                                }
                            }
                            \""",
                          \"""
                            class A {
                                void method1() {
                                    StringBuilder sb = new StringBuilder();
                                    String op = "+";
                                    sb.append("A").append(op).append("B");
                                    sb.append(1).append(op).append(2);
                                }
                            }
                            \"""
                        )
                      );
                  }
              }
              """
          )
        );
        String yaml = examplesExtractor.printRecipeExampleYaml();
        // language=yaml
        assertThat(yaml).isEqualTo(
          """
            type: specs.openrewrite.org/v1beta/example
            recipeName: org.openrewrite.staticanalysis.ChainStringBuilderAppendCalls
            examples:
            - description: Objects concatenation.
              sources:
              - before: |
                  class A {
                      void method1() {
                          StringBuilder sb = new StringBuilder();
                          String op = "+";
                          sb.append("A" + op + "B");
                          sb.append(1 + op + 2);
                      }
                  }
                after: |
                  class A {
                      void method1() {
                          StringBuilder sb = new StringBuilder();
                          String op = "+";
                          sb.append("A").append(op).append("B");
                          sb.append(1).append(op).append(2);
                      }
                  }
                path: A.java
                language: java
            """
        );
    }

    @Test
    void extractJavaExampleWithNoDescription() {
        ExamplesExtractor examplesExtractor = new ExamplesExtractor();
        // language=java
        rewriteRun(
          spec -> spec.recipe(toRecipe(() -> examplesExtractor))
            .parser(JavaParser.fromJavaVersion()
              .classpath(JavaParser.runtimeClasspath())),
          java(RECIPE_JAVA_FILE),
          java(
            """
              package org.openrewrite.staticanalysis;

              import org.junit.jupiter.api.Test;
              import org.openrewrite.DocumentExample;
              import org.openrewrite.test.RecipeSpec;
              import org.openrewrite.test.RewriteTest;

              import static org.openrewrite.java.Assertions.java;

              class ChainStringBuilderAppendCallsTest implements RewriteTest {

                  @DocumentExample
                  @Test
                  void objectsConcatenation() {
                      rewriteRun(
                        spec -> spec.recipe(new ChainStringBuilderAppendCalls()),
                        java(
                          \"""
                            class A {
                                void method1() {
                                    StringBuilder sb = new StringBuilder();
                                    String op = "+";
                                    sb.append("A" + op + "B");
                                    sb.append(1 + op + 2);
                                }
                            }
                            \""",
                          \"""
                            class A {
                                void method1() {
                                    StringBuilder sb = new StringBuilder();
                                    String op = "+";
                                    sb.append("A").append(op).append("B");
                                    sb.append(1).append(op).append(2);
                                }
                            }
                            \"""
                        )
                      );
                  }
              }
              """
          )
        );
        String yaml = examplesExtractor.printRecipeExampleYaml();
        // language=yaml
        assertThat(yaml).isEqualTo(
          """
            type: specs.openrewrite.org/v1beta/example
            recipeName: org.openrewrite.staticanalysis.ChainStringBuilderAppendCalls
            examples:
            - description: ''
              sources:
              - before: |
                  class A {
                      void method1() {
                          StringBuilder sb = new StringBuilder();
                          String op = "+";
                          sb.append("A" + op + "B");
                          sb.append(1 + op + 2);
                      }
                  }
                after: |
                  class A {
                      void method1() {
                          StringBuilder sb = new StringBuilder();
                          String op = "+";
                          sb.append("A").append(op).append("B");
                          sb.append(1).append(op).append(2);
                      }
                  }
                path: A.java
                language: java
            """
        );
    }

    @Test
    void extractParameters() {
        ExamplesExtractor examplesExtractor = new ExamplesExtractor();
        // language=java
        rewriteRun(
          spec -> spec.recipe(toRecipe(() -> examplesExtractor))
            .parser(JavaParser.fromJavaVersion()
              .classpath(JavaParser.runtimeClasspath()))
            .typeValidationOptions(TypeValidation.none()),
          java(
            """
              package org.openrewrite.staticanalysis;

              import lombok.EqualsAndHashCode;
              import lombok.Value;
              import org.openrewrite.*;
              import org.openrewrite.internal.lang.NonNullApi;import org.openrewrite.internal.lang.Nullable;
              import org.openrewrite.java.JavaIsoVisitor;

              import java.util.List;

              @Value
              @EqualsAndHashCode(callSuper = true)
              @NonNullApi
              public class DeclarationSiteTypeVariance extends Recipe {

                  @Option(displayName = "Variant types",
                          description = "A list of well-known classes that have in/out type variance.",
                          example = "java.util.function.Function<IN, OUT>")
                  List<String> variantTypes;

                  @Option(displayName = "Excluded bounds",
                          description = "A list of bounds that should not receive explicit variance. Globs supported.",
                          example = "java.lang.*",
                          required = false)
                  @Nullable
                  List<String> excludedBounds;

                  @Option(displayName = "Exclude final classes",
                          description = "If true, do not add `? extends` variance to final classes. " +
                                        "`? super` variance will be added regardless of finality.",
                          required = false)
                  @Nullable
                  Boolean excludeFinalClasses;

                  @Override
                  public TreeVisitor<?, ExecutionContext> getVisitor() {
                      return new JavaIsoVisitor<>() {
                      };
                  }
              }
              """
          ),
          java(
            """
              package org.openrewrite.staticanalysis;

              import org.junit.jupiter.api.Test;
              import org.openrewrite.DocumentExample;
              import org.openrewrite.config.Environment;
              import org.openrewrite.test.RecipeSpec;
              import org.openrewrite.test.RewriteTest;
              import org.openrewrite.test.SourceSpec;

              import java.util.List;

              import static org.assertj.core.api.Assertions.assertThat;
              import static org.openrewrite.java.Assertions.java;

              class DeclarationSiteTypeVarianceTest implements RewriteTest {

                  @Override
                  public void defaults(RecipeSpec spec) {
                      spec.recipe(new DeclarationSiteTypeVariance(
                          List.of("java.util.function.Function<IN, OUT>"),
                          List.of("java.lang.*"),
                          true
                      ));
                  }

                  @DocumentExample
                  @Test
                  void inOutVariance() {
                      rewriteRun(
                          java(
                              \"""
                                interface In {}
                                interface Out {}
                                \"""
                          ),
                          java(
                              \"""
                                import java.util.function.Function;
                                class Test {
                                    void test(Function<In, Out> f) {
                                    }
                                }
                                \""",
                              \"""
                                import java.util.function.Function;
                                class Test {
                                    void test(Function<? super In, ? extends Out> f) {
                                    }
                                }
                                \"""
                          )
                      );
                  }

                  @DocumentExample
                  @Test
                  void invariance() {
                      rewriteRun(
                          spec -> spec.recipe(new DeclarationSiteTypeVariance(
                              List.of("java.util.function.Function<INVARIANT, OUT>"),
                              List.of("java.lang.*"),
                              null
                          )),
                          java(
                              \"""
                                interface In {}
                                interface Out {}
                                \"""
                          ),
                          java(
                              \"""
                                import java.util.function.Function;
                                class Test {
                                    void test(Function<In, Out> f) {
                                    }
                                }
                                \""",
                              \"""
                                import java.util.function.Function;
                                class Test {
                                    void test(Function<In, ? extends Out> f) {
                                    }
                                }
                                \"""
                          )
                      );
                  }
              }
              """
          )
        );
        String yaml = examplesExtractor.printRecipeExampleYaml();
        // language=yaml
        assertThat(yaml).isEqualTo(
          """
            type: specs.openrewrite.org/v1beta/example
            recipeName: org.openrewrite.staticanalysis.DeclarationSiteTypeVariance
            examples:
            - description: ''
              parameters:
              - List.of("java.util.function.Function<IN, OUT>")
              - List.of("java.lang.*")
              - 'true'
              sources:
              - before: |
                  interface In {}
                  interface Out {}
                path: In.java
                language: java
              - before: |
                  import java.util.function.Function;
                  class Test {
                      void test(Function<In, Out> f) {
                      }
                  }
                after: |
                  import java.util.function.Function;
                  class Test {
                      void test(Function<? super In, ? extends Out> f) {
                      }
                  }
                path: Test.java
                language: java
            - description: ''
              parameters:
              - List.of("java.util.function.Function<INVARIANT, OUT>")
              - List.of("java.lang.*")
              - 'null'
              sources:
              - before: |
                  interface In {}
                  interface Out {}
                path: In.java
                language: java
              - before: |
                  import java.util.function.Function;
                  class Test {
                      void test(Function<In, Out> f) {
                      }
                  }
                after: |
                  import java.util.function.Function;
                  class Test {
                      void test(Function<In, ? extends Out> f) {
                      }
                  }
                path: Test.java
                language: java
            """
        );
    }

    @Test
    void extractYamlRecipe() {
        ExamplesExtractor examplesExtractor = new ExamplesExtractor();
        // language=java
        rewriteRun(
          spec -> spec.recipe(toRecipe(() -> examplesExtractor))
            .parser(JavaParser.fromJavaVersion()
              .classpath(JavaParser.runtimeClasspath())),
          java(
            """
              package org.openrewrite.java.migrate.net;

              import org.junit.jupiter.api.Test;
              import org.openrewrite.DocumentExample;
              import org.openrewrite.config.Environment;
              import org.openrewrite.test.RecipeSpec;
              import org.openrewrite.test.RewriteTest;

              import static org.openrewrite.java.Assertions.java;

              class JavaNetAPIsTest implements RewriteTest {
                  @Override
                  public void defaults(RecipeSpec spec) {
                      spec.recipe(
                        Environment.builder()
                          .scanRuntimeClasspath("org.openrewrite.java.migrate.net")
                          .build()
                          .activateRecipes("org.openrewrite.java.migrate.net.JavaNetAPIs"));
                  }

                  @DocumentExample
                  @Test
                  void multicastSocketGetTTLToGetTimeToLive() {
                      //language=java
                      rewriteRun(
                        java(
                          ""\"
                            package org.openrewrite.example;

                            import java.net.MulticastSocket;

                            public class Test {
                                public static void method() {
                                    MulticastSocket s = new MulticastSocket(0);
                                    s.getTTL();
                                }
                            }
                            ""\",
                          ""\"
                            package org.openrewrite.example;

                            import java.net.MulticastSocket;

                            public class Test {
                                public static void method() {
                                    MulticastSocket s = new MulticastSocket(0);
                                    s.getTimeToLive();
                                }
                            }
                            ""\"
                        )
                      );
                  }
              }
              """
          )
        );
        String yaml = examplesExtractor.printRecipeExampleYaml();
        // language=yaml
        assertThat(yaml).isEqualTo(
          """
            type: specs.openrewrite.org/v1beta/example
            recipeName: org.openrewrite.java.migrate.net.JavaNetAPIs
            examples:
            - description: ''
              sources:
              - before: |
                  package org.openrewrite.example;

                  import java.net.MulticastSocket;

                  public class Test {
                      public static void method() {
                          MulticastSocket s = new MulticastSocket(0);
                          s.getTTL();
                      }
                  }
                after: |
                  package org.openrewrite.example;

                  import java.net.MulticastSocket;

                  public class Test {
                      public static void method() {
                          MulticastSocket s = new MulticastSocket(0);
                          s.getTimeToLive();
                      }
                  }
                path: org/openrewrite/example/Test.java
                language: java
            """
        );
    }

    @Disabled("Why this test case fails needs to be analyzed")
    @Test
    void extractPath() {
        ExamplesExtractor examplesExtractor = new ExamplesExtractor();

        // language=java
        rewriteRun(
          spec -> spec.recipe(toRecipe(() -> examplesExtractor))
            .parser(JavaParser.fromJavaVersion()
              .classpath(JavaParser.runtimeClasspath()))
            .typeValidationOptions(TypeValidation.none()),
          java(
            """
              package org.openrewrite.maven;

              import org.junit.jupiter.api.Test;
              import org.openrewrite.DocumentExample;
              import org.openrewrite.test.RecipeSpec;
              import org.openrewrite.test.RewriteTest;
              import org.openrewrite.test.SourceSpecs;

              import static org.openrewrite.maven.Assertions.pomXml;
              import static org.openrewrite.xml.Assertions.xml;

              class AddGradleEnterpriseMavenExtensionTest implements RewriteTest {
                  @Override
                  public void defaults(RecipeSpec spec) {
                      spec.recipe(new AddGradleEnterpriseMavenExtension("1.17", "https://foo", true));
                  }

                  private static final SourceSpecs POM_XML_SOURCE_SPEC = pomXml(
                    ""\"
                      <project>
                          <groupId>com.mycompany.app</groupId>
                          <artifactId>my-app</artifactId>
                          <version>1</version>
                      </project>
                      ""\"
                  );

                  @DocumentExample
                  @Test
                  void addGradleEnterpriseMavenExtensionToExistingExtensionsXmlFile() {
                      rewriteRun(
                        pomXml(
                          ""\"
                            <project>
                                <groupId>com.mycompany.app</groupId>
                                <artifactId>my-app</artifactId>
                                <version>1</version>
                            </project>
                            ""\"
                        ),
                        xml(
                          ""\"
                            <?xml version="1.0" encoding="UTF-8"?>
                            <extensions>
                            </extensions>
                            ""\",
                          ""\"
                            <?xml version="1.0" encoding="UTF-8"?>
                            <extensions>
                              <extension>
                                <groupId>com.gradle</groupId>
                                <artifactId>gradle-enterprise-maven-extension</artifactId>
                                <version>1.17</version>
                              </extension>
                            </extensions>
                            ""\",
                          spec -> spec.path(".mvn/extensions.xml")
                        ),
                        xml(
                          null,
                          ""\"
                            <?xml version="1.0" encoding="UTF-8" standalone="yes" ?>
                            <gradleEnterprise
                                xmlns="https://www.gradle.com/gradle-enterprise-maven" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                xsi:schemaLocation="https://www.gradle.com/gradle-enterprise-maven https://www.gradle.com/schema/gradle-enterprise-maven.xsd">
                              <server>
                                <url>https://foo</url>
                                <allowUntrusted>true</allowUntrusted>
                              </server>
                              <buildScan>
                                <backgroundBuildScanUpload>false</backgroundBuildScanUpload>
                                <publish>ALWAYS</publish>
                              </buildScan>
                            </gradleEnterprise>
                            ""\",
                          spec -> spec.path(".mvn/gradle-enterprise.xml")
                        )
                      );
                  }
              }
              """
          )
        );
        String yaml = examplesExtractor.printRecipeExampleYaml();
        // language=yaml
        assertThat(yaml).isEqualTo(
          """
            type: specs.openrewrite.org/v1beta/example
            recipeName: org.openrewrite.maven.AddGradleEnterpriseMavenExtension
            examples:
            - description: ''
              parameters:
              - '1.17'
              - https://foo
              - 'true'
              sources:
              - before: |
                  <project>
                      <groupId>com.mycompany.app</groupId>
                      <artifactId>my-app</artifactId>
                      <version>1</version>
                  </project>
                path: pom.xml
                language: xml
              - before: |
                  <?xml version="1.0" encoding="UTF-8"?>
                  <extensions>
                  </extensions>
                after: |
                  <?xml version="1.0" encoding="UTF-8"?>
                  <extensions>
                    <extension>
                      <groupId>com.gradle</groupId>
                      <artifactId>gradle-enterprise-maven-extension</artifactId>
                      <version>1.17</version>
                    </extension>
                  </extensions>
                path: .mvn/extensions.xml
                language: xml
              - after: |
                  <?xml version="1.0" encoding="UTF-8" standalone="yes" ?>
                  <gradleEnterprise
                      xmlns="https://www.gradle.com/gradle-enterprise-maven" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                      xsi:schemaLocation="https://www.gradle.com/gradle-enterprise-maven https://www.gradle.com/schema/gradle-enterprise-maven.xsd">
                    <server>
                      <url>https://foo</url>
                      <allowUntrusted>true</allowUntrusted>
                    </server>
                    <buildScan>
                      <backgroundBuildScanUpload>false</backgroundBuildScanUpload>
                      <publish>ALWAYS</publish>
                    </buildScan>
                  </gradleEnterprise>
                path: .mvn/gradle-enterprise.xml
                language: xml
            """
        );
    }

    @Test
    void textBlockAsParameter() {
        ExamplesExtractor examplesExtractor = new ExamplesExtractor();

        // language=java
        rewriteRun(
          spec -> spec.recipe(toRecipe(() -> examplesExtractor))
            .parser(JavaParser.fromJavaVersion()
              .classpath(JavaParser.runtimeClasspath()))
            .typeValidationOptions(TypeValidation.none()),
          java(
            """
              package org.openrewrite.yaml;

              import org.junit.jupiter.api.Disabled;
              import org.junit.jupiter.api.Test;
              import org.openrewrite.Issue;
              import org.openrewrite.DocumentExample;
              import org.openrewrite.test.RewriteTest;

              import static org.openrewrite.yaml.Assertions.yaml;

              class MergeYamlTest implements RewriteTest {

                  @DocumentExample
                  @Test
                  void nonExistentBlock() {
                      rewriteRun(
                        spec -> spec.recipe(new MergeYaml(
                          "$.spec",
                          //language=yaml
                          ""\"
                                                lifecycleRule:
                                                    - action:
                                                          type: Delete
                                                      condition:
                                                          age: 7
                                                ""\",
                          false,
                          null,
                          null
                        )),
                        yaml(
                          ""\"
                                                apiVersion: storage.cnrm.cloud.google.com/v1beta1
                                                kind: StorageBucket
                                                spec:
                                                    bucketPolicyOnly: true
                                                ""\",
                          ""\"
                                                apiVersion: storage.cnrm.cloud.google.com/v1beta1
                                                kind: StorageBucket
                                                spec:
                                                    bucketPolicyOnly: true
                                                    lifecycleRule:
                                                        - action:
                                                              type: Delete
                                                          condition:
                                                              age: 7
                                                ""\"
                        )
                      );
                  }
              }
              """
          )
        );
        String yaml = examplesExtractor.printRecipeExampleYaml();
        // language=yaml
        assertThat(yaml).isEqualTo(
          """
            type: specs.openrewrite.org/v1beta/example
            recipeName: org.openrewrite.yaml.MergeYaml
            examples:
            - description: ''
              parameters:
              - $.spec
              - |
                lifecycleRule:
                    - action:
                          type: Delete
                      condition:
                          age: 7
              - 'false'
              - 'null'
              - 'null'
              sources:
              - before: |
                  apiVersion: storage.cnrm.cloud.google.com/v1beta1
                  kind: StorageBucket
                  spec:
                      bucketPolicyOnly: true
                after: |
                  apiVersion: storage.cnrm.cloud.google.com/v1beta1
                  kind: StorageBucket
                  spec:
                      bucketPolicyOnly: true
                      lifecycleRule:
                          - action:
                                type: Delete
                            condition:
                                age: 7
                language: yaml
            """
        );
    }

    @Test
    void starAsParameter() {
        ExamplesExtractor examplesExtractor = new ExamplesExtractor();

        // language=java
        rewriteRun(
          spec -> spec.recipe(toRecipe(() -> examplesExtractor))
            .parser(JavaParser.fromJavaVersion()
              .classpath(JavaParser.runtimeClasspath()))
            .typeValidationOptions(TypeValidation.none()),
          java(
            """
              package org.openrewrite.gradle;

              import org.junit.jupiter.api.Test;
              import org.junit.jupiter.params.ParameterizedTest;
              import org.junit.jupiter.params.provider.CsvSource;
              import org.openrewrite.DocumentExample;
              import org.openrewrite.test.RewriteTest;

              import static org.openrewrite.gradle.Assertions.buildGradle;

              class ChangeDependencyExtensionTest implements RewriteTest {
                  @DocumentExample
                  @Test
                  void worksWithEmptyStringConfig() {
                      rewriteRun(
                        spec -> spec.recipe(new ChangeDependencyExtension("org.openrewrite", "*", "war", Collections.singletonList("com.jcraft:jsch"))),
                        buildGradle(
                          ""\"
                                plugins {
                                    id 'java-library'
                                }

                                repositories {
                                    mavenCentral()
                                }

                                dependencies {
                                    api 'org.openrewrite:rewrite-gradle:latest.integration@jar'
                                }
                                ""\",
                          ""\"
                                plugins {
                                    id 'java-library'
                                }

                                repositories {
                                    mavenCentral()
                                }

                                dependencies {
                                    api 'org.openrewrite:rewrite-gradle:latest.integration@war'
                                }
                                ""\"
                        )
                      );
                  }
              }
              """
          )
        );
        String yaml = examplesExtractor.printRecipeExampleYaml();
        // language=yaml
        assertThat(yaml).isEqualTo(
          """
            type: specs.openrewrite.org/v1beta/example
            recipeName: org.openrewrite.gradle.ChangeDependencyExtension
            examples:
            - description: ''
              parameters:
              - org.openrewrite
              - '*'
              - war
              - Collections.singletonList("com.jcraft:jsch")
              sources:
              - before: |
                  plugins {
                      id 'java-library'
                  }

                  repositories {
                      mavenCentral()
                  }

                  dependencies {
                      api 'org.openrewrite:rewrite-gradle:latest.integration@jar'
                  }
                after: |
                  plugins {
                      id 'java-library'
                  }

                  repositories {
                      mavenCentral()
                  }

                  dependencies {
                      api 'org.openrewrite:rewrite-gradle:latest.integration@war'
                  }
                path: build.gradle
                language: groovy
            """
        );
    }
}
