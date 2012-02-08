/*
 * Copyright 2011 the original author or authors.
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

package org.gradle.launcher.daemon

import org.gradle.internal.nativeplatform.OperatingSystem
import org.gradle.launcher.daemon.logging.DaemonMessages
import org.gradle.tests.fixtures.ConcurrentTestUtil
import spock.lang.IgnoreIf
import spock.lang.Timeout
import static org.gradle.tests.fixtures.ConcurrentTestUtil.poll

/**
 * by Szczepan Faber, created at: 1/20/12
 */
class DaemonFeedbackIntegrationSpec extends DaemonIntegrationSpec {

    def cleanup() {
        stopDaemonsNow()
    }

    @Timeout(10)
    @IgnoreIf({OperatingSystem.current().isWindows()})
    def "promptly shows decent message when daemon cannot be started"() {
        when:
        executer.withArguments("-Dorg.gradle.jvmargs=-Xyz").run()

        then:
        def ex = thrown(Exception)
        ex.message.contains(DaemonMessages.UNABLE_TO_START_DAEMON)
        ex.message.contains("-Xyz")
    }

    @Timeout(10)
    def "promptly shows decent message when awkward java home used"() {
        def dummyJdk = distribution.file("dummyJdk").createDir()
        assert dummyJdk.isDirectory()
        
        when:
        executer.withArguments("-Dorg.gradle.java.home=${dummyJdk.absolutePath}").run()

        then:
        def ex = thrown(Exception)
        ex.message.contains('org.gradle.java.home')
        ex.message.contains(dummyJdk.absolutePath)
    }

    def "daemon log contains all necessary logging"() {
        given:
        def baseDir = distribution.file("daemonBaseDir").createDir()
        executer.withDaemonBaseDir(baseDir)
        distribution.file("build.gradle") << "println 'Hello build!'"

        when:
        executer.withArguments("-i").run()

        then:
        def log = readLog(baseDir)

        //output before started relying logs via connection
        log.count(DaemonMessages.PROCESS_STARTED) == 1
        //output after started relying logs via connection
        log.count(DaemonMessages.STARTED_RELAYING_LOGS) == 1
        //output from the build
        log.count('Hello build!') == 1

        when: "another build requested with the same daemon"
        executer.withArguments("-i").run()

        then:
        def aLog = readLog(baseDir)

        aLog.count(DaemonMessages.PROCESS_STARTED) == 1
        aLog.count(DaemonMessages.STARTED_RELAYING_LOGS) == 2
        aLog.count('Hello build!') == 2
    }

    def "daemon infrastructure logs with DEBUG"() {
        given:
        def baseDir = distribution.file("daemonBaseDir").createDir()
        executer.withDaemonBaseDir(baseDir)

        when: "runing build with --info"
        executer.withArguments("-i").run()

        then:
        def log = readLog(baseDir)
        //TODO SF make sure that those are DEBUG statements
        log.findAll(DaemonMessages.STARTED_EXECUTING_COMMAND).size() == 1
        //if the log level was configured back to DEBUG after build:
        ConcurrentTestUtil.poll {
            //in theory the client could have received result and complete
            // but the daemon has not yet finished processing hence polling
            readLog(baseDir).findAll(DaemonMessages.FINISHED_EXECUTING_COMMAND).size() == 1
        }

        when: "another build requested with the same daemon with --info"
        executer.withArguments("-i").run()

        then:
        def aLog = readLog(baseDir)
        aLog.findAll(DaemonMessages.STARTED_EXECUTING_COMMAND).size() == 2
    }

    def "daemon log honors log levels for logging"() {
        given:
        def baseDir = distribution.file("daemonBaseDir").createDir()
        executer.withDaemonBaseDir(baseDir)
        distribution.file("build.gradle") << """
            println 'println me!'

            logger.debug('debug me!')
            logger.info('info me!')
            logger.quiet('quiet me!')
            logger.lifecycle('lifecycle me!')
            logger.warn('warn me!')
            logger.error('error me!')
        """

        when:
        executer.withArguments("-q").run()

        then:
        def log = readLog(baseDir)

        //before the build is requested we don't know the log level so we print eagerly
        log.count(DaemonMessages.PROCESS_STARTED) == 1
        //after the build started log level is understood
        log.count(DaemonMessages.STARTED_RELAYING_LOGS) == 0
        //output from the build:
        log.count('debug me!') == 0
        log.count('info me!') == 0
        log.count('println me!') == 1
        log.count('quiet me!') == 1
        log.count('lifecycle me!') == 0
        log.count('warn me!') == 0
        log.count('error me!') == 1
    }

    def "foreground daemon log honors log levels for logging"() {
        given:
        def baseDir = distribution.file("daemonBaseDir").createDir()
        distribution.file("build.gradle") << """
            logger.debug('debug me!')
            logger.info('info me!')
        """

        when:
        def daemon = executer.setAllowExtraLogging(false).withDaemonBaseDir(baseDir).withArguments("--foreground").start()
        
        then:
        poll { assert daemon.standardOutput.contains(DaemonMessages.PROCESS_STARTED) }

        when:
        def infoBuild = executer.withDaemonBaseDir(baseDir).withArguments("-i", "-Dorg.gradle.jvmargs=-ea").run()

        then:
        getLogs(baseDir).size() == 0 //we should connect to the foreground daemon so no log was created

        daemon.standardOutput.count(DaemonMessages.ABOUT_TO_START_RELAYING_LOGS) == 0
        daemon.standardOutput.count("info me!") == 1

        infoBuild.output.count("debug me!") == 0
        infoBuild.output.count("info me!") == 1

        when:
        def debugBuild = executer.withDaemonBaseDir(baseDir).withArguments("-d", "-Dorg.gradle.jvmargs=-ea").run()

        then:
        daemon.standardOutput.count(DaemonMessages.ABOUT_TO_START_RELAYING_LOGS) == 0
        daemon.standardOutput.count("debug me!") == 1

        debugBuild.output.count("debug me!") == 1
    }

    List<File> getLogs(baseDir) {
        //the gradle version dir
        assert baseDir.listFiles().length == 1
        def daemonFiles = baseDir.listFiles()[0].listFiles()

        daemonFiles.findAll { it.name.endsWith('.log') }
    }

    String readLog(baseDir) {
        def logs = getLogs(baseDir)

        //assert single log
        assert logs.size() == 1

        logs[0].text
    }
}
