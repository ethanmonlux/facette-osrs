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

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One plugin run, and the lifecycle authority for everything that run owns: its telemetry state,
 * its writer, its publisher thread, and its generation.
 *
 * <p>RuneLite reuses a plugin instance across disable and enable, and startup work is deferred
 * onto the client thread, so a disabled run can still have a callback pending or a publication in
 * flight while the next run starts. Binding all of that to one object per start gives three
 * guarantees that do not depend on timing:
 *
 * <ul>
 *   <li><b>Retirement is per-run and permanent.</b> {@link #retire()} affects only this context;
 *       a later start creates a different object and cannot revive it.</li>
 *   <li><b>State, writer, and executor are reachable only through the run that owns them,</b> so
 *       work bound to a retired context cannot consume a later run's sequence, clear its dirty
 *       flag, or write through its writer.</li>
 *   <li><b>Generation decides who may replace the target file.</b> Runs share a monotonic
 *       counter; a run may commit only while it is still the newest one started — see
 *       {@link #isCommitAuthorized()}.</li>
 * </ul>
 *
 * <p>Holds no RuneLite types, so every lifecycle rule here is exercisable without a game client.
 */
final class PublisherRunContext
{
	private final long generation;

	/** Highest generation started so far, shared by every run of one plugin instance. */
	private final AtomicLong newestGeneration;

	private final TelemetryState state;
	private final TelemetrySnapshotWriter writer;

	/** False once this run has been retired. Never returns to true. */
	private final AtomicBoolean current = new AtomicBoolean(true);

	/**
	 * Set once client-thread initialization has sampled state and seeded baselines. Until then
	 * nothing may publish and events are ignored, so no snapshot is built from a partly
	 * initialized run.
	 */
	private final AtomicBoolean initialized = new AtomicBoolean(false);

	private ExecutorService executor;
	private Future<?> publishTask;

	private PublisherRunContext(long generation, AtomicLong newestGeneration,
		TelemetryState state, TelemetrySnapshotWriter writer)
	{
		this.generation = generation;
		this.newestGeneration = newestGeneration;
		this.state = state;
		this.writer = writer;
	}

	/**
	 * Begins a run, claiming the next generation. Claiming it at start rather than at first
	 * publication is what makes an older run stop being allowed to commit the moment a newer one
	 * exists.
	 */
	static PublisherRunContext begin(AtomicLong newestGeneration, TelemetryState state,
		TelemetrySnapshotWriter writer)
	{
		Objects.requireNonNull(newestGeneration, "newestGeneration");
		Objects.requireNonNull(state, "state");
		Objects.requireNonNull(writer, "writer");
		return new PublisherRunContext(
			newestGeneration.incrementAndGet(), newestGeneration, state, writer);
	}

	TelemetryState getState()
	{
		return state;
	}

	TelemetrySnapshotWriter getWriter()
	{
		return writer;
	}

	/**
	 * Whether work bound to this run may still act. Checked when a deferred startup callback
	 * finally runs, when it goes to attach a publisher, and at the top of each periodic tick.
	 * What protects the target file is {@link #isCommitAuthorized()}.
	 */
	boolean isCurrent()
	{
		return current.get();
	}

	/**
	 * Retires this run. Idempotent, one-way, and scoped to this context alone.
	 *
	 * @return true if this call retired the run, false if it was already retired
	 */
	synchronized boolean retire()
	{
		return current.compareAndSet(true, false);
	}

	boolean isInitialized()
	{
		return initialized.get();
	}

	/** Records that client-thread initialization completed. One-way. */
	void markInitialized()
	{
		initialized.set(true);
	}

	/**
	 * Whether this run may still replace the target file.
	 *
	 * <p>Deliberately independent of {@link #isCurrent()}: a retired run is still allowed to commit
	 * its own final inactive snapshot, which is the point of shutdown. What it may not do is commit
	 * once a newer run has started, because that run is now the authority on what the file should
	 * say. Checked immediately before replacement rather than when the write begins, so a write
	 * that stages slowly and finishes after a re-enable is refused rather than landing stale.
	 */
	boolean isCommitAuthorized()
	{
		return newestGeneration.get() == generation;
	}

	/**
	 * Adopts the publisher thread, before anything is scheduled on it.
	 *
	 * <p>Adoption is deliberately earlier than scheduling the periodic task, which runs with a
	 * zero initial delay and can therefore execute before the scheduling call returns. If the run
	 * only counted as having a publisher after that, a disable landing in the gap would find
	 * nothing to stop, leak the executor, and skip the final snapshot.
	 *
	 * <p>Refuses once retired, atomically with {@link #retire()} — both are synchronized on this
	 * context — so either retirement wins and the caller disposes of the executor, or this wins
	 * and shutdown is guaranteed to find it.
	 *
	 * @return false if the run was already retired, in which case it did not adopt the executor
	 *         and the caller must shut it down
	 */
	synchronized boolean attachPublisherIfCurrent(ExecutorService executor)
	{
		if (!current.get())
		{
			return false;
		}
		this.executor = executor;
		return true;
	}

	/**
	 * Adopts the periodic task once it has been scheduled. Harmless if shutdown already claimed
	 * the executor — the task is cancelled below either way.
	 */
	synchronized void attachPublishTask(Future<?> publishTask)
	{
		this.publishTask = publishTask;
	}

	/** Whether a publisher was ever started for this run. */
	synchronized boolean hasPublisher()
	{
		return executor != null;
	}

	/**
	 * Stops periodic publication and hands the publisher its last task.
	 *
	 * <p>Non-blocking. The final write is queued on the run's own single publisher thread, so a
	 * stalled filesystem cannot block whoever is disabling the plugin — on the real client,
	 * RuneLite's client thread. Because that thread is single, the final write also queues behind
	 * any publication already in flight instead of racing it.
	 *
	 * @return false when this run never had a publisher, or the executor had already stopped
	 *         accepting work, in which case nothing was submitted and there is no final snapshot
	 *         to wait for
	 */
	synchronized boolean submitFinalWrite(Runnable finalWrite)
	{
		if (executor == null)
		{
			return false;
		}
		if (publishTask != null)
		{
			// Stops further periodic publications without interrupting one already running.
			publishTask.cancel(false);
			publishTask = null;
		}
		try
		{
			executor.execute(finalWrite);
		}
		catch (RejectedExecutionException e)
		{
			// Already shut down; nothing further to do.
			return false;
		}
		// Accepts no new work but lets the queued final write run to completion.
		executor.shutdown();
		return true;
	}

	/**
	 * Waits a bounded time for the publisher to finish, including its final write.
	 *
	 * @return true if it finished within the bound; false means the write is still running and the
	 *         caller must return anyway, leaving it to commit only if {@link #isCommitAuthorized()}
	 *         still holds when it reaches the target
	 */
	boolean awaitPublisherTermination(long timeout, TimeUnit unit)
	{
		ExecutorService toAwait;
		synchronized (this)
		{
			toAwait = executor;
		}
		if (toAwait == null)
		{
			return true;
		}
		try
		{
			// Awaited outside the monitor so a slow write cannot block another thread that only
			// wants to read this context.
			return toAwait.awaitTermination(timeout, unit);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/**
	 * Stops the publisher without submitting a final write, for a run being abandoned — a startup
	 * that failed, or one disabled before it ever published. Idempotent.
	 */
	synchronized void abandonPublisher()
	{
		if (publishTask != null)
		{
			publishTask.cancel(false);
			publishTask = null;
		}
		if (executor != null)
		{
			executor.shutdownNow();
			executor = null;
		}
	}
}
