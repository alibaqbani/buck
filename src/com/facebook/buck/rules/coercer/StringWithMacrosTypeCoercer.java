/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.rules.coercer;

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.macros.MacroFinderAutomaton;
import com.facebook.buck.core.macros.MacroMatchResult;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.macros.Macro;
import com.facebook.buck.rules.macros.MacroContainer;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.UnconfiguredMacro;
import com.facebook.buck.util.types.Either;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import com.google.common.reflect.TypeToken;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/** Coerce to {@link com.facebook.buck.rules.macros.StringWithMacros}. */
public class StringWithMacrosTypeCoercer implements TypeCoercer<Object, StringWithMacros> {

  private final ImmutableMap<String, Class<? extends Macro>> macros;
  private final ImmutableMap<
          Class<? extends Macro>, MacroTypeCoercer<? extends UnconfiguredMacro, ? extends Macro>>
      coercers;

  private StringWithMacrosTypeCoercer(
      ImmutableMap<String, Class<? extends Macro>> macros,
      ImmutableMap<
              Class<? extends Macro>,
              MacroTypeCoercer<? extends UnconfiguredMacro, ? extends Macro>>
          coercers) {
    Preconditions.checkArgument(
        Sets.difference(coercers.keySet(), new HashSet<>(macros.values())).isEmpty());
    this.macros = macros;
    this.coercers = coercers;
  }

  @Override
  public TypeToken<StringWithMacros> getOutputType() {
    return TypeToken.of(StringWithMacros.class);
  }

  @Override
  public TypeToken<Object> getUnconfiguredType() {
    return TypeToken.of(Object.class);
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    for (MacroTypeCoercer<? extends UnconfiguredMacro, ? extends Macro> coercer :
        coercers.values()) {
      if (coercer.hasElementClass(types)) {
        return true;
      }
    }
    return false;
  }

  private <U extends UnconfiguredMacro, M extends Macro> void traverse(
      CellNameResolver cellRoots,
      MacroTypeCoercer<U, M> coercer,
      Macro macro,
      Traversal traversal) {
    coercer.traverse(cellRoots, coercer.getOutputClass().cast(macro), traversal);
  }

  @Override
  public void traverse(
      CellNameResolver cellRoots, StringWithMacros stringWithMacros, Traversal traversal) {
    for (MacroContainer macroContainer : stringWithMacros.getMacros()) {
      MacroTypeCoercer<? extends UnconfiguredMacro, ? extends Macro> coercer =
          Objects.requireNonNull(coercers.get(macroContainer.getMacro().getMacroClass()));
      traverse(cellRoots, coercer, macroContainer.getMacro(), traversal);
    }
  }

  @Override
  public Object coerceToUnconfigured(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {
    return object;
  }

  // Most strings with macros do not contain any macros.
  // This method is optimistic fast-path optimization of string with macro parsing.
  @Nullable
  private StringWithMacros tryParseFast(String blob) {
    if (blob.indexOf('$') >= 0) {
      return null;
    }

    return StringWithMacros.ofConstantString(blob);
  }

  private StringWithMacros parse(
      CellNameResolver cellNameResolver,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      TargetConfiguration hostConfiguration,
      String blob)
      throws CoerceFailedException {

    StringWithMacros fast = tryParseFast(blob);
    if (fast != null) {
      return fast;
    }

    ImmutableList.Builder<Either<String, MacroContainer>> parts = ImmutableList.builder();

    // Iterate over all macros found in the string, expanding each found macro.
    int lastEnd = 0;
    MacroFinderAutomaton matcher = new MacroFinderAutomaton(blob);
    while (matcher.hasNext()) {
      MacroMatchResult matchResult = matcher.next();

      // Add everything from the original string since the last match to this one.
      if (lastEnd < matchResult.getStartIndex()) {
        parts.add(Either.ofLeft(blob.substring(lastEnd, matchResult.getStartIndex())));
      }

      if (matchResult.isEscaped()) {

        // If the macro is escaped, add it as-is.
        parts.add(
            Either.ofLeft(
                blob.substring(matchResult.getStartIndex() + 1, matchResult.getEndIndex())));

      } else {
        String macroString = blob.substring(matchResult.getStartIndex(), matchResult.getEndIndex());

        // Extract the macro name and hande the `@` prefix.
        String name = matchResult.getMacroType();
        boolean outputToFile;
        if (name.startsWith("@")) {
          outputToFile = true;
          name = name.substring(1);
        } else {
          outputToFile = false;
        }

        // Look up the macro coercer that owns this macro name.
        Class<? extends Macro> clazz = macros.get(name);
        if (clazz == null) {
          throw new CoerceFailedException(
              String.format(
                  "Macro '%s' not found when expanding '%s'",
                  matchResult.getMacroType(), macroString));
        }
        MacroTypeCoercer<? extends UnconfiguredMacro, ? extends Macro> coercer =
            Objects.requireNonNull(coercers.get(clazz));
        ImmutableList<String> args = matchResult.getMacroInput();

        // Delegate to the macro coercers to parse the macro..
        Macro macro;
        try {
          macro =
              coercer.coerce(
                  cellNameResolver,
                  filesystem,
                  pathRelativeToProjectRoot,
                  targetConfiguration,
                  hostConfiguration,
                  args);
        } catch (CoerceFailedException e) {
          throw new CoerceFailedException(
              String.format(
                  "The macro '%s' could not be expanded:\n%s", macroString, e.getMessage()),
              e);
        }

        parts.add(Either.ofRight(MacroContainer.of(macro, outputToFile)));
      }

      lastEnd = matchResult.getEndIndex();
    }

    // Append the remaining part of the original string after the last match.
    if (lastEnd < blob.length()) {
      parts.add(Either.ofLeft(blob.substring(lastEnd)));
    }

    return StringWithMacros.of(parts.build());
  }

  @Override
  public StringWithMacros coerce(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      TargetConfiguration hostConfiguration,
      Object object)
      throws CoerceFailedException {
    if (!(object instanceof String)) {
      throw CoerceFailedException.simple(object, getOutputType());
    }
    return parse(
        cellRoots,
        filesystem,
        pathRelativeToProjectRoot,
        targetConfiguration,
        hostConfiguration,
        (String) object);
  }

  @Override
  public StringWithMacros concat(Iterable<StringWithMacros> elements) {
    Stream<Either<String, MacroContainer>> parts =
        Streams.stream(elements).map(StringWithMacros::getParts).flatMap(List::stream);

    return StringWithMacros.of(mergeStringParts(parts));
  }

  /** Merges all adjacent string elements. */
  private static ImmutableList<Either<String, MacroContainer>> mergeStringParts(
      Stream<Either<String, MacroContainer>> parts) {
    ImmutableList.Builder<Either<String, MacroContainer>> mergedParts = ImmutableList.builder();
    StringBuilder currentStringPart = new StringBuilder();

    parts.forEachOrdered(
        part -> {
          if (part.isLeft()) {
            currentStringPart.append(part.getLeft());
          } else {
            addStringToParts(mergedParts, currentStringPart);
            mergedParts.add(part);
          }
        });

    addStringToParts(mergedParts, currentStringPart);

    return mergedParts.build();
  }

  private static void addStringToParts(
      ImmutableList.Builder<Either<String, MacroContainer>> parts, StringBuilder string) {
    if (string.length() > 0) {
      parts.add(Either.ofLeft(string.toString()));
      string.setLength(0);
    }
  }

  /** Builder of {@link StringWithMacrosTypeCoercer}. */
  static class Builder {

    private Builder() {}

    private ImmutableMap.Builder<String, Class<? extends Macro>> macros = ImmutableMap.builder();
    private ImmutableList.Builder<MacroTypeCoercer<? extends UnconfiguredMacro, ? extends Macro>>
        macroCoercers = ImmutableList.builder();

    public <U extends UnconfiguredMacro, M extends Macro> Builder put(
        String macro, Class<M> macroClass, MacroTypeCoercer<U, M> coercer) {
      macros.put(macro, macroClass);
      macroCoercers.add(coercer);
      return this;
    }

    StringWithMacrosTypeCoercer build() {
      return new StringWithMacrosTypeCoercer(
          this.macros.build(),
          Maps.uniqueIndex(this.macroCoercers.build(), MacroTypeCoercer::getOutputClass));
    }
  }

  /** New {@link Builder}. */
  static Builder builder() {
    return new Builder();
  }
}
