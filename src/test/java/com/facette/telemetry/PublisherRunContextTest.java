/*
 * Copyright (c) 2026, Ethan Monlux
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.facette.telemetry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Covers the run-generation rule that keeps a disabled run's scheduled work out of the next run: a
 * task bound to a retired run must not publish, must not reach the newer run's state or writer, and
 * must not be revivable. The interleaving is driven by latches and by the lock's own queue state,
 * never by sleeping.
 */
public class PublisherRunContextTest
{
	private static final long NOW = 1_770_000_000_000L;

	/** Bound on every wait, so a broken invariant fails the test instead of hanging it. */
	private static final long TIMEOUT_SECONDS = 10L;

	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private LongSupplier clock;
	private int directoryCounter;
	private AtomicLong newestGeneration;

	@Before
	public void setUp()
	{
		clock = () -> NOW;
		newestGeneration = new AtomicLong();
	}

	private PublisherRunContext newRun()
	{
		// A separate directory per run, so a cross-run write would be visible as one run's
		// file appearing under another run's directory rather than being masked by a shared
		// target path.
		Path directory = folder.getRoot().toPath().resolve("run-" + directoryCounter++);
		return PublisherRunContext.begin(
			newestGeneration,
			new TelemetryState(UUID.randomUUID().toString(), clock, System::nanoTime),
			new TelemetrySnapshotWriter(directory));
	}

	/** Mirrors the plugin's publication: build, write, and record, all through one run. */
	private static void publish(PublisherRunContext run, boolean pluginActive) throws IOException
	{
		TelemetrySnapshot snapshot = run.getState().nextSnapshot(pluginActive);
		run.getWriter().write(snapshot, new ReentrantLock(), run::isCommitAuthorized);
		run.getState().recordPublished();
	}

	@Test
	public void aFreshRunIsCurrentAndItsWorkMayProceed()
	{
		PublisherRunContext run = newRun();
		assertTrue(run.isCurrent());
		assertEquals(0L, run.getState().getNextSeq());
	}

	@Test
	public void retirementIsOneWayAndIdempotent()
	{
		PublisherRunContext run = newRun();
		assertTrue("first retire takes effect", run.retire());
		assertFalse(run.isCurrent());
		assertFalse("retiring again is a no-op", run.retire());
		assertFalse("a retired run never becomes current again", run.isCurrent());
	}

	@Test
	public void startingALaterRunCannotReviveAnEarlierOne()
	{
		PublisherRunContext first = newRun();
		first.retire();

		// Standing in for what the old shared shuttingDown flag did on re-enable: starting a
		// new run. Retirement is per-context, so this cannot reach the retired one.
		PublisherRunContext second = newRun();

		assertFalse("the retired run stays retired", first.isCurrent());
		assertTrue("the new run is current", second.isCurrent());
		assertNotEquals("each run has its own identity",
			first.getState().getInstanceId(), second.getState().getInstanceId());
	}

	/**
	 * The interleaving that matters, driven deterministically: an old task is already executing
	 * and waiting on the publication lock when its run is disabled, and the plugin is re-enabled
	 * before the lock is released.
	 */
	@Test
	public void aTaskAlreadyWaitingOnTheLockDoesNotPublishAfterItsRunIsRetired() throws Exception
	{
		ReentrantLock publishLock = new ReentrantLock();
		PublisherRunContext oldRun = newRun();

		CountDownLatch oldTaskReachedTheLock = new CountDownLatch(1);
		AtomicBoolean oldTaskPublished = new AtomicBoolean(false);
		AtomicBoolean passedTheFirstCheck = new AtomicBoolean(false);

		// The client thread holds the lock, standing in for the shutdown write.
		publishLock.lock();

		Thread oldTask = new Thread(() ->
		{
			// The pre-lock check, taken while the run is still current.
			if (!oldRun.isCurrent())
			{
				return;
			}
			passedTheFirstCheck.set(true);
			oldTaskReachedTheLock.countDown();

			publishLock.lock();
			try
			{
				// The post-lock check. By now the run has been retired and a new one started.
				if (!oldRun.isCurrent())
				{
					return;
				}
				publish(oldRun, true);
				oldTaskPublished.set(true);
			}
			catch (IOException e)
			{
				throw new IllegalStateException(e);
			}
			finally
			{
				publishLock.unlock();
			}
		}, "old-run-task");

		oldTask.start();
		assertTrue("old task should reach the lock",
			oldTaskReachedTheLock.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
		awaitQueuedOnLock(publishLock);

		// Disable, then a rapid re-enable while the old task is still queued on the lock.
		oldRun.retire();
		PublisherRunContext newRun = newRun();

		publishLock.unlock();
		oldTask.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
		assertFalse("old task should have finished", oldTask.isAlive());

		assertTrue("the old task must genuinely have raced, not exited early",
			passedTheFirstCheck.get());
		assertFalse("work from a retired run must not publish", oldTaskPublished.get());

		// The new run is untouched: sequence still at zero, nothing written under it.
		assertTrue(newRun.isCurrent());
		assertEquals("the new run's sequence must not have been consumed",
			0L, newRun.getState().getNextSeq());
		assertFalse("the new run's file must not exist yet",
			newRun.getWriter().getTarget().toFile().exists());
	}

	/**
	 * The same interleaving, but confirming the new run still works normally afterwards. A guard
	 * that suppressed all work rather than only old work would pass the test above.
	 */
	@Test
	public void theNewRunPublishesNormallyAndStartsAtSequenceZero() throws Exception
	{
		PublisherRunContext oldRun = newRun();
		oldRun.retire();
		PublisherRunContext newRun = newRun();

		publish(newRun, true);

		assertTrue("the new run's file should exist",
			newRun.getWriter().getTarget().toFile().exists());
		assertEquals("first publication of a fresh run is seq 0",
			1L, newRun.getState().getNextSeq());

		// Its first snapshot carried seq 0 and this run's own identity.
		TelemetrySnapshot second = newRun.getState().nextSnapshot(true);
		assertEquals(1L, second.getSeq());
		assertEquals(newRun.getState().getInstanceId(), second.getInstanceId());

		// And the retired run never advanced.
		assertEquals(0L, oldRun.getState().getNextSeq());
	}

	@Test
	public void aRetiredRunCannotReachALaterRunsStateOrWriter()
	{
		PublisherRunContext oldRun = newRun();
		PublisherRunContext newRun = newRun();
		oldRun.retire();

		// The two runs share nothing: there is no path from the retired context to the
		// current one's collaborators, so no interleaving can cross them.
		assertNotEquals(oldRun.getState(), newRun.getState());
		assertNotEquals(oldRun.getWriter(), newRun.getWriter());
		assertNotEquals(oldRun.getWriter().getTarget(), newRun.getWriter().getTarget());
		assertNotEquals(oldRun.getState().getInstanceId(), newRun.getState().getInstanceId());
	}

	// --- generation authority and bounded shutdown ---

	@Test
	public void onlyTheNewestRunMayCommit()
	{
		PublisherRunContext runA = newRun();
		assertTrue("the only run so far may commit", runA.isCommitAuthorized());

		// Retiring alone must NOT revoke commit authority: a disabled run still has to be able
		// to write its own final inactive snapshot.
		runA.retire();
		assertTrue("a retired run may still commit its final snapshot", runA.isCommitAuthorized());

		// Starting a newer run does revoke it. From here Run A is only allowed to abandon.
		PublisherRunContext runB = newRun();
		assertFalse("an older run loses authority the moment a newer one starts",
			runA.isCommitAuthorized());
		assertTrue(runB.isCommitAuthorized());
	}

	/**
	 * The hazard a naive off-thread shutdown write would reintroduce: Run A stages an inactive
	 * snapshot, Run B starts and publishes an active one, and Run A's write completes last. The
	 * file must not end up reporting the plugin inactive while Run B is running.
	 */
	@Test
	public void aDelayedInactiveWriteCannotOverwriteANewerActiveRun() throws Exception
	{
		Path shared = folder.getRoot().toPath().resolve("shared");
		PublisherRunContext runA = new RunBuilder(shared).build();

		CountDownLatch runAStaged = new CountDownLatch(1);
		CountDownLatch runBPublished = new CountDownLatch(1);
		AtomicBoolean runACommitted = new AtomicBoolean(false);

		runA.getState().updateSession("LOGGED_IN", true);

		Thread runAFinalWrite = new Thread(() ->
		{
			try
			{
				TelemetrySnapshot inactive = runA.getState().nextSnapshot(false);
				runA.getWriter().write(inactive, new ReentrantLock(), () ->
				{
					// Stand at the commit boundary until Run B has published, then answer
					// truthfully. This is the exact interleaving, forced deterministically.
					runAStaged.countDown();
					try
					{
						runBPublished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
					}
					catch (InterruptedException e)
					{
						Thread.currentThread().interrupt();
					}
					return runA.isCommitAuthorized();
				});
				runACommitted.set(true);
			}
			catch (TelemetrySnapshotWriter.CommitNotAuthorizedException expected)
			{
				// The correct outcome.
			}
			catch (IOException e)
			{
				throw new IllegalStateException(e);
			}
		}, "run-a-final-write");

		runAFinalWrite.start();
		assertTrue("Run A should reach the commit boundary",
			runAStaged.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

		// Run B starts (revoking Run A's authority) and publishes an active snapshot.
		runA.retire();
		PublisherRunContext runB = new RunBuilder(shared).build();
		runB.getState().updateSession("LOGGED_IN", true);
		TelemetrySnapshot active = runB.getState().nextSnapshot(true);
		runB.getWriter().write(active, new ReentrantLock(), runB::isCommitAuthorized);
		runBPublished.countDown();

		runAFinalWrite.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
		assertFalse("Run A's write should have finished", runAFinalWrite.isAlive());
		assertFalse("Run A must not have committed over Run B", runACommitted.get());

		String onDisk = new String(
			java.nio.file.Files.readAllBytes(runB.getWriter().getTarget()), StandardCharsets.UTF_8);
		assertTrue("the file must still be Run B's active snapshot",
			onDisk.contains("\"pluginActive\":true"));
		assertTrue(onDisk.contains(runB.getState().getInstanceId()));
		assertFalse("Run A's identity must not be on disk",
			onDisk.contains(runA.getState().getInstanceId()));
	}

	@Test
	public void aRunWithNoPublisherHasNothingToStopOrWaitFor()
	{
		PublisherRunContext run = newRun();
		assertFalse("no publisher was ever attached", run.hasPublisher());
		assertFalse("there is no final write to submit", run.submitFinalWrite(() -> { }));
		assertTrue("waiting on nothing returns immediately",
			run.awaitPublisherTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));

		// Abandoning is safe and idempotent, which is what a failed start relies on.
		run.abandonPublisher();
		run.abandonPublisher();
		assertFalse(run.hasPublisher());
	}

	/**
	 * The shutdown bound: a stalled final write must not hold the caller. On the real client
	 * that caller is RuneLite's client thread, so this is the responsiveness guarantee.
	 */
	@Test
	public void shutdownReturnsWithinItsBoundWhileTheFinalWriteIsStalled() throws Exception
	{
		PublisherRunContext run = newRun();
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r ->
		{
			Thread t = new Thread(r, "stalled-publisher");
			t.setDaemon(true);
			return t;
		});
		run.attachPublisherIfCurrent(executor);
		run.attachPublishTask(executor.scheduleWithFixedDelay(
			() -> { }, 1L, 1L, TimeUnit.HOURS));

		CountDownLatch writeStarted = new CountDownLatch(1);
		CountDownLatch releaseWrite = new CountDownLatch(1);

		assertTrue(run.submitFinalWrite(() ->
		{
			writeStarted.countDown();
			try
			{
				releaseWrite.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
			}
		}));
		assertTrue("the final write should have started",
			writeStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

		// The bound elapses while the write is deliberately stuck.
		assertFalse("a stalled write must not report termination",
			run.awaitPublisherTermination(50L, TimeUnit.MILLISECONDS));

		// The caller is free; the write is still going.
		releaseWrite.countDown();
		assertTrue("it finishes once unstuck",
			run.awaitPublisherTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
	}

	@Test
	public void aSubmittedFinalWriteRunsAndTerminatesThePublisher() throws Exception
	{
		PublisherRunContext run = newRun();
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r ->
		{
			Thread t = new Thread(r, "publisher");
			t.setDaemon(true);
			return t;
		});
		run.attachPublisherIfCurrent(executor);
		run.attachPublishTask(executor.scheduleWithFixedDelay(
			() -> { }, 1L, 1L, TimeUnit.HOURS));
		assertTrue(run.hasPublisher());

		AtomicBoolean ran = new AtomicBoolean(false);
		assertTrue(run.submitFinalWrite(() -> ran.set(true)));
		assertTrue(run.awaitPublisherTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
		assertTrue("the final write must actually run", ran.get());

		// The periodic task was cancelled, so nothing else can publish afterwards.
		assertFalse("a second submission has no executor left to accept it",
			run.submitFinalWrite(() -> { }));
	}

	/**
	 * The publisher must count as attached before its first tick is scheduled. The tick runs
	 * with a zero initial delay, so a disable landing between scheduling and adoption would
	 * otherwise find no publisher: the executor would leak and no final snapshot would be
	 * written, while a tick already in flight still committed an active one.
	 */
	@Test
	public void aRunOwnsItsPublisherBeforeAnyTaskIsScheduled()
	{
		PublisherRunContext run = newRun();
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r ->
		{
			Thread t = new Thread(r, "publisher");
			t.setDaemon(true);
			return t;
		});

		// Adoption happens first, with no task yet: exactly the window the ordering closes.
		run.attachPublisherIfCurrent(executor);
		assertTrue("shutdown must be able to find the executor immediately", run.hasPublisher());

		AtomicBoolean finalWriteRan = new AtomicBoolean(false);
		assertTrue("a final write is submittable with no periodic task attached",
			run.submitFinalWrite(() -> finalWriteRan.set(true)));
		assertTrue(run.awaitPublisherTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
		assertTrue("the final snapshot is still written", finalWriteRan.get());
		assertTrue("the executor was actually stopped, not leaked", executor.isShutdown());
	}

	/**
	 * The window between authorization and target replacement. Authorization can say yes and a
	 * newer run can publish before the move lands, so the two must be one indivisible step.
	 * This drives that interleaving and asserts the shared publication lock prevents it.
	 */
	@Test
	public void aStalledOldWriteNeitherBlocksNorBuriesANewerPublication() throws Exception
	{
		ReentrantLock publishLock = new ReentrantLock();
		Path shared = folder.getRoot().toPath().resolve("locked");
		PublisherRunContext runA = new RunBuilder(shared).build();
		runA.getState().updateSession("LOGGED_IN", true);

		CountDownLatch runAInsideTheLock = new CountDownLatch(1);
		CountDownLatch runAMayFinish = new CountDownLatch(1);

		// Run A's final write, holding the lock across staging, authorization, and the move, which
		// is the discipline the plugin's publish() now enforces for every publication path.
		Thread runAFinalWrite = new Thread(() ->
		{
			try
			{
				// Staging is deliberately outside the commit lock now, so this thread parks
				// where a stalled write would: holding nothing another run needs.
				runAInsideTheLock.countDown();
				runAMayFinish.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
				runA.getWriter().write(
					runA.getState().nextSnapshot(false), publishLock, runA::isCommitAuthorized);
			}
			catch (TelemetrySnapshotWriter.CommitNotAuthorizedException expected)
			{
				// Correct once Run B exists.
			}
			catch (IOException e)
			{
				throw new IllegalStateException(e);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
			}
		}, "run-a-final-write");

		runAFinalWrite.start();
		assertTrue(runAInsideTheLock.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

		runA.retire();
		PublisherRunContext runB = new RunBuilder(shared).build();
		runB.getState().updateSession("LOGGED_IN", true);

		// Run B tries to publish while Run A holds the lock; it must wait rather than interleave.
		AtomicBoolean runBPublished = new AtomicBoolean(false);
		Thread runBPublish = new Thread(() ->
		{
			try
			{
				runB.getWriter().write(
					runB.getState().nextSnapshot(true), publishLock, runB::isCommitAuthorized);
				runBPublished.set(true);
			}
			catch (IOException e)
			{
				throw new IllegalStateException(e);
			}
		}, "run-b-publish");
		runBPublish.start();

		// Run B must NOT be blocked by Run A's parked staging. That is the liveness property this
		// narrower lock buys: it publishes while Run A is still stuck.
		runBPublish.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
		assertTrue("a newer run must publish even while an older one is stalled staging",
			runBPublished.get());

		runAMayFinish.countDown();
		runAFinalWrite.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
		assertFalse(runAFinalWrite.isAlive());
		assertFalse(runBPublish.isAlive());

		// Whichever order the lock granted, the file ends as Run B's active snapshot: Run A
		// either committed first and was replaced, or was refused for lack of authority.
		String onDisk = new String(
			java.nio.file.Files.readAllBytes(runB.getWriter().getTarget()), StandardCharsets.UTF_8);
		assertTrue("the newest run's active snapshot must be what survives",
			onDisk.contains("\"pluginActive\":true"));
		assertTrue(onDisk.contains(runB.getState().getInstanceId()));
	}

	/**
	 * Startup samples and seeds before it attaches a publisher, which is long enough for a
	 * disable to land in between. Shutdown will already have decided there was no publisher to
	 * stop, so adopting the executor at that point would leak it: no later shutdown can reach it
	 * once a re-enable replaces the current run.
	 */
	@Test
	public void aRetiredRunRefusesToAdoptAPublisher()
	{
		PublisherRunContext run = newRun();

		// The disable lands while startup is still sampling and seeding.
		run.retire();

		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r ->
		{
			Thread t = new Thread(r, "late-publisher");
			t.setDaemon(true);
			return t;
		});
		assertFalse("a retired run must not adopt a publisher",
			run.attachPublisherIfCurrent(executor));
		assertFalse("and must not report one", run.hasPublisher());

		// The caller owns the executor it was refused, and shutting it down is what prevents
		// the leaked daemon thread per disable/re-enable toggle.
		executor.shutdownNow();
		assertTrue(executor.isShutdown());
	}

	@Test
	public void aCurrentRunStillAdoptsItsPublisher()
	{
		PublisherRunContext run = newRun();
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r ->
		{
			Thread t = new Thread(r, "publisher");
			t.setDaemon(true);
			return t;
		});
		assertTrue(run.attachPublisherIfCurrent(executor));
		assertTrue(run.hasPublisher());
		run.abandonPublisher();
	}

	/** Builds runs that share one target directory, for cross-run file-contention tests. */
	private final class RunBuilder
	{
		private final Path directory;

		RunBuilder(Path directory)
		{
			this.directory = directory;
		}

		PublisherRunContext build()
		{
			return PublisherRunContext.begin(
				newestGeneration,
				new TelemetryState(UUID.randomUUID().toString(), clock, System::nanoTime),
				new TelemetrySnapshotWriter(directory));
		}
	}

	/**
	 * Waits until the other thread is actually queued on the lock. This is a state check with
	 * a bounded deadline, not a timing assumption. Correctness comes from
	 * {@code hasQueuedThreads()} being true, and the deadline only converts a hang into a
	 * failure.
	 */
	private static void awaitQueuedOnLock(ReentrantLock lock) throws InterruptedException
	{
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
		while (!lock.hasQueuedThreads())
		{
			if (System.nanoTime() > deadline)
			{
				throw new AssertionError("thread never queued on the publication lock");
			}
			Thread.yield();
		}
	}
}
