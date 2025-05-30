/*
 * Copyright 2019 the original author or authors.
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

package org.gradle.workers.internal;

import org.gradle.internal.service.scopes.Scope;
import org.gradle.internal.service.scopes.ServiceScope;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;

import java.util.Set;

@ServiceScope(Scope.UserHome.class)
public interface ActionExecutionSpecFactory {
    <T extends WorkParameters> TransportableActionExecutionSpec newTransportableSpec(IsolatedParametersActionExecutionSpec<T> spec);

    <T extends WorkParameters> IsolatedParametersActionExecutionSpec<T> newIsolatedSpec(String displayName, Class<? extends WorkAction<T>> implementationClass, T params, WorkerRequirement workerRequirement, Set<Class<?>> additionalWhitelistedServices);

    <T extends WorkParameters> SimpleActionExecutionSpec<T> newSimpleSpec(IsolatedParametersActionExecutionSpec<T> spec);

    <T extends WorkParameters> SimpleActionExecutionSpec<T> newSimpleSpec(TransportableActionExecutionSpec spec);
}
