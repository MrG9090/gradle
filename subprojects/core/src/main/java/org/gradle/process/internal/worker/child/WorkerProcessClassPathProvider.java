/*
 * Copyright 2010 the original author or authors.
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

package org.gradle.process.internal.worker.child;

import org.gradle.api.GradleException;
import org.gradle.api.JavaVersion;
import org.gradle.api.internal.ClassPathProvider;
import org.gradle.api.internal.classpath.ModuleRegistry;
import org.gradle.api.internal.jvm.JavaVersionParser;
import org.gradle.api.specs.Spec;
import org.gradle.cache.FileLockManager;
import org.gradle.cache.PersistentCache;
import org.gradle.cache.scopes.GlobalScopedCacheBuilderFactory;
import org.gradle.internal.Factory;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.classloader.ClassLoaderHierarchy;
import org.gradle.internal.classloader.ClassLoaderSpec;
import org.gradle.internal.classloader.ClassLoaderUtils;
import org.gradle.internal.classloader.ClassLoaderVisitor;
import org.gradle.internal.classloader.FilteringClassLoader;
import org.gradle.internal.classloader.SystemClassLoaderSpec;
import org.gradle.internal.classpath.ClassPath;
import org.gradle.internal.classpath.DefaultClassPath;
import org.gradle.internal.reflect.JavaMethod;
import org.gradle.internal.reflect.JavaReflectionUtil;
import org.gradle.internal.reflect.NoSuchMethodException;
import org.gradle.internal.reflect.NoSuchPropertyException;
import org.gradle.internal.reflect.PropertyAccessor;
import org.gradle.internal.reflect.PropertyMutator;
import org.gradle.internal.service.scopes.Scope;
import org.gradle.internal.service.scopes.ServiceScope;
import org.gradle.internal.stream.EncodedStream;
import org.gradle.process.internal.worker.GradleWorkerMain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.gradle.process.internal.worker.child.ApplicationClassesInSystemClassLoaderWorkerImplementationFactory.WORKER_GRADLE_REMAPPING_PREFIX;

@ServiceScope(Scope.UserHome.class)
public class WorkerProcessClassPathProvider implements ClassPathProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkerProcessClassPathProvider.class);
    private final GlobalScopedCacheBuilderFactory cacheBuilderFactory;
    private final ModuleRegistry moduleRegistry;
    private final Object lock = new Object();

    private ClassPath workerClassPath;
    private ClassPath daemonServerWorkerClasspath;

    public WorkerProcessClassPathProvider(GlobalScopedCacheBuilderFactory cacheBuilderFactory, ModuleRegistry moduleRegistry) {
        this.cacheBuilderFactory = cacheBuilderFactory;
        this.moduleRegistry = moduleRegistry;
    }

    @Override
    public ClassPath findClassPath(String name) {
        if (name.equals("WORKER_MAIN")) {
            synchronized (lock) {
                if (workerClassPath == null) {
                    PersistentCache workerClassPathCache = cacheBuilderFactory
                        .createCacheBuilder("workerMain")
                        .withInitialLockMode(FileLockManager.LockMode.Exclusive)
                        .withInitializer(new CacheInitializer())
                        .open();
                    try {
                        workerClassPath = DefaultClassPath.of(jarFile(workerClassPathCache));
                    } finally {
                        workerClassPathCache.close();
                    }
                }
                LOGGER.debug("Using worker process classpath: {}", workerClassPath);
                return workerClassPath;
            }
        }

        if (name.equals("DAEMON_SERVER_WORKER")) {
            synchronized (lock) {
                if (daemonServerWorkerClasspath == null) {
                    daemonServerWorkerClasspath = moduleRegistry.getModule("gradle-daemon-server-worker").getAllRequiredModulesClasspath();
                }
            }
            return daemonServerWorkerClasspath;
        }

        return null;
    }

    private static File jarFile(PersistentCache cache) {
        return new File(cache.getBaseDir(), "gradle-worker.jar");
    }

    private static class CacheInitializer implements Consumer<PersistentCache> {
        private final WorkerClassRemapper remapper = new WorkerClassRemapper();

        @Override
        public void accept(PersistentCache cache) {
            try {
                File jarFile = jarFile(cache);
                LOGGER.debug("Generating worker process classes to {}.", jarFile);
                try (ZipOutputStream outputStream = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(jarFile)))) {
                    for (Class<?> classToMap : getClassesForWorkerJar()) {
                        remapClass(classToMap, outputStream);
                    }
                }
            } catch (Exception e) {
                throw new GradleException("Could not generate worker process bootstrap classes.", e);
            }
        }

        private Set<Class<?>> getClassesForWorkerJar() {
            // TODO - calculate this list of classes dynamically
            List<Class<?>> classes = Arrays.asList(
                GradleWorkerMain.class,
                BootstrapSecurityManager.class,
                EncodedStream.EncodedInput.class,
                ClassLoaderUtils.class,
                FilteringClassLoader.class,
                FilteringClassLoader.Action.class,
                FilteringClassLoader.Trie.Builder.class,
                FilteringClassLoader.Trie.class,
                FilteringClassLoader.TrieSet.class,
                ClassLoaderHierarchy.class,
                ClassLoaderVisitor.class,
                ClassLoaderSpec.class,
                SystemClassLoaderSpec.class,
                JavaReflectionUtil.class,
                JavaMethod.class,
                GradleException.class,
                NoSuchPropertyException.class,
                NoSuchMethodException.class,
                UncheckedException.class,
                PropertyAccessor.class,
                PropertyMutator.class,
                Factory.class,
                Spec.class,
                JavaVersion.class,
                JavaVersionParser.class);
            Set<Class<?>> result = new HashSet<Class<?>>(classes);
            for (Class<?> klass : classes) {
                result.addAll(Arrays.asList(klass.getDeclaredClasses()));
            }

            return result;
        }

        private void remapClass(Class<?> classToMap, ZipOutputStream jar) throws IOException {
            String internalName = Type.getInternalName(classToMap);
            String resourceName = internalName.concat(".class");
            URL resource = WorkerProcessClassPathProvider.class.getClassLoader().getResource(resourceName);
            if (resource == null) {
                throw new IllegalStateException("Could not locate classpath resource for class " + classToMap.getName());
            }
            InputStream inputStream = resource.openStream();
            ClassReader classReader;
            try {
                classReader = new ClassReader(inputStream);
            } finally {
                inputStream.close();
            }
            ClassWriter classWriter = new ClassWriter(0);
            ClassVisitor remappingVisitor = new ClassRemapper(classWriter, remapper);
            classReader.accept(remappingVisitor, ClassReader.EXPAND_FRAMES);
            byte[] remappedClass = classWriter.toByteArray();
            String remappedClassName = remapper.map(internalName).concat(".class");
            jar.putNextEntry(new ZipEntry(remappedClassName));
            jar.write(remappedClass);
        }

        private static class WorkerClassRemapper extends Remapper {
            @Override
            public String map(String typeName) {
                if (typeName.startsWith("org/gradle/")) {
                    return WORKER_GRADLE_REMAPPING_PREFIX + "/" + typeName;
                }
                return typeName;
            }
        }
    }

}
