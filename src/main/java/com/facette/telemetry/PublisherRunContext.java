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
 * One plugin run, and the lifecycle authority for its state, writer, publisher thread, and
 * generation. RuneLite reuses a plugin instance across disable and enable, and startup work is
 * deferred onto the client thread, so a disabled run can still have a callback pending when the next
 * run starts. Generation decides who may replace the target file: runs share a monotonic counter,
 * and a run may commit only while it is still the newest one started.
 */
final class PublisherRunContext
{
	private final long generation;

	private final AtomicLong newestGeneration;

	private final TelemetryState state;
	private final TelemetrySnapshotWriter writer;

	// False once this run has been retired. Never returns to true.
	private final AtomicBoolean current = new AtomicBoolean(true);

	// Set once client-thread initialization has sampled state and seeded baselines. Nothing may
	// publish before then.
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

	// Claiming the generation at start rather than at first publication is what stops an older run
	// committing the moment a newer one exists.
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

	// What protects the target file is commit authority, not this.
	boolean isCurrent()
	{
		return current.get();
	}

	// One-way and scoped to this run alone, so a later start cannot bring this one back.
	synchronized boolean retire()
	{
		return current.compareAndSet(true, false);
	}

	boolean isInitialized()
	{
		return initialized.get();
	}

	void markInitialized()
	{
		initialized.set(true);
	}

	/**
	 * Whether this run may still replace the target file. A retired run may still commit its own final
	 * inactive snapshot, but not once a newer run has started. Checked immediately before replacement,
	 * so a slow write that finished after a re-enable is refused rather than landing stale.
	 */
	boolean isCommitAuthorized()
	{
		return newestGeneration.get() == generation;
	}

	/**
	 * Adopts the publisher thread before anything is scheduled on it, because the periodic task has a
	 * zero initial delay. Refuses once retired, atomically with retirement, so either the caller shuts
	 * the executor down or shutdown is guaranteed to find it.
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

	synchronized void attachPublishTask(Future<?> publishTask)
	{
		this.publishTask = publishTask;
	}

	synchronized boolean hasPublisher()
	{
		return executor != null;
	}

	/**
	 * Stops periodic publication and queues the final write on the run's own publisher thread. A
	 * stalled filesystem cannot hold up whoever is disabling the plugin, and the final write queues
	 * behind any publication already in flight instead of racing it.
	 */
	synchronized boolean submitFinalWrite(Runnable finalWrite)
	{
		if (executor == null)
		{
			return false;
		}
		if (publishTask != null)
		{
			// Stops further publications without interrupting one already running.
			publishTask.cancel(false);
			publishTask = null;
		}
		try
		{
			executor.execute(finalWrite);
		}
		catch (RejectedExecutionException e)
		{
			return false;
		}
		// Accepts no new work but lets the queued final write run to completion.
		executor.shutdown();
		return true;
	}

	/**
	 * Waits a bounded time for the publisher to finish. False means the write is still running, and it
	 * will commit only if it still holds authority when it gets there.
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
			// Awaited outside the monitor so a slow write cannot block a thread that only reads.
			return toAwait.awaitTermination(timeout, unit);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			return false;
		}
	}

	// Stops the publisher without a final write, for a run being abandoned.
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
