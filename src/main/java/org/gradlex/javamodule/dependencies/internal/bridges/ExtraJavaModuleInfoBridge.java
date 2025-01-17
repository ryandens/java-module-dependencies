/*
 * Copyright the GradleX team.
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

package org.gradlex.javamodule.dependencies.internal.bridges;

import org.gradlex.javamodule.moduleinfo.ExtraJavaModuleInfoPluginExtension;
import org.gradlex.javamodule.dependencies.JavaModuleDependenciesExtension;
import org.gradle.api.Project;

import java.util.Map;
import java.util.stream.Collectors;

public class ExtraJavaModuleInfoBridge {

    public static void autoRegisterPatchedModuleMappings(Project project, JavaModuleDependenciesExtension javaModuleDependencies) {
        ExtraJavaModuleInfoPluginExtension extraJavaModuleInfo = project.getExtensions().getByType(ExtraJavaModuleInfoPluginExtension.class);
        javaModuleDependencies.getModuleNameToGA().putAll(extraJavaModuleInfo.getModuleSpecs().map(
                moduleSpecs -> moduleSpecs.entrySet().stream().collect(Collectors.toMap(e -> e.getValue().getModuleName(), Map.Entry::getKey))));
    }
}
