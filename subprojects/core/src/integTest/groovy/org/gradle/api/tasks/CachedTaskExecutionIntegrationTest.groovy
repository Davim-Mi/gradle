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

package org.gradle.api.tasks

import org.gradle.integtests.fixtures.AbstractIntegrationSpec

class CachedTaskExecutionIntegrationTest extends AbstractIntegrationSpec {
    def cacheDir = testDirectoryProvider.createDir("task-cache")

    def setup() {
        buildFile << """
            apply plugin: "java"
        """

        file("src/main/java/Hello.java") << """
            public class Hello {
                public static void main(String... args) {
                    System.out.println("Hello World!");
                }
            }
        """
        file("src/main/resources/resource.properties") << """
            test=true
        """
    }

    def "tasks get cached"() {
        expect:
        succeedsWithCache "assemble"
        skippedTasks.empty

        succeedsWithCache "clean"

        succeedsWithCache "assemble"
        nonSkippedTasks.empty
    }

    def "clean doesn't get cached"() {
        expect:
        succeedsWithCache "assemble"
        succeedsWithCache "clean"
        succeedsWithCache "assemble"
        succeedsWithCache "clean"
        nonSkippedTasks.contains ":clean"
    }

    def "task with cacheIf off doesn't get cached"() {
        buildFile << """
            compileJava.cacheIf { false }
        """

        expect:
        succeedsWithCache "assemble"
        succeedsWithCache "clean"
        succeedsWithCache "assemble"
        // :compileJava is not cached, but :jar is still cached as its inputs haven't changed
        nonSkippedTasks.contains ":compileJava"
        skippedTasks.contains ":jar"
    }

    def succeedsWithCache(String... tasks) {
        executer.withArguments "-Dorg.gradle.cache.tasks=true", "-Dorg.gradle.cache.tasks.directory=" + cacheDir.absolutePath
        succeeds tasks
    }
}