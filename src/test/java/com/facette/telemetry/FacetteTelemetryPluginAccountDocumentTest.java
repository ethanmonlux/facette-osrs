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
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.events.RuneScapeProfileChanged;

/**
 * Drives the real plugin, configured as it ships, against a mocked client and a temporary managed
 * directory, for what the schema-5 account document adds: the bank and the Seed Vault recorded only
 * when the game delivers them and with their original observation time, bounded quest reads where a
 * failed read is never "not started", diary and Combat Achievement tier variables, clearing at
 * logout, profile change and disable, and an account document that can never stop the character
 * from being published. Login recognition itself is covered by {@link FacetteTelemetryPluginSchema5Test}.
 */
public class FacetteTelemetryPluginAccountDocumentTest
{
	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	private Client client;
	private ClientThread clientThread;
	private final List<Runnable> clientThreadQueue = new ArrayList<>();
	private ScheduledExecutorService executor;
	private Runnable scheduledTask;
	private final List<Runnable> executorQueue = new ArrayList<>();
	private Path managedDirectory;
	private SnapshotStore managedStore;
	// When set, every attempt to stage account-v5.json fails with an unchecked exception.
	private boolean accountStoreBroken;
	private boolean managedDirectoryAvailable = true;
	private long now = 1_770_000_000_000L;
	private long elapsed;
	private FacetteTelemetryPlugin plugin;

	@Before
	public void setUp() throws IOException
	{
		client = mock(Client.class);
		clientThread = mock(ClientThread.class);
		doAnswer(invocation ->
		{
			clientThreadQueue.add(invocation.getArgument(0));
			return null;
		}).when(clientThread).invoke(any(Runnable.class));

		executor = mock(ScheduledExecutorService.class);
		when(executor.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(),
			any(TimeUnit.class))).thenAnswer(invocation ->
		{
			scheduledTask = invocation.getArgument(0);
			return mock(ScheduledFuture.class);
		});
		doAnswer(invocation ->
		{
			executorQueue.add(invocation.getArgument(0));
			return null;
		}).when(executor).execute(any(Runnable.class));

		managedDirectory = temporaryFolder.getRoot().toPath()
			.resolve("plugin-data").resolve("facette-companion");
		PathSnapshotStore directory = new PathSnapshotStore(managedDirectory);
		managedStore = new SnapshotStore()
		{
			@Override
			public String describe(String fileName)
			{
				return directory.describe(fileName);
			}

			@Override
			public void createDirectories() throws IOException
			{
				directory.createDirectories();
			}

			@Override
			public StagedFile createTempFile(String prefix, String suffix) throws IOException
			{
				if (accountStoreBroken && prefix.startsWith("account-v5"))
				{
					throw new IllegalStateException("account store unavailable");
				}
				return directory.createTempFile(prefix, suffix);
			}

			@Override
			public void deleteStale(String prefix, String suffix, long cutoffMillis) throws IOException
			{
				directory.deleteStale(prefix, suffix, cutoffMillis);
			}
		};

		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getWorld()).thenReturn(302);
		Player localPlayer = mock(Player.class);
		when(localPlayer.getCombatLevel()).thenReturn(3);
		when(client.getLocalPlayer()).thenReturn(localPlayer);
		// Every skill initialized, so the skill group is current and account reads may start.
		when(client.getRealSkillLevel(any(Skill.class))).thenReturn(1);
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(1);
		when(client.getSkillExperience(any(Skill.class))).thenReturn(0);
		// Every quest reads as finished: RuneLite's Quest.getState runs the status script and reads
		// the top of the integer stack.
		when(client.getIntStack()).thenReturn(new int[]{2});

		ItemComposition ordinary = mock(ItemComposition.class);
		when(ordinary.getPlaceholderTemplateId()).thenReturn(-1);
		when(ordinary.getMembersName()).thenReturn("Sample item");
		when(client.getItemDefinition(anyInt())).thenReturn(ordinary);
		ItemComposition placeholder = mock(ItemComposition.class);
		when(placeholder.getPlaceholderTemplateId()).thenReturn(14401);
		when(client.getItemDefinition(14402)).thenReturn(placeholder);

		// The live reproduction's worst case: the client has provided neither container.
		when(client.getItemContainer(InventoryID.WORN)).thenReturn(null);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(null);

		AtomicInteger sessions = new AtomicInteger();
		plugin = new FacetteTelemetryPlugin(() -> now, () -> elapsed, () -> "instance-1",
			() -> executor,
			() -> managedDirectoryAvailable ? managedStore : null,
			() -> "session-" + sessions.incrementAndGet());
		plugin.client = client;
		plugin.clientThread = clientThread;
	}

	private void start()
	{
		plugin.startUp();
		List<Runnable> pending = new ArrayList<>(clientThreadQueue);
		clientThreadQueue.clear();
		pending.forEach(Runnable::run);
	}

	private void publish()
	{
		assertTrue("a periodic task was scheduled", scheduledTask != null);
		scheduledTask.run();
	}

	private void tick()
	{
		now += 600L;
		elapsed += 600_000_000L;
		plugin.onGameTick(new GameTick());
	}

	private String read(Path path) throws IOException
	{
		assertTrue(path + " was not written", Files.exists(path));
		return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
	}

	private String stateV5() throws IOException
	{
		return read(managedDirectory.resolve("state-v5.json"));
	}

	private String accountV5() throws IOException
	{
		return read(managedDirectory.resolve("account-v5.json"));
	}

	private void gameState(GameState state)
	{
		when(client.getGameState()).thenReturn(state);
		GameStateChanged changed = new GameStateChanged();
		changed.setGameState(state);
		plugin.onGameStateChanged(changed);
	}

	private ItemContainer containerOf(Item... items)
	{
		ItemContainer container = mock(ItemContainer.class);
		when(container.getItems()).thenReturn(items);
		return container;
	}

	@Test
	public void aBankThePlayerOpensIsWrittenOnceAndReferencedExactly() throws IOException
	{
		start();
		publish();
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.BANK, containerOf(
			new Item(995, 1_000), new Item(-1, 0), new Item(14402, 0), new Item(20594, 1))));
		// Past the write interval the run's first document, which observed nothing, started.
		elapsed += 3_000_000_000L;
		publish();

		String account = accountV5();
		assertTrue(account.contains("\"characterSessionId\":\"session-1\",\"revision\":2"));
		assertTrue(account.contains("\"items\":[{\"itemId\":995,\"quantity\":1000,\"kind\":\"item\"},"
			+ "{\"itemId\":14402,\"quantity\":0,\"kind\":\"placeholder\"},"
			+ "{\"itemId\":20594,\"quantity\":1,\"kind\":\"filler\"}]"));
		assertTrue(account.contains("\"seedVault\":{\"availability\":\"not_observed\""));
		assertTrue(stateV5().endsWith("\"accountData\":{\"file\":\"account-v5.json\",\"revision\":2}}"));

		// Unchanged, so the heartbeat never rewrites it.
		long modified = Files.getLastModifiedTime(managedDirectory.resolve("account-v5.json")).toMillis();
		elapsed += 10_000_000_000L;
		publish();
		assertTrue(accountV5().contains("\"revision\":2,"));
		assertEquals(modified,
			Files.getLastModifiedTime(managedDirectory.resolve("account-v5.json")).toMillis());
	}

	@Test
	public void questReadsAreBoundedPerTickAndStartOnTheSecondTick() throws IOException
	{
		when(client.getVarbitValue(VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE)).thenReturn(1);
		// Karamja's staged variable: 1 is started, not complete.
		when(client.getVarbitValue(VarbitID.ATJUN_EASY_DONE)).thenReturn(1);
		when(client.getVarbitValue(VarbitID.ATJUN_MED_DONE)).thenReturn(2);
		when(client.getVarbitValue(VarbitID.CA_TIER_STATUS_EASY)).thenReturn(2);
		when(client.getVarbitValue(VarbitID.CA_TIER_STATUS_MEDIUM)).thenReturn(1);
		start();

		tick();
		assertEquals("nothing on the first tick of a session", 0, scriptRuns());
		tick();
		assertEquals("one bounded batch", 24, scriptRuns());
		publish();

		String account = accountV5();
		assertTrue(account.contains("\"questStates\":{\"availability\":\"partial\""));
		assertTrue(account.contains("\"state\":\"finished\""));
		assertTrue(account.contains("{\"region\":\"ardougne\",\"easy\":true,"));
		assertTrue(account.contains("{\"region\":\"karamja\",\"easy\":false,\"medium\":true,"));
		assertTrue(account.contains("[{\"tier\":\"easy\",\"completed\":true},"
			+ "{\"tier\":\"medium\",\"completed\":false},"));

		for (int i = 0; i < 20; i++)
		{
			tick();
		}
		elapsed += 3_000_000_000L;
		publish();
		assertTrue("a whole pass completes within a bounded number of ticks",
			accountV5().contains("\"questStates\":{\"availability\":\"current\""));
	}

	private long scriptRuns()
	{
		return mockingDetails(client).getInvocations().stream()
			.filter(invocation -> invocation.getMethod().getName().equals("runScript"))
			.count();
	}

	@Test
	public void logoutClearsTheAccountDocumentAndEndsTheSession() throws IOException
	{
		start();
		publish();
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.BANK,
			containerOf(new Item(995, 1_000))));
		elapsed += 3_000_000_000L;
		publish();
		assertTrue(accountV5().contains("995"));

		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		GameStateChanged loggedOut = new GameStateChanged();
		loggedOut.setGameState(GameState.LOGIN_SCREEN);
		plugin.onGameStateChanged(loggedOut);
		publish();

		String account = accountV5();
		assertTrue(account.contains("\"characterSessionId\":null,\"revision\":3"));
		assertFalse("the previous character's bank does not stay on disk", account.contains("995"));
		String v5 = stateV5();
		assertTrue(v5.contains("\"readiness\":{\"state\":\"logged_out\",\"missing\":[]}"));
		assertTrue(v5.endsWith("\"accountData\":null}"));
	}

	@Test
	public void disablingWritesAnInactiveSchemaFiveAndClearsTheAccountDocument() throws IOException
	{
		start();
		publish();
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.SEED_VAULT,
			containerOf(new Item(5318, 3))));
		elapsed += 3_000_000_000L;
		publish();
		assertTrue(accountV5().contains("5318"));

		plugin.shutDown();
		new ArrayList<>(executorQueue).forEach(Runnable::run);

		assertTrue(stateV5().contains("\"readiness\":{\"state\":\"inactive\",\"missing\":[]}"));
		assertTrue(stateV5().contains("\"characterSessionId\":null"));
		assertFalse(accountV5().contains("5318"));
	}
	@Test
	public void aDeliveredContainerKeepsTheTimeTheGameDeliveredItAndAnUnopenedOneIsNotObserved()
		throws IOException
	{
		start();
		publish();
		long deliveredAt = now;
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.BANK, containerOf()));
		now += 30_000L;
		elapsed += 30_000_000_000L;
		publish();

		String account = accountV5();
		assertTrue("a delivered empty bank is a confirmed empty bank", account.contains(
			"\"bank\":{\"availability\":\"observed\",\"observedAt\":" + deliveredAt + ",\"items\":[]}"));
		assertTrue("a Seed Vault the player never opened is not observed, not empty", account.contains(
			"\"seedVault\":{\"availability\":\"not_observed\",\"observedAt\":null,\"items\":null}"));
		assertFalse("the write time is not the observation time",
			account.contains("\"observedAt\":" + now));
	}

	@Test
	public void aQuestThatCannotBeReadIsUnavailableAndTheCharacterIsStillReady() throws IOException
	{
		ItemContainer worn = containerOf(new Item[14]);
		ItemContainer inventory = containerOf(new Item[28]);
		when(client.getItemContainer(InventoryID.WORN)).thenReturn(worn);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inventory);
		// RuneLite's Quest.getState reads the status script's result from the integer stack.
		when(client.getIntStack()).thenThrow(new IllegalStateException("script failed"));
		start();
		tick();
		tick();
		publish();

		String account = accountV5();
		assertTrue(account.contains("\"state\":\"unavailable\""));
		assertFalse("a failed read is never reported as not started",
			account.contains("\"state\":\"not_started\""));
		assertFalse(account.contains("\"state\":\"finished\""));
		String v5 = stateV5();
		assertTrue(v5.contains("\"loggedIn\":true"));
		assertTrue(v5.contains("\"readiness\":{\"state\":\"ready\",\"missing\":[]}"));
	}

	@Test
	public void aProfileChangeClearsThePreviousCharactersBankAndStartsWithNothingObserved()
		throws IOException
	{
		start();
		publish();
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.BANK,
			containerOf(new Item(995, 1_000))));
		elapsed += 3_000_000_000L;
		publish();
		String banked = accountV5();
		assertTrue(banked.contains("\"characterSessionId\":\"session-1\""));
		assertTrue("the bank reached the file before the profile change", banked.contains("995"));

		plugin.onRuneScapeProfileChanged(new RuneScapeProfileChanged("profile-a", "profile-b"));
		publish();
		String cleared = accountV5();
		assertTrue(cleared.contains("\"characterSessionId\":null,\"revision\":3"));
		assertFalse("the previous character's bank does not stay on disk", cleared.contains("995"));

		tick();
		elapsed += 3_000_000_000L;
		publish();
		String v5 = stateV5();
		assertTrue(v5.contains("\"characterSessionId\":\"session-2\""));
		assertTrue("the new session references no account revision until it observes something",
			v5.endsWith("\"accountData\":null}"));
		assertFalse(accountV5().contains("995"));
	}

	@Test
	public void aLogoutAndALoginAsAnotherCharacterNeverCarryTheBankAcross() throws IOException
	{
		start();
		publish();
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.BANK,
			containerOf(new Item(995, 1_000))));
		elapsed += 3_000_000_000L;
		publish();
		assertTrue("the first character's bank reached the file", accountV5().contains("995"));

		gameState(GameState.LOGIN_SCREEN);
		publish();
		gameState(GameState.LOGGING_IN);
		gameState(GameState.LOGGED_IN);
		tick();
		elapsed += 3_000_000_000L;
		publish();
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.BANK,
			containerOf(new Item(4151, 1))));
		elapsed += 3_000_000_000L;
		publish();

		String account = accountV5();
		assertTrue(account.contains("\"characterSessionId\":\"session-2\",\"revision\":4"));
		assertTrue(account.contains("4151"));
		assertFalse(account.contains("995"));
		assertTrue(stateV5().endsWith("\"accountData\":{\"file\":\"account-v5.json\",\"revision\":4}}"));
	}

	/**
	 * The account document is optional. A failure writing it, even an unchecked one, must not stop
	 * the state files that recognize the character from being published.
	 */
	@Test
	public void aFailingAccountWriteNeverStopsTheCharacterBeingPublished() throws IOException
	{
		accountStoreBroken = true;
		start();
		publish();
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.BANK,
			containerOf(new Item(995, 1_000))));
		for (int i = 0; i < 3; i++)
		{
			elapsed += 3_000_000_000L;
			publish();
		}

		assertFalse(Files.exists(managedDirectory.resolve("account-v5.json")));
		String v5 = stateV5();
		assertTrue(v5.contains("\"loggedIn\":true"));
		assertTrue("every heartbeat still publishes", v5.contains("\"seq\":3,"));
		assertTrue(v5.endsWith("\"accountData\":null}"));
		assertTrue(read(managedDirectory.resolve("state-v3.json")).contains("\"seq\":3,"));

		accountStoreBroken = false;
		elapsed += 3_000_000_000L;
		publish();
		assertTrue("the account document is retried once it can be written", accountV5().contains("995"));
	}
	@Test
	public void aFailingAccountClearingNeverStopsTheInactiveSnapshotAtDisable() throws IOException
	{
		start();
		publish();
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.BANK,
			containerOf(new Item(995, 1_000))));
		elapsed += 3_000_000_000L;
		publish();
		assertTrue(accountV5().contains("995"));

		accountStoreBroken = true;
		plugin.shutDown();
		new ArrayList<>(executorQueue).forEach(Runnable::run);

		String v5 = stateV5();
		assertTrue(v5.contains("\"readiness\":{\"state\":\"inactive\",\"missing\":[]}"));
		assertTrue("an inactive snapshot references no account document",
			v5.endsWith("\"accountData\":null}"));
	}
	/**
	 * Over a long session, well past several diary and Combat Achievement refreshes and complete
	 * quest passes, the plugin never asks the client for the bank or the Seed Vault. It records them
	 * only from the game's own container events.
	 */
	@Test
	public void theBankAndSeedVaultAreNeverPolledOnlyRecordedWhenDelivered() throws IOException
	{
		start();
		for (int i = 0; i < 60; i++)
		{
			tick();
			if (i % 4 == 0)
			{
				elapsed += 3_000_000_000L;
				publish();
			}
		}
		assertTrue("quests were read", scriptRuns() > 0);
		assertEquals("nothing asks for the bank or the Seed Vault", 0,
			mockingDetails(client).getInvocations().stream()
				.filter(invocation -> invocation.getMethod().getName().equals("getItemContainer"))
				.map(invocation -> invocation.getArgument(0))
				.filter(id -> Integer.valueOf(InventoryID.BANK).equals(id)
					|| Integer.valueOf(InventoryID.SEED_VAULT).equals(id))
				.count());
		assertTrue(accountV5().contains("\"bank\":{\"availability\":\"not_observed\""));
	}

	/**
	 * RuneLite closed or killed while logged in leaves the last account document on disk. The next
	 * run's first write replaces it, before any login, so it does not survive into that run.
	 */
	@Test
	public void aDocumentAnEarlierRunLeftIsReplacedByTheNextRunsFirstWrite() throws IOException
	{
		Files.createDirectories(managedDirectory);
		Files.write(managedDirectory.resolve("account-v5.json"),
			("{\"schema\":5,\"instanceId\":\"earlier-run\",\"bank\":[995]}").getBytes(StandardCharsets.UTF_8));
		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		start();
		publish();

		String account = accountV5();
		assertFalse("the earlier run's bank is gone", account.contains("995"));
		assertTrue(account.contains("\"instanceId\":\"instance-1\""));
		assertTrue(account.contains("\"characterSessionId\":null,"));
	}
	/**
	 * The startup replacement stays pending until it lands. A first write that fails, followed by a
	 * logout before any retry succeeds, must not cancel it and leave the earlier run's file behind.
	 */
	@Test
	public void aFailedStartupReplacementIsStillWrittenAfterALogout() throws IOException
	{
		Files.createDirectories(managedDirectory);
		Files.write(managedDirectory.resolve("account-v5.json"),
			("{\"instanceId\":\"earlier-run\",\"bank\":[995]}").getBytes(StandardCharsets.UTF_8));
		accountStoreBroken = true;
		start();
		publish();
		gameState(GameState.LOGIN_SCREEN);
		publish();
		accountStoreBroken = false;
		for (int i = 0; i < 3; i++)
		{
			elapsed += 3_000_000_000L;
			publish();
		}

		String account = accountV5();
		assertFalse("the earlier run's file is replaced", account.contains("earlier-run"));
		assertTrue(account.contains("\"instanceId\":\"instance-1\""));
		assertTrue(account.contains("\"characterSessionId\":null,"));
	}

	/** Started while logged in, the run's first document belongs to the new session and is referenced. */
	@Test
	public void aRunStartedWhileLoggedInPairsItsFirstDocumentWithTheNewSession() throws IOException
	{
		start();
		publish();

		String account = accountV5();
		assertTrue(account.contains("\"instanceId\":\"instance-1\",\"characterSessionId\":\"session-1\",\"revision\":1,"));
		assertTrue(account.contains("\"bank\":{\"availability\":\"not_observed\""));
		String v5 = stateV5();
		assertTrue(v5.contains("\"characterSessionId\":\"session-1\""));
		assertTrue(v5.endsWith("\"accountData\":{\"file\":\"account-v5.json\",\"revision\":1}}"));
	}
}
