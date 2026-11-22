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
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.ParamID;
import net.runelite.api.Player;
import net.runelite.api.Prayer;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.StructComposition;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

/**
 * Exports a read-only view of the local player's live state to documented local JSON files. Nothing flows
 * back: no command channel, no menu action, no synthesized input, and no network request. This class
 * is the only part that touches RuneLite. Client state is sampled on the client thread, where those
 * reads are legal, and publication runs on a separate thread per run. Schema 3 accepts only an NPC
 * target, so an interacted-with player is discarded here at the point of reading.
 *
 * Every per-tick read here is a plain accessor or a variable lookup. Nothing walks the widget tree,
 * opens an interface, or reads the chat, and the sampling cost of a tick does not grow with how much
 * the account has done. The one exception to "plain accessor" is per-quest state, which RuneLite
 * reads by running the game's own read-only quest-status script; those reads are bounded to a fixed
 * batch per tick, round-robin, and only for schema 5.
 *
 * Schema 5 also records the bank and the Seed Vault when the game delivers them, which happens only
 * when the player opens them. The plugin never opens either.
 */
@Slf4j
@PluginDescriptor(
	name = "Facette Companion",
	// The Plugin Hub name. RuneLite derives the managed directory, plugin-data/facette-companion, from
	// it, and every file the plugin writes is in that directory.
	internalName = "facette-companion",
	// Earlier versions wrote state-v3.json and state-v4.json to .runelite/facette. RuneLite moves
	// that directory into the managed one the first time it is opened, if the managed one does not
	// exist yet.
	legacyDataDirectory = "facette",
	description = "Exports your live game state, bank and Seed Vault when you open them, quest "
		+ "states, and diary and Combat Achievement tier completion to documented local JSON files. "
		+ "Facette is an optional reader. Read-only; no network requests.",
	tags = {"facette", "companion", "second screen", "local file", "telemetry"}
)
public class FacetteTelemetryPlugin extends Plugin
{
	private static final long PUBLISH_INTERVAL_MILLIS = 250L;

	private static final long HEARTBEAT_INTERVAL_MILLIS = 1_500L;

	// Matched positionally to the slot names schema 3 declares. RuneLite's enumeration also carries
	// the player model's arms, hair, and jaw, which never hold an item.
	private static final EquipmentInventorySlot[] EXPORTED_EQUIPMENT_SLOTS = {
		EquipmentInventorySlot.HEAD,
		EquipmentInventorySlot.CAPE,
		EquipmentInventorySlot.AMULET,
		EquipmentInventorySlot.WEAPON,
		EquipmentInventorySlot.BODY,
		EquipmentInventorySlot.SHIELD,
		EquipmentInventorySlot.LEGS,
		EquipmentInventorySlot.GLOVES,
		EquipmentInventorySlot.BOOTS,
		EquipmentInventorySlot.RING,
		EquipmentInventorySlot.AMMO,
	};

	// The combat mode at which the game consults a second variable to choose between a staff's
	// casting and defensive-casting entries.
	private static final int STAFF_CASTING_STYLE_INDEX = 4;

	// The style name the game's own data uses to mean "this weapon has no style in this position".
	private static final String NO_ATTACK_STYLE = "other";

	// The client's aggregate-experience sentinel, which is not a trainable skill.
	private static final String OVERALL_SKILL_NAME = "OVERALL";

	// Every skill starts at level one, so a base level below this is the client's own uninitialized
	// reading rather than a level anyone has.
	private static final int MIN_REAL_SKILL_LEVEL = 1;

	// A schema-5 account document is written at most this often, and only after something changed.
	private static final long ACCOUNT_WRITE_INTERVAL_MILLIS = 2_000L;

	// Account progress is read from this sampled tick of a character session on, so the login
	// variable sync has arrived first.
	private static final long PROGRESS_FIRST_TICK = 2L;

	// Diary and Combat Achievement tiers are re-read every this many sampled ticks.
	private static final long PROGRESS_REFRESH_TICKS = 10L;

	// Quests read per tick, round-robin. A full pass of today's catalogue takes about ten ticks.
	private static final int QUESTS_PER_TICK = 24;

	// RuneLite's quest catalogue as this build was compiled against it.
	private static final Quest[] QUEST_CATALOGUE = Quest.values();

	// The game's tier-completion variable for each diary region and tier, in the order
	// TelemetryAccountState.DIARY_REGIONS and DIARY_TIERS name them. RuneLite's Varbits.DIARY_*.
	static final int[][] DIARY_TIER_VARBITS = {
		{VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE, VarbitID.ARDOUGNE_DIARY_MEDIUM_COMPLETE,
			VarbitID.ARDOUGNE_DIARY_HARD_COMPLETE, VarbitID.ARDOUGNE_DIARY_ELITE_COMPLETE},
		{VarbitID.DESERT_DIARY_EASY_COMPLETE, VarbitID.DESERT_DIARY_MEDIUM_COMPLETE,
			VarbitID.DESERT_DIARY_HARD_COMPLETE, VarbitID.DESERT_DIARY_ELITE_COMPLETE},
		{VarbitID.FALADOR_DIARY_EASY_COMPLETE, VarbitID.FALADOR_DIARY_MEDIUM_COMPLETE,
			VarbitID.FALADOR_DIARY_HARD_COMPLETE, VarbitID.FALADOR_DIARY_ELITE_COMPLETE},
		{VarbitID.FREMENNIK_DIARY_EASY_COMPLETE, VarbitID.FREMENNIK_DIARY_MEDIUM_COMPLETE,
			VarbitID.FREMENNIK_DIARY_HARD_COMPLETE, VarbitID.FREMENNIK_DIARY_ELITE_COMPLETE},
		{VarbitID.KANDARIN_DIARY_EASY_COMPLETE, VarbitID.KANDARIN_DIARY_MEDIUM_COMPLETE,
			VarbitID.KANDARIN_DIARY_HARD_COMPLETE, VarbitID.KANDARIN_DIARY_ELITE_COMPLETE},
		{VarbitID.ATJUN_EASY_DONE, VarbitID.ATJUN_MED_DONE, VarbitID.ATJUN_HARD_DONE,
			VarbitID.KARAMJA_DIARY_ELITE_COMPLETE},
		{VarbitID.KOUREND_DIARY_EASY_COMPLETE, VarbitID.KOUREND_DIARY_MEDIUM_COMPLETE,
			VarbitID.KOUREND_DIARY_HARD_COMPLETE, VarbitID.KOUREND_DIARY_ELITE_COMPLETE},
		{VarbitID.LUMBRIDGE_DIARY_EASY_COMPLETE, VarbitID.LUMBRIDGE_DIARY_MEDIUM_COMPLETE,
			VarbitID.LUMBRIDGE_DIARY_HARD_COMPLETE, VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE},
		{VarbitID.MORYTANIA_DIARY_EASY_COMPLETE, VarbitID.MORYTANIA_DIARY_MEDIUM_COMPLETE,
			VarbitID.MORYTANIA_DIARY_HARD_COMPLETE, VarbitID.MORYTANIA_DIARY_ELITE_COMPLETE},
		{VarbitID.VARROCK_DIARY_EASY_COMPLETE, VarbitID.VARROCK_DIARY_MEDIUM_COMPLETE,
			VarbitID.VARROCK_DIARY_HARD_COMPLETE, VarbitID.VARROCK_DIARY_ELITE_COMPLETE},
		{VarbitID.WESTERN_DIARY_EASY_COMPLETE, VarbitID.WESTERN_DIARY_MEDIUM_COMPLETE,
			VarbitID.WESTERN_DIARY_HARD_COMPLETE, VarbitID.WESTERN_DIARY_ELITE_COMPLETE},
		{VarbitID.WILDERNESS_DIARY_EASY_COMPLETE, VarbitID.WILDERNESS_DIARY_MEDIUM_COMPLETE,
			VarbitID.WILDERNESS_DIARY_HARD_COMPLETE, VarbitID.WILDERNESS_DIARY_ELITE_COMPLETE},
	};

	// Karamja's easy, medium and hard variables read 1 while a tier is only started and 2 once it is
	// complete. Every other diary variable reads 1 for complete.
	private static final int KARAMJA_REGION_INDEX = 5;
	private static final int KARAMJA_STAGED_TIERS = 3;
	private static final int KARAMJA_TIER_DONE = 2;

	// RuneLite documents 2 as "completed" for these, and documents no other value.
	static final int[] COMBAT_ACHIEVEMENT_TIER_VARBITS = {
		VarbitID.CA_TIER_STATUS_EASY, VarbitID.CA_TIER_STATUS_MEDIUM, VarbitID.CA_TIER_STATUS_HARD,
		VarbitID.CA_TIER_STATUS_ELITE, VarbitID.CA_TIER_STATUS_MASTER,
		VarbitID.CA_TIER_STATUS_GRANDMASTER,
	};
	private static final int COMBAT_ACHIEVEMENT_TIER_COMPLETED = 2;

	// Whether this build reads and writes the schema-5 account document (account-v5.json): the bank
	// and Seed Vault when the game delivers them, per-quest state, and diary and Combat Achievement
	// tier completion. On. One switch, so the whole account document can be turned off in one place.
	private static final boolean ACCOUNT_DOCUMENT = true;

	@Inject
	Client client;

	// Client reads must happen on the client thread, and enabling from the configuration panel calls
	// startUp on the AWT thread, so startup work is handed here instead.
	@Inject
	ClientThread clientThread;

	private final LongSupplier wallClockMillis;

	private final LongSupplier elapsedNanos;

	private final Supplier<String> instanceIds;

	private final Supplier<ScheduledExecutorService> executors;

	// The directory every file is written to. Null means the plugin's own managed directory,
	// resolved per run through RuneLite's Filepath.
	private final Supplier<SnapshotStore> managedStores;

	// Whether state-v5.json is written. Always outside tests.
	private final boolean schema5;

	// Whether the account document is read and written. ACCOUNT_DOCUMENT outside tests.
	private final boolean accountDocument;

	private final Supplier<String> characterSessionIds;

	// Serializes the commit step across runs: the authority check and the target replacement only.
	// Staging stays outside it, so a stalled write delays nobody.
	private final ReentrantLock publishLock = new ReentrantLock();

	// A run may replace the target file only while it is still the newest generation started.
	private final AtomicLong newestGeneration = new AtomicLong();

	private volatile PublisherRunContext currentRun;

	public FacetteTelemetryPlugin()
	{
		this(
			System::currentTimeMillis,
			System::nanoTime,
			() -> UUID.randomUUID().toString(),
			FacetteTelemetryPlugin::newPublisherExecutor,
			null,
			() -> UUID.randomUUID().toString());
	}

	/** Writes schemas 3 and 4 only, into the given directory: the lifecycle tests' configuration. */
	FacetteTelemetryPlugin(LongSupplier wallClockMillis, LongSupplier elapsedNanos,
		Supplier<String> instanceIds, Supplier<SnapshotStore> stores,
		Supplier<ScheduledExecutorService> executors)
	{
		this(wallClockMillis, elapsedNanos, instanceIds, executors,
			Objects.requireNonNull(stores, "stores"), () -> UUID.randomUUID().toString(), false, false);
	}

	/** Every file, with the account document as this build ships it: switched on. */
	FacetteTelemetryPlugin(LongSupplier wallClockMillis, LongSupplier elapsedNanos,
		Supplier<String> instanceIds, Supplier<ScheduledExecutorService> executors,
		Supplier<SnapshotStore> managedStores, Supplier<String> characterSessionIds)
	{
		this(wallClockMillis, elapsedNanos, instanceIds, executors, managedStores,
			characterSessionIds, true, ACCOUNT_DOCUMENT);
	}

	private FacetteTelemetryPlugin(LongSupplier wallClockMillis, LongSupplier elapsedNanos,
		Supplier<String> instanceIds, Supplier<ScheduledExecutorService> executors,
		Supplier<SnapshotStore> managedStores, Supplier<String> characterSessionIds, boolean schema5,
		boolean accountDocument)
	{
		this.wallClockMillis = Objects.requireNonNull(wallClockMillis, "wallClockMillis");
		this.elapsedNanos = Objects.requireNonNull(elapsedNanos, "elapsedNanos");
		this.instanceIds = Objects.requireNonNull(instanceIds, "instanceIds");
		this.executors = Objects.requireNonNull(executors, "executors");
		this.managedStores = managedStores;
		this.characterSessionIds = Objects.requireNonNull(characterSessionIds, "characterSessionIds");
		this.schema5 = schema5;
		this.accountDocument = schema5 && accountDocument;
	}

	@Override
	protected void startUp()
	{
		// A fresh identity every start, derived from nothing: not the account, profile, machine, or
		// any game state. It only lets a reader notice a restart.
		ManagedExport managedExport = new ManagedExport(
			managedStores != null ? managedStores : this::openManagedDirectory, wallClockMillis,
			schema5, accountDocument);
		SnapshotStore directory = managedExport.getStore();
		PublisherRunContext run = PublisherRunContext.begin(
			newestGeneration,
			new TelemetryState(instanceIds.get(), wallClockMillis, elapsedNanos,
				schema5, QUEST_CATALOGUE.length, characterSessionIds),
			new TelemetrySnapshotWriter(directory, TelemetrySnapshot.SCHEMA, wallClockMillis),
			new TelemetrySnapshotWriter(directory, TelemetrySnapshot.SCHEMA_4, wallClockMillis),
			managedExport);
		if (accountDocument)
		{
			// The first account write of this run replaces whatever an earlier run left on disk.
			run.getState().getAccount().replacePreviousDocument();
		}
		currentRun = run;

		clientThread.invoke(() -> initializeOnClientThread(run));
	}

	/**
	 * Completes startup on the client thread: sample, seed, then publish. This runs later than startUp,
	 * so a run retired in the meantime must not write over what a newer run already put on disk.
	 */
	private void initializeOnClientThread(PublisherRunContext run)
	{
		if (!run.isCurrent())
		{
			log.debug("Skipping startup for a run retired before its client-thread callback ran");
			return;
		}

		try
		{
			// Seeding happens inside the sample, so a callback landing during a loading screen
			// leaves it to the first live sample rather than skipping it.
			sampleClientState(run.getState());
			run.markInitialized();
			startPublisher(run);
		}
		catch (RuntimeException | Error e)
		{
			// Leave nothing half-started. The file stops advancing and reads as stale.
			run.retire();
			run.abandonPublisher();
			log.error("Facette Telemetry failed to start; no telemetry will be published", e);
			throw e;
		}
	}

	private void startPublisher(PublisherRunContext run)
	{
		ScheduledExecutorService executor = executors.get();
		// Adopted before anything is scheduled on it, because the first tick has a zero initial
		// delay and can run before scheduling returns.
		if (!run.attachPublisherIfCurrent(executor))
		{
			// Shutdown already ran and found no publisher, so nothing else would dispose of this.
			// Nothing has been scheduled on it yet, so a graceful shutdown ends it outright.
			executor.shutdown();
			log.debug("Run was retired during startup; publisher discarded");
			return;
		}
		try
		{
			// Bound to this run for its whole life. It reads no field, so a later start cannot
			// redirect it at a newer run's state or writer.
			ScheduledFuture<?> publishTask = executor.scheduleWithFixedDelay(
				() -> publishTick(run), 0L, PUBLISH_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
			run.attachPublishTask(publishTask);
		}
		catch (RejectedExecutionException e)
		{
			log.debug("Publisher was shut down while starting; no periodic task scheduled");
			return;
		}

		log.debug("Facette Telemetry started; publishing to {}", run.getWriter().getTarget());
	}

	@Override
	protected void shutDown()
	{
		PublisherRunContext run = currentRun;
		if (run == null)
		{
			return;
		}

		// Retiring first makes a pending startup callback, and any tick already waiting on the
		// publication lock, inert.
		run.retire();

		if (!run.hasPublisher())
		{
			// Never published, so there is no state worth reporting and nothing is written.
			run.abandonPublisher();
			log.debug("Facette Telemetry stopped before it published; no final snapshot written");
			return;
		}

		// The final write reports the plugin as inactive and carries no gameplay data. It is queued on
		// the run's own publisher thread and completes there, unwaited for: this thread may be the
		// client thread, and disabling a plugin must not wait on a filesystem operation.
		if (run.submitFinalWrite(() -> publishFinal(run)))
		{
			// It commits only if it still holds authority when it gets there, so a re-enable that
			// beats it to the file leaves it nothing to do.
			log.debug("Facette Telemetry stopped; a final snapshot is queued on its publisher");
			return;
		}

		log.debug("Facette Telemetry stopped; its publisher had already stopped, so no final snapshot");
	}

	/**
	 * The run currently publishing, or null. Called from the client thread only. Callers then branch on
	 * whether the run has initialized: an event dropped before then is re-read by the next sample, but
	 * experience is an event no later sample can reconstruct.
	 */
	private PublisherRunContext liveRun()
	{
		PublisherRunContext run = currentRun;
		return run != null && run.isCurrent() ? run : null;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		GameState gameState = gameStateChanged.getGameState();
		PublisherRunContext run = liveRun();
		if (run == null)
		{
			return;
		}
		if (!run.isInitialized())
		{
			// A session ending still matters while starting: totals retained during startup belong
			// to the session that ended, and seeding against them after a new login would measure
			// one character's total against another's.
			if (endsSession(gameState))
			{
				run.getState().discardPreInitialXp();
			}
			return;
		}
		run.getState().updateSession(
			gameState.name(), gameState == GameState.LOGGED_IN, endsSession(gameState));
	}

	@Subscribe
	public void onGameTick(GameTick gameTick)
	{
		PublisherRunContext run = liveRun();
		if (run != null && run.isInitialized())
		{
			sampleClientState(run.getState());
			if (accountDocument)
			{
				readAccountProgress(run.getState());
			}
		}
	}

	/**
	 * A different RuneScape profile is a different character, even without a logout: a move between
	 * a standard and a seasonal world, for example. The first profile of a login is not a change.
	 */
	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		PublisherRunContext run = liveRun();
		if (run == null || !run.isInitialized() || event.getPreviousProfile() == null)
		{
			return;
		}
		run.getState().endCharacterSession();
	}

	/**
	 * Records the bank or the Seed Vault when the game delivers it, which it does when the player
	 * opens one and while its contents change. Nothing here asks for either.
	 */
	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (!accountDocument)
		{
			return;
		}
		int containerId = event.getContainerId();
		if (containerId != InventoryID.BANK && containerId != InventoryID.SEED_VAULT)
		{
			return;
		}
		PublisherRunContext run = liveRun();
		if (run == null || !run.isInitialized() || !run.getState().isSchema5Enabled())
		{
			return;
		}
		TelemetryState state = run.getState();
		String session = state.getCharacterSessionId();
		if (session == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		boolean bank = containerId == InventoryID.BANK;
		List<TelemetryAccountState.StoredItem> items = readStoredItems(event.getItemContainer(),
			bank ? TelemetryAccountState.MAX_BANK_ITEMS : TelemetryAccountState.MAX_SEED_VAULT_ITEMS);
		long at = wallClockMillis.getAsLong();
		if (bank)
		{
			state.getAccount().observeBank(session, items, at);
		}
		else
		{
			state.getAccount().observeSeedVault(session, items, at);
		}
	}

	/**
	 * A delivered container's items in container order, empty positions dropped, or null when it is
	 * missing or holds more than the bound. Every position is classified from the item itself: the
	 * bank filler by its identifier, a placeholder by its own composition.
	 */
	private List<TelemetryAccountState.StoredItem> readStoredItems(ItemContainer container, int max)
	{
		if (container == null)
		{
			return null;
		}
		Item[] items = container.getItems();
		List<TelemetryAccountState.StoredItem> stored = new ArrayList<>();
		for (Item item : items)
		{
			if (item == null || item.getId() < 0)
			{
				continue;
			}
			if (stored.size() >= max)
			{
				return null;
			}
			int id = item.getId();
			String kind;
			if (id == ItemID.BANK_FILLER)
			{
				kind = TelemetryAccountState.KIND_FILLER;
			}
			else if (isPlaceholder(id))
			{
				kind = TelemetryAccountState.KIND_PLACEHOLDER;
			}
			else
			{
				kind = TelemetryAccountState.KIND_ITEM;
			}
			stored.add(new TelemetryAccountState.StoredItem(id, Math.max(0, item.getQuantity()), kind));
		}
		return stored;
	}

	private boolean isPlaceholder(int itemId)
	{
		ItemComposition composition = client.getItemDefinition(itemId);
		return composition != null && composition.getPlaceholderTemplateId() != -1;
	}

	/**
	 * Reads quests, diaries and Combat Achievement tiers for schema 5, a bounded amount per tick:
	 * from the second sampled tick of a character session, and only while the skill totals are
	 * current, which is the sign that the login variable sync has arrived.
	 */
	private void readAccountProgress(TelemetryState state)
	{
		if (!state.isSchema5Enabled())
		{
			return;
		}
		String session = state.getCharacterSessionId();
		if (session == null)
		{
			return;
		}
		long tick = state.countSessionTick();
		if (tick < PROGRESS_FIRST_TICK || !state.isSkillsCurrent())
		{
			return;
		}
		TelemetryAccountState account = state.getAccount();
		long at = wallClockMillis.getAsLong();
		readQuestBatch(account, session, at);
		if ((tick - PROGRESS_FIRST_TICK) % PROGRESS_REFRESH_TICKS == 0)
		{
			account.observeDiaries(session, readDiaryTiers(), at);
			account.observeCombatAchievements(session, readCombatAchievementTiers(), at);
		}
	}

	private void readQuestBatch(TelemetryAccountState account, String session, long at)
	{
		int size = QUEST_CATALOGUE.length;
		int start = account.claimQuestBatch(QUESTS_PER_TICK);
		if (start < 0)
		{
			return;
		}
		for (int i = 0; i < Math.min(QUESTS_PER_TICK, size); i++)
		{
			int index = (start + i) % size;
			Quest quest = QUEST_CATALOGUE[index];
			account.observeQuest(session, index,
				new TelemetryAccountState.QuestEntry(quest.getId(), quest.getName(), readQuestState(quest)),
				at);
		}
	}

	// A read that fails is "unavailable", never "not started".
	private String readQuestState(Quest quest)
	{
		QuestState state;
		try
		{
			state = quest.getState(client);
		}
		catch (RuntimeException e)
		{
			return TelemetryAccountState.QUEST_UNAVAILABLE;
		}
		if (state == null)
		{
			return TelemetryAccountState.QUEST_UNAVAILABLE;
		}
		switch (state)
		{
			case FINISHED:
				return TelemetryAccountState.QUEST_FINISHED;
			case IN_PROGRESS:
				return TelemetryAccountState.QUEST_IN_PROGRESS;
			case NOT_STARTED:
				return TelemetryAccountState.QUEST_NOT_STARTED;
			default:
				return TelemetryAccountState.QUEST_UNAVAILABLE;
		}
	}

	private boolean[][] readDiaryTiers()
	{
		boolean[][] complete = new boolean[DIARY_TIER_VARBITS.length][];
		for (int region = 0; region < DIARY_TIER_VARBITS.length; region++)
		{
			int[] varbits = DIARY_TIER_VARBITS[region];
			complete[region] = new boolean[varbits.length];
			for (int tier = 0; tier < varbits.length; tier++)
			{
				int value = client.getVarbitValue(varbits[tier]);
				complete[region][tier] =
					region == KARAMJA_REGION_INDEX && tier < KARAMJA_STAGED_TIERS
						? value == KARAMJA_TIER_DONE
						: value == 1;
			}
		}
		return complete;
	}

	private boolean[] readCombatAchievementTiers()
	{
		boolean[] completed = new boolean[COMBAT_ACHIEVEMENT_TIER_VARBITS.length];
		for (int tier = 0; tier < COMBAT_ACHIEVEMENT_TIER_VARBITS.length; tier++)
		{
			completed[tier] = client.getVarbitValue(COMBAT_ACHIEVEMENT_TIER_VARBITS[tier])
				== COMBAT_ACHIEVEMENT_TIER_COMPLETED;
		}
		return completed;
	}

	/**
	 * Records an experience total. A gain is the difference between two totals, so a total arriving
	 * before the run initialized is retained rather than discarded.
	 */
	@Subscribe
	public void onStatChanged(StatChanged statChanged)
	{
		Skill skill = statChanged.getSkill();
		if (skill == null)
		{
			return;
		}
		PublisherRunContext run = liveRun();
		if (run == null)
		{
			return;
		}
		if (!run.isInitialized())
		{
			run.getState().recordPreInitialXp(skill.name(), statChanged.getXp());
			return;
		}
		// Only the skill and the increase are kept; the total is never exported.
		run.getState().observeXp(skill.name(), skill.ordinal(), statChanged.getXp());
	}

	/** Reads supported client state into the given state. Called from the client thread only. */
	private void sampleClientState(TelemetryState state)
	{
		GameState gameState = client.getGameState();
		boolean loggedIn = gameState == GameState.LOGGED_IN;
		state.updateSession(gameState.name(), loggedIn);
		state.beginSample();
		boolean completed = false;
		try
		{
			if (loggedIn)
			{
				sampleLoggedInState(state);
				completed = true;
			}
		}
		finally
		{
			state.endSample(completed);
		}
	}

	/**
	 * One logged-in sample. Every group is read whether or not another is missing: a container the
	 * client has not provided leaves that group unread and nothing else, so a character wearing
	 * nothing is recognized on its first sample.
	 */
	private void sampleLoggedInState(TelemetryState state)
	{
		// The first live sample of a session establishes its baselines. Reading on a tick rather
		// than on the logged-in transition narrows the window in which the client's totals are
		// still zero, and the state refuses a zero baseline for the case where it has not closed.
		if (state.needsXpBaselineSeeding())
		{
			seedXpBaselines(state);
			state.markXpBaselinesSeeded();
		}

		state.updateWorld(client.getWorld());

		// Read for combat level and interaction only. The name, account hash, and composition are
		// never touched, and no other player is ever read.
		Player localPlayer = client.getLocalPlayer();
		if (localPlayer != null)
		{
			state.updateCombatLevel(localPlayer.getCombatLevel());
		}

		state.updateVitals(
			client.getBoostedSkillLevel(Skill.HITPOINTS),
			client.getRealSkillLevel(Skill.HITPOINTS),
			client.getBoostedSkillLevel(Skill.PRAYER),
			client.getRealSkillLevel(Skill.PRAYER),
			client.getEnergy(),
			client.getVarpValue(VarPlayerID.SA_ENERGY),
			client.getWeight());

		state.updateCombat(readAttackStyle(), readActivePrayers());
		state.updateTarget(readNpcTarget(localPlayer));
		state.updateEquipment(readEquipmentSlots());
		state.updateInventory(readInventorySlots());

		state.markPlayerStateComplete();

		// Schema 3, read after the completeness mark on purpose. These groups answer for themselves
		// through the document's capabilities, so one of them failing to read leaves the rest of the
		// snapshot valid instead of nulling a player's whole document.
		//
		// Read first, committed second, and the split is the point. Publication runs on another
		// thread, so committing group by group would let a snapshot be built between two of them and
		// carry this tick's skills beside the previous tick's task -- a sample no tick ever read,
		// dated as though it had been. Every client read happens here, on the client thread and
		// outside the state's monitor, and the gathered result is committed as one transition.
		List<TelemetrySkillState> skillStates = readSkillStates();
		int accountTypeId = client.getVarbitValue(VarbitID.IRONMAN);
		int questPoints = client.getVarpValue(VarPlayerID.QP);
		int slayerRemaining = client.getVarpValue(VarPlayerID.SLAYER_COUNT);
		int slayerTaskCreatureId = client.getVarpValue(VarPlayerID.SLAYER_TARGET);
		// Which boss, on the boss task, and which area, when the master assigned one. Both are read
		// every tick and the state decides whether the task they would describe is running.
		int slayerTaskBossId = client.getVarbitValue(VarbitID.SLAYER_TARGET_BOSSID);
		int slayerTaskAreaId = client.getVarpValue(VarPlayerID.SLAYER_AREA);
		List<TelemetryGrandExchangeSlot> grandExchangeSlots = readGrandExchangeSlots();

		state.applySchema3Sample(skillStates, accountTypeId, questPoints, slayerRemaining,
			slayerTaskCreatureId, slayerTaskBossId, slayerTaskAreaId, grandExchangeSlots);
	}

	/**
	 * Every skill the client's own enumeration defines, in its order: the base level, the boosted or
	 * drained level, and the lifetime experience. Bounded by the enumeration, so a client that adds a
	 * skill grows this list by one entry and nothing else changes.
	 */
	private List<TelemetrySkillState> readSkillStates()
	{
		List<TelemetrySkillState> states = new ArrayList<>(Skill.values().length);
		for (Skill skill : Skill.values())
		{
			// The same guard the experience baselines use. The aggregate sentinel is not a
			// trainable skill, and its experience is the account's total rather than a skill's,
			// which is not a fact this schema exports.
			if (skill == null || OVERALL_SKILL_NAME.equals(skill.name()))
			{
				continue;
			}
			int level = client.getRealSkillLevel(skill);
			int boostedLevel = client.getBoostedSkillLevel(skill);
			int xp = client.getSkillExperience(skill);
			if (level < MIN_REAL_SKILL_LEVEL || boostedLevel < 0 || xp < 0)
			{
				// One unreadable skill makes the whole group unavailable rather than exporting a
				// list that looks complete while a skill silently reads as level zero.
				//
				// Zero is the reason the base level is checked against one rather than against
				// zero. Every skill starts at level one, so a base level of zero is not a low
				// reading, it is the client's arrays before they were filled in — the same
				// initialization window the experience baselines already refuse to seed from. A
				// boosted level of zero is real (a drained skill), and so is zero experience (an
				// untrained one), so neither of those is treated the same way.
				return null;
			}
			states.add(new TelemetrySkillState(
				skill.name().toLowerCase(Locale.ROOT), level, boostedLevel, xp));
		}
		return states;
	}

	/**
	 * The player's own Grand Exchange slots, as the client currently holds them. One entry per slot
	 * the client exposes, in its own order, so the list is bounded by the client rather than by a
	 * number assumed here. Nothing is looked up about any item beyond what the offer itself says: no
	 * price guide, no market history, and no valuation.
	 */
	private List<TelemetryGrandExchangeSlot> readGrandExchangeSlots()
	{
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null)
		{
			return null;
		}
		List<TelemetryGrandExchangeSlot> slots = new ArrayList<>(offers.length);
		for (GrandExchangeOffer offer : offers)
		{
			slots.add(readGrandExchangeSlot(offer));
		}
		return slots;
	}

	private static TelemetryGrandExchangeSlot readGrandExchangeSlot(GrandExchangeOffer offer)
	{
		if (offer == null)
		{
			return TelemetryGrandExchangeSlot.UNAVAILABLE;
		}
		GrandExchangeOfferState state = offer.getState();
		if (state == null)
		{
			return TelemetryGrandExchangeSlot.UNAVAILABLE;
		}
		String name = state.name().toLowerCase(Locale.ROOT);
		if (state == GrandExchangeOfferState.EMPTY)
		{
			// An empty slot's other readings describe an offer that does not exist.
			return TelemetryGrandExchangeSlot.empty(name);
		}
		return TelemetryGrandExchangeSlot.offer(
			name,
			offer.getItemId(),
			offer.getPrice(),
			offer.getTotalQuantity(),
			offer.getQuantitySold(),
			offer.getSpent());
	}

	/**
	 * The label for the selected attack style, or null. The label is the game's own: this walks the
	 * client's lookup path from weapon category to style list to entry to name, so nothing here maps a
	 * number onto a word. Every step that can fail yields null rather than a guess.
	 */
	private String readAttackStyle()
	{
		int weaponCategory = client.getVarbitValue(VarbitID.COMBAT_WEAPON_CATEGORY);
		int styleIndex = client.getVarpValue(VarPlayerID.COM_MODE);
		if (weaponCategory < 0 || styleIndex < 0)
		{
			return null;
		}

		EnumComposition weaponStyles = client.getEnum(EnumID.WEAPON_STYLES);
		if (weaponStyles == null)
		{
			return null;
		}
		int styleListId = weaponStyles.getIntValue(weaponCategory);
		if (styleListId <= 0)
		{
			return null;
		}
		EnumComposition styleList = client.getEnum(styleListId);
		if (styleList == null)
		{
			return null;
		}
		int[] styleStructIds = styleList.getIntVals();
		if (styleStructIds == null)
		{
			return null;
		}

		if (styleIndex == STAFF_CASTING_STYLE_INDEX)
		{
			// Staves use indexes 0 to 4, and a separate casting mode selects the defensive entry
			// that follows.
			styleIndex += client.getVarbitValue(VarbitID.AUTOCAST_DEFMODE);
		}
		if (styleIndex < 0 || styleIndex >= styleStructIds.length)
		{
			return null;
		}

		StructComposition style = client.getStructComposition(styleStructIds[styleIndex]);
		if (style == null)
		{
			return null;
		}
		return normalizeAttackStyle(style.getStringValue(ParamID.ATTACK_STYLE_NAME));
	}

	private static String normalizeAttackStyle(String raw)
	{
		if (raw == null)
		{
			return null;
		}
		String normalized = raw.trim().toLowerCase(Locale.ROOT);
		if (normalized.isEmpty() || NO_ATTACK_STYLE.equals(normalized))
		{
			return null;
		}
		return normalized;
	}

	/**
	 * The active prayers, as lowercase RuneLite prayer names in enum order. The upgraded ranged and
	 * magic prayers share a variable with the ones they replace, so which of a pair is really active
	 * depends on whether the upgrade is unlocked, and unlocks are suspended in Last Man Standing.
	 */
	private List<String> readActivePrayers()
	{
		boolean inLastManStanding = client.getVarbitValue(VarbitID.BR_INGAME) != 0;
		boolean deadeyeReplacesEagleEye = !inLastManStanding
			&& client.getVarbitValue(VarbitID.PRAYER_DEADEYE_UNLOCKED) != 0;
		boolean vigourReplacesMight = !inLastManStanding
			&& client.getVarbitValue(VarbitID.PRAYER_MYSTIC_VIGOUR_UNLOCKED) != 0;

		List<String> active = new ArrayList<>();
		for (Prayer prayer : Prayer.values())
		{
			if (prayer == null || client.getVarbitValue(prayer.getVarbit()) == 0)
			{
				continue;
			}
			if (!isReportableVariant(prayer, deadeyeReplacesEagleEye, vigourReplacesMight))
			{
				continue;
			}
			active.add(prayer.name().toLowerCase(Locale.ROOT));
		}
		return active;
	}

	// Whether this member of an upgradable prayer pair is the one actually in use.
	private static boolean isReportableVariant(Prayer prayer, boolean deadeyeReplacesEagleEye,
		boolean vigourReplacesMight)
	{
		switch (prayer)
		{
			case EAGLE_EYE:
				return !deadeyeReplacesEagleEye;
			case DEADEYE:
				return deadeyeReplacesEagleEye;
			case MYSTIC_MIGHT:
				return !vigourReplacesMight;
			case MYSTIC_VIGOUR:
				return vigourReplacesMight;
			default:
				return true;
		}
	}

	/**
	 * The NPC the local player is interacting with, or null. This type check is the privacy boundary:
	 * an interacted-with player carries another person's display name, so anything that is not an NPC
	 * is discarded here.
	 */
	private static TelemetryTarget readNpcTarget(Player localPlayer)
	{
		if (localPlayer == null)
		{
			return null;
		}
		Actor interacting = localPlayer.getInteracting();
		if (!(interacting instanceof NPC))
		{
			return null;
		}
		NPC npc = (NPC) interacting;
		// Health is the ratio and scale the server transmits; it sends no real hitpoints figure.
		return TelemetryTarget.npc(
			npc.getId(),
			npc.getName(),
			npc.getCombatLevel(),
			npc.getHealthRatio(),
			npc.getHealthScale(),
			npc.isDead());
	}

	// Null means "not read", so the state keeps what it last saw rather than reporting everything
	// as unequipped.
	private List<TelemetryItemSlot> readEquipmentSlots()
	{
		Item[] worn = itemsOf(InventoryID.WORN);
		if (worn == null)
		{
			return null;
		}
		List<TelemetryItemSlot> slots = new ArrayList<>(EXPORTED_EQUIPMENT_SLOTS.length);
		for (EquipmentInventorySlot slot : EXPORTED_EQUIPMENT_SLOTS)
		{
			slots.add(readItemSlot(worn, slot.getSlotIdx()));
		}
		return slots;
	}

	private List<TelemetryItemSlot> readInventorySlots()
	{
		Item[] inventory = itemsOf(InventoryID.INV);
		if (inventory == null)
		{
			return null;
		}
		List<TelemetryItemSlot> slots = new ArrayList<>(TelemetryState.INVENTORY_CAPACITY);
		for (int slot = 0; slot < TelemetryState.INVENTORY_CAPACITY; slot++)
		{
			slots.add(readItemSlot(inventory, slot));
		}
		return slots;
	}

	private Item[] itemsOf(int containerId)
	{
		ItemContainer container = client.getItemContainer(containerId);
		return container == null ? null : container.getItems();
	}

	private TelemetryItemSlot readItemSlot(Item[] items, int slot)
	{
		if (slot < 0 || slot >= items.length)
		{
			return TelemetryItemSlot.EMPTY;
		}
		Item item = items[slot];
		if (item == null)
		{
			return TelemetryItemSlot.EMPTY;
		}
		// The identity, count, and name only. No price, examine text, tradeability, or aggregate is
		// looked up.
		return TelemetryItemSlot.of(item.getId(), item.getQuantity(), itemName(item.getId()));
	}

	private String itemName(int itemId)
	{
		ItemComposition composition = client.getItemDefinition(itemId);
		if (composition == null)
		{
			return null;
		}
		// The members' name, so an item reads the same on a free and a members world.
		return composition.getMembersName();
	}

	/**
	 * Seeds the experience baselines from the client's current totals, for the case where the plugin is
	 * enabled while the player is already logged in. Called from the first live sample of a session, so
	 * the totals belong to a real logged-in character.
	 */
	private void seedXpBaselines(TelemetryState state)
	{
		for (Skill skill : Skill.values())
		{
			if (skill == null || OVERALL_SKILL_NAME.equals(skill.name()))
			{
				continue;
			}
			state.seedXpBaseline(skill.name(), skill.ordinal(), client.getSkillExperience(skill));
		}
	}

	// Whether reaching this state ends the play session and discards the baselines. Every login
	// passes through LOGGING_IN, so a world hop keeps its baselines while a new login does not.
	private static boolean endsSession(GameState gameState)
	{
		return gameState == GameState.LOGIN_SCREEN
			|| gameState == GameState.LOGIN_SCREEN_AUTHENTICATOR
			|| gameState == GameState.LOGGING_IN;
	}

	// Bound to the run that scheduled it. Unchecked failures are contained here, because a periodic
	// task that throws is cancelled by its executor and would silently end publication.
	private void publishTick(PublisherRunContext run)
	{
		if (!run.isCurrent())
		{
			return;
		}
		// The account document first, so the snapshot that follows can reference it. It is optional,
		// so its own failure is contained here and never costs the snapshot its publication.
		try
		{
			publishAccountIfDue(run);
		}
		catch (RuntimeException e)
		{
			log.warn("Account document publication failed; the snapshot is still published", e);
		}
		try
		{
			if (run.getState().isPublicationDue(HEARTBEAT_INTERVAL_MILLIS))
			{
				publish(run, true);
			}
		}
		catch (RuntimeException e)
		{
			// Logged without any part of the snapshot payload.
			log.warn("Telemetry publication tick failed; publication continues", e);
		}
	}

	/**
	 * The schema-5 account document, when one is due. Committed before the state file that will
	 * reference it, so a reader never finds a reference to a revision that is not on disk yet.
	 */
	private void publishAccountIfDue(PublisherRunContext run)
	{
		ManagedExport export = run.getManagedExport();
		if (export == null || !export.isAvailable() || export.getAccountWriter() == null)
		{
			return;
		}
		TelemetryState state = run.getState();
		TelemetryAccountState.Document document =
			state.getAccount().prepareDocument(ACCOUNT_WRITE_INTERVAL_MILLIS);
		if (document == null)
		{
			return;
		}
		try
		{
			int bytes = export.getAccountWriter().write(
				document.getBytes(), publishLock, run::isCommitAuthorized);
			state.getAccount().markCommitted(document);
			state.recordAccountCommitted(document.getCharacterSessionId(), document.getRevision());
			log.debug("Published account document revision={} ({} bytes)",
				document.getRevision(), bytes);
		}
		catch (TelemetrySnapshotWriter.CommitNotAuthorizedException e)
		{
			log.debug("Abandoned an account document superseded by a newer plugin run");
		}
		catch (IOException e)
		{
			// Logged without the payload. The revision was not consumed, so this is retried.
			log.warn("Unable to publish the account document to {}",
				export.getAccountWriter().describeTarget(), e);
		}
	}

	/**
	 * The final write as the plugin stops: the account document, when this run writes one, is
	 * cleared first, so a stopped plugin leaves no character's bank behind looking current, then the
	 * inactive snapshot.
	 */
	private void publishFinal(PublisherRunContext run)
	{
		if (run.getManagedExport() != null && accountDocument)
		{
			run.getState().getAccount().close();
			try
			{
				publishAccountIfDue(run);
			}
			catch (RuntimeException e)
			{
				log.warn("Account document clearing failed; the inactive snapshot is still published", e);
			}
		}
		publish(run, false);
	}

	private void publish(PublisherRunContext run, boolean pluginActive)
	{
		ManagedExport export = run.getManagedExport();
		if (export != null && !export.isAvailable())
		{
			// Already logged once, when the directory could not be opened. Nothing is written.
			return;
		}
		TelemetryState publishingState = run.getState();
		TelemetrySnapshot snapshot = publishingState.nextSnapshot(pluginActive);
		try
		{
			// The authority check runs inside the writer, immediately before it replaces the
			// target, rather than here where a slow write could make the answer stale.
			int bytes = run.getWriter().write(snapshot, publishLock, run::isCommitAuthorized);
			publishingState.recordPublished();
			log.debug("Published telemetry snapshot seq={} ({} bytes)", snapshot.getSeq(), bytes);
		}
		catch (TelemetrySnapshotWriter.CommitNotAuthorizedException e)
		{
			// A newer run started while this one was writing; the target is untouched.
			log.debug("Abandoned a telemetry snapshot superseded by a newer plugin run");
			return;
		}
		catch (IOException e)
		{
			// Logged without the payload. The sequence did not advance, so this is retried.
			log.warn("Unable to publish telemetry snapshot to {}", run.getWriter().getTarget(), e);
			return;
		}

		// Schema 4 follows schema 3 and only once schema 3 has committed. The sequence is schema 3's,
		// so writing schema 4 first could put a number on disk that a failed schema-3 write then
		// reuses for different content. A schema-4 failure changes nothing about schema 3: its
		// number is spent, and the next publication rewrites schema 4 under a higher one.
		publishSchema4(run, snapshot);
		publishSchema5(run, snapshot);
	}

	/**
	 * Schema 5, last, under the same number. Like schema 4 it never affects the schema-3 file: a
	 * failure here leaves it as it is and is retried by the next publication.
	 */
	private void publishSchema5(PublisherRunContext run, TelemetrySnapshot snapshot)
	{
		TelemetrySnapshot view = snapshot.getSchema5View();
		ManagedExport export = run.getManagedExport();
		if (view == null || export == null || !export.isAvailable() || export.getStateWriter() == null)
		{
			return;
		}
		try
		{
			int bytes = export.getStateWriter().write(
				view.toJsonBytes(TelemetrySnapshot.SCHEMA_5), publishLock, run::isCommitAuthorized);
			log.debug("Published schema-5 telemetry snapshot seq={} ({} bytes)",
				view.getSeq(), bytes);
			export.logReadiness(view.getReadinessState(), view.getReadinessMissing());
		}
		catch (TelemetrySnapshotWriter.CommitNotAuthorizedException e)
		{
			log.debug("Abandoned a telemetry snapshot superseded by a newer plugin run");
		}
		catch (IOException e)
		{
			log.warn("Unable to publish telemetry snapshot to {}",
				export.getStateWriter().describeTarget(), e);
		}
	}

	private void publishSchema4(PublisherRunContext run, TelemetrySnapshot snapshot)
	{
		TelemetrySnapshotWriter schema4Writer = run.getSchema4Writer();
		if (schema4Writer == null)
		{
			return;
		}
		try
		{
			int bytes = schema4Writer.write(snapshot, publishLock, run::isCommitAuthorized);
			log.debug("Published schema-4 telemetry snapshot seq={} ({} bytes)",
				snapshot.getSeq(), bytes);
		}
		catch (TelemetrySnapshotWriter.CommitNotAuthorizedException e)
		{
			// A newer run started while this one was writing; the target is untouched.
			log.debug("Abandoned a telemetry snapshot superseded by a newer plugin run");
		}
		catch (IOException e)
		{
			// Logged without the payload. Schema 3 already holds this number, so the file is left
			// for the next publication, a change or the heartbeat, to rewrite under a higher one.
			log.warn("Unable to publish telemetry snapshot to {}", schema4Writer.getTarget(), e);
		}
	}

	/**
	 * The plugin's own managed directory, from RuneLite. Called on the publisher thread, on the run's
	 * first publication and again on a later one if it failed, which is also where RuneLite moves an
	 * earlier version's .runelite/facette into it. A failure is reported to {@link ManagedExport},
	 * which logs it once and leaves the run writing nothing until it succeeds.
	 */
	private SnapshotStore openManagedDirectory()
	{
		try
		{
			return new FilepathSnapshotStore(getPluginDirectory());
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	// One thread per run, named for a thread dump and daemon so it cannot hold the client open.
	private static ScheduledExecutorService newPublisherExecutor()
	{
		return Executors.newSingleThreadScheduledExecutor(runnable ->
		{
			Thread thread = new Thread(runnable, "facette-telemetry-publisher");
			thread.setDaemon(true);
			return thread;
		});
	}
}
