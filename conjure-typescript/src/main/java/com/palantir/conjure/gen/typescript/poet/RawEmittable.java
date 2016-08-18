/*
 * Copyright 2016 Palantir Technologies, Inc. All rights reserved.
 */

package com.palantir.conjure.gen.typescript.poet;

import com.palantir.conjure.defs.ConjureImmutablesStyle;
import org.immutables.value.Value;

@ConjureImmutablesStyle
@Value.Immutable
public interface RawEmittable extends Emittable {
    String content();

    @Override
    default void emit(TypescriptPoetWriter writer) {
        writer.write(content());
    }

    static ImmutableRawEmittable.Builder builder() {
        return ImmutableRawEmittable.builder();
    }
}