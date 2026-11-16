package dev.javahost.core.service

import dev.javahost.core.model.RunState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Exhaustive truth table for the foreground service's Stop/Start/Restart
 * transition rules. Hermetic: no Android framework, no coroutines, no I/O.
 *
 * The table is deliberately written out row by row (every combination of
 * "runtime claims active", published state including the null fallback, and
 * "a real session exists") so a future change to [ServiceLifecycleDecisions]
 * cannot silently alter behaviour.
 */
class ServiceLifecycleDecisionsTest {

    @Test
    fun `decideStart follows the exhaustive truth table`() {
        val table: List<Row<StartDecision>> = listOf(
            // A stop is in flight (STOPPING): queue the requested start, whether
            // or not the child JVM still exists.
            Row(true, RunState.STOPPING, true, StartDecision.QUEUE_AFTER_STOP),
            Row(true, RunState.STOPPING, false, StartDecision.QUEUE_AFTER_STOP),
            // Active runtime backed by a real session: adopt it, never spawn a
            // second JVM.
            Row(true, RunState.STARTING, true, StartDecision.ADOPT_RUNNING),
            Row(true, RunState.RUNNING, true, StartDecision.ADOPT_RUNNING),
            Row(true, RunState.STOPPED, true, StartDecision.ADOPT_RUNNING),
            Row(true, RunState.CRASHED, true, StartDecision.ADOPT_RUNNING),
            Row(true, null, true, StartDecision.ADOPT_RUNNING),
            // Active runtime with no session: stale snapshot -> clear it first.
            Row(true, RunState.STARTING, false, StartDecision.CLEAR_STALE_THEN_START),
            Row(true, RunState.RUNNING, false, StartDecision.CLEAR_STALE_THEN_START),
            Row(true, RunState.STOPPED, false, StartDecision.CLEAR_STALE_THEN_START),
            Row(true, RunState.CRASHED, false, StartDecision.CLEAR_STALE_THEN_START),
            Row(true, null, false, StartDecision.CLEAR_STALE_THEN_START),
            // Inactive runtime: always a fresh start, regardless of the session
            // flag (a session without an active runtime is equally stale).
            Row(false, RunState.STARTING, true, StartDecision.START_FRESH),
            Row(false, RunState.STARTING, false, StartDecision.START_FRESH),
            Row(false, RunState.RUNNING, true, StartDecision.START_FRESH),
            Row(false, RunState.RUNNING, false, StartDecision.START_FRESH),
            Row(false, RunState.STOPPING, true, StartDecision.START_FRESH),
            Row(false, RunState.STOPPING, false, StartDecision.START_FRESH),
            Row(false, RunState.STOPPED, true, StartDecision.START_FRESH),
            Row(false, RunState.STOPPED, false, StartDecision.START_FRESH),
            Row(false, RunState.CRASHED, true, StartDecision.START_FRESH),
            Row(false, RunState.CRASHED, false, StartDecision.START_FRESH),
            Row(false, null, true, StartDecision.START_FRESH),
            Row(false, null, false, StartDecision.START_FRESH)
        )
        table.forEach { row ->
            assertEquals(
                "decideStart(active=${row.active}, state=${row.state}, session=${row.session})",
                row.expected,
                ServiceLifecycleDecisions.decideStart(row.active, row.state, row.session)
            )
        }
        assertEquals("truth table must cover every combination", 24, table.size)
    }

    @Test
    fun `decideRestart follows the exhaustive truth table`() {
        val table: List<Row<RestartDecision>> = listOf(
            // Active runtime with a real session: restart that session. The
            // transient terminal state of an in-flight restart is handled by the
            // service's restartInFlight guard, not here.
            Row(true, RunState.STARTING, true, RestartDecision.RESTART_EXISTING),
            Row(true, RunState.RUNNING, true, RestartDecision.RESTART_EXISTING),
            Row(true, RunState.STOPPING, true, RestartDecision.RESTART_EXISTING),
            Row(true, RunState.STOPPED, true, RestartDecision.RESTART_EXISTING),
            Row(true, RunState.CRASHED, true, RestartDecision.RESTART_EXISTING),
            Row(true, null, true, RestartDecision.RESTART_EXISTING),
            // Active runtime without a session: stale -> clear, then start.
            Row(true, RunState.STARTING, false, RestartDecision.CLEAR_STALE_THEN_START),
            Row(true, RunState.RUNNING, false, RestartDecision.CLEAR_STALE_THEN_START),
            Row(true, RunState.STOPPING, false, RestartDecision.CLEAR_STALE_THEN_START),
            Row(true, RunState.STOPPED, false, RestartDecision.CLEAR_STALE_THEN_START),
            Row(true, RunState.CRASHED, false, RestartDecision.CLEAR_STALE_THEN_START),
            Row(true, null, false, RestartDecision.CLEAR_STALE_THEN_START),
            // Nothing running: the restart is just a fresh start.
            Row(false, RunState.STARTING, true, RestartDecision.START_FRESH),
            Row(false, RunState.STARTING, false, RestartDecision.START_FRESH),
            Row(false, RunState.RUNNING, true, RestartDecision.START_FRESH),
            Row(false, RunState.RUNNING, false, RestartDecision.START_FRESH),
            Row(false, RunState.STOPPING, true, RestartDecision.START_FRESH),
            Row(false, RunState.STOPPING, false, RestartDecision.START_FRESH),
            Row(false, RunState.STOPPED, true, RestartDecision.START_FRESH),
            Row(false, RunState.STOPPED, false, RestartDecision.START_FRESH),
            Row(false, RunState.CRASHED, true, RestartDecision.START_FRESH),
            Row(false, RunState.CRASHED, false, RestartDecision.START_FRESH),
            Row(false, null, true, RestartDecision.START_FRESH),
            Row(false, null, false, RestartDecision.START_FRESH)
        )
        table.forEach { row ->
            assertEquals(
                "decideRestart(active=${row.active}, state=${row.state}, session=${row.session})",
                row.expected,
                ServiceLifecycleDecisions.decideRestart(row.active, row.state, row.session)
            )
        }
        assertEquals("truth table must cover every combination", 24, table.size)
    }

    @Test
    fun `a stop in progress queues the start even when the session still exists`() {
        // Regression guard for the old "Start during Stop does nothing" bug: the
        // STOPPING rule must win over the adopt rule (the child may still exist).
        assertEquals(
            StartDecision.QUEUE_AFTER_STOP,
            ServiceLifecycleDecisions.decideStart(true, RunState.STOPPING, true)
        )
        assertEquals(
            StartDecision.QUEUE_AFTER_STOP,
            ServiceLifecycleDecisions.decideStart(true, RunState.STOPPING, false)
        )
    }

    @Test
    fun `a stale active snapshot without a session is cleared before starting`() {
        // Regression guard for the disposed/zombie manager failure mode.
        assertEquals(
            StartDecision.CLEAR_STALE_THEN_START,
            ServiceLifecycleDecisions.decideStart(true, RunState.RUNNING, false)
        )
        assertEquals(
            RestartDecision.CLEAR_STALE_THEN_START,
            ServiceLifecycleDecisions.decideRestart(true, RunState.RUNNING, false)
        )
    }

    @Test
    fun `a crashed runtime with a real session is restarted not started fresh`() {
        // A crash that races with Restart must reuse the restart path (the
        // manager decides whether a session still exists), not double-start.
        assertEquals(
            RestartDecision.RESTART_EXISTING,
            ServiceLifecycleDecisions.decideRestart(true, RunState.CRASHED, true)
        )
    }

    private data class Row<T>(
        val active: Boolean,
        val state: RunState?,
        val session: Boolean,
        val expected: T
    )
}
