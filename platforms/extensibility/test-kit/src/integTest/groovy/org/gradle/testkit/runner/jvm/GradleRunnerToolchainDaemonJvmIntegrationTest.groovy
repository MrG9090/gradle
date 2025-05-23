/*
 * Copyright 2024 the original author or authors.
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

package org.gradle.testkit.runner.jvm

import org.gradle.internal.jvm.Jvm
import org.gradle.testkit.runner.GradleRunner

/**
 * Verifies JDK compatibility for GradleRunner when configuring the daemon JVM via toolchains.
 */
class GradleRunnerToolchainDaemonJvmIntegrationTest extends GradleRunnerExplicitDaemonJvmIntegrationTest {

    @Override
    def configureBuild(Jvm jvm) {
        writeJvmCriteria(jvm)
    }

    @Override
    def configureRunner(GradleRunner runner, Jvm jvm) {
        runner.withArguments(["-Porg.gradle.java.installations.paths=" + jvm.javaHome.canonicalPath] + runner.getArguments())
    }
}
