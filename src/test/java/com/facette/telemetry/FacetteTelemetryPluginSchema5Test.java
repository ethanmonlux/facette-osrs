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
import static org.junit.Assert.assertNotEquals;
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
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.events.RuneScapeProfileChanged;

/**
 * Drives the real plugin, configured as it ships, against a mocked client and a temporary managed
 * directory. The legacy-only lifecycle is covered by {@link FacetteTelemetryPluginLifecycleTest};
 * this covers what schema 5 adds: a logged-in character recognized without any gameplay input or
 * container, and its character session across logout, world hop and profile change. None of it
 * waits on the account document, which {@link FacetteTelemetryPluginAccountDocumentTest} covers:
 * the character is live before any account value is read, and the account document observes
 * nothing until the game delivers something to put in it.
 */
public class FacetteTelemetryPluginSchema5Test
{
	private static final String ACCOUNT_FILE = "account-v5.json";

	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	private Client client;
	private ClientThread clientThread;
	private final List<Runnable> clientThreadQueue = new ArrayList<>();
	private ScheduledExecutorService executor;
	private Runnable scheduledTask;
	private final List<Runnable> executorQueue = new ArrayList<>();
	private Path legacyDirectory;
	private Path managedDirectory;
	private PathSnapshotStore managedStore;
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

		legacyDirectory = temporaryFolder.newFolder("facette").toPath();
		managedDirectory = temporaryFolder.getRoot().toPath()
			.resolve("plugin-data").resolve("facette-companion");
		managedStore = new PathSnapshotStore(managedDirectory);

		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getWorld()).thenReturn(302);
		Player localPlayer = mock(Player.class);
		when(localPlayer.getCombatLevel()).thenReturn(3);
		when(client.getLocalPlayer()).thenReturn(localPlayer);
		// Every skill initialized, so the skill group is current.
		when(client.getRealSkillLevel(any(Skill.class))).thenReturn(1);
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(1);
		when(client.getSkillExperience(any(Skill.class))).thenReturn(0);

		ItemComposition ordinary = mock(ItemComposition.class);
		when(ordinary.getPlaceholderTemplateId()).thenReturn(-1);
		when(ordinary.getMembersName()).thenReturn("Sample item");
		when(client.getItemDefinition(anyInt())).thenReturn(ordinary);

		// The live reproduction's worst case: the client has provided neither container.
		when(client.getItemContainer(InventoryID.WORN)).thenReturn(null);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(null);

		AtomicInteger sessions = new AtomicInteger();
		plugin = new FacetteTelemetryPlugin(() -> now, () -> elapsed, () -> "instance-1",
			() -> legacyDirectory, () -> executor,
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

	private void gameState(GameState state)
	{
		when(client.getGameState()).thenReturn(state);
		GameStateChanged changed = new GameStateChanged();
		changed.setGameState(state);
		plugin.onGameStateChanged(changed);
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

	private String stateV3() throws IOException
	{
		return read(legacyDirectory.resolve("state-v3.json"));
	}

	/**
	 * The account document holds no observation: every group is not observed. The run's first write
	 * replaces whatever an earlier run left, so the file exists, but recognition never depends on it.
	 */
	private void assertAccountDocumentObservesNothing() throws IOException
	{
		String account = read(managedDirectory.resolve(ACCOUNT_FILE));
		for (String group : new String[]{"bank", "seedVault", "questStates", "diaries",
			"combatAchievements"})
		{
			assertTrue(group + " is not observed", account.contains(
				"\"" + group + "\":{\"availability\":\"not_observed\",\"observedAt\":null,"));
		}
	}

	/** The session's identifier: the last key of the session object, so it ends at its brace. */
	private static String sessionId(String json)
	{
		String key = "\"characterSessionId\":";
		int start = json.indexOf(key) + key.length();
		return json.substring(start, json.indexOf('}', start));
	}

	private ItemContainer containerOf(Item... items)
	{
		ItemContainer container = mock(ItemContainer.class);
		when(container.getItems()).thenReturn(items);
		return container;
	}

	private static Item[] fullBackpack()
	{
		Item[] items = new Item[TelemetryState.INVENTORY_CAPACITY];
		for (int i = 0; i < items.length; i++)
		{
			items[i] = new Item(3101 + i, 1);
		}
		return items;
	}

	private long clientCalls(String method)
	{
		return mockingDetails(client).getInvocations().stream()
			.filter(invocation -> invocation.getMethod().getName().equals(method))
			.count();
	}

	// --- recognition without input ---------------------------------------------------------

	@Test
	public void aBareCharacterIsRecognizedOnTheFirstPublicationWhenEnabledWhileLoggedIn()
		throws IOException
	{
		start();
		publish();

		String v5 = stateV5();
		assertTrue(v5.contains("\"loggedIn\":true"));
		assertTrue(v5.contains("\"readiness\":{\"state\":\"partial\",\"missing\":[\"equipment\",\"inventory\"]}"));
		assertTrue(v5.contains("\"equipment\":{\"availability\":\"not_observed\",\"observedAt\":null}"));
		String v3 = stateV3();
		assertTrue("schema 3 keeps its all-or-nothing meaning", v3.contains("\"loggedIn\":false"));
		assertTrue(v3.contains("\"gameState\":\"LOGGED_IN\""));
		assertTrue("the same publication, the same number",
			v5.contains("\"seq\":0") && v3.contains("\"seq\":0"));
		assertAccountDocumentObservesNothing();
	}

	/**
	 * The live reproduction: a full backpack, nothing worn, no experience gained, no equip and no
	 * other input. Recognized on the first publication, in every schema.
	 */
	@Test
	public void aFullBackpackWithNothingWornIsRecognizedOnTheFirstPublication() throws IOException
	{
		ItemContainer worn = containerOf(new Item[14]);
		ItemContainer inventory = containerOf(fullBackpack());
		when(client.getItemContainer(InventoryID.WORN)).thenReturn(worn);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inventory);
		start();
		publish();

		String v5 = stateV5();
		assertTrue(v5.contains("\"loggedIn\":true"));
		assertTrue(v5.contains("\"readiness\":{\"state\":\"ready\",\"missing\":[]}"));
		assertTrue(v5.contains("\"usedSlots\":28,\"freeSlots\":0"));
		assertTrue(v5.contains("\"equipment\":{\"availability\":\"current\""));
		assertTrue(stateV3().contains("\"loggedIn\":true"));
		assertAccountDocumentObservesNothing();
	}

	@Test
	public void theNextIdleTickPicksUpAContainerWithoutAnyGameplayEvent() throws IOException
	{
		start();
		publish();
		// Built before stubbing the client, because Mockito cannot stub inside a stubbing.
		ItemContainer worn = containerOf(new Item[14]);
		ItemContainer inventory = containerOf(new Item[28]);
		when(client.getItemContainer(InventoryID.WORN)).thenReturn(worn);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inventory);
		tick();
		publish();
		assertTrue(stateV5().contains("\"readiness\":{\"state\":\"ready\",\"missing\":[]}"));
		assertTrue(stateV3().contains("\"loggedIn\":true"));
	}

	// --- session boundaries -----------------------------------------------------------------

	@Test
	public void logoutEndsTheCharacterSessionAndTheNextLoginStartsANewOne() throws IOException
	{
		start();
		publish();
		String first = sessionId(stateV5());

		gameState(GameState.LOGIN_SCREEN);
		publish();
		String v5 = stateV5();
		assertTrue(v5.contains("\"readiness\":{\"state\":\"logged_out\",\"missing\":[]}"));
		assertTrue(v5.contains("\"characterSessionId\":null"));
		assertAccountDocumentObservesNothing();

		gameState(GameState.LOGGING_IN);
		gameState(GameState.LOGGED_IN);
		tick();
		publish();
		assertTrue(stateV5().contains("\"loggedIn\":true"));
		assertNotEquals("a new login is a new character session", first, sessionId(stateV5()));
		assertAccountDocumentObservesNothing();
	}

	@Test
	public void aWorldHopKeepsTheCharacterSession() throws IOException
	{
		start();
		publish();
		String session = sessionId(stateV5());

		gameState(GameState.HOPPING);
		publish();
		assertTrue(stateV5().contains("\"gameState\":\"HOPPING\""));
		assertEquals(session, sessionId(stateV5()));

		gameState(GameState.LOADING);
		gameState(GameState.LOGGED_IN);
		when(client.getWorld()).thenReturn(303);
		tick();
		publish();
		String v5 = stateV5();
		assertTrue(v5.contains("\"loggedIn\":true"));
		assertTrue(v5.contains("\"world\":303"));
		assertEquals(session, sessionId(v5));
		assertAccountDocumentObservesNothing();
	}

	@Test
	public void aProfileChangeStartsANewCharacterSession() throws IOException
	{
		start();
		publish();
		String first = sessionId(stateV5());

		plugin.onRuneScapeProfileChanged(new RuneScapeProfileChanged("profile-a", "profile-b"));
		tick();
		publish();
		assertTrue(stateV5().contains("\"loggedIn\":true"));
		assertNotEquals(first, sessionId(stateV5()));
		assertAccountDocumentObservesNothing();
	}

	@Test
	public void theFirstProfileOfALoginIsNotAChange() throws IOException
	{
		start();
		publish();
		String first = sessionId(stateV5());
		plugin.onRuneScapeProfileChanged(new RuneScapeProfileChanged(null, "profile-a"));
		tick();
		publish();
		assertEquals(first, sessionId(stateV5()));
	}

	// --- recognition never waits on the account document -------------------------------------

	/**
	 * The character is live on the first publication, before the account document has read
	 * anything: no quest-status script, no diary or Combat Achievement variable, and no bank. Those
	 * reads start on the second game tick of a session, in a bounded batch, and only after the
	 * character is already published.
	 */
	@Test
	public void theCharacterIsLiveBeforeAnyAccountValueIsRead() throws IOException
	{
		start();
		publish();
		assertTrue(stateV5().contains("\"loggedIn\":true"));
		assertTrue(stateV3().contains("\"gameState\":\"LOGGED_IN\""));
		assertAccountDocumentObservesNothing();
		tick();
		publish();
		assertTrue(stateV5().contains("\"loggedIn\":true"));

		assertEquals("no quest-status script before the second tick", 0, clientCalls("runScript"));
		List<Integer> accountVarbits = new ArrayList<>();
		for (int[] region : FacetteTelemetryPlugin.DIARY_TIER_VARBITS)
		{
			for (int varbit : region)
			{
				accountVarbits.add(varbit);
			}
		}
		for (int varbit : FacetteTelemetryPlugin.COMBAT_ACHIEVEMENT_TIER_VARBITS)
		{
			accountVarbits.add(varbit);
		}
		assertTrue("no diary or Combat Achievement tier before the second tick",
			mockingDetails(client).getInvocations().stream()
				.filter(invocation -> invocation.getMethod().getName().equals("getVarbitValue"))
				.noneMatch(invocation -> accountVarbits.contains(invocation.<Integer>getArgument(0))));
		assertEquals("nothing asks for the bank or the Seed Vault", 0,
			mockingDetails(client).getInvocations().stream()
				.filter(invocation -> invocation.getMethod().getName().equals("getItemContainer"))
				.map(invocation -> invocation.getArgument(0))
				.filter(id -> Integer.valueOf(InventoryID.BANK).equals(id)
					|| Integer.valueOf(InventoryID.SEED_VAULT).equals(id))
				.count());
	}

	@Test
	public void disablingWritesAnInactiveSchemaFiveWithNoSession() throws IOException
	{
		start();
		publish();
		assertTrue(stateV5().contains("\"loggedIn\":true"));

		plugin.shutDown();
		new ArrayList<>(executorQueue).forEach(Runnable::run);

		assertTrue(stateV5().contains("\"readiness\":{\"state\":\"inactive\",\"missing\":[]}"));
		assertTrue(stateV5().contains("\"characterSessionId\":null"));
		assertAccountDocumentObservesNothing();
	}

	@Test
	public void anUnavailableManagedDirectoryLeavesTheLegacyFilesPublishing() throws IOException
	{
		managedDirectoryAvailable = false;
		start();
		publish();
		assertTrue(stateV3().contains("\"gameState\":\"LOGGED_IN\""));
		assertTrue(read(legacyDirectory.resolve("state-v4.json")).contains("\"schema\":4"));
		assertFalse(Files.exists(managedDirectory.resolve("state-v5.json")));
	}
}
