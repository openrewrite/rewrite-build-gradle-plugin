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

import lombok.Data;
import org.openrewrite.ExecutionContext;
import org.openrewrite.config.RecipeExample;
import org.openrewrite.internal.StringUtils;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Extract recipe examples from a test file which are annotated with @DocumentExample
 * Output is the content of the yaml file to present examples
 * Format is like:
 * <pre>
 *               type: specs.openrewrite.org/v1beta/example
 *               recipeName: test.ChangeTextToHello
 *               examples:
 *                 - description: "Change World to Hello in a text file"
 *                   sources:
 *                     - before: "World"
 *                       after: "Hello!"
 *                       path: "1.txt"
 *                       language: "text"
 *                     - before: "World 2"
 *                       after: "Hello 2!"
 *                       path: "2.txt"
 *                       language: "text"
 *                 - description: "Change World to Hello in a java file"
 *                   parameters:
 *                     - arg0
 *                     - arg1
 *                   sources:
 *                     - before: |
 *                         public class A {
 *                             void method() {
 *                                 System.out.println("World");
 *                             }
 *                         }
 *                       after: |
 *                         public class A {
 *                             void method() {
 *                                 System.out.println("Hello!");
 *                             }
 *                         }
 *                       language: "java"
 * </pre>
 */
public class ExamplesExtractor extends JavaIsoVisitor<ExecutionContext> {

    private static final AnnotationMatcher TEST_ANNOTATION_MATCHER = new AnnotationMatcher("@org.junit.jupiter.api.Test");
    private static final AnnotationMatcher DOCUMENT_EXAMPLE_ANNOTATION_MATCHER = new AnnotationMatcher("@org.openrewrite.DocumentExample");

    private static final MethodMatcher REWRITE_RUN_METHOD_MATCHER_WITH_SPEC =
        new MethodMatcher("org.openrewrite.test.RewriteTest rewriteRun(java.util.function.Consumer, org.openrewrite.test.SourceSpecs[])");
    private static final MethodMatcher REWRITE_RUN_METHOD_MATCHER =
        new MethodMatcher("org.openrewrite.test.RewriteTest rewriteRun(org.openrewrite.test.SourceSpecs[])");

    private static final MethodMatcher JAVA_METHOD_MATCHER = new MethodMatcher("org.openrewrite.java.Assertions java(..)");
    private static final MethodMatcher BUILD_GRADLE_METHOD_MATCHER = new MethodMatcher("org.openrewrite.gradle.Assertions buildGradle(..)");
    private static final MethodMatcher POM_XML_METHOD_MATCHER = new MethodMatcher("org.openrewrite.maven.Assertions pomXml(..)");
    private static final MethodMatcher XML_METHOD_MATCHER = new MethodMatcher("org.openrewrite.xml.Assertions xml(..)");
    private static final MethodMatcher YAML_METHOD_MATCHER = new MethodMatcher("org.openrewrite.yaml.Assertions yaml(..)");
    private static final MethodMatcher PROTOBUF_METHOD_MATCHER = new MethodMatcher("org.openrewrite.protobuf.proto.Assertions proto(..)");
    private static final MethodMatcher PROPERTIES_METHOD_MATCHER = new MethodMatcher("org.openrewrite.properties.Assertions properties(..)");
    private static final MethodMatcher JSON_METHOD_MATCHER = new MethodMatcher("org.openrewrite.json.Assertions json(..)");
    private static final MethodMatcher HCL_METHOD_MATCHER = new MethodMatcher("org.openrewrite.hcl.Assertions hcl(..)");
    private static final MethodMatcher GROOVY_METHOD_MATCHER = new MethodMatcher("org.openrewrite.groovy.Assertions groovy(..)");
    private static final MethodMatcher SPEC_RECIPE_METHOD_MATCHER = new MethodMatcher("org.openrewrite.test.RecipeSpec recipe(..)");
    private static final MethodMatcher ACTIVE_RECIPES_METHOD_MATCHER = new MethodMatcher("org.openrewrite.config.Environment activateRecipes(..)");
    private static final MethodMatcher PATH_METHOD_MATCHER = new MethodMatcher("org.openrewrite.test.SourceSpec path(java.lang.String)");


    private final String recipeType;
    private RecipeNameAndParameters defaultRecipe;
    private RecipeNameAndParameters specifiedRecipe;
    private List<RecipeExample> recipeExamples;
    private String exampleDescription;

    public ExamplesExtractor() {
        recipeType = "specs.openrewrite.org/v1beta/example";
        defaultRecipe = new RecipeNameAndParameters();
        specifiedRecipe = new RecipeNameAndParameters();
        recipeExamples = new ArrayList<>();
        exampleDescription = "";
    }

    /**
     * print the recipe example yaml.
     */
    public String printRecipeExampleYaml() {
        boolean usingDefaultRecipe = !specifiedRecipe.isValid();
        return new ExamplesExtractor.YamlPrinter().print(recipeType,
            usingDefaultRecipe ? defaultRecipe : specifiedRecipe,
            usingDefaultRecipe,
            recipeExamples);
    }

    @Override
    public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
        if (method.getName().getSimpleName().equals("defaults") &&
            method.getMethodType() != null &&
            method.getMethodType().getDeclaringType() != null &&
            !method.getMethodType().getDeclaringType().getInterfaces().isEmpty() &&
            method.getMethodType().getDeclaringType().getInterfaces().get(0).getFullyQualifiedName().equals("org.openrewrite.test.RewriteTest")
        ) {
            defaultRecipe = findRecipe(method);
            return method;
        }

        List<J.Annotation> annotations = method.getLeadingAnnotations();
        if (!hasAnnotation(annotations, TEST_ANNOTATION_MATCHER) ||
            !hasAnnotation(annotations, DOCUMENT_EXAMPLE_ANNOTATION_MATCHER)) {
            return method;
        }

        new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.Annotation visitAnnotation(J.Annotation annotation, ExecutionContext executionContext) {
                if (DOCUMENT_EXAMPLE_ANNOTATION_MATCHER.matches(annotation)) {
                    List<Expression> args = annotation.getArguments();
                    if (args != null && args.size() == 1) {
                        Expression arg = args.get(0);
                        if (arg instanceof J.Assignment) {
                            J.Assignment assignment = (J.Assignment) args.get(0);
                            if (assignment.getAssignment() instanceof J.Literal) {
                                exampleDescription = (String) ((J.Literal) assignment.getAssignment()).getValue();
                            }
                        } else if (arg instanceof J.Literal) {
                            exampleDescription = (String) ((J.Literal) args.get(0)).getValue();
                        }
                    }
                }
                return annotation;
            }
        }.visit(method, ctx);

        return super.visitMethodDeclaration(method, ctx);
    }

    @Override
    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
        List<Expression> args = method.getArguments();
        RecipeExample example = new RecipeExample();
        RecipeNameAndParameters recipe = null;

        int sourceStartIndex;

        if (REWRITE_RUN_METHOD_MATCHER_WITH_SPEC.matches(method)) {
            recipe = findRecipe(args.get(0));
            if (recipe != null) {
                specifiedRecipe = recipe;
            }
            sourceStartIndex = 1;
        } else if (REWRITE_RUN_METHOD_MATCHER.matches(method)) {
            sourceStartIndex = 0;
        } else {
            return method;
        }

        for (int i = sourceStartIndex; i < args.size(); i++) {
            RecipeExample.Source source = extractRecipeExampleSource(args.get(i));
            if (source != null) {
                example.getSources().add(source);
            }
        }

        if (!example.getSources().isEmpty()) {
            example.setDescription(exampleDescription);
            example.setParameters(recipe != null ? recipe.getParameters() :
                defaultRecipe != null ? defaultRecipe.getParameters() : new ArrayList<>());
            this.recipeExamples.add(example);
        } else {
            // System.out.println("Failed to extract an example for method : " + method);
        }

        return method;
    }

    private static boolean hasAnnotation( List<J.Annotation> annotations, AnnotationMatcher matcher) {
        return annotations.stream().anyMatch(matcher::matches);
    }

    public static class YamlPrinter {
        String print(String recipeType,
                     RecipeNameAndParameters recipe,
                     boolean usingDefaultRecipe,
                     List<RecipeExample> examples) {
            if (StringUtils.isNullOrEmpty(recipe.getName()) ||
                examples.isEmpty()
            ) {
                return "";
            }

            StringBuilder output = new StringBuilder();
            output.append("type: ").append(recipeType).append("\n");
            output.append("recipeName: ").append(recipe.getName()).append("\n");
            output.append("examples:\n");

            for (RecipeExample example : examples) {
                output.append("  - description: \"").append(example.getDescription() == null ? "" : example.getDescription()).append("\"\n");
                List<String> params = usingDefaultRecipe ? recipe.getParameters() : example.getParameters();
                if (!params.isEmpty()) {
                    output.append("    parameters:\n");
                    for (String param : params) {
                        output.append("      - ").append(param).append("\n");
                    }
                }

                output.append("    sources:\n");

                boolean isFirst = true;
                String firstPrefix = "      - ";
                String elsePrefix = "        ";

                for (RecipeExample.Source source : example.getSources()) {
                    if (StringUtils.isNotEmpty(source.getBefore())) {
                        isFirst = false;
                        output.append(firstPrefix).append("before: |\n");
                        output.append(indentTextBlock(source.getBefore()));
                    }

                    if (StringUtils.isNotEmpty(source.getAfter())) {
                        String prefix = isFirst ? firstPrefix : elsePrefix;
                        isFirst = false;
                        output.append(prefix).append("after: |\n");
                        output.append(indentTextBlock(source.getAfter()));
                    }

                    if (StringUtils.isNotEmpty(source.getPath())) {
                        output.append(elsePrefix).append("path: ").append(source.getPath()).append("\n");
                    }

                    if (StringUtils.isNotEmpty(source.getLanguage())) {
                        output.append(elsePrefix).append("language: ").append(source.getLanguage()).append("\n");
                    }

                    isFirst = true;
                }
            }
            return output.toString();
        }

        private String indentTextBlock(String text) {
            String str = "          " + text.replace("\n", "\n          ").trim();
            if (!str.endsWith("\n")) {
                str = str + "\n";
            }
            return str;
        }
    }

    private RecipeNameAndParameters findRecipe(J tree) {
        return new JavaIsoVisitor<AtomicReference<RecipeNameAndParameters>>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, AtomicReference<RecipeNameAndParameters> recipe) {
                if (SPEC_RECIPE_METHOD_MATCHER.matches(method)) {
                    new JavaIsoVisitor<AtomicReference<RecipeNameAndParameters>>() {
                        @Override
                        public J.NewClass visitNewClass(J.NewClass newClass, AtomicReference<RecipeNameAndParameters> recipe) {
                            JavaType type = newClass.getClazz().getType();
                            if (type == null) {
                                type = newClass.getType();
                            }

                            if (TypeUtils.isAssignableTo("org.openrewrite.Recipe", type)) {
                                if (type instanceof JavaType.Class) {
                                    JavaType.Class tc = (JavaType.Class) type;
                                    RecipeNameAndParameters recipeNameAndParameters = new RecipeNameAndParameters();
                                    recipeNameAndParameters.setName(tc.getFullyQualifiedName());
                                    recipeNameAndParameters.setParameters(extractParameters(newClass.getArguments()));
                                    recipe.set(recipeNameAndParameters);
                                }
                            }
                            return newClass;
                        }

                        @Override
                        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method,
                                                                        AtomicReference<RecipeNameAndParameters> recipeNameAndParametersAtomicReference) {
                            if (ACTIVE_RECIPES_METHOD_MATCHER.matches(method)) {
                                Expression arg = method.getArguments().get(0);
                                if (arg instanceof J.Literal) {
                                    RecipeNameAndParameters recipeNameAndParameters = new RecipeNameAndParameters();
                                    recipeNameAndParameters.setName(((J.Literal) arg).getValue().toString());
                                    recipe.set(recipeNameAndParameters);
                                }
                                return method;
                            }

                            return super.visitMethodInvocation(method, recipeNameAndParametersAtomicReference);
                        }
                    }.visit(method, recipe);
                }
                return super.visitMethodInvocation(method, recipe);
            }
        }.reduce(tree, new AtomicReference<>()).get();
    }

    private static List<String> extractParameters(List<Expression> args) {
        return args.stream().map(arg -> {
            if (arg instanceof J.Empty) {
                return null;
            } else if (arg instanceof J.Literal) {
                return ((J.Literal) arg).getValueSource();
            } else {
                return arg.toString();
            }
        }).filter(Objects::nonNull).collect(Collectors.toList());
    }

    @Nullable
    private RecipeExample.Source extractRecipeExampleSource(Expression sourceSpecArg) {
        RecipeExample.Source source = new RecipeExample.Source("", null, null, "");

        new JavaIsoVisitor<RecipeExample.Source>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method,
                                                            RecipeExample.Source source) {
                method = super.visitMethodInvocation(method, source);
                String language;
                if (JAVA_METHOD_MATCHER.matches(method)) {
                    language = "java";
                } else if (BUILD_GRADLE_METHOD_MATCHER.matches(method)) {
                    source.setPath("build.gradle");
                    language = "groovy";
                } else if (POM_XML_METHOD_MATCHER.matches(method)) {
                    source.setPath("pom.xml");
                    language = "xml";
                } else if (XML_METHOD_MATCHER.matches(method)) {
                    language = "xml";
                } else if (YAML_METHOD_MATCHER.matches(method)) {
                    language = "yaml";
                } else if (PROTOBUF_METHOD_MATCHER.matches(method)) {
                    language = "protobuf";
                } else if (PROPERTIES_METHOD_MATCHER.matches(method)) {
                    language = "properties";
                } else if (JSON_METHOD_MATCHER.matches(method)) {
                    language = "json";
                } else if (HCL_METHOD_MATCHER.matches(method)) {
                    language = "hcl";
                } else if (GROOVY_METHOD_MATCHER.matches(method)) {
                    language = "groovy";
                } else if (PATH_METHOD_MATCHER.matches(method)) {
                    if (method.getArguments().get(0) instanceof J.Literal) {
                        source.setPath((String) ((J.Literal) method.getArguments().get(0)).getValue());
                    }
                    return method;
                } else {
                    return method;
                }

                source.setLanguage(language);

                List<Expression> args = method.getArguments();

                // arg0 is always `before`. arg1 is optional to be `after`, to adjust if code changed
                J.Literal before = !args.isEmpty() ? (args.get(0) instanceof J.Literal ? (J.Literal) args.get(0) : null) : null;
                J.Literal after = args.size() > 1? (args.get(1) instanceof J.Literal ? (J.Literal) args.get(1) : null) : null;

                if (before != null) {
                    source.setBefore((String) before.getValue());
                }

                if (after != null) {
                    source.setAfter((String) after.getValue());
                }
                return method;
            }
        }.visit(sourceSpecArg, source);

        if (StringUtils.isNotEmpty(source.getBefore()) || StringUtils.isNotEmpty(source.getAfter())) {
            return source;
        } else {
            return null;
        }
    }

    @Data
    private static class RecipeNameAndParameters {
        String name = "";
        List<String> parameters = new ArrayList<>();

        boolean isValid() {
            return StringUtils.isNotEmpty(name);
        }
    }
}

