package org.thisisthepy.python.multiplatform.toolchain.typedpython

import org.junit.Assume.assumeTrue
import kotlin.test.fail

/**
 * A gate test's precondition (the TypedPython wheels, or the gate zip in ~/.m2). Without it, a local run skips the
 * test with [message]. Under CI (GitHub Actions sets `CI=true`) it fails instead, because a gate test that CI skips
 * never ran, and its passing count hid that (team lead, 2026-11-27). test.yml provides the wheels.
 */
fun requireGatePrecondition(met: Boolean, message: String) {
    if (met) return
    if (System.getenv("CI") == "true") fail("$message. Under CI a gate test must run, not skip.")
    assumeTrue(message, false)
}
