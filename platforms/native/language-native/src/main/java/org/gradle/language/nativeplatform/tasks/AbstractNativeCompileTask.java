/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.language.nativeplatform.tasks;

import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.api.internal.file.TaskFileVarFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SkipWhenEmpty;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.WorkResult;
import org.gradle.internal.Cast;
import org.gradle.internal.operations.logging.BuildOperationLogger;
import org.gradle.internal.operations.logging.BuildOperationLoggerFactory;
import org.gradle.language.base.internal.compile.Compiler;
import org.gradle.language.nativeplatform.internal.incremental.IncrementalCompilerBuilder;
import org.gradle.nativeplatform.internal.BuildOperationLoggingCompilerDecorator;
import org.gradle.nativeplatform.platform.NativePlatform;
import org.gradle.nativeplatform.platform.internal.NativePlatformInternal;
import org.gradle.nativeplatform.toolchain.Clang;
import org.gradle.nativeplatform.toolchain.Gcc;
import org.gradle.nativeplatform.toolchain.NativeToolChain;
import org.gradle.nativeplatform.toolchain.internal.NativeCompileSpec;
import org.gradle.nativeplatform.toolchain.internal.NativeToolChainInternal;
import org.gradle.nativeplatform.toolchain.internal.PlatformToolProvider;
import org.gradle.work.DisableCachingByDefault;
import org.gradle.work.Incremental;
import org.gradle.work.InputChanges;

import javax.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Compiles native source files into object files.
 */
@DisableCachingByDefault(because = "Abstract super-class, not to be instantiated directly")
public abstract class AbstractNativeCompileTask extends DefaultTask {
    private boolean positionIndependentCode;
    private boolean debug;
    private boolean optimize;
    private final ConfigurableFileCollection source;
    private final Map<String, String> macros = new LinkedHashMap<String, String>();
    // Don't serialize the compiler. It holds state that is mostly only required at execution time and that can be calculated from the other fields of this task
    // after being deserialized. However, it is also required to calculate the producers of the header files to calculate the work graph.
    // It would be better to provide some way for a task to express these things separately.
    private transient IncrementalCompilerBuilder.IncrementalCompiler incrementalCompiler;

    public AbstractNativeCompileTask() {
        dependsOn(getIncludes());
        dependsOn(getSystemIncludes());

        this.source = getTaskFileVarFactory().newInputFileCollection(this);
    }

    private IncrementalCompilerBuilder.IncrementalCompiler getIncrementalCompiler() {
        if (incrementalCompiler == null) {
            incrementalCompiler = getIncrementalCompilerBuilder().newCompiler(this, source, getIncludes().plus(getSystemIncludes()), macros, getToolChain().map(nativeToolChain -> nativeToolChain instanceof Gcc || nativeToolChain instanceof Clang));
        }
        return incrementalCompiler;
    }

    @Inject
    protected abstract TaskFileVarFactory getTaskFileVarFactory();

    @Inject
    protected abstract IncrementalCompilerBuilder getIncrementalCompilerBuilder();

    @Inject
    protected abstract BuildOperationLoggerFactory getOperationLoggerFactory();

    @Inject
    protected abstract FileCollectionFactory getFileCollectionFactory();

    @TaskAction
    protected void compile(InputChanges inputs) {
        BuildOperationLogger operationLogger = getOperationLoggerFactory().newOperationLogger(getName(), getTemporaryDir());
        NativeCompileSpec spec = createCompileSpec();
        spec.setTargetPlatform(getTargetPlatform().get());
        spec.setTempDir(getTemporaryDir());
        spec.setObjectFileDir(getObjectFileDir().get().getAsFile());
        spec.include(getIncludes());
        spec.systemInclude(getSystemIncludes());
        spec.source(getSource());
        spec.setMacros(getMacros());
        spec.args(getCompilerArgs().get());
        spec.setPositionIndependentCode(isPositionIndependentCode());
        spec.setDebuggable(isDebuggable());
        spec.setOptimized(isOptimized());
        spec.setIncrementalCompile(inputs.isIncremental());
        spec.setOperationLogger(operationLogger);

        configureSpec(spec);

        NativeToolChainInternal nativeToolChain = (NativeToolChainInternal) getToolChain().get();
        NativePlatformInternal nativePlatform = (NativePlatformInternal) getTargetPlatform().get();
        PlatformToolProvider platformToolProvider = nativeToolChain.select(nativePlatform);
        setDidWork(doCompile(spec, platformToolProvider).getDidWork());
    }

    protected void configureSpec(NativeCompileSpec spec) {
    }

    private <T extends NativeCompileSpec> WorkResult doCompile(T spec, PlatformToolProvider platformToolProvider) {
        Class<T> specType = Cast.uncheckedCast(spec.getClass());
        Compiler<T> baseCompiler = platformToolProvider.newCompiler(specType);
        Compiler<T> incrementalCompiler = getIncrementalCompiler().createCompiler(baseCompiler);
        Compiler<T> loggingCompiler = BuildOperationLoggingCompilerDecorator.wrap(incrementalCompiler);
        return loggingCompiler.execute(spec);
    }

    protected abstract NativeCompileSpec createCompileSpec();

    /**
     * The tool chain used for compilation.
     *
     * @since 4.7
     */
    @Internal
    public abstract Property<NativeToolChain> getToolChain();

    /**
     * The platform being compiled for.
     *
     * @since 4.7
     */
    @Nested
    public abstract Property<NativePlatform> getTargetPlatform();

    /**
     * Should the compiler generate position independent code?
     */
    @Input
    public boolean isPositionIndependentCode() {
        return positionIndependentCode;
    }

    public void setPositionIndependentCode(boolean positionIndependentCode) {
        this.positionIndependentCode = positionIndependentCode;
    }

    /**
     * Should the compiler generate debuggable code?
     *
     * @since 4.3
     */
    @Input
    public boolean isDebuggable() {
        return debug;
    }

    /**
     * Should the compiler generate debuggable code?
     *
     * @since 4.3
     */
    public void setDebuggable(boolean debug) {
        this.debug = debug;
    }

    /**
     * Should the compiler generate optimized code?
     *
     * @since 4.3
     */
    @Input
    public boolean isOptimized() {
        return optimize;
    }

    /**
     * Should the compiler generate optimized code?
     *
     * @since 4.3
     */
    public void setOptimized(boolean optimize) {
        this.optimize = optimize;
    }

    /**
     * The directory where object files will be generated.
     *
     * @since 4.3
     */
    @OutputDirectory
    public abstract DirectoryProperty getObjectFileDir();

    /**
     * Returns the header directories to be used for compilation.
     */
    @Internal("The paths for include directories are tracked via the includePaths property, the contents are tracked via discovered inputs")
    public abstract ConfigurableFileCollection getIncludes();

    /**
     * Add directories where the compiler should search for header files.
     */
    public void includes(Object includeRoots) {
        getIncludes().from(includeRoots);
    }

    /**
     * Returns the system include directories to be used for compilation.
     *
     * @since 4.8
     */
    @Internal("The paths for include directories are tracked via the includePaths property, the contents are tracked via discovered inputs")
    public abstract ConfigurableFileCollection getSystemIncludes();

    /**
     * Returns the source files to be compiled.
     */
    @InputFiles
    @SkipWhenEmpty
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    public ConfigurableFileCollection getSource() {
        return source;
    }

    /**
     * Adds a set of source files to be compiled. The provided sourceFiles object is evaluated as per {@link Project#files(Object...)}.
     */
    public void source(Object sourceFiles) {
        source.from(sourceFiles);
    }

    /**
     * Macros that should be defined for the compiler.
     */
    @Input
    public Map<String, String> getMacros() {
        return macros;
    }

    public void setMacros(Map<String, String> macros) {
        this.macros.clear();
        this.macros.putAll(macros);
    }

    /**
     * <em>Additional</em> arguments to provide to the compiler.
     *
     * @since 4.3
     */
    @Input
    public abstract ListProperty<String> getCompilerArgs();

    /**
     * The set of dependent headers. This is used for up-to-date checks only.
     *
     * @since 4.3
     */
    @Incremental
    @InputFiles
    @PathSensitive(PathSensitivity.NAME_ONLY)
    protected FileCollection getHeaderDependencies() {
        return getIncrementalCompiler().getHeaderFiles();
    }
}
