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


import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RewriteTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.test.RewriteTest.toRecipe;

class ExamplesExtractorTest implements RewriteTest {

    @Test
    void extractJavaExampleWithDefault() {
        ExamplesExtractor examplesExtractor = new ExamplesExtractor();
        // language=java
        rewriteRun(
            spec -> spec.recipe(toRecipe(() -> examplesExtractor))
                .parser(JavaParser.fromJavaVersion()
                    .classpath(JavaParser.runtimeClasspath())
                ),
            java(
                """
                package org.openrewrite.java.cleanup;
                
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
              recipeName: org.openrewrite.java.cleanup.ChainStringBuilderAppendCalls
              examples:
                - description: "Objects concatenation."
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
            java(
                """
                package org.openrewrite.java.cleanup;
                
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
              recipeName: org.openrewrite.java.cleanup.ChainStringBuilderAppendCalls
              examples:
                - description: "Objects concatenation."
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
            java(
                """
                package org.openrewrite.java.cleanup;
                
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
              recipeName: org.openrewrite.java.cleanup.ChainStringBuilderAppendCalls
              examples:
                - description: ""
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
                    .classpath(JavaParser.runtimeClasspath())),
            java(
                """
                    package org.openrewrite.java.cleanup;

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
                recipeName: org.openrewrite.java.cleanup.DeclarationSiteTypeVariance
                examples:
                  - description: ""
                    parameters:
                      - List.of("java.util.function.Function<IN, OUT>")
                      - List.of("java.lang.*")
                      - true
                    sources:
                      - before: |
                          interface In {}
                          interface Out {}
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
                        language: java
                  - description: ""
                    parameters:
                      - List.of("java.util.function.Function<INVARIANT, OUT>")
                      - List.of("java.lang.*")
                      - null
                    sources:
                      - before: |
                          interface In {}
                          interface Out {}
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
                        language: java
                """
        );
    }
}
