/*
 * (c) Copyright 2016 Palantir Technologies Inc. All rights reserved.
 */

package com.palantir.conjure.gen.java.types;


import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableSet;
import com.palantir.conjure.defs.types.BaseObjectTypeDefinition;
import com.palantir.conjure.defs.types.TypesDefinition;
import com.palantir.conjure.defs.types.builtin.BinaryType;
import com.palantir.conjure.defs.types.collect.ListType;
import com.palantir.conjure.defs.types.collect.MapType;
import com.palantir.conjure.defs.types.collect.SetType;
import com.palantir.conjure.defs.types.complex.EnumTypeDefinition;
import com.palantir.conjure.defs.types.complex.ErrorTypeDefinition;
import com.palantir.conjure.defs.types.complex.FieldDefinition;
import com.palantir.conjure.defs.types.complex.ObjectTypeDefinition;
import com.palantir.conjure.defs.types.complex.UnionTypeDefinition;
import com.palantir.conjure.defs.types.names.ConjurePackage;
import com.palantir.conjure.defs.types.names.ConjurePackages;
import com.palantir.conjure.defs.types.names.FieldName;
import com.palantir.conjure.defs.types.names.TypeName;
import com.palantir.conjure.defs.types.reference.AliasTypeDefinition;
import com.palantir.conjure.gen.java.ConjureAnnotations;
import com.palantir.conjure.gen.java.ExperimentalFeatures;
import com.palantir.conjure.gen.java.Settings;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeSpec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.Modifier;
import org.apache.commons.lang3.StringUtils;
import org.immutables.value.Value;

public final class BeanGenerator implements TypeGenerator {

    private final Set<ExperimentalFeatures> enabledExperimentalFeatures;
    private final Settings settings;

    /** The maximum number of parameters for which a static factory method is generated in addition to the builder. */
    private static final int MAX_NUM_PARAMS_FOR_FACTORY = 3;

    /** The name of the singleton instance field generated for empty objects. */
    private static final String SINGLETON_INSTANCE_NAME = "INSTANCE";

    public BeanGenerator(Settings settings) {
        this(settings, ImmutableSet.of());
    }

    public BeanGenerator(Settings settings, Set<ExperimentalFeatures> enabledExperimentalFeatures) {
        this.settings = settings;
        this.enabledExperimentalFeatures = ImmutableSet.copyOf(enabledExperimentalFeatures);
    }

    @Override
    public JavaFile generateObjectType(
            TypesDefinition types,
            Optional<ConjurePackage> defaultPackage,
            TypeName typeName,
            BaseObjectTypeDefinition typeDef) {
        TypeMapper typeMapper = new TypeMapper(types);
        if (typeDef instanceof ObjectTypeDefinition) {
            return generateBeanType(typeMapper, defaultPackage, typeName, (ObjectTypeDefinition) typeDef);
        } else if (typeDef instanceof UnionTypeDefinition) {
            return UnionGenerator.generateUnionType(
                    typeMapper, defaultPackage, typeName, (UnionTypeDefinition) typeDef);
        } else if (typeDef instanceof EnumTypeDefinition) {
            return EnumGenerator.generateEnumType(
                    defaultPackage, typeName, (EnumTypeDefinition) typeDef, settings.supportUnknownEnumValues());
        } else if (typeDef instanceof AliasTypeDefinition) {
            return AliasGenerator.generateAliasType(
                    typeMapper, defaultPackage, typeName, (AliasTypeDefinition) typeDef);
        }
        throw new IllegalArgumentException("Unknown object definition type " + typeDef.getClass());
    }

    @Override
    public Set<JavaFile> generateErrorTypes(
            TypesDefinition allTypes,
            Optional<ConjurePackage> defaultPackage,
            Map<TypeName, ErrorTypeDefinition> errorTypeNameToDef) {
        if (errorTypeNameToDef.isEmpty()) {
            return Collections.emptySet();
        }

        requireExperimentalFeature(ExperimentalFeatures.ErrorTypes);

        TypeMapper typeMapper = new TypeMapper(allTypes);
        return ErrorGenerator.generateErrorTypes(typeMapper, defaultPackage, errorTypeNameToDef);
    }

    private void requireExperimentalFeature(ExperimentalFeatures feature) {
        if (!enabledExperimentalFeatures.contains(feature)) {
            throw new ExperimentalFeatureDisabledException(feature);
        }
    }

    private JavaFile generateBeanType(
            TypeMapper typeMapper,
            Optional<ConjurePackage> defaultPackage,
            TypeName typeName,
            ObjectTypeDefinition typeDef) {

        ConjurePackage typePackage = ConjurePackages.getPackage(typeDef.conjurePackage(), defaultPackage, typeName);
        ClassName objectClass = ClassName.get(typePackage.name(), typeName.name());
        ClassName builderClass = ClassName.get(objectClass.packageName(), objectClass.simpleName(), "Builder");

        Collection<EnrichedField> fields = createFields(typeMapper, typeDef.fields());
        Collection<FieldSpec> poetFields = EnrichedField.toPoetSpecs(fields);
        Collection<FieldSpec> nonPrimitivePoetFields = Collections2.filter(poetFields, f -> !f.type.isPrimitive());

        TypeSpec.Builder typeBuilder = TypeSpec.classBuilder(typeName.name())
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addFields(poetFields)
                .addMethod(createConstructor(fields, nonPrimitivePoetFields))
                .addMethods(createGetters(fields));

        if (!poetFields.isEmpty()) {
            typeBuilder
                .addMethod(MethodSpecs.createEquals(objectClass))
                .addMethod(MethodSpecs.createEqualTo(objectClass, poetFields))
                .addMethod(MethodSpecs.createHashCode(poetFields));
        }

        typeBuilder
                .addMethod(MethodSpecs.createToString(typeName.name(), poetFields));

        if (poetFields.size() <= MAX_NUM_PARAMS_FOR_FACTORY) {
            typeBuilder.addMethod(createStaticFactoryMethod(poetFields, objectClass));
        }

        if (!nonPrimitivePoetFields.isEmpty()) {
            typeBuilder
                    .addMethod(createValidateFields(nonPrimitivePoetFields))
                    .addMethod(createAddFieldIfMissing(nonPrimitivePoetFields.size()));
        }

        if (poetFields.isEmpty()) {
            // Need to add JsonSerialize annotation which indicates that the empty bean serializer should be used to
            // serialize this class. Without this annotation no serializer will be set for this class, thus preventing
            // serialization.
            // See https://github.palantir.build/foundry/conjure/pull/444.
            typeBuilder
                    .addAnnotation(JsonSerialize.class)
                    .addField(createSingletonField(objectClass));
        } else {
            typeBuilder
                    .addAnnotation(AnnotationSpec.builder(JsonDeserialize.class)
                            .addMember("builder", "$T.class", builderClass).build())
                    .addMethod(createBuilder(builderClass))
                    .addType(BeanBuilderGenerator.generate(
                            typeMapper, objectClass, builderClass, typeDef, settings.ignoreUnknownProperties()));
        }

        typeBuilder.addAnnotation(ConjureAnnotations.getConjureGeneratedAnnotation(BeanGenerator.class));

        typeDef.docs().ifPresent(docs -> typeBuilder.addJavadoc("$L", StringUtils.appendIfMissing(docs, "\n")));

        return JavaFile.builder(typePackage.name(), typeBuilder.build())
                .skipJavaLangImports(true)
                .indent("    ")
                .build();
    }

    private static FieldSpec createSingletonField(ClassName objectClass) {
        return FieldSpec
                .builder(objectClass, SINGLETON_INSTANCE_NAME, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("new $T()", objectClass)
                .build();
    }

    private static Collection<EnrichedField> createFields(
            TypeMapper typeMapper, Map<FieldName, FieldDefinition> fields) {
        return fields.entrySet().stream()
                .map(e -> EnrichedField.of(e.getKey().name(), e.getValue(), FieldSpec.builder(
                        typeMapper.getClassName(e.getValue().type()),
                        e.getKey().toCase(FieldName.Case.LOWER_CAMEL_CASE).name(),
                        Modifier.PRIVATE, Modifier.FINAL)
                        .build()))
                .collect(Collectors.toList());
    }

    private static MethodSpec createConstructor(
            Collection<EnrichedField> fields,
            Collection<FieldSpec> nonPrimitivePoetFields) {
        MethodSpec.Builder builder = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PRIVATE);

        if (!nonPrimitivePoetFields.isEmpty()) {
            builder.addStatement("$L", Expressions.localMethodCall("validateFields", nonPrimitivePoetFields));
        }

        CodeBlock.Builder body = CodeBlock.builder();
        for (EnrichedField field : fields) {
            FieldSpec spec = field.poetSpec();

            builder.addParameter(spec.type, spec.name);

            // Collection and Map types not copied in constructor for performance. This assumes that the constructor
            // is private and necessarily called from the builder, which does its own defensive copying.
            if (field.conjureDef().type() instanceof ListType) {
                // TODO(melliot): contribute a fix to JavaPoet that parses $T correctly for a JavaPoet FieldSpec
                body.addStatement("this.$1N = $2T.unmodifiableList($1N)", spec, Collections.class);
            } else if (field.conjureDef().type() instanceof SetType) {
                body.addStatement("this.$1N = $2T.unmodifiableSet($1N)", spec, Collections.class);
            } else if (field.conjureDef().type() instanceof MapType) {
                body.addStatement("this.$1N = $2T.unmodifiableMap($1N)", spec, Collections.class);
            } else {
                body.addStatement("this.$1N = $1N", spec);
            }
        }

        builder.addCode(body.build());

        return builder.build();
    }

    private static Collection<MethodSpec> createGetters(Collection<EnrichedField> fields) {
        return fields.stream()
                .map(f -> createGetter(f))
                .collect(Collectors.toList());
    }

    private static MethodSpec createGetter(EnrichedField field) {
        MethodSpec.Builder getterBuilder = MethodSpec.methodBuilder(generateGetterName(field.poetSpec().name))
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(AnnotationSpec.builder(JsonProperty.class)
                        .addMember("value", "$S", field.jsonKey())
                        .build())
                .returns(field.poetSpec().type);

        if (field.conjureDef().type() instanceof BinaryType) {
            getterBuilder.addStatement("return this.$N.asReadOnlyBuffer()", field.poetSpec().name);
        } else {
            getterBuilder.addStatement("return this.$N", field.poetSpec().name);
        }

        if (field.conjureDef().docs().isPresent()) {
            getterBuilder.addJavadoc("$L", StringUtils.appendIfMissing(field.conjureDef().docs().get(), "\n"));
        }
        return getterBuilder.build();
    }

    private static MethodSpec createValidateFields(Collection<FieldSpec> fields) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("validateFields")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC);

        builder.addStatement("$T missingFields = null", ParameterizedTypeName.get(List.class, String.class));
        for (FieldSpec spec : fields) {
            builder.addParameter(ParameterSpec.builder(spec.type, spec.name).build());
            builder.addStatement("missingFields = addFieldIfMissing(missingFields, $N, $S)", spec, spec.name);
        }

        builder
                .beginControlFlow("if (missingFields != null)")
                .addStatement("throw new $T(\"Some required fields have not been set: \" + missingFields)",
                        IllegalArgumentException.class)
                .endControlFlow();
        return builder.build();
    }

    private static MethodSpec createStaticFactoryMethod(Collection<FieldSpec> fields, ClassName objectClass) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("of")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(objectClass);

        if (fields.isEmpty()) {
            builder
                    .addAnnotation(JsonCreator.class)
                    .addCode("return $L;", SINGLETON_INSTANCE_NAME);
        } else {
            builder.addCode("return builder()");
            for (FieldSpec spec : fields) {
                if (isOptional(spec)) {
                    builder.addCode("\n    .$L(Optional.of($L))", spec.name, spec.name);
                } else {
                    builder.addCode("\n    .$L($L)", spec.name, spec.name);
                }
                builder.addParameter(ParameterSpec.builder(getTypeNameWithoutOptional(spec), spec.name).build());
            }
            builder.addCode("\n    .build();\n");
        }

        return builder.build();
    }

    private static MethodSpec createAddFieldIfMissing(int fieldCount) {
        ParameterizedTypeName listOfStringType = ParameterizedTypeName.get(List.class, String.class);
        ParameterSpec listParam = ParameterSpec.builder(listOfStringType, "prev").build();
        ParameterSpec fieldValueParam =
                ParameterSpec.builder(com.squareup.javapoet.TypeName.OBJECT, "fieldValue").build();
        ParameterSpec fieldNameParam = ParameterSpec.builder(ClassName.get(String.class), "fieldName").build();

        return MethodSpec.methodBuilder("addFieldIfMissing")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(listOfStringType)
                .addParameter(listParam)
                .addParameter(fieldValueParam)
                .addParameter(fieldNameParam)
                .addStatement("$T missingFields = $N", listOfStringType, listParam)
                .beginControlFlow("if ($N == null)", fieldValueParam)
                .beginControlFlow("if (missingFields == null)")
                .addStatement("missingFields = new $T<>($L)", ArrayList.class, fieldCount)
                .endControlFlow()
                .addStatement("missingFields.add($N)", fieldNameParam)
                .endControlFlow()
                .addStatement("return missingFields")
                .build();
    }

    private static MethodSpec createBuilder(ClassName builderClass) {
        return MethodSpec.methodBuilder("builder")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(builderClass)
                .addStatement("return new $T()", builderClass)
                .build();
    }

    static String generateGetterName(String fieldName) {
        return "get" + StringUtils.capitalize(fieldName);
    }

    private static com.squareup.javapoet.TypeName getTypeNameWithoutOptional(FieldSpec spec) {
        if (!isOptional(spec)) {
            return spec.type;
        }
        return ((ParameterizedTypeName) spec.type).typeArguments.get(0);
    }

    private static boolean isOptional(FieldSpec spec) {
        if (!(spec.type instanceof ParameterizedTypeName)) {
            // spec isn't a wrapper class
            return false;
        }
        return ((ParameterizedTypeName) spec.type).rawType.simpleName().equals("Optional");
    }

    @Value.Immutable
    interface EnrichedField {
        @Value.Parameter
        String jsonKey();

        @Value.Parameter
        FieldDefinition conjureDef();

        @Value.Parameter
        FieldSpec poetSpec();

        static EnrichedField of(String jsonKey, FieldDefinition conjureDef, FieldSpec poetSpec) {
            return ImmutableEnrichedField.of(jsonKey, conjureDef, poetSpec);
        }

        static Collection<FieldSpec> toPoetSpecs(Collection<EnrichedField> fields) {
            return fields.stream().map(EnrichedField::poetSpec).collect(Collectors.toList());
        }
    }

}
