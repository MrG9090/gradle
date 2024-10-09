/*
 * Copyright 2018 the original author or authors.
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

package org.gradle.nativeplatform.toolchain.internal.xcode;

import com.google.common.collect.ImmutableList;
import org.gradle.process.internal.ExecActionFactory;

import javax.inject.Inject;
import java.util.List;

public class MacOSSdkPathLocator extends AbstractLocator {
    @Inject
    public MacOSSdkPathLocator(ExecActionFactory execActionFactory) {
        super(execActionFactory);
    }

    @Override
    protected List<String> getXcrunFlags() {
        // The manpage of `xcrun` states for the --sdk argument:
        // If no --sdk argument is provided,
        // then the SDK used will be taken from the SDKROOT environment variable, if present.
        // Thus, if `SDKROOT` is set, avoid changing behavior in relation to 8.10 and before.
        String sdkroot = System.getenv("SDKROOT");
        if (sdkroot != null) {
            return ImmutableList.of("--show-sdk-path");
        }
        return ImmutableList.of("--sdk", "macosx", "--show-sdk-path");
    }
}
