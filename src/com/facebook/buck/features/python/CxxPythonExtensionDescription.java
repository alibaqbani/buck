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

package com.facebook.buck.features.python;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorConvertible;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.Optionals;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.cxx.CxxCompilationDatabase;
import com.facebook.buck.cxx.CxxConstructorArg;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxFlags;
import com.facebook.buck.cxx.CxxLinkOptions;
import com.facebook.buck.cxx.CxxLinkableEnhancer;
import com.facebook.buck.cxx.CxxPreprocessAndCompile;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.CxxSourceRuleFactory;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.HeaderSymlinkTree;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.PicType;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.linker.LinkerProvider;
import com.facebook.buck.cxx.toolchain.linker.impl.Linkers;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkTarget;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkTargetInfo;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkTargetMode;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroup;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.features.python.toolchain.PythonPlatform;
import com.facebook.buck.features.python.toolchain.PythonPlatformsProvider;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.facebook.buck.util.stream.RichStream;
import com.facebook.buck.versions.HasVersionUniverse;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimaps;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

public class CxxPythonExtensionDescription
    implements DescriptionWithTargetGraph<CxxPythonExtensionDescriptionArg>,
        ImplicitDepsInferringDescription<
            CxxPythonExtensionDescription.AbstractCxxPythonExtensionDescriptionArg>,
        VersionPropagator<CxxPythonExtensionDescriptionArg>,
        Flavored {

  public enum Type implements FlavorConvertible {
    EXTENSION(CxxDescriptionEnhancer.SHARED_FLAVOR),
    COMPILATION_DATABASE(CxxCompilationDatabase.COMPILATION_DATABASE);

    private final Flavor flavor;

    Type(Flavor flavor) {
      this.flavor = flavor;
    }

    @Override
    public Flavor getFlavor() {
      return flavor;
    }
  }

  private static final FlavorDomain<Type> LIBRARY_TYPE =
      FlavorDomain.from("C/C++ Library Type", Type.class);

  private final ToolchainProvider toolchainProvider;
  private final CxxBuckConfig cxxBuckConfig;

  public CxxPythonExtensionDescription(
      ToolchainProvider toolchainProvider, CxxBuckConfig cxxBuckConfig) {
    this.toolchainProvider = toolchainProvider;
    this.cxxBuckConfig = cxxBuckConfig;
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains(
      TargetConfiguration toolchainTargetConfiguration) {
    return Optional.of(
        ImmutableSet.of(
            getPythonPlatforms(toolchainTargetConfiguration),
            getCxxPlatforms(toolchainTargetConfiguration),
            LIBRARY_TYPE));
  }

  @Override
  public Class<CxxPythonExtensionDescriptionArg> getConstructorArgType() {
    return CxxPythonExtensionDescriptionArg.class;
  }

  @VisibleForTesting
  static BuildTarget getExtensionTarget(
      BuildTarget target, Flavor pythonPlatform, Flavor platform) {
    return CxxDescriptionEnhancer.createSharedLibraryBuildTarget(
        target.withAppendedFlavors(pythonPlatform), platform, Linker.LinkType.SHARED);
  }

  @VisibleForTesting
  static String getExtensionName(String moduleName, CxxPlatform cxxPlatform) {
    // *.so is used on OS X too (as opposed to *.dylib)
    // *.pyd is used on Windows (as opposed to *.dll)
    return String.format(
        "%s.%s",
        moduleName, cxxPlatform.getLd().getType() == LinkerProvider.Type.WINDOWS ? "pyd" : "so");
  }

  private Path getExtensionPath(
      ProjectFilesystem filesystem,
      BuildTarget target,
      String moduleName,
      Flavor pythonPlatform,
      CxxPlatform cxxPlatform) {
    return BuildTargetPaths.getGenPath(
            filesystem, getExtensionTarget(target, pythonPlatform, cxxPlatform.getFlavor()), "%s")
        .resolve(getExtensionName(moduleName, cxxPlatform));
  }

  private ImmutableMap<CxxPreprocessAndCompile, SourcePath> requireCxxObjects(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      CxxPlatform cxxPlatform,
      CxxPythonExtensionDescriptionArg args,
      ImmutableSet<BuildRule> deps) {

    StringWithMacrosConverter macrosConverter =
        CxxDescriptionEnhancer.getStringWithMacrosArgsConverter(
            target, cellRoots, graphBuilder, cxxPlatform);

    // Extract all C/C++ sources from the constructor arg.
    ImmutableMap<String, CxxSource> srcs =
        CxxDescriptionEnhancer.parseCxxSources(target, graphBuilder, cxxPlatform, args);
    ImmutableMap<Path, SourcePath> headers =
        CxxDescriptionEnhancer.parseHeaders(
            target, graphBuilder, projectFilesystem, Optional.of(cxxPlatform), args);

    // Setup the header symlink tree and combine all the preprocessor input from this rule
    // and all dependencies.
    HeaderSymlinkTree headerSymlinkTree =
        CxxDescriptionEnhancer.requireHeaderSymlinkTree(
            target,
            projectFilesystem,
            graphBuilder,
            cxxPlatform,
            headers,
            HeaderVisibility.PRIVATE,
            true);

    ImmutableList<CxxPreprocessorInput> cxxPreprocessorInput =
        CxxDescriptionEnhancer.collectCxxPreprocessorInput(
            target,
            cxxPlatform,
            graphBuilder,
            deps,
            ImmutableListMultimap.copyOf(
                Multimaps.transformValues(
                    CxxFlags.getLanguageFlagsWithMacros(
                        args.getPreprocessorFlags(),
                        args.getPlatformPreprocessorFlags(),
                        args.getLangPreprocessorFlags(),
                        args.getLangPlatformPreprocessorFlags(),
                        cxxPlatform),
                    macrosConverter::convert)),
            ImmutableList.of(headerSymlinkTree),
            ImmutableSet.of(),
            CxxPreprocessables.getTransitiveCxxPreprocessorInputFromDeps(
                cxxPlatform, graphBuilder, deps),
            args.getRawHeaders(),
            args.getIncludeDirectories(),
            projectFilesystem);

    // Generate rule to build the object files.
    ImmutableMultimap<CxxSource.Type, Arg> compilerFlags =
        ImmutableListMultimap.copyOf(
            Multimaps.transformValues(
                CxxFlags.getLanguageFlagsWithMacros(
                    args.getCompilerFlags(),
                    args.getPlatformCompilerFlags(),
                    args.getLangCompilerFlags(),
                    args.getLangPlatformCompilerFlags(),
                    cxxPlatform),
                macrosConverter::convert));
    CxxSourceRuleFactory factory =
        CxxSourceRuleFactory.of(
            projectFilesystem,
            target,
            graphBuilder,
            graphBuilder.getSourcePathResolver(),
            cxxBuckConfig,
            cxxPlatform,
            cxxPreprocessorInput,
            compilerFlags,
            args.getPrefixHeader(),
            args.getPrecompiledHeader(),
            PicType.PIC);
    return factory.requirePreprocessAndCompileRules(srcs);
  }

  private ImmutableList<Arg> getExtensionArgs(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      CxxPlatform cxxPlatform,
      CxxPythonExtensionDescriptionArg args,
      ImmutableSet<BuildRule> deps,
      boolean includePrivateLinkerFlags) {

    ImmutableList.Builder<Arg> argsBuilder = ImmutableList.builder();

    if (includePrivateLinkerFlags) {
      CxxFlags.getFlagsWithMacrosWithPlatformMacroExpansion(
              args.getLinkerFlags(), args.getPlatformLinkerFlags(), cxxPlatform)
          .stream()
          .map(
              CxxDescriptionEnhancer.getStringWithMacrosArgsConverter(
                      target, cellRoots, graphBuilder, cxxPlatform)
                  ::convert)
          .forEach(argsBuilder::add);
    }

    if (cxxPlatform.getLd().getType() != LinkerProvider.Type.WINDOWS) {
      // Embed a origin-relative library path into the binary so it can find the shared libraries.
      argsBuilder.addAll(
          StringArg.from(
              Linkers.iXlinker(
                  "-rpath",
                  String.format(
                      "%s/",
                      cxxPlatform
                          .getLd()
                          .resolve(graphBuilder, target.getTargetConfiguration())
                          .libOrigin()))));
    }

    // Add object files into the args.
    ImmutableMap<CxxPreprocessAndCompile, SourcePath> picObjects =
        requireCxxObjects(
            target, projectFilesystem, graphBuilder, cellRoots, cxxPlatform, args, deps);
    argsBuilder.addAll(SourcePathArg.from(picObjects.values()));

    return argsBuilder.build();
  }

  private ImmutableSet<BuildRule> getPlatformDeps(
      BuildRuleResolver ruleResolver,
      PythonPlatform pythonPlatform,
      CxxPlatform cxxPlatform,
      CxxPythonExtensionDescriptionArg args) {

    ImmutableSet.Builder<BuildRule> rules = ImmutableSet.builder();

    // Add declared deps.
    rules.addAll(args.getCxxDeps().get(ruleResolver, cxxPlatform));

    // Add platform specific deps.
    rules.addAll(
        ruleResolver.getAllRules(
            Iterables.concat(
                args.getPlatformDeps().getMatchingValues(pythonPlatform.getFlavor().toString()))));

    // Add a dep on the python C/C++ library.
    if (pythonPlatform.getCxxLibrary().isPresent()) {
      rules.add(ruleResolver.getRule(pythonPlatform.getCxxLibrary().get()));
    }

    return rules.build();
  }

  private BuildRule createExtensionBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      PythonPlatform pythonPlatform,
      CxxPlatform cxxPlatform,
      CxxPythonExtensionDescriptionArg args) {
    String moduleName = args.getModuleName().orElse(buildTarget.getShortName());
    String extensionName = getExtensionName(moduleName, cxxPlatform);
    Path extensionPath =
        getExtensionPath(
            projectFilesystem, buildTarget, moduleName, pythonPlatform.getFlavor(), cxxPlatform);
    ImmutableSet<BuildRule> deps = getPlatformDeps(graphBuilder, pythonPlatform, cxxPlatform, args);
    return CxxLinkableEnhancer.createCxxLinkableBuildRule(
        cxxBuckConfig,
        cxxPlatform,
        projectFilesystem,
        graphBuilder,
        getExtensionTarget(buildTarget, pythonPlatform.getFlavor(), cxxPlatform.getFlavor()),
        Linker.LinkType.SHARED,
        Optional.of(extensionName),
        extensionPath,
        args.getLinkerExtraOutputs(),
        args.getLinkStyle().orElse(Linker.LinkableDepType.SHARED),
        Optional.empty(),
        CxxLinkOptions.of(),
        RichStream.from(deps)
            .filter(NativeLinkableGroup.class)
            .map(g -> g.getNativeLinkable(cxxPlatform, graphBuilder))
            .toImmutableList(),
        args.getCxxRuntimeType(),
        Optional.empty(),
        ImmutableSet.of(),
        ImmutableSet.of(),
        NativeLinkableInput.builder()
            .setArgs(
                getExtensionArgs(
                    buildTarget.withoutFlavors(LinkerMapMode.FLAVOR_DOMAIN.getFlavors()),
                    projectFilesystem,
                    graphBuilder,
                    cellRoots,
                    cxxPlatform,
                    args,
                    deps,
                    true))
            .setFrameworks(args.getFrameworks())
            .setLibraries(args.getLibraries())
            .build(),
        Optional.empty(),
        cellRoots);
  }

  private BuildRule createCompilationDatabase(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      PythonPlatform pythonPlatform,
      CxxPlatform cxxPlatform,
      CxxPythonExtensionDescriptionArg args) {
    ImmutableSet<BuildRule> deps = getPlatformDeps(graphBuilder, pythonPlatform, cxxPlatform, args);
    ImmutableMap<CxxPreprocessAndCompile, SourcePath> objects =
        requireCxxObjects(
            target, projectFilesystem, graphBuilder, cellRoots, cxxPlatform, args, deps);
    return CxxCompilationDatabase.createCompilationDatabase(
        target, projectFilesystem, objects.keySet());
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      CxxPythonExtensionDescriptionArg args) {
    ActionGraphBuilder graphBuilderLocal = context.getActionGraphBuilder();
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    CellPathResolver cellRoots = context.getCellPathResolver();
    args.checkDuplicateSources(graphBuilderLocal.getSourcePathResolver());

    // See if we're building a particular "type" of this library, and if so, extract it as an enum.
    Optional<Type> type = LIBRARY_TYPE.getValue(buildTarget);
    if (type.isPresent()) {

      FlavorDomain<UnresolvedCxxPlatform> cxxPlatforms =
          getCxxPlatforms(buildTarget.getTargetConfiguration());
      FlavorDomain<PythonPlatform> pythonPlatforms =
          getPythonPlatforms(buildTarget.getTargetConfiguration());

      // If we *are* building a specific type of this lib, call into the type specific rule builder
      // methods.
      switch (type.get()) {
        case EXTENSION:
          return createExtensionBuildRule(
              buildTarget,
              projectFilesystem,
              graphBuilderLocal,
              cellRoots,
              pythonPlatforms.getRequiredValue(buildTarget),
              cxxPlatforms
                  .getRequiredValue(buildTarget)
                  .resolve(graphBuilderLocal, buildTarget.getTargetConfiguration()),
              args);
        case COMPILATION_DATABASE:
          // so for the moment, when we get a target whose flavor is just #compilation-database
          // we'll give it the default C++ and Python platforms to build with.
          // of course, these may not be the desired/correct ones, but up until now
          // the target would often end up without a Python platform at all, causing
          // us to miss out on the compilation database altogether.
          BuildTarget target = buildTarget;

          if (!cxxPlatforms.containsAnyOf(target.getFlavors())) {
            // constructor args *should* contain a default flavor, but
            // we keep the platform default as a final fallback
            ImmutableSet<Flavor> defaultCxxFlavors = args.getDefaultFlavors();
            if (!cxxPlatforms.containsAnyOf(defaultCxxFlavors)) {
              defaultCxxFlavors =
                  ImmutableSet.of(
                      getDefaultCxxPlatform(buildTarget.getTargetConfiguration()).getFlavor());
            }

            target = target.withAppendedFlavors(defaultCxxFlavors);
          }

          if (!pythonPlatforms.containsAnyOf(target.getFlavors())) {
            target = target.withAppendedFlavors(PythonBuckConfig.DEFAULT_PYTHON_PLATFORM);
          }

          return createCompilationDatabase(
              target,
              projectFilesystem,
              graphBuilderLocal,
              cellRoots,
              pythonPlatforms.getRequiredValue(target),
              cxxPlatforms
                  .getRequiredValue(target)
                  .resolve(graphBuilderLocal, buildTarget.getTargetConfiguration()),
              args);
      }
    }

    // Otherwise, we return the generic placeholder of this library, that dependents can use
    // get the real build rules via querying the action graph.
    return new CxxPythonExtension(buildTarget, projectFilesystem, params) {

      @Override
      protected BuildRule getExtension(
          PythonPlatform pythonPlatform, CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
        return graphBuilder.requireRule(
            getBuildTarget()
                .withAppendedFlavors(
                    pythonPlatform.getFlavor(),
                    cxxPlatform.getFlavor(),
                    CxxDescriptionEnhancer.SHARED_FLAVOR));
      }

      @Override
      public Path getModule(CxxPlatform cxxPlatform) {
        Path baseModule = PythonUtil.getBasePath(buildTarget, args.getBaseModule());
        String moduleName = args.getModuleName().orElse(buildTarget.getShortName());
        return baseModule.resolve(getExtensionName(moduleName, cxxPlatform));
      }

      @Override
      public Iterable<BuildRule> getPythonPackageDeps(
          PythonPlatform pythonPlatform, CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
        return PythonUtil.getDeps(
                pythonPlatform, cxxPlatform, args.getDeps(), args.getPlatformDeps())
            .stream()
            .map(graphBuilder::getRule)
            .filter(PythonPackagable.class::isInstance)
            .collect(ImmutableList.toImmutableList());
      }

      @Override
      public Optional<PythonMappedComponents> getPythonModules(
          PythonPlatform pythonPlatform, CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
        BuildRule extension = getExtension(pythonPlatform, cxxPlatform, graphBuilder);
        SourcePath output = extension.getSourcePathToOutput();
        return Optional.of(
            PythonMappedComponents.of(ImmutableSortedMap.of(getModule(cxxPlatform), output)));
      }

      @Override
      public NativeLinkTarget getNativeLinkTarget(
          PythonPlatform pythonPlatform,
          CxxPlatform cxxPlatform,
          ActionGraphBuilder graphBuilder,
          boolean includePrivateLinkerFlags) {
        ImmutableList<NativeLinkable> linkTargetDeps =
            args.getLinkStyle().orElse(Linker.LinkableDepType.SHARED)
                    == Linker.LinkableDepType.SHARED
                ? RichStream.from(getPlatformDeps(graphBuilder, pythonPlatform, cxxPlatform, args))
                    .filter(NativeLinkableGroup.class)
                    .map(g -> g.getNativeLinkable(cxxPlatform, graphBuilder))
                    .toImmutableList()
                : ImmutableList.of();

        NativeLinkableInput linkableInput =
            NativeLinkableInput.builder()
                .addAllArgs(
                    getExtensionArgs(
                        buildTarget.withAppendedFlavors(
                            pythonPlatform.getFlavor(), CxxDescriptionEnhancer.SHARED_FLAVOR),
                        projectFilesystem,
                        graphBuilder,
                        cellRoots,
                        cxxPlatform,
                        args,
                        getPlatformDeps(graphBuilder, pythonPlatform, cxxPlatform, args),
                        includePrivateLinkerFlags))
                .addAllFrameworks(args.getFrameworks())
                .build();
        return new NativeLinkTargetInfo(
            buildTarget.withAppendedFlavors(pythonPlatform.getFlavor()),
            NativeLinkTargetMode.library(),
            linkTargetDeps,
            linkableInput,
            Optional.empty());
      }

      @Override
      public Stream<BuildTarget> getRuntimeDeps(BuildRuleResolver buildRuleResolver) {
        return getDeclaredDeps().stream().map(BuildRule::getBuildTarget);
      }
    };
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      AbstractCxxPythonExtensionDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    // Get any parse time deps from the C/C++ platforms.
    getCxxPlatforms(buildTarget.getTargetConfiguration())
        .getValues(buildTarget)
        .forEach(
            p -> extraDepsBuilder.addAll(p.getParseTimeDeps(buildTarget.getTargetConfiguration())));

    for (PythonPlatform pythonPlatform :
        getPythonPlatforms(buildTarget.getTargetConfiguration()).getValues()) {
      Optionals.addIfPresent(pythonPlatform.getCxxLibrary(), extraDepsBuilder);
    }
  }

  @Override
  public boolean producesCacheableSubgraph() {
    return true;
  }

  private FlavorDomain<PythonPlatform> getPythonPlatforms(
      TargetConfiguration toolchainTargetConfiguration) {
    return toolchainProvider
        .getByName(
            PythonPlatformsProvider.DEFAULT_NAME,
            toolchainTargetConfiguration,
            PythonPlatformsProvider.class)
        .getPythonPlatforms();
  }

  private UnresolvedCxxPlatform getDefaultCxxPlatform(
      TargetConfiguration toolchainTargetConfiguration) {
    return toolchainProvider
        .getByName(
            CxxPlatformsProvider.DEFAULT_NAME,
            toolchainTargetConfiguration,
            CxxPlatformsProvider.class)
        .getDefaultUnresolvedCxxPlatform();
  }

  private FlavorDomain<UnresolvedCxxPlatform> getCxxPlatforms(
      TargetConfiguration toolchainTargetConfiguration) {
    return toolchainProvider
        .getByName(
            CxxPlatformsProvider.DEFAULT_NAME,
            toolchainTargetConfiguration,
            CxxPlatformsProvider.class)
        .getUnresolvedCxxPlatforms();
  }

  @RuleArg
  interface AbstractCxxPythonExtensionDescriptionArg extends CxxConstructorArg, HasVersionUniverse {
    Optional<String> getBaseModule();

    Optional<String> getModuleName();

    Optional<Linker.LinkableDepType> getLinkStyle();
  }
}
