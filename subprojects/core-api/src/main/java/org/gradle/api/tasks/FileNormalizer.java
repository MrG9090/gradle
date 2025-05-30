/*
 * Copyright 2017 the original author or authors.
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
package org.gradle.api.tasks;

/**
 * A normalizer used to remove unwanted noise when considering file inputs.
 *
 * The default behavior without specifying a normalizer is to ignore the order of the files.
 *
 * @see TaskInputFilePropertyBuilder#withNormalizer(Class)
 * @see ClasspathNormalizer
 * @see CompileClasspathNormalizer
 *
 * @since 4.3
 */
public interface FileNormalizer {
}
