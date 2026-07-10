package com.ditchoom.socket.testsuite.harness

/**
 * In-repo materialization of [NetworkHarnessTestSuite] on every target this module
 * builds (JVM, Android unit, Apple, Linux). The suite's skip-on-unreachable
 * discipline keeps targets without a reachable docker stack green, and
 * `skipsCleanlyWhenControllerUnreachable` runs deterministically everywhere — so
 * every platform test task discovers at least one test (Gradle fails a test task
 * whose compiled test sources yield zero discovered tests).
 */
class NetworkHarnessTests : NetworkHarnessTestSuite()
