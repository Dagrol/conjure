/*
 * (c) Copyright 2017 Palantir Technologies Inc. All rights reserved.
 */

package com.palantir.conjure.gen.python.types;

import com.palantir.conjure.defs.types.TypeDefinition;
import com.palantir.conjure.gen.python.PackageNameProcessor;
import com.palantir.conjure.gen.python.poet.PythonClass;
import java.util.List;

public interface PythonBeanGenerator {

    enum ExperimentalFeatures {}

    PythonClass generateObject(
            List<TypeDefinition> types,
            PackageNameProcessor packageNameProvider,
            TypeDefinition typeDef);

}
