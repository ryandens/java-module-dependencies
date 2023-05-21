/*
 * Copyright 2022 the GradleX team.
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

package org.gradlex.javamodule.dependencies;

import org.gradle.api.GradleException;
import org.gradle.api.NonNullApi;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.VersionCatalogsExtension;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.util.GradleVersion;
import org.gradlex.javamodule.dependencies.internal.bridges.DependencyAnalysisBridge;
import org.gradlex.javamodule.dependencies.internal.bridges.ExtraJavaModuleInfoBridge;
import org.gradlex.javamodule.dependencies.internal.utils.ModuleInfo;
import org.gradlex.javamodule.dependencies.tasks.ModuleDependencyReport;
import org.gradlex.javamodule.dependencies.tasks.ModuleDirectivesOrderingCheck;
import org.gradlex.javamodule.dependencies.tasks.ModuleInfoGeneration;
import org.gradlex.javamodule.dependencies.tasks.ModulePathAnalysis;
import org.gradlex.javamodule.dependencies.tasks.ModuleVersionRecommendation;

import javax.annotation.Nullable;
import java.io.File;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.gradle.api.plugins.HelpTasksPlugin.HELP_GROUP;
import static org.gradle.language.base.plugins.LifecycleBasePlugin.VERIFICATION_GROUP;
import static org.gradlex.javamodule.dependencies.JavaModuleDependenciesExtension.JAVA_MODULE_DEPENDENCIES;
import static org.gradlex.javamodule.dependencies.internal.utils.DependencyDeclarationsUtil.declaredDependencies;
import static org.gradlex.javamodule.dependencies.internal.utils.ModuleNamingUtil.sourceSetToModuleName;
import static org.gradlex.javamodule.dependencies.internal.utils.TaskConfigurationUtil.isJavaCompileTask;
import static org.gradlex.javamodule.dependencies.internal.utils.TaskConfigurationUtil.isJavadocTask;

@SuppressWarnings("unused")
@NonNullApi
public abstract class JavaModuleDependenciesPlugin implements Plugin<Project> {

    private static final String EXTRA_JAVA_MODULE_INFO_PLUGIN_ID = "org.gradlex.extra-java-module-info";

    @Override
    public void apply(Project project) {
        if (GradleVersion.current().compareTo(GradleVersion.version("7.4")) < 0) {
            throw new GradleException("This plugin requires Gradle 7.4+");
        }

        VersionCatalogsExtension versionCatalogs = project.getExtensions().findByType(VersionCatalogsExtension.class);
        JavaModuleDependenciesExtension javaModuleDependencies = project.getExtensions().create(
                JAVA_MODULE_DEPENDENCIES, JavaModuleDependenciesExtension.class, versionCatalogs);

        setupExtraJavaModulePluginBridge(project, javaModuleDependencies);

        project.getPlugins().withType(JavaPlugin.class, javaPlugin -> setupForJavaProject(project, javaModuleDependencies));
    }

    private void setupForJavaProject(Project project, JavaModuleDependenciesExtension javaModuleDependencies) {
        SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        sourceSets.all(sourceSet -> {
            process(ModuleInfo.Directive.REQUIRES, sourceSet.getImplementationConfigurationName(), sourceSet, project, javaModuleDependencies);
            process(ModuleInfo.Directive.REQUIRES_STATIC, sourceSet.getCompileOnlyConfigurationName(), sourceSet, project, javaModuleDependencies);
            process(ModuleInfo.Directive.REQUIRES_TRANSITIVE, sourceSet.getApiConfigurationName(), sourceSet, project, javaModuleDependencies);
            process(ModuleInfo.Directive.REQUIRES_STATIC_TRANSITIVE, sourceSet.getCompileOnlyApiConfigurationName(), sourceSet, project, javaModuleDependencies);
            process(ModuleInfo.Directive.REQUIRES_RUNTIME, sourceSet.getRuntimeOnlyConfigurationName(), sourceSet, project, javaModuleDependencies);

            project.getTasks().configureEach(task -> {
                if (isJavaCompileTask(task, sourceSet) || isJavadocTask(task, sourceSet)) {
                    javaModuleDependencies.doAddRequiresRuntimeSupport(task, sourceSet);
                }
            });
        });

        TaskProvider<Task> checkAllModuleInfo = project.getTasks().register("checkAllModuleInfo", t -> {
            t.setGroup(VERIFICATION_GROUP);
            t.setDescription("Check scope and order of directives in 'module-info.java' files");
        });

        setupOrderingCheckTasks(project, checkAllModuleInfo, javaModuleDependencies);
        setupModuleDependenciesTask(project);
        setupReportTasks(project, javaModuleDependencies);
        setupMigrationTasks(project, javaModuleDependencies);

        project.getPlugins().withId("com.autonomousapps.dependency-analysis", analysisPlugin -> {
            DependencyAnalysisBridge.registerDependencyAnalysisPostProcessingTask(project, checkAllModuleInfo, javaModuleDependencies);
        });
    }

    private void setupExtraJavaModulePluginBridge(Project project, JavaModuleDependenciesExtension javaModuleDependencies) {
        project.getPlugins().withId(EXTRA_JAVA_MODULE_INFO_PLUGIN_ID,
                e -> ExtraJavaModuleInfoBridge.autoRegisterPatchedModuleMappings(project, javaModuleDependencies));
    }

    private void setupModuleDependenciesTask(Project project) {
        SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        TaskProvider<ModuleDependencyReport> moduleDependencies = project.getTasks().register("moduleDependencies", ModuleDependencyReport.class, t -> {
            t.setGroup(HELP_GROUP);
            if (t.isConfigurationSetByUser()) {
                Configuration conf = t.getConfigurations().iterator().next();
                t.getModuleArtifacts().add(project.provider(() -> conf.getIncoming().getArtifacts()));
            }
        });
        sourceSets.all(sourceSet -> {
            moduleDependencies.configure(t -> {
                if (!t.isConfigurationSetByUser()) {
                    HashSet<Configuration> reportConfigurations = new HashSet<>();
                    if (t.getConfigurations() != null) {
                        reportConfigurations.addAll(t.getConfigurations());
                    }
                    Configuration cpClasspath = project.getConfigurations().getByName(sourceSet.getCompileClasspathConfigurationName());
                    Configuration rtClasspath = project.getConfigurations().getByName(sourceSet.getRuntimeClasspathConfigurationName());
                    reportConfigurations.add(cpClasspath);
                    reportConfigurations.add(rtClasspath);
                    t.setConfigurations(reportConfigurations);

                    t.getModuleArtifacts().add(project.provider(() -> cpClasspath.getIncoming().getArtifacts()));
                    t.getModuleArtifacts().add(project.provider(() -> rtClasspath.getIncoming().getArtifacts()));
                }
            });
        });
    }

    private void setupReportTasks(Project project, JavaModuleDependenciesExtension javaModuleDependencies) {
        SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        boolean usesVersionCatalog = project.getExtensions().findByType(VersionCatalogsExtension.class) != null;
        project.getTasks().register("analyzeModulePath", ModulePathAnalysis.class, t -> {
            t.setGroup(HELP_GROUP);
            t.setDescription("Check consistency of the Module Path");

            for (SourceSet sourceSet : sourceSets) {
                t.getClasspathConfigurations().add(project.getConfigurations().getByName(sourceSet.getCompileClasspathConfigurationName()));
                t.getClasspathConfigurations().add(project.getConfigurations().getByName(sourceSet.getRuntimeClasspathConfigurationName()));
            }
        });
        project.getTasks().register("recommendModuleVersions", ModuleVersionRecommendation.class, t -> {
            t.setGroup(HELP_GROUP);
            t.setDescription("Query repositories for latest stable versions of the used Java Modules");

            t.getPrintForPlatform().convention(!usesVersionCatalog);
            t.getPrintForCatalog().convention(usesVersionCatalog);
        });
    }

    private void setupMigrationTasks(Project project, JavaModuleDependenciesExtension javaModuleDependencies) {
        SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        ConfigurationContainer configurations = project.getConfigurations();

        TaskProvider<Task> generateAllModuleInfoFiles = project.getTasks().register("generateAllModuleInfoFiles", t -> {
            t.setGroup("java modules");
            t.setDescription("Generate 'module-info.java' files in all source sets");
        });

        sourceSets.all(sourceSet -> {
            TaskProvider<ModuleInfoGeneration> generateModuleInfo = project.getTasks().register(sourceSet.getTaskName("generate", "ModuleInfoFile"), ModuleInfoGeneration.class, t -> {
                t.setGroup("java modules");
                t.setDescription("Generate 'module-info.java' in '" + sourceSet.getName() + "' source set");

                t.getModuleNameToGA().putAll(javaModuleDependencies.getModuleNameToGA());

                t.getModuleName().convention(project.provider(() -> project.getGroup() + "." + sourceSetToModuleName(project.getName(), sourceSet.getName())));

                t.getApiDependencies().convention(declaredDependencies(project, sourceSet.getApiConfigurationName()));
                t.getImplementationDependencies().convention(declaredDependencies(project, sourceSet.getImplementationConfigurationName()));
                t.getCompileOnlyApiDependencies().convention(declaredDependencies(project, sourceSet.getCompileOnlyApiConfigurationName()));
                t.getCompileOnlyDependencies().convention(declaredDependencies(project, sourceSet.getCompileOnlyConfigurationName()));
                t.getRuntimeOnlyDependencies().convention(declaredDependencies(project, sourceSet.getRuntimeOnlyConfigurationName()));

                t.getModuleInfoFile().convention(project.getLayout().file(project.provider(() ->
                        new File(sourceSet.getJava().getSrcDirs().iterator().next(), "module-info.java"))));
            });

            generateAllModuleInfoFiles.configure(t -> t.dependsOn(generateModuleInfo));
        });
    }

    private void setupOrderingCheckTasks(Project project, TaskProvider<Task> checkAllModuleInfo, JavaModuleDependenciesExtension javaModuleDependencies) {
        SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        ConfigurationContainer configurations = project.getConfigurations();

        sourceSets.all(sourceSet -> {
            TaskProvider<ModuleDirectivesOrderingCheck> checkModuleInfo = project.getTasks().register(sourceSet.getTaskName("check", "ModuleInfo"), ModuleDirectivesOrderingCheck.class, t -> {
                t.setGroup("java modules");
                t.setDescription("Check order of directives in 'module-info.java' in '" + sourceSet.getName() + "' source set");

                ModuleInfo moduleInfo = javaModuleDependencies.getModuleInfoCache().get(sourceSet);

                t.getModuleInfoPath().convention(project.getLayout().getProjectDirectory().getAsFile().getParentFile().toPath().relativize(
                        sourceSet.getJava().getSrcDirs().iterator().next().toPath()).resolve("module-info.java").toString());
                t.getModuleNamePrefix().convention(moduleInfo.moduleNamePrefix(project.getName(), sourceSet.getName()));
                t.getModuleInfo().convention(moduleInfo);
            });

            checkAllModuleInfo.configure(t -> t.dependsOn(checkModuleInfo));
        });
    }

    private void process(ModuleInfo.Directive moduleDirective, String gradleConfiguration, SourceSet sourceSet, Project project, JavaModuleDependenciesExtension javaModuleDependenciesExtension) {
        Configuration conf = project.getConfigurations().findByName(gradleConfiguration);
        if (conf != null) {
            conf.withDependencies(d -> readModuleInfo(moduleDirective, sourceSet, project, conf, javaModuleDependenciesExtension));
        } else {
            project.getConfigurations().whenObjectAdded(lateAddedConf -> {
                if (gradleConfiguration.equals(lateAddedConf.getName())) {
                    lateAddedConf.withDependencies(d -> readModuleInfo(moduleDirective, sourceSet, project, lateAddedConf, javaModuleDependenciesExtension));
                }
            });
        }
    }

    private void readModuleInfo(ModuleInfo.Directive moduleDirective, SourceSet sourceSet, Project project, Configuration configuration, JavaModuleDependenciesExtension javaModuleDependenciesExtension) {
        if (javaModuleDependenciesExtension.getAnalyseOnly().get()) {
            return;
        }
        ModuleInfo moduleInfo = javaModuleDependenciesExtension.getModuleInfoCache().get(sourceSet);
        String ownModuleNamesPrefix = moduleInfo.moduleNamePrefix(project.getName(), sourceSet.getName());
        for (String moduleName : moduleInfo.get(moduleDirective)) {
            declareDependency(moduleName, ownModuleNamesPrefix, moduleInfo.getFilePath(), project, configuration, javaModuleDependenciesExtension);
        }
    }

    private void declareDependency(String moduleName, @Nullable String ownModuleNamesPrefix, File moduleInfoFile, Project project, Configuration configuration, JavaModuleDependenciesExtension javaModuleDependencies) {
        if (JDKInfo.MODULES.contains(moduleName)) {
            // The module is part of the JDK, no dependency required
            return;
        }

        Map<String, String> allProjectNamesAndGroups = project.getRootProject().getSubprojects().stream().collect(
                Collectors.toMap(Project::getName, p -> (String) p.getGroup()));

        Provider<Map<String, Object>> gav = javaModuleDependencies.gav(moduleName);
        String moduleNameSuffix = ownModuleNamesPrefix == null ? null :
                moduleName.startsWith(ownModuleNamesPrefix + ".") ? moduleName.substring(ownModuleNamesPrefix.length() + 1) :
                        ownModuleNamesPrefix.isEmpty() ? moduleName : null;

        String parentPath = project.getParent() == null ? "" : project.getParent().getPath();
        Optional<String> perfectMatch = allProjectNamesAndGroups.keySet().stream().filter(p -> p.replace("-", ".").equals(moduleNameSuffix)).findFirst();
        Optional<String> existingProjectName = allProjectNamesAndGroups.keySet().stream().filter(p -> moduleNameSuffix != null && moduleNameSuffix.startsWith(p.replace("-", ".") + "."))
                .max(Comparator.comparingInt(String::length));

        if (perfectMatch.isPresent()) {
            Dependency projectDependency = project.getDependencies().add(
                    configuration.getName(), project.project(parentPath + ":" + perfectMatch.get()));
            assert projectDependency != null;
            projectDependency.because(moduleName);
        } else if (existingProjectName.isPresent()) {
            // no exact match -> add capability to point at Module in other source set
            ProjectDependency projectDependency = (ProjectDependency) project.getDependencies().add(
                    configuration.getName(), project.project(parentPath + ":" + existingProjectName.get()));
            assert projectDependency != null;
            projectDependency.capabilities(c -> c.requireCapabilities(
                    allProjectNamesAndGroups.get(existingProjectName.get()) + ":" + moduleNameSuffix.replace(".", "-")));
            projectDependency.because(moduleName);
        } else if (gav.isPresent()) {
            project.getDependencies().addProvider(configuration.getName(), gav, d -> d.because(moduleName));
            if (!gav.get().containsKey(GAV.VERSION)) {
                warnVersionMissing(moduleName, gav.get(), moduleInfoFile, project, javaModuleDependencies);
            }
        } else {
            project.getLogger().lifecycle(
                    "[WARN] [Java Module Dependencies] javaModuleDependencies.moduleNameToGA.put(\"" + moduleName + "\", \"group:artifact\") mapping is missing.");
        }
    }

    private void warnVersionMissing(String moduleName, Map<String, Object> ga, File moduleInfoFile, Project project, JavaModuleDependenciesExtension javaModuleDependencies) {
        if (javaModuleDependencies.getWarnForMissingVersions().get()) {
            project.getLogger().warn("[WARN] [Java Module Dependencies] No version defined in catalog - " + ga.get(GAV.GROUP) + ":" + ga.get(GAV.ARTIFACT) + " - "
                    + moduleDebugInfo(moduleName.replace('.', '_'), moduleInfoFile, project.getRootDir()));
        }
    }

    private String moduleDebugInfo(String moduleName, File moduleInfoFile, File rootDir) {
        return moduleName
                + " (required in "
                + moduleInfoFile.getAbsolutePath().substring(rootDir.getAbsolutePath().length() + 1)
                + ")";
    }

}
