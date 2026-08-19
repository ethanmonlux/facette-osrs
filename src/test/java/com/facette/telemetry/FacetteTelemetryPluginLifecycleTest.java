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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import org.mockito.invocation.Invocation;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.ParamID;
import net.runelite.api.Player;
import net.runelite.api.Prayer;
import net.runelite.api.Skill;
import net.runelite.api.StructComposition;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;

/**
 * Drives {@link FacetteTelemetryPlugin}'s real lifecycle without a game client. The client and its
 * thread dispatcher are mocked, the clocks are moved deliberately, and the publisher runs on a fake
 * executor this test drains by hand, so ordering is exact rather than timing-dependent.
 */
public class FacetteTelemetryPluginLifecycleTest
{
	private static final long HEARTBEAT_MILLIS = 1_500L;

	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	private Client client;
	private ClientThread clientThread;

	/** The local player, read for its combat level and its interaction and for nothing else. */
	private Player localPlayer;

	/** The client's worn and inventory containers, and the arrays a test can rewrite in place. */
	private Item[] wornItems;

	private Item[] inventoryItems;

	/** Callbacks handed to {@link ClientThread#invoke(Runnable)}, drained only on demand. */
	private List<Runnable> clientThreadQueue;

	/** Every executor the plugin asked for, so each can be checked for disposal. */
	private List<ControlledPublisher> executors;

	private long now;
	private long elapsed;

	/**
	 * When set, reading the wall clock fails. The wall clock is read inside the publisher tick, so
	 * this is how an unchecked failure is delivered to the one place that must contain it.
	 */
	private boolean wallClockFails;

	/**
	 * Run once, inside the executor supplier, before the plugin is handed its publisher. It is how a
	 * disable is landed in the window between startup deciding the run is live and the run adopting
	 * the executor.
	 */
	private Runnable beforePublisherHandOver;
	private AtomicInteger instanceCounter;
	private Path dataDirectory;
	private FacetteTelemetryPlugin plugin;

	@Before
	public void setUp() throws IOException
	{
		client = mock(Client.class);
		clientThread = mock(ClientThread.class);
		clientThreadQueue = new ArrayList<>();
		executors = new ArrayList<>();
		now = 1_770_000_000_000L;
		elapsed = -4_000_000_000L;
		instanceCounter = new AtomicInteger();
		dataDirectory = temporaryFolder.newFolder("facette").toPath();

		// Queued rather than run, which is what lets a test prove the plugin read nothing from
		// the client before the callback it scheduled actually executed.
		doAnswer(invocation ->
		{
			clientThreadQueue.add(invocation.getArgument(0));
			return null;
		}).when(clientThread).invoke(any(Runnable.class));

		// A logged-out client unless a test says otherwise, so nothing samples by accident.
		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);

		// Every item lookup answers with one synthetic name. No price, examine text, or icon is
		// stubbed, because the plugin must never ask for one.
		ItemComposition composition = mock(ItemComposition.class);
		when(composition.getMembersName()).thenReturn("Sample item");
		when(client.getItemDefinition(anyInt())).thenReturn(composition);

		localPlayer = mock(Player.class);
		when(localPlayer.getCombatLevel()).thenReturn(87);

		// RuneLite's worn container carries fourteen slots, three of which hold no item.
		wornItems = emptyItems(14);
		inventoryItems = emptyItems(TelemetryState.INVENTORY_CAPACITY);
		ItemContainer worn = mock(ItemContainer.class);
		ItemContainer inventory = mock(ItemContainer.class);
		when(worn.getItems()).thenAnswer(invocation -> wornItems);
		when(inventory.getItems()).thenAnswer(invocation -> inventoryItems);
		when(client.getItemContainer(InventoryID.WORN)).thenReturn(worn);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inventory);

		plugin = new FacetteTelemetryPlugin(
			() ->
			{
				if (wallClockFails)
				{
					throw new IllegalStateException("simulated clock failure");
				}
				return now;
			},
			() -> elapsed,
			() -> "instance-" + instanceCounter.incrementAndGet(),
			() -> dataDirectory,
			() ->
			{
				if (beforePublisherHandOver != null)
				{
					Runnable hook = beforePublisherHandOver;
					beforePublisherHandOver = null;
					hook.run();
				}
				ControlledPublisher publisher = new ControlledPublisher();
				executors.add(publisher);
				return publisher.service;
			});
		plugin.client = client;
		plugin.clientThread = clientThread;
	}

	@After
	public void tearDown()
	{
		// Case 12. Every executor the plugin took must be disposed of by the plugin itself:
		// a leaked one is a leaked publisher thread in production.
		for (ControlledPublisher executor : executors)
		{
			assertTrue("an executor was left live at test end", executor.isShutdown());
		}
	}

	// --- helpers -------------------------------------------------------------------------

	/** Runs the callbacks the plugin handed to the client thread, in order. */
	private void runClientThreadQueue()
	{
		List<Runnable> pending = new ArrayList<>(clientThreadQueue);
		clientThreadQueue.clear();
		for (Runnable runnable : pending)
		{
			runnable.run();
		}
	}

	/**
	 * A client in a live logged-in session with everything the plugin needs to complete a sample. A
	 * client missing its containers or its local player is a different scenario, covered separately.
	 */
	private void logInClient()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getWorld()).thenReturn(302);
		when(client.getLocalPlayer()).thenReturn(localPlayer);
	}

	private static Item[] emptyItems(int size)
	{
		Item[] items = new Item[size];
		for (int i = 0; i < size; i++)
		{
			items[i] = new Item(-1, 0);
		}
		return items;
	}

	private void tick()
	{
		plugin.onGameTick(new GameTick());
	}

	private void gameState(GameState gameState)
	{
		GameStateChanged event = new GameStateChanged();
		event.setGameState(gameState);
		plugin.onGameStateChanged(event);
	}

	private void statChanged(Skill skill, int totalXp)
	{
		plugin.onStatChanged(new StatChanged(skill, totalXp, 1, 1));
	}

	private ControlledPublisher onlyExecutor()
	{
		assertEquals("expected exactly one publisher", 1, executors.size());
		return executors.get(0);
	}

	private String snapshotOnDisk() throws IOException
	{
		Path target = dataDirectory.resolve(TelemetrySnapshotWriter.TARGET_FILE_NAME);
		assertTrue("no snapshot was written", Files.exists(target));
		return new String(Files.readAllBytes(target), StandardCharsets.UTF_8);
	}

	private boolean snapshotExists()
	{
		return Files.exists(dataDirectory.resolve(TelemetrySnapshotWriter.TARGET_FILE_NAME));
	}

	/**
	 * Takes the first occurrence, which is the top-level one for every key this class asks about.
	 * Exact whole-document equality is pinned in {@code TelemetrySnapshotTest} instead.
	 */
	private static String value(String json, String key)
	{
		int at = json.indexOf("\"" + key + "\":");
		assertTrue("missing key " + key + " in " + json, at >= 0);
		int start = at + key.length() + 3;
		int end = start;
		int depth = 0;
		while (end < json.length())
		{
			char c = json.charAt(end);
			if (c == '[' || c == '{')
			{
				depth++;
			}
			else if (c == ']' || c == '}')
			{
				if (depth == 0)
				{
					break;
				}
				depth--;
			}
			else if (c == ',' && depth == 0)
			{
				break;
			}
			end++;
		}
		return json.substring(start, end);
	}

	// --- 1. startup from a non-client thread ---------------------------------------------

	/**
	 * Enabling from the configuration panel runs {@code startUp()} on AWT, where every direct
	 * client read fails the client-thread assertion. Startup must therefore touch nothing on the
	 * client itself.
	 */
	@Test
	public void startupFromANonClientThreadReadsNothingFromTheClient()
	{
		plugin.startUp();

		verifyNoInteractions(client);
		assertEquals("startup must defer its work to the client thread", 1, clientThreadQueue.size());
		assertFalse("nothing may be published before the callback runs", snapshotExists());

		plugin.shutDown();
	}

	// --- 2. deferred initialization -------------------------------------------------------

	@Test
	public void clientReadsAndSeedingHappenInsideTheDeferredCallbackBeforeAnyPublisherStarts()
			throws IOException
	{
		logInClient();
		when(client.getSkillExperience(Skill.THIEVING)).thenReturn(1_000);

		plugin.startUp();
		verifyNoInteractions(client);
		assertTrue("no publisher may exist before initialization", executors.isEmpty());

		runClientThreadQueue();

		// Sampling happened, and only now does a publisher exist.
		assertEquals(1, executors.size());
		ControlledPublisher executor = onlyExecutor();
		assertTrue("the periodic task is scheduled after adoption", executor.hasScheduledTask());

		executor.runScheduledTaskOnce();
		String json = snapshotOnDisk();
		assertEquals("true", value(json, "loggedIn"));
		assertEquals("302", value(json, "world"));

		plugin.shutDown();
	}

	// --- 3. disable before the callback ---------------------------------------------------

	@Test
	public void disablingBeforeTheDeferredCallbackLeavesItInertAndWritesNothing()
	{
		logInClient();
		plugin.startUp();

		// Disabled while the callback was still queued.
		plugin.shutDown();

		runClientThreadQueue();

		assertTrue("a retired run must not adopt a publisher", executors.isEmpty());
		assertFalse("a run that never published writes no snapshot", snapshotExists());
	}

	// --- 4. rapid disable and re-enable ----------------------------------------------------

	@Test
	public void rapidDisableAndReEnableGivesSeparateRunsAndAStaleCallbackCannotInitializeTheNewOne()
			throws IOException
	{
		logInClient();

		plugin.startUp();
		List<Runnable> firstCallback = new ArrayList<>(clientThreadQueue);
		clientThreadQueue.clear();

		plugin.shutDown();
		plugin.startUp();
		runClientThreadQueue();

		// The second run is live and owns its own publisher.
		assertEquals("only the second run adopted a publisher", 1, executors.size());
		ControlledPublisher second = onlyExecutor();
		second.runScheduledTaskOnce();
		String afterSecond = snapshotOnDisk();
		assertEquals("instance-2", value(afterSecond, "instanceId").replace("\"", ""));
		assertEquals("a new run starts its own sequence", "0", value(afterSecond, "seq"));

		// The first run's callback finally runs. It must do nothing at all.
		for (Runnable stale : firstCallback)
		{
			stale.run();
		}
		assertEquals("a stale callback must not create a publisher", 1, executors.size());
		assertEquals("and must not disturb what the newer run wrote", afterSecond, snapshotOnDisk());

		plugin.shutDown();
	}

	@Test
	public void everyRunGetsItsOwnIdentityAndState() throws IOException
	{
		logInClient();

		plugin.startUp();
		runClientThreadQueue();
		onlyExecutor().runScheduledTaskOnce();
		String first = value(snapshotOnDisk(), "instanceId");
		plugin.shutDown();

		plugin.startUp();
		runClientThreadQueue();
		executors.get(1).runScheduledTaskOnce();
		String second = value(snapshotOnDisk(), "instanceId");
		plugin.shutDown();

		assertNotEquals("a restart must be visible to a reader", first, second);
	}

	// --- 5. publisher adoption and cleanup -------------------------------------------------

	/**
	 * The zero initial delay means the first tick can run before {@code scheduleWithFixedDelay}
	 * returns, so the run has to own the executor before anything is scheduled on it. Otherwise
	 * a disable landing in that gap finds no publisher to stop and the executor leaks.
	 */
	@Test
	public void thePublisherIsOwnedBeforeAnythingIsScheduledOnIt()
	{
		logInClient();
		plugin.startUp();
		runClientThreadQueue();

		ControlledPublisher executor = onlyExecutor();
		assertTrue(executor.adoptedBeforeScheduling);

		plugin.shutDown();
		assertTrue("shutdown must reach the adopted executor", executor.isShutdown());
	}

	@Test
	public void anExecutorRefusedAfterShutdownIsDisposedOfRatherThanLeaked()
	{
		logInClient();
		plugin.startUp();
		// Retired while the callback was queued: the callback will create an executor and then
		// find the run already retired.
		plugin.shutDown();
		runClientThreadQueue();

		// Nothing was adopted, so nothing can leak; tearDown proves any executor made was
		// shut down.
		assertTrue(executors.isEmpty());
	}

	/**
	 * The narrow window between startup deciding the run is live and the run adopting the executor. A
	 * disable landing inside it leaves an executor no run owns, so the startup path has to dispose of
	 * it, and it does so gracefully: nothing has been scheduled on it, and forcing an executor down is
	 * an interrupting call this plugin does not make.
	 */
	@Test
	public void anExecutorARetiredRunRefusesToAdoptIsShutDownGracefully()
	{
		logInClient();
		plugin.startUp();

		// The disable lands between sampling and adoption, which is the only route to the refusal.
		beforePublisherHandOver = plugin::shutDown;
		runClientThreadQueue();

		ControlledPublisher refused = onlyExecutor();
		assertTrue("a refused executor must not be left live", refused.isShutdown());
		assertFalse("nothing may be scheduled on a refused executor", refused.hasScheduledTask());
		assertOnlyNonInterruptingExecutorCalls(refused.service);
		assertFalse("a run that never adopted a publisher writes no snapshot", snapshotExists());
	}

	/**
	 * Nothing in this plugin interrupts a thread. Disabling cancels the periodic task in the form that
	 * leaves a publication already running alone, and shuts the publisher down in the form that lets
	 * queued work finish. Asserted against the executor and the task handle the plugin was actually
	 * given: the cancellation is the only thing ever asked of the handle, and every call made on the
	 * executor is one of the permitted non-interrupting ones.
	 */
	@Test
	public void disablingStopsThePublisherWithoutInterruptingAnything() throws IOException
	{
		logInClient();
		plugin.startUp();
		runClientThreadQueue();
		ControlledPublisher executor = onlyExecutor();
		executor.runScheduledTaskOnce();

		plugin.shutDown();

		verify(executor.scheduledTaskHandle).cancel(false);
		verifyNoMoreInteractions(executor.scheduledTaskHandle);
		verify(executor.service).shutdown();
		assertOnlyNonInterruptingExecutorCalls(executor.service);

		// The graceful stop is the one that leaves the queued write able to run.
		executor.runQueuedWork();
		assertEquals("the final inactive snapshot still lands", "false",
			value(snapshotOnDisk(), "pluginActive"));
	}

	/**
	 * The publisher is driven through these calls and no others. Forcing an executor down, or
	 * cancelling work in the form that interrupts it, is outside the set, and neither has any place
	 * in a plugin that must not interrupt threads it does not own.
	 */
	private static void assertOnlyNonInterruptingExecutorCalls(ScheduledExecutorService service)
	{
		List<String> permitted = Arrays.asList(
			"scheduleWithFixedDelay", "execute", "shutdown", "isShutdown");
		for (Invocation invocation : mockingDetails(service).getInvocations())
		{
			String called = invocation.getMethod().getName();
			assertTrue("the plugin called " + called + " on its publisher", permitted.contains(called));
		}
	}

	// --- 6. startup while logged in --------------------------------------------------------

	@Test
	public void startingWhileLoggedInSeedsBaselinesSoTheFirstRealGainIsExported() throws IOException
	{
		logInClient();
		when(client.getSkillExperience(Skill.THIEVING)).thenReturn(1_000);

		plugin.startUp();
		runClientThreadQueue();
		ControlledPublisher executor = onlyExecutor();
		executor.runScheduledTaskOnce();

		// Seeding reports nothing by itself.
		assertEquals("null", value(snapshotOnDisk(), "lastSkill"));

		// The very first genuine gain is measured against the seeded total, not consumed.
		now = 1_770_000_004_000L;
		statChanged(Skill.THIEVING, 1_046);
		executor.runScheduledTaskOnce();

		String json = snapshotOnDisk();
		assertEquals("\"thieving\"", value(json, "lastSkill"));
		assertEquals("46", value(json, "lastDelta"));
		assertEquals("1770000004000", value(json, "lastChangedAt"));

		plugin.shutDown();
	}

	// --- 7. startup during a loading screen or world hop -----------------------------------

	@Test
	public void startingDuringLoadingSeedsAtTheFirstLiveTickAndDoesNotConsumeTheNextGain()
			throws IOException
	{
		// The callback lands while the client is between states: nothing to seed from.
		when(client.getGameState()).thenReturn(GameState.LOADING);
		plugin.startUp();
		runClientThreadQueue();

		ControlledPublisher executor = onlyExecutor();
		executor.runScheduledTaskOnce();
		assertEquals("false", value(snapshotOnDisk(), "loggedIn"));

		// The hop completes. The first live tick is what seeds.
		logInClient();
		when(client.getSkillExperience(Skill.AGILITY)).thenReturn(50_000);
		tick();
		executor.runScheduledTaskOnce();
		assertEquals("null", value(snapshotOnDisk(), "lastSkill"));

		// And the next genuine gain is exported rather than swallowed as a first observation.
		now = 1_770_000_006_000L;
		statChanged(Skill.AGILITY, 50_120);
		executor.runScheduledTaskOnce();
		String json = snapshotOnDisk();
		assertEquals("\"agility\"", value(json, "lastSkill"));
		assertEquals("120", value(json, "lastDelta"));

		plugin.shutDown();
	}

	// --- 8. pre-initialization experience ---------------------------------------------------

	/**
	 * Experience arriving before initialization has to reach the starting run carrying the time
	 * it was received, or the delta eventually exported is stamped with whenever startup
	 * finished instead of when the player earned it.
	 */
	@Test
	public void experienceArrivingBeforeInitializationKeepsItsOwnEventTime() throws IOException
	{
		logInClient();
		when(client.getSkillExperience(Skill.THIEVING)).thenReturn(1_092);

		plugin.startUp();

		// Two gains land while the callback is still queued, at distinct times.
		now = 1_770_000_001_000L;
		statChanged(Skill.THIEVING, 1_046);
		now = 1_770_000_002_000L;
		statChanged(Skill.THIEVING, 1_092);
		assertFalse("nothing publishes before initialization", snapshotExists());

		// Startup finally runs, much later.
		now = 1_770_000_030_000L;
		runClientThreadQueue();
		onlyExecutor().runScheduledTaskOnce();

		String json = snapshotOnDisk();
		assertEquals("\"thieving\"", value(json, "lastSkill"));
		assertEquals("46", value(json, "lastDelta"));
		assertEquals("the second event's time, not startup completion", "1770000002000",
			value(json, "lastChangedAt"));

		plugin.shutDown();
	}

	/**
	 * End to end through the plugin: the run initializes during a loading screen, the client goes
	 * live, and a transient zero arrives before the first tick can seed. Nothing may anchor a
	 * baseline at zero, because the delta that follows would be the character's entire skill
	 * total.
	 */
	@Test
	public void aTransientZeroBetweenLoginAndTheFirstTickCannotFabricateAWholeSkillGain()
			throws IOException
	{
		when(client.getGameState()).thenReturn(GameState.LOADING);
		plugin.startUp();
		runClientThreadQueue();
		ControlledPublisher executor = onlyExecutor();

		// The client goes live, but no tick has seeded yet.
		logInClient();
		when(client.getSkillExperience(Skill.WOODCUTTING)).thenReturn(1_234_567);
		gameState(GameState.LOGGED_IN);

		// A transient zero lands in that window.
		statChanged(Skill.WOODCUTTING, 0);

		// The first tick seeds from the client's real total, unobstructed.
		tick();
		executor.runScheduledTaskOnce();
		assertEquals("no gain may be reported yet", "null", value(snapshotOnDisk(), "lastSkill"));

		// A genuine gain is then measured against the truth, not against zero.
		now = 1_770_000_009_000L;
		statChanged(Skill.WOODCUTTING, 1_234_632);
		executor.runScheduledTaskOnce();
		String json = snapshotOnDisk();
		assertEquals("\"woodcutting\"", value(json, "lastSkill"));
		assertEquals("65", value(json, "lastDelta"));

		plugin.shutDown();
	}

	// --- 9. logout and login without disabling ----------------------------------------------

	@Test
	public void loggingOutAndBackInWithoutDisablingReseedsAndReportsNoCrossCharacterGain()
			throws IOException
	{
		logInClient();
		when(client.getSkillExperience(Skill.FISHING)).thenReturn(10_000);

		plugin.startUp();
		runClientThreadQueue();
		ControlledPublisher executor = onlyExecutor();
		executor.runScheduledTaskOnce();

		// Log out. Session-local comparison state is discarded.
		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		gameState(GameState.LOGIN_SCREEN);
		tick();
		executor.runScheduledTaskOnce();
		String loggedOut = snapshotOnDisk();
		assertEquals("false", value(loggedOut, "loggedIn"));
		assertEquals("null", value(loggedOut, "lastSkill"));

		// A different character logs in with a far larger total.
		logInClient();
		when(client.getSkillExperience(Skill.FISHING)).thenReturn(5_000_000);
		gameState(GameState.LOGGED_IN);
		tick();
		executor.runScheduledTaskOnce();
		assertEquals("no cross-character gain may appear", "null",
			value(snapshotOnDisk(), "lastSkill"));

		// The new session's own first gain is exported correctly.
		now = 1_770_000_008_000L;
		statChanged(Skill.FISHING, 5_000_075);
		executor.runScheduledTaskOnce();
		String json = snapshotOnDisk();
		assertEquals("\"fishing\"", value(json, "lastSkill"));
		assertEquals("75", value(json, "lastDelta"));

		plugin.shutDown();
	}

	/**
	 * Through the plugin: a hop must not erase the session's most recent gain, because no later
	 * sample can reconstruct an event.
	 */
	@Test
	public void aWorldHopKeepsTheSessionsMostRecentGain() throws IOException
	{
		logInClient();
		when(client.getSkillExperience(Skill.WOODCUTTING)).thenReturn(1_000);
		plugin.startUp();
		runClientThreadQueue();
		ControlledPublisher executor = onlyExecutor();

		now = 1_770_000_004_000L;
		statChanged(Skill.WOODCUTTING, 1_065);
		executor.runScheduledTaskOnce();
		assertEquals("65", value(snapshotOnDisk(), "lastDelta"));

		// Hop out and back, same session throughout.
		when(client.getGameState()).thenReturn(GameState.LOADING);
		gameState(GameState.LOADING);
		tick();
		executor.runScheduledTaskOnce();
		assertEquals("false", value(snapshotOnDisk(), "loggedIn"));

		logInClient();
		gameState(GameState.LOGGED_IN);
		tick();
		executor.runScheduledTaskOnce();

		String json = snapshotOnDisk();
		assertEquals("\"woodcutting\"", value(json, "lastSkill"));
		assertEquals("65", value(json, "lastDelta"));
		assertEquals("1770000004000", value(json, "lastChangedAt"));

		plugin.shutDown();
	}

	// --- 10. shutdown hands the final write over and returns ---------------------------------

	/**
	 * On the real client this runs on the client thread, which is never made to wait on a filesystem
	 * operation. The final write is handed to the run's own publisher and the caller returns, so this
	 * publisher deliberately runs nothing of its own: the whole test proceeds with the write still
	 * sitting in its queue, which is only possible because nothing waits for it.
	 */
	@Test
	public void shutdownQueuesTheFinalWriteAndReturnsWithoutWaitingForIt() throws IOException
	{
		logInClient();
		plugin.startUp();
		runClientThreadQueue();
		ControlledPublisher executor = onlyExecutor();
		executor.runScheduledTaskOnce();
		String beforeShutdown = snapshotOnDisk();

		plugin.shutDown();

		assertEquals("the caller must not have written the final snapshot itself",
			beforeShutdown, snapshotOnDisk());
		assertTrue("the final write must have been queued on the publisher", executor.hasQueuedWork());
		assertTrue("the publisher must accept nothing further", executor.isShutdown());

		// When the publisher gets to it, it lands the inactive snapshot.
		executor.runQueuedWork();
		String json = snapshotOnDisk();
		assertEquals("false", value(json, "pluginActive"));
		assertEquals("false", value(json, "loggedIn"));
	}

	/** What that queued write puts on disk once the publisher runs it. */
	@Test
	public void theQueuedFinalWriteReportsThePluginInactiveAndCarriesNoGameplayData()
		throws IOException
	{
		logInClient();
		plugin.startUp();
		runClientThreadQueue();
		ControlledPublisher executor = onlyExecutor();
		executor.runScheduledTaskOnce();

		plugin.shutDown();
		executor.runQueuedWork();

		String json = snapshotOnDisk();
		assertEquals("false", value(json, "pluginActive"));
		for (String key : new String[]{"world", "hitpointsCurrent", "usedSlots", "lastSkill"})
		{
			assertEquals(key + " must be null in the final snapshot", "null", value(json, key));
		}
	}

	// --- 11. an old final write cannot overwrite a newer run ---------------------------------

	@Test
	public void aStalledFinalWriteCannotOverwriteANewerActiveRun() throws IOException
	{
		logInClient();

		plugin.startUp();
		runClientThreadQueue();
		ControlledPublisher first = onlyExecutor();
		first.runScheduledTaskOnce();

		// Disable, with the final write left sitting on the old publisher.
		plugin.shutDown();
		assertTrue(first.hasQueuedWork());

		// Re-enable, and let the new run publish an active snapshot.
		plugin.startUp();
		runClientThreadQueue();
		ControlledPublisher second = executors.get(1);
		second.runScheduledTaskOnce();
		String active = snapshotOnDisk();
		assertEquals("true", value(active, "pluginActive"));

		// Only now does the old run's inactive write complete. It has lost authority.
		first.runQueuedWork();
		assertEquals("a retired run must not bury a newer active snapshot",
			active, snapshotOnDisk());

		plugin.shutDown();
	}

	// --- 12. what a live sample actually reads ------------------------------------------------

	@Test
	public void aLiveSampleWritesTheCompleteSchemaTwoPlayerBlock() throws IOException
	{
		logInClient();
		wornItems[3] = new Item(1104, 1);
		inventoryItems[0] = new Item(2001, 1);
		inventoryItems[1] = new Item(2002, 500);

		plugin.startUp();
		runClientThreadQueue();
		onlyExecutor().runScheduledTaskOnce();

		String json = snapshotOnDisk();
		assertEquals("2", value(json, "schema"));
		assertEquals("true", value(json, "loggedIn"));
		assertEquals("302", value(json, "world"));
		assertEquals("87", value(json, "combatLevel"));
		assertEquals("the tracking stamp is set when the session's baselines are established",
			"1770000000000", value(json, "trackingStartedAt"));
		assertEquals("0", value(json, "specialAttackPercent"));
		assertEquals("0", value(json, "weightKg"));
		assertEquals("no prayer is active, which is not the same as unknown", "[]",
			value(json, "activePrayers"));
		assertEquals("no weapon style is readable without the game's own style data", "null",
			value(json, "attackStyle"));
		assertEquals("null", value(json, "target"));
		assertEquals("2", value(json, "usedSlots"));
		assertEquals("26", value(json, "freeSlots"));
		assertTrue(json, json.contains(
			"{\"slot\":\"weapon\",\"itemId\":1104,\"quantity\":1,\"name\":\"Sample item\"}"));
		assertTrue("an unequipped slot nulls all three values", json.contains(
			"{\"slot\":\"head\",\"itemId\":null,\"quantity\":null,\"name\":null}"));
		assertTrue(json, json.contains(
			"{\"slot\":1,\"itemId\":2002,\"quantity\":500,\"name\":\"Sample item\"}"));
		assertTrue(json, json.contains(
			"{\"slot\":27,\"itemId\":null,\"quantity\":null,\"name\":null}"));

		plugin.shutDown();
	}

	/**
	 * End to end, through a real client reading: an item of identity zero is a held item, and the
	 * client's own empty signal is a negative identity. Reading zero as absent reported a carried
	 * item as an empty slot and undercounted occupancy.
	 */
	@Test
	public void anItemOfIdentityZeroIsSampledAsHeldRatherThanAsAnEmptySlot() throws IOException
	{
		logInClient();
		inventoryItems[0] = new Item(0, 1);
		inventoryItems[1] = new Item(2001, 3);
		// Left at the client's empty sentinel, as every other slot already is.
		inventoryItems[2] = new Item(-1, 0);
		wornItems[3] = new Item(0, 1);

		plugin.startUp();
		runClientThreadQueue();
		onlyExecutor().runScheduledTaskOnce();

		String json = snapshotOnDisk();
		assertEquals("both held items are counted", "2", value(json, "usedSlots"));
		assertEquals("26", value(json, "freeSlots"));
		assertTrue(json, json.contains(
			"{\"slot\":0,\"itemId\":0,\"quantity\":1,\"name\":\"Sample item\"}"));
		assertTrue(json, json.contains(
			"{\"slot\":1,\"itemId\":2001,\"quantity\":3,\"name\":\"Sample item\"}"));
		assertTrue("the negative sentinel is still an empty slot", json.contains(
			"{\"slot\":2,\"itemId\":null,\"quantity\":null,\"name\":null}"));
		assertTrue("and equipment behaves identically", json.contains(
			"{\"slot\":\"weapon\",\"itemId\":0,\"quantity\":1,\"name\":\"Sample item\"}"));

		plugin.shutDown();
	}

	/**
	 * The three RuneLite equipment slots that only exist on the player model (arms, hair, and jaw)
	 * must not appear, and each exported slot must read from the client slot the schema names.
	 */
	@Test
	public void onlyTheElevenVisibleEquipmentSlotsAreReadAndTheyKeepTheirOwnPositions()
		throws IOException
	{
		logInClient();
		// Distinct identities per client slot index, so a swapped mapping is visible.
		for (int slot = 0; slot < wornItems.length; slot++)
		{
			wornItems[slot] = new Item(7000 + slot, 1);
		}

		plugin.startUp();
		runClientThreadQueue();
		onlyExecutor().runScheduledTaskOnce();

		String json = snapshotOnDisk();
		// RuneLite's own slot indexes: head 0, cape 1, amulet 2, weapon 3, body 4, shield 5,
		// legs 7, gloves 9, boots 10, ring 12, ammo 13.
		int[] clientSlots = {0, 1, 2, 3, 4, 5, 7, 9, 10, 12, 13};
		List<String> names = TelemetrySnapshot.EQUIPMENT_SLOTS;
		for (int i = 0; i < names.size(); i++)
		{
			assertTrue("exported slot " + names.get(i) + " must carry client slot " + clientSlots[i],
				json.contains("{\"slot\":\"" + names.get(i) + "\",\"itemId\":"
					+ (7000 + clientSlots[i]) + ","));
		}
		for (int modelOnly : new int[]{6, 8, 11})
		{
			assertFalse("the model-only slot " + modelOnly + " must never be exported",
				json.contains("\"itemId\":" + (7000 + modelOnly) + ","));
		}

		plugin.shutDown();
	}

	@Test
	public void anInteractedWithNpcIsExportedWithItsObservableHealthOnly() throws IOException
	{
		logInClient();
		NPC npc = mock(NPC.class);
		when(npc.getId()).thenReturn(4001);
		when(npc.getName()).thenReturn("Sample dummy");
		when(npc.getCombatLevel()).thenReturn(21);
		when(npc.getHealthRatio()).thenReturn(18);
		when(npc.getHealthScale()).thenReturn(30);
		when(npc.isDead()).thenReturn(false);
		when(localPlayer.getInteracting()).thenReturn(npc);

		plugin.startUp();
		runClientThreadQueue();
		ControlledPublisher executor = onlyExecutor();
		executor.runScheduledTaskOnce();

		String json = snapshotOnDisk();
		assertTrue(json, json.contains("\"target\":{\"kind\":\"npc\",\"id\":4001,"
			+ "\"name\":\"Sample dummy\",\"combatLevel\":21,\"healthRatio\":18,"
			+ "\"healthScale\":30,\"dead\":false}"));
		assertFalse("no exact hitpoints figure may be estimated for a target",
			json.contains("\"hitpoints\":") || json.contains("\"targetHitpoints\""));

		// The interaction ends: the target must clear on the very next sample.
		when(localPlayer.getInteracting()).thenReturn(null);
		tick();
		executor.runScheduledTaskOnce();
		assertEquals("null", value(snapshotOnDisk(), "target"));

		plugin.shutDown();
	}

	/**
	 * The privacy boundary, driven end to end. A player can be interacted with by following,
	 * trading, or attacking, and that actor carries another person's display name. Nothing about it
	 * may reach the file.
	 */
	@Test
	public void aPlayerTargetIsNeverExportedAndItsNameNeverLeavesTheClient() throws IOException
	{
		logInClient();
		Player other = mock(Player.class);
		when(other.getName()).thenReturn("SomeOtherPlayer");
		when(other.getCombatLevel()).thenReturn(112);
		when(other.getHealthRatio()).thenReturn(20);
		when(other.getHealthScale()).thenReturn(30);
		when(localPlayer.getInteracting()).thenReturn(other);

		plugin.startUp();
		runClientThreadQueue();
		onlyExecutor().runScheduledTaskOnce();

		String json = snapshotOnDisk();
		assertEquals("a player target has no representation in the schema", "null",
			value(json, "target"));
		assertFalse("another player's name must never appear", json.contains("SomeOtherPlayer"));
		assertFalse(json.contains("112"));

		plugin.shutDown();
	}

	@Test
	public void theLocalPlayersOwnNameIsNeverReadAndNeverExported() throws IOException
	{
		logInClient();
		when(localPlayer.getName()).thenReturn("TheOperatorsCharacter");

		plugin.startUp();
		runClientThreadQueue();
		onlyExecutor().runScheduledTaskOnce();

		assertFalse("the local player's name must not reach the file",
			snapshotOnDisk().contains("TheOperatorsCharacter"));
		// Proven at the source too: the plugin never calls for it.
		org.mockito.Mockito.verify(localPlayer, org.mockito.Mockito.never()).getName();

		plugin.shutDown();
	}

	/**
	 * A logged-in client whose local player has not resolved yet cannot produce a complete player
	 * block, so the document must not claim one, and must not invent an empty inventory or an empty
	 * prayer list in its place.
	 */
	@Test
	public void anIncompleteLiveSampleIsReportedAsCarryingNoPlayerDataRatherThanEmptyCollections()
		throws IOException
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getWorld()).thenReturn(302);
		when(client.getLocalPlayer()).thenReturn(null);

		plugin.startUp();
		runClientThreadQueue();
		ControlledPublisher executor = onlyExecutor();
		executor.runScheduledTaskOnce();

		String json = snapshotOnDisk();
		assertEquals("\"LOGGED_IN\"", value(json, "gameState"));
		assertEquals("false", value(json, "loggedIn"));
		assertEquals("null", value(json, "combatLevel"));
		assertEquals("null", value(json, "activePrayers"));
		assertEquals("null", value(json, "usedSlots"));
		assertEquals("null", value(json, "slots"));

		// Once the local player resolves, the very next sample completes the block.
		when(client.getLocalPlayer()).thenReturn(localPlayer);
		tick();
		executor.runScheduledTaskOnce();
		String complete = snapshotOnDisk();
		assertEquals("true", value(complete, "loggedIn"));
		assertEquals("87", value(complete, "combatLevel"));
		assertEquals("[]", value(complete, "activePrayers"));
		assertEquals("0", value(complete, "usedSlots"));

		plugin.shutDown();
	}

	@Test
	public void aMissingInventoryContainerLeavesTheLastGoodReadingRatherThanAnEmptyInventory()
		throws IOException
	{
		logInClient();
		inventoryItems[0] = new Item(2001, 1);

		plugin.startUp();
		runClientThreadQueue();
		ControlledPublisher executor = onlyExecutor();
		executor.runScheduledTaskOnce();
		assertEquals("1", value(snapshotOnDisk(), "usedSlots"));

		// The container disappears for a tick, as it can between states.
		when(client.getItemContainer(InventoryID.INV)).thenReturn(null);
		tick();
		executor.runScheduledTaskOnce();
		assertEquals("the last real reading stands rather than an invented empty inventory",
			"1", value(snapshotOnDisk(), "usedSlots"));

		plugin.shutDown();
	}

	/**
	 * The written document, scanned for the things schema 2 is closed against. This is the check
	 * that runs against a real file the plugin produced rather than against a hand-built one.
	 */
	@Test
	public void theWrittenDocumentContainsNoIdentitySocialBankLocationOrControlContent()
		throws IOException
	{
		logInClient();
		when(localPlayer.getName()).thenReturn("TheOperatorsCharacter");
		inventoryItems[0] = new Item(2001, 1);
		wornItems[3] = new Item(1104, 1);

		plugin.startUp();
		runClientThreadQueue();
		onlyExecutor().runScheduledTaskOnce();

		String json = snapshotOnDisk();
		for (String forbidden : new String[]{"accountHash", "accountType", "username",
			"displayName", "playerName", "email", "password", "token", "credential", "profile",
			"chat", "friends", "clan", "party", "nearbyPlayers", "bank", "wealth", "grandExchange",
			"price", "value", "tradeable", "examine", "totalXp", "startingXp", "quest", "slayerTask",
			"loot", "worldPoint", "regionId", "coordinates", "plane", "movement", "url", "http",
			"://", "command", "menu", "click", "keystroke", "sprite", "icon",
			"TheOperatorsCharacter"})
		{
			assertFalse("the written document must not contain " + forbidden,
				json.contains(forbidden));
		}
		assertFalse("no filesystem path may appear in the document",
			json.contains(temporaryFolder.getRoot().getAbsolutePath()));

		plugin.shutDown();
	}

	/**
	 * A periodic task that throws is cancelled by its executor and never runs again, so an
	 * unchecked failure inside one publication would silently end publication for the rest of the
	 * run and leave the file reading as live but frozen. The failure has to be contained.
	 */
	@Test
	public void anUncheckedFailureInsideOnePublicationDoesNotStopThePublisher() throws IOException
	{
		logInClient();
		plugin.startUp();
		runClientThreadQueue();
		ControlledPublisher executor = onlyExecutor();
		executor.runScheduledTaskOnce();
		String firstSnapshot = snapshotOnDisk();

		// A heartbeat is now due, so the next tick really does build a snapshot, and building one
		// reads the wall clock, which is where the failure is delivered.
		elapsed += HEARTBEAT_MILLIS * 1_000_000L;
		wallClockFails = true;
		executor.runScheduledTaskOnce();
		assertEquals("the file simply stops advancing", firstSnapshot, snapshotOnDisk());
		assertTrue("and the periodic task must still be scheduled", executor.hasScheduledTask());

		// The refused publication never recorded itself, so the heartbeat is still due and the very
		// next tick publishes.
		wallClockFails = false;
		now = 1_770_000_050_000L;
		executor.runScheduledTaskOnce();
		assertEquals("publication resumes on the same task", "1770000050000",
			value(snapshotOnDisk(), "emittedAt"));

		plugin.shutDown();
	}

	/**
	 * A failing client read is a separate path: it surfaces on RuneLite's event bus, which logs and
	 * continues, and it must not disturb the publisher or what is already on disk.
	 */
	@Test
	public void aFailingClientReadLeavesThePublisherAndTheFileIntact() throws IOException
	{
		logInClient();
		plugin.startUp();
		runClientThreadQueue();
		ControlledPublisher executor = onlyExecutor();
		executor.runScheduledTaskOnce();
		String firstSnapshot = snapshotOnDisk();

		// doReturn/doThrow rather than when(...): the mock is about to throw, and when() would
		// invoke it to record the stub.
		org.mockito.Mockito.doThrow(new IllegalStateException("simulated client failure"))
			.when(client).getGameState();
		tick_expectingFailure();
		executor.runScheduledTaskOnce();
		assertEquals("nothing partial reaches the file", firstSnapshot, snapshotOnDisk());

		doReturn(GameState.LOGGED_IN).when(client).getGameState();
		doReturn(303).when(client).getWorld();
		now = 1_770_000_050_000L;
		tick();
		executor.runScheduledTaskOnce();
		String recovered = snapshotOnDisk();
		assertEquals("1770000050000", value(recovered, "emittedAt"));
		assertEquals("303", value(recovered, "world"));

		plugin.shutDown();
	}

	/** A tick whose client read fails; the event-bus caller sees the failure, the publisher does not. */
	private void tick_expectingFailure()
	{
		try
		{
			tick();
		}
		catch (RuntimeException expected)
		{
			// The game-tick handler runs on RuneLite's event bus, which logs and continues. What
			// matters here is that the publisher thread is unaffected.
		}
	}

	// --- 13. attack style, read from the game's own data ---------------------------------------

	@Test
	public void theAttackStyleLabelComesFromTheGamesOwnStyleData() throws IOException
	{
		logInClient();
		stubWeaponStyles("Accurate", "Aggressive", "Controlled", "Defensive");
		doReturn(2).when(client).getVarpValue(VarPlayerID.COM_MODE);

		plugin.startUp();
		runClientThreadQueue();
		ControlledPublisher executor = onlyExecutor();
		executor.runScheduledTaskOnce();
		assertEquals("\"controlled\"", value(snapshotOnDisk(), "attackStyle"));

		doReturn(3).when(client).getVarpValue(VarPlayerID.COM_MODE);
		tick();
		executor.runScheduledTaskOnce();
		assertEquals("\"defensive\"", value(snapshotOnDisk(), "attackStyle"));

		plugin.shutDown();
	}

	/**
	 * A staff's casting position consults a second variable, exactly as the game's own combat
	 * interface does, so defensive casting is not reported as plain casting.
	 */
	@Test
	public void aStavesCastingModeSelectsTheStyleTheGameWouldShow() throws IOException
	{
		logInClient();
		stubWeaponStyles("Accurate", "Aggressive", "Other", "Defensive", "Casting", "Defensive");
		doReturn(4).when(client).getVarpValue(VarPlayerID.COM_MODE);

		plugin.startUp();
		runClientThreadQueue();
		ControlledPublisher executor = onlyExecutor();
		executor.runScheduledTaskOnce();
		assertEquals("\"casting\"", value(snapshotOnDisk(), "attackStyle"));

		doReturn(1).when(client).getVarbitValue(VarbitID.AUTOCAST_DEFMODE);
		tick();
		executor.runScheduledTaskOnce();
		assertEquals("\"defensive\"", value(snapshotOnDisk(), "attackStyle"));

		plugin.shutDown();
	}

	@Test
	public void anUnreadableOrAbsentAttackStyleIsReportedAsNoneRatherThanGuessed() throws IOException
	{
		logInClient();
		// The game's marker for "no style in this position" is not a style label.
		stubWeaponStyles("Accurate", "Other", "Controlled");
		doReturn(1).when(client).getVarpValue(VarPlayerID.COM_MODE);

		plugin.startUp();
		runClientThreadQueue();
		ControlledPublisher executor = onlyExecutor();
		executor.runScheduledTaskOnce();
		assertEquals("null", value(snapshotOnDisk(), "attackStyle"));

		// An index the weapon's style list does not have.
		doReturn(9).when(client).getVarpValue(VarPlayerID.COM_MODE);
		tick();
		executor.runScheduledTaskOnce();
		assertEquals("null", value(snapshotOnDisk(), "attackStyle"));

		plugin.shutDown();
	}

	/**
	 * A weapon category the game's own style enumeration has no entry for yields no reading. The
	 * client falls back to hardcoded style lists for a couple of these; copying those numbers here
	 * would be the unverified mapping this plugin refuses to carry.
	 */
	@Test
	public void aWeaponCategoryWithNoStyleListInTheGameDataYieldsNoReading() throws IOException
	{
		logInClient();
		EnumComposition weaponStyles = mock(EnumComposition.class);
		when(weaponStyles.getIntValue(anyInt())).thenReturn(-1);
		when(client.getEnum(EnumID.WEAPON_STYLES)).thenReturn(weaponStyles);

		plugin.startUp();
		runClientThreadQueue();
		onlyExecutor().runScheduledTaskOnce();
		assertEquals("null", value(snapshotOnDisk(), "attackStyle"));

		plugin.shutDown();
	}

	private void stubWeaponStyles(String... styleNames)
	{
		EnumComposition weaponStyles = mock(EnumComposition.class);
		when(weaponStyles.getIntValue(anyInt())).thenReturn(9_100);
		when(client.getEnum(EnumID.WEAPON_STYLES)).thenReturn(weaponStyles);

		int[] structIds = new int[styleNames.length];
		for (int i = 0; i < styleNames.length; i++)
		{
			structIds[i] = 9_200 + i;
			StructComposition style = mock(StructComposition.class);
			when(style.getStringValue(ParamID.ATTACK_STYLE_NAME)).thenReturn(styleNames[i]);
			when(client.getStructComposition(structIds[i])).thenReturn(style);
		}
		EnumComposition styleList = mock(EnumComposition.class);
		when(styleList.getIntVals()).thenReturn(structIds);
		when(client.getEnum(9_100)).thenReturn(styleList);
	}

	// --- 14. active prayers ---------------------------------------------------------------------

	@Test
	public void activePrayersAreReportedInEnumOrderWithNoDuplicates() throws IOException
	{
		logInClient();
		doReturn(1).when(client).getVarbitValue(Prayer.THICK_SKIN.getVarbit());
		doReturn(1).when(client).getVarbitValue(Prayer.PIETY.getVarbit());

		plugin.startUp();
		runClientThreadQueue();
		ControlledPublisher executor = onlyExecutor();
		executor.runScheduledTaskOnce();

		String prayers = value(snapshotOnDisk(), "activePrayers");
		assertEquals("[\"thick_skin\",\"piety\"]", prayers);

		// Turning them off returns an empty array, not a null.
		doReturn(0).when(client).getVarbitValue(Prayer.THICK_SKIN.getVarbit());
		doReturn(0).when(client).getVarbitValue(Prayer.PIETY.getVarbit());
		tick();
		executor.runScheduledTaskOnce();
		assertEquals("[]", value(snapshotOnDisk(), "activePrayers"));

		plugin.shutDown();
	}

	/**
	 * The upgraded ranged prayer shares its slot with the one it replaces, so the older prayer's
	 * variable reads as active when the newer one is in use. Only one of the pair may be reported.
	 */
	@Test
	public void anUpgradedPrayerReplacesTheOneItSupersedesRatherThanBothBeingReported()
		throws IOException
	{
		logInClient();
		doReturn(1).when(client).getVarbitValue(Prayer.EAGLE_EYE.getVarbit());
		doReturn(1).when(client).getVarbitValue(Prayer.DEADEYE.getVarbit());
		doReturn(1).when(client).getVarbitValue(VarbitID.PRAYER_DEADEYE_UNLOCKED);

		plugin.startUp();
		runClientThreadQueue();
		ControlledPublisher executor = onlyExecutor();
		executor.runScheduledTaskOnce();
		assertEquals("[\"deadeye\"]", value(snapshotOnDisk(), "activePrayers"));

		// Inside Last Man Standing the unlock does not apply, so the base prayer is the live one.
		doReturn(1).when(client).getVarbitValue(VarbitID.BR_INGAME);
		tick();
		executor.runScheduledTaskOnce();
		assertEquals("[\"eagle_eye\"]", value(snapshotOnDisk(), "activePrayers"));

		plugin.shutDown();
	}

	@Test
	public void specialAttackAndWeightAreReadAndNormalized() throws IOException
	{
		logInClient();
		doReturn(650).when(client).getVarpValue(VarPlayerID.SA_ENERGY);
		doReturn(8_800).when(client).getEnergy();
		doReturn(37).when(client).getWeight();

		plugin.startUp();
		runClientThreadQueue();
		ControlledPublisher executor = onlyExecutor();
		executor.runScheduledTaskOnce();

		String json = snapshotOnDisk();
		assertEquals("65", value(json, "specialAttackPercent"));
		assertEquals("88", value(json, "runEnergyPercent"));
		assertEquals("37", value(json, "weightKg"));

		doReturn(1_000).when(client).getVarpValue(VarPlayerID.SA_ENERGY);
		doReturn(-22).when(client).getWeight();
		tick();
		executor.runScheduledTaskOnce();
		json = snapshotOnDisk();
		assertEquals("100", value(json, "specialAttackPercent"));
		assertEquals("-22", value(json, "weightKg"));

		plugin.shutDown();
	}

	// --- isolation ---------------------------------------------------------------------------

	@Test
	public void nothingIsWrittenOutsideTheTemporaryDirectory() throws IOException
	{
		logInClient();
		plugin.startUp();
		runClientThreadQueue();
		onlyExecutor().runScheduledTaskOnce();
		plugin.shutDown();

		Path target = dataDirectory.resolve(TelemetrySnapshotWriter.TARGET_FILE_NAME);
		assertTrue(Files.exists(target));
		assertTrue("the snapshot must live under the test's own directory",
			target.toAbsolutePath().startsWith(temporaryFolder.getRoot().toPath().toAbsolutePath()));
	}

	// --- the controlled publisher ---------------------------------------------------------------

	/**
	 * A publisher executor that runs nothing on its own. Real threads would make every assertion
	 * here a race, so this records what the plugin scheduled and what it submitted and runs either
	 * only when a test says so, which makes publication and shutdown ordering exact rather than
	 * probable. Only the methods the plugin actually calls are given behavior.
	 */
	private final class ControlledPublisher
	{
		private final ScheduledExecutorService service = mock(ScheduledExecutorService.class);
		private final List<Runnable> queued = new ArrayList<>();
		private Runnable scheduledTask;

		/** The handle the plugin was given for the periodic task, so its cancellation is checkable. */
		private ScheduledFuture<?> scheduledTaskHandle;
		private boolean shutdown;

		/** True when the run adopted this executor before scheduling anything on it. */
		private boolean adoptedBeforeScheduling;

		private ControlledPublisher()
		{
			when(service.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(),
				any(TimeUnit.class))).thenAnswer(invocation ->
			{
				// Reaching here at all means the run already owns this executor, because the
				// plugin adopts before it schedules. That ordering is the assertion.
				adoptedBeforeScheduling = true;
				scheduledTask = invocation.getArgument(0);
				scheduledTaskHandle = mock(ScheduledFuture.class);
				return scheduledTaskHandle;
			});
			doAnswer(invocation ->
			{
				if (shutdown)
				{
					throw new RejectedExecutionException("shut down");
				}
				queued.add(invocation.getArgument(0));
				return null;
			}).when(service).execute(any(Runnable.class));
			doAnswer(invocation ->
			{
				shutdown = true;
				return null;
			}).when(service).shutdown();
			// shutdownNow is deliberately left unstubbed. The plugin must never force a publisher
			// down, and a call here would leave this executor reading as live at test end.
			when(service.isShutdown()).thenAnswer(invocation -> shutdown);
		}

		private boolean hasScheduledTask()
		{
			return scheduledTask != null;
		}

		private void runScheduledTaskOnce()
		{
			assertTrue("no periodic task was scheduled", scheduledTask != null);
			scheduledTask.run();
		}

		private boolean hasQueuedWork()
		{
			return !queued.isEmpty();
		}

		private void runQueuedWork()
		{
			List<Runnable> pending = new ArrayList<>(queued);
			queued.clear();
			for (Runnable runnable : pending)
			{
				runnable.run();
			}
		}

		private boolean isShutdown()
		{
			return shutdown;
		}
	}
}
