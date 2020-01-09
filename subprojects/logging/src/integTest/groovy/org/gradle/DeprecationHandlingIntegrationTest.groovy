/*
 * Copyright 2016 the original author or authors.
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

package org.gradle

import org.gradle.api.JavaVersion
import org.gradle.api.logging.configuration.WarningMode
import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.internal.featurelifecycle.LoggingDeprecatedFeatureHandler
import org.gradle.util.GradleVersion
import spock.lang.Unroll

class DeprecationHandlingIntegrationTest extends AbstractIntegrationSpec {
    public static final String PLUGIN_DEPRECATION_MESSAGE = 'The DeprecatedPlugin plugin has been deprecated'
    private static final String RUN_WITH_STACKTRACE = '(Run with --stacktrace to get the full stack trace of this deprecation warning.)'

    def setup() {
        file('buildSrc/src/main/java/DeprecatedTask.java') << """
            import org.gradle.api.DefaultTask;
            import org.gradle.api.tasks.TaskAction;
            import org.gradle.util.DeprecationLogger;
            import org.gradle.internal.deprecation.DeprecationMessage;

            public class DeprecatedTask extends DefaultTask {
                @TaskAction
                void causeDeprecationWarning() {
                    DeprecationLogger.nagUserWith(DeprecationMessage.replacedTask("deprecated", "foobar"));
                    System.out.println("DeprecatedTask.causeDeprecationWarning() executed.");
                }

                public static void someFeature() {
                    DeprecationLogger.nagUserWith(DeprecationMessage.discontinuedMethod("someFeature()"));
                    System.out.println("DeprecatedTask.someFeature() executed.");
                }

                void otherFeature() {
                    DeprecationLogger.nagUserWith(DeprecationMessage.discontinuedMethod("otherFeature()").withAdvice("Relax. This is just a test."));
                    System.out.println("DeprecatedTask.otherFeature() executed.");
                }

            }
        """.stripIndent()
        file('buildSrc/src/main/java/DeprecatedPlugin.java') << """
            import org.gradle.api.Plugin;
            import org.gradle.api.Project;
            import org.gradle.util.DeprecationLogger;
            import org.gradle.internal.deprecation.DeprecationMessage;

            public class DeprecatedPlugin implements Plugin<Project> {
                @Override
                public void apply(Project project) {
                    DeprecationLogger.nagUserWith(DeprecationMessage.pluginReplacedWithExternalOne("DeprecatedPlugin", "Foobar"));
                    project.getTasks().create("deprecated", DeprecatedTask.class);
                }
            }
        """.stripIndent()
        file('buildSrc/src/main/resources/META-INF/gradle-plugins/org.acme.deprecated.properties') << """
            implementation-class=DeprecatedPlugin
        """.stripIndent()
    }

    @Unroll
    def 'DeprecatedPlugin and DeprecatedTask - #scenario'() {
        given:
        buildFile << """
            apply plugin: DeprecatedPlugin // line 2

            DeprecatedTask.someFeature() // line 4
            DeprecatedTask.someFeature()

            task broken(type: DeprecatedTask) {
                doLast {
                    otherFeature() // line 9
                }
            }
        """.stripIndent()

        when:
        if (!fullStacktraceEnabled) {
            executer.withFullDeprecationStackTraceDisabled()
        }
        if (warningsCountInConsole > 0) {
            executer.expectDeprecationWarnings(warningsCountInConsole)
        }
        executer.withWarningMode(warnings)
        warnings == WarningMode.Fail ? fails('deprecated', 'broken') : succeeds('deprecated', 'broken')

        then:
        output.contains('build.gradle:2)') == warningsCountInConsole > 0
        output.contains('build.gradle:4)') == warningsCountInConsole > 0
        output.contains('build.gradle:9)') == warningsCountInConsole > 0

        and:
        output.contains(PLUGIN_DEPRECATION_MESSAGE) == warningsCountInConsole > 0
        output.contains('The someFeature() method has been deprecated') == warningsCountInConsole > 0
        output.contains('The otherFeature() method has been deprecated') == warningsCountInConsole > 0
        output.contains('The deprecated task has been deprecated') == warningsCountInConsole > 0

        and:
        output.contains(LoggingDeprecatedFeatureHandler.WARNING_SUMMARY) == (warningsCountInSummary > 0)
        output.contains("Use '--warning-mode all' to show the individual deprecation warnings.") == (warningsCountInSummary > 0)
        output.contains(LoggingDeprecatedFeatureHandler.WARNING_LOGGING_DOCS_MESSAGE) == (warningsCountInSummary > 0)

        and: "system stack frames are filtered"
        !output.contains('jdk.internal.')
        !output.contains('sun.') || output.contains('sun.run')
        !output.contains('org.codehaus.groovy.')
        !output.contains('org.gradle.internal.metaobject.')
        !output.contains('org.gradle.kotlin.dsl.execution.')

        and:
        assertFullStacktraceResult(fullStacktraceEnabled, warningsCountInConsole)

        and:
        if (warnings == WarningMode.Fail) {
            failure.assertHasDescription("Deprecated Gradle features were used in this build, making it incompatible with Gradle ${GradleVersion.current().nextMajor.version}")
        }

        where:
        scenario                                        | warnings            | warningsCountInConsole | warningsCountInSummary | fullStacktraceEnabled
        'without stacktrace and --warning-mode=all'     | WarningMode.All     | 4                      | 0                      | false
        'with stacktrace and --warning-mode=all'        | WarningMode.All     | 4                      | 0                      | true
        'without stacktrace and --warning-mode=no'      | WarningMode.None    | 0                      | 0                      | false
        'with stacktrace and --warning-mode=no'         | WarningMode.None    | 0                      | 0                      | true
        'without stacktrace and --warning-mode=summary' | WarningMode.Summary | 0                      | 4                      | false
        'with stacktrace and --warning-mode=summary'    | WarningMode.Summary | 0                      | 4                      | true
        'without stacktrace and --warning-mode=fail'    | WarningMode.Fail    | 4                      | 0                      | false
        'with stacktrace and --warning-mode=fail'       | WarningMode.Fail    | 4                      | 0                      | true
    }

    def 'build error and deprecation failure combined'() {
        given:
        buildFile << """
            apply plugin: DeprecatedPlugin // line 2

            task broken() {
                doLast {
                    throw new IllegalStateException("Can't do that")
                }
            }
        """.stripIndent()

        when:
        executer.expectDeprecationWarning("The DeprecatedPlugin plugin has been deprecated. This is scheduled to be removed in ${GradleVersion.current().nextMajor}. Consider using the Foobar plugin instead.")
        executer.withWarningMode(WarningMode.Fail)

        then:
        fails('broken')
        output.contains('build.gradle:2)')
        failure.assertHasCause("Can't do that")
        failure.assertHasDescription('Deprecated Gradle features were used in this build')
    }

    def 'DeprecatedPlugin from init script - without full stacktrace.'() {
        given:
        def initScript = file("init.gradle") << """
            allprojects {
                org.gradle.util.DeprecationLogger.nagUserWith(org.gradle.internal.deprecation.DeprecationMessage.pluginReplacedWithExternalOne("DeprecatedPlugin", "Foobar")) // line 2
            }
        """.stripIndent()

        when:
        executer.withFullDeprecationStackTraceDisabled()
        executer.expectDeprecationWarning()
        executer.usingInitScript(initScript)
        run '-s'

        then:
        output.contains('init.gradle:3)')

        output.count(PLUGIN_DEPRECATION_MESSAGE) == 1

        output.count('\tat') == 1
        output.count('(Run with --stacktrace to get the full stack trace of this deprecation warning.)') == 1
    }

    @Unroll
    def 'DeprecatedPlugin from applied script - #scenario'() {
        given:
        file("project.gradle") << """
            apply plugin:  DeprecatedPlugin // line 2
        """.stripIndent()

        buildFile << """
            allprojects {
                apply from: 'project.gradle' // line 2
            }
        """.stripIndent()

        when:
        if (!withFullStacktrace) {
            executer.withFullDeprecationStackTraceDisabled()
        }
        executer.expectDeprecationWarning()
        run()

        then:
        output.contains('project.gradle:2)')
        output.contains('build.gradle:2)') == withFullStacktrace
        output.count(PLUGIN_DEPRECATION_MESSAGE) == 1

        withFullStacktrace ? (output.count('\tat') > 1) : (output.count('\tat') == 1)
        withFullStacktrace == !output.contains(RUN_WITH_STACKTRACE)

        where:
        scenario                  | withFullStacktrace
        'without full stacktrace' | false
        'with full stacktrace'    | true
    }

    @Unroll
    def 'DeprecatedPlugin from applied kotlin script - #scenario'() {
        given:
        file("project.gradle.kts") << """
           apply(plugin = "org.acme.deprecated") // line 1
        """.stripIndent()

        buildFile << """
            allprojects {
                apply from: 'project.gradle.kts' // line 3
            }
        """.stripIndent()

        when:
        if (!withFullStacktrace) {
            executer.withFullDeprecationStackTraceDisabled()
        }
        executer.expectDeprecationWarning()
        run()

        then:
        output.contains('build.gradle:3)')
        output.contains('build.gradle:2)') == withFullStacktrace
        output.contains('Project_gradle.<init>') == withFullStacktrace
        output.count(PLUGIN_DEPRECATION_MESSAGE) == 1

        withFullStacktrace ? (output.count('\tat') > 1) : (output.count('\tat') == 1)
        withFullStacktrace == !output.contains(RUN_WITH_STACKTRACE)

        where:
        scenario                  | withFullStacktrace
        'without full stacktrace' | false
        'with full stacktrace'    | true
    }

    def incrementWarningCountIfJava7(int warningCount) {
        return JavaVersion.current().isJava7() ? warningCount + 1 : warningCount
    }

    boolean assertFullStacktraceResult(boolean fullStacktraceEnabled, int warningsCountInConsole) {
        if (warningsCountInConsole == 0) {
            output.count('\tat') == 0 && output.count(RUN_WITH_STACKTRACE) == 0
        } else if (fullStacktraceEnabled) {
            output.count('\tat') > 3 && output.count(RUN_WITH_STACKTRACE) == 0
        } else {
            output.count('\tat') == 3 && output.count(RUN_WITH_STACKTRACE) == 3
        }
    }

}
