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
import org.intellij.lang.annotations.Language;
import org.openrewrite.ExecutionContext;
import org.openrewrite.PathUtils;
import org.openrewrite.SourceFile;
import org.openrewrite.config.RecipeExample;
import org.openrewrite.internal.StringUtils;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;
import org.yaml.snakeyaml.Yaml;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Extract recipe examples from a test file which are annotated with @DocumentExample
 * Output is the content of the yaml file to present examples
 * Format is like:
 * <pre>
 * type: specs.openrewrite.org/v1beta/example
 * recipeName: test.ChangeTextToHello
 * examples:
 *   - description: "Change World to Hello in a text file"
 *     sources:
 *       - before: "World"
 *         after: "Hello!"
 *         path: "1.txt"
 *         language: "text"
 *       - before: "World 2"
 *         after: "Hello 2!"
 *         path: "2.txt"
 *         language: "text"
 *   - description: "Change World to Hello in a java file"
 *     parameters:
 *       - arg0
 *       - arg1
 *     sources:
 *       - before: |
 *           public class A {
 *               void method() {
 *                   System.out.println("World");
 *               }
 *           }
 *         after: |
 *           public class A {
 *               void method() {
 *                   System.out.println("Hello!");
 *               }
 *           }
 *         language: "java"
 * </pre>
 */
public class ExamplesExtractor extends JavaIsoVisitor<ExecutionContext> {

    private static final AnnotationMatcher TEST_ANNOTATION_MATCHER = new AnnotationMatcher("@org.junit.jupiter.api.Test");
    private static final AnnotationMatcher DOCUMENT_EXAMPLE_ANNOTATION_MATCHER = new AnnotationMatcher("@org.openrewrite.DocumentExample");

    private static final MethodMatcher DEFAULTS_METHOD_MATCHER = new MethodMatcher(
            "org.openrewrite.test.RewriteTest defaults(org.openrewrite.test.RecipeSpec)", true);
    private static final MethodMatcher REWRITE_RUN_METHOD_MATCHER_WITH_SPEC =
            new MethodMatcher("org.openrewrite.test.RewriteTest rewriteRun(java.util.function.Consumer, org.openrewrite.test.SourceSpecs[])");
    private static final MethodMatcher REWRITE_RUN_METHOD_MATCHER =
            new MethodMatcher("org.openrewrite.test.RewriteTest rewriteRun(org.openrewrite.test.SourceSpecs[])");

    private static final MethodMatcher ASSERTIONS_METHOD_MATCHER = new MethodMatcher("org.openrewrite.*.Assertions *(..)");
    private static final MethodMatcher BUILD_GRADLE_METHOD_MATCHER = new MethodMatcher("org.openrewrite.gradle.Assertions buildGradle(..)");
    private static final MethodMatcher POM_XML_METHOD_MATCHER = new MethodMatcher("org.openrewrite.maven.Assertions pomXml(..)");
    private static final MethodMatcher ACTIVE_RECIPES_METHOD_MATCHER = new MethodMatcher("org.openrewrite.config.Environment activateRecipes(..)");
    private static final MethodMatcher PATH_METHOD_MATCHER = new MethodMatcher("org.openrewrite.test.SourceSpec path(java.lang.String)");

    private final String recipeType;
    private RecipeNameAndParameters defaultRecipe;
    private RecipeNameAndParameters specifiedRecipe;
    private final List<RecipeExample> recipeExamples;
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
        if (DEFAULTS_METHOD_MATCHER.matches(method.getMethodType())) {
            defaultRecipe = findRecipe(method);
            return method;
        }

        List<J.Annotation> annotations = method.getLeadingAnnotations();
        if (hasNotAnnotation(annotations, TEST_ANNOTATION_MATCHER) ||
                hasNotAnnotation(annotations, DOCUMENT_EXAMPLE_ANNOTATION_MATCHER)) {
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
        }

        return method;
    }

    private static boolean hasNotAnnotation(List<J.Annotation> annotations, AnnotationMatcher matcher) {
        return annotations.stream().noneMatch(matcher::matches);
    }

    public static class YamlPrinter {
        String print(String recipeType,
                     @Nullable RecipeNameAndParameters recipe,
                     boolean usingDefaultRecipe,
                     List<RecipeExample> examples) {
            if (recipe == null ||
                    StringUtils.isNullOrEmpty(recipe.getName()) ||
                    examples.isEmpty()
            ) {
                return "";
            }

            Map<String, Object> data = new LinkedHashMap<>();

            data.put("type", recipeType);
            data.put("recipeName", recipe.getName());
            List<Map<String, Object>> examplesData = new ArrayList<>();


            for (RecipeExample example : examples) {
                Map<String, Object> exampleData = new LinkedHashMap<>();
                example.getDescription();
                exampleData.put("description", example.getDescription());

                List<String> params = usingDefaultRecipe ? recipe.getParameters() : example.getParameters();
                if (params != null && !params.isEmpty()) {
                    exampleData.put("parameters", params);
                }

                List<Map<String, String>> sourcesData = new ArrayList<>();
                for (RecipeExample.Source source : example.getSources()) {

                    Map<String, String> sourceData = new LinkedHashMap<>();
                    if (StringUtils.isNotEmpty(source.getBefore())) {
                        sourceData.put("before", source.getBefore());
                    }

                    if (StringUtils.isNotEmpty(source.getAfter())) {
                        sourceData.put("after", source.getAfter());
                    }

                    if (StringUtils.isNotEmpty(source.getPath())) {
                        sourceData.put("path", PathUtils.separatorsToUnix(source.getPath()));
                    }

                    if (StringUtils.isNotEmpty(source.getLanguage())) {
                        sourceData.put("language", source.getLanguage());
                    }

                    sourcesData.add(sourceData);
                }

                exampleData.put("sources", sourcesData);
                examplesData.add(exampleData);
            }

            data.put("examples", examplesData);
            Yaml yaml = new Yaml();
            return yaml.dumpAsMap(data);
        }
    }

    @Nullable
    private RecipeNameAndParameters findRecipe(J tree) {
        return new JavaIsoVisitor<AtomicReference<RecipeNameAndParameters>>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, AtomicReference<RecipeNameAndParameters> recipe) {
                if (isRecipeSpecRecipeMethod(method)) {
                    new JavaIsoVisitor<AtomicReference<RecipeNameAndParameters>>() {
                        @Override
                        public J.NewClass visitNewClass(J.NewClass newClass, AtomicReference<RecipeNameAndParameters> recipe) {
                            JavaType type = newClass.getClazz() != null ? newClass.getClazz().getType() : null;
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
                                if (arg instanceof J.Literal && ((J.Literal) arg).getValue() != null) {
                                    RecipeNameAndParameters recipeNameAndParameters = new RecipeNameAndParameters();
                                    recipeNameAndParameters.setName(((J.Literal) arg).getValue().toString());
                                    recipe.set(recipeNameAndParameters);
                                }
                                return method;
                            }

                            return super.visitMethodInvocation(method, recipeNameAndParametersAtomicReference);
                        }
                    }.visit(tree, recipe);
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
                J.Literal literal = (J.Literal) arg;
                if (literal.getValue() != null) {
                    return literal.getValue().toString();
                } else {
                    return ((J.Literal) arg).getValueSource();
                }
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
                if (BUILD_GRADLE_METHOD_MATCHER.matches(method)) {
                    source.setPath("build.gradle");
                    language = "groovy";
                } else if (POM_XML_METHOD_MATCHER.matches(method)) {
                    source.setPath("pom.xml");
                    language = "xml";
                } else if (ASSERTIONS_METHOD_MATCHER.matches(method)) {
                    language = method.getSimpleName();
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
                J.Literal after = args.size() > 1 ? (args.get(1) instanceof J.Literal ? (J.Literal) args.get(1) : null) : null;

                if (before != null && before.getValue() != null) {
                    source.setBefore((String) before.getValue());
                }

                if (after != null) {
                    source.setAfter((String) after.getValue());
                }

                if (StringUtils.isNullOrEmpty(source.getPath())) {
                    source.getBefore();
                    source.setPath(getPath(source.getBefore(), language));
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

    @Nullable
    String getPath(@Language("java") @Nullable String content, String language) {
        if (content == null) {
            return null;
        }

        if (language.equals("java")) {
            try {
                Stream<SourceFile> cusStream = JavaParser.fromJavaVersion()
                        .build().parse(content);
                Optional<SourceFile> firstElement = cusStream.findFirst();

                if (firstElement.isPresent()) {
                    return firstElement.get().getSourcePath().toString();
                }
            } catch (Exception e) {
                // do nothing
            }
        }
        return null;
    }

    private static boolean isRecipeSpecRecipeMethod(J.MethodInvocation method) {
        return "recipe".equals(method.getName().getSimpleName()) &&
                method.getSelect() != null &&
                TypeUtils.isOfClassType(method.getSelect().getType(), "org.openrewrite.test.RecipeSpec");
    }
}

