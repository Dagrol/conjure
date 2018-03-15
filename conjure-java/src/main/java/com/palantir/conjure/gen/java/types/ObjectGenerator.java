/*
 * (c) Copyright 2016 Palantir Technologies Inc. All rights reserved.
 */

package com.palantir.conjure.gen.java.types;


import com.google.common.collect.ImmutableSet;
import com.palantir.conjure.defs.types.TypeDefinition;
import com.palantir.conjure.defs.types.complex.AliasTypeDefinition;
import com.palantir.conjure.defs.types.complex.EnumTypeDefinition;
import com.palantir.conjure.defs.types.complex.ErrorTypeDefinition;
import com.palantir.conjure.defs.types.complex.ObjectTypeDefinition;
import com.palantir.conjure.defs.types.complex.UnionTypeDefinition;
import com.palantir.conjure.gen.java.ExperimentalFeatures;
import com.palantir.conjure.gen.java.Settings;
import com.squareup.javapoet.JavaFile;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class ObjectGenerator implements TypeGenerator {

    private final Set<ExperimentalFeatures> enabledExperimentalFeatures;
    private final Settings settings;

    public ObjectGenerator(Settings settings) {
        this(settings, ImmutableSet.of());
    }

    public ObjectGenerator(Settings settings, Set<ExperimentalFeatures> enabledExperimentalFeatures) {
        this.settings = settings;
        this.enabledExperimentalFeatures = ImmutableSet.copyOf(enabledExperimentalFeatures);
    }

    @Override
    public Set<JavaFile> generateTypes(List<TypeDefinition> types) {
        TypeMapper typeMapper = new TypeMapper(types);

        return types.stream().map(typeDef -> {
            if (typeDef instanceof ObjectTypeDefinition) {
                return BeanGenerator.generateBeanType(typeMapper,
                        (ObjectTypeDefinition) typeDef, settings.ignoreUnknownProperties(),
                        enabledExperimentalFeatures);
            } else if (typeDef instanceof UnionTypeDefinition) {
                return UnionGenerator.generateUnionType(
                        typeMapper, (UnionTypeDefinition) typeDef,
                        enabledExperimentalFeatures);
            } else if (typeDef instanceof EnumTypeDefinition) {
                return EnumGenerator.generateEnumType(
                        (EnumTypeDefinition) typeDef, settings.supportUnknownEnumValues(),
                        enabledExperimentalFeatures);
            } else if (typeDef instanceof AliasTypeDefinition) {
                return AliasGenerator.generateAliasType(
                        typeMapper, (AliasTypeDefinition) typeDef, enabledExperimentalFeatures);
            } else {
                throw new IllegalArgumentException("Unknown object definition type " + typeDef.getClass());
            }
        }).collect(Collectors.toSet());
    }

    @Override
    public Set<JavaFile> generateErrors(List<TypeDefinition> types, List<ErrorTypeDefinition> errors) {
        if (errors.isEmpty()) {
            return ImmutableSet.of();
        }
        requireExperimentalFeature(ExperimentalFeatures.ErrorTypes);

        TypeMapper typeMapper = new TypeMapper(types);
        return ErrorGenerator.generateErrorTypes(typeMapper, errors);
    }

    private void requireExperimentalFeature(ExperimentalFeatures feature) {
        if (!enabledExperimentalFeatures.contains(feature)) {
            throw new ExperimentalFeatureDisabledException(feature);
        }
    }

}
