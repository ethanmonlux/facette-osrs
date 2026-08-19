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

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
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
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

/**
 * Exports a read-only view of the local player's live state to one local JSON file. Nothing flows
 * back: no command channel, no menu action, no synthesized input, and no network request. This class
 * is the only part that touches RuneLite. Client state is sampled on the client thread, where those
 * reads are legal, and publication runs on a separate thread per run. Schema 2 accepts only an NPC
 * target, so an interacted-with player is discarded here at the point of reading.
 */
@Slf4j
@PluginDescriptor(
	name = "Facette Companion",
	description = "Exports your live game state to one documented local JSON file. "
		+ "Facette is an optional reader. Read-only; no network requests.",
	tags = {"facette", "companion", "second screen", "local file", "telemetry"}
)
public class FacetteTelemetryPlugin extends Plugin
{
	private static final long PUBLISH_INTERVAL_MILLIS = 250L;

	private static final long HEARTBEAT_INTERVAL_MILLIS = 1_500L;

	private static final String DATA_SUBDIRECTORY = "facette";

	// Matched positionally to the slot names schema 2 declares. RuneLite's enumeration also carries
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

	@Inject
	Client client;

	// Client reads must happen on the client thread, and enabling from the configuration panel calls
	// startUp on the AWT thread, so startup work is handed here instead.
	@Inject
	ClientThread clientThread;

	private final LongSupplier wallClockMillis;

	private final LongSupplier elapsedNanos;

	private final Supplier<String> instanceIds;

	private final Supplier<Path> dataDirectories;

	private final Supplier<ScheduledExecutorService> executors;

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
			FacetteTelemetryPlugin::runeLiteDataDirectory,
			FacetteTelemetryPlugin::newPublisherExecutor);
	}

	FacetteTelemetryPlugin(LongSupplier wallClockMillis, LongSupplier elapsedNanos,
		Supplier<String> instanceIds, Supplier<Path> dataDirectories,
		Supplier<ScheduledExecutorService> executors)
	{
		this.wallClockMillis = Objects.requireNonNull(wallClockMillis, "wallClockMillis");
		this.elapsedNanos = Objects.requireNonNull(elapsedNanos, "elapsedNanos");
		this.instanceIds = Objects.requireNonNull(instanceIds, "instanceIds");
		this.dataDirectories = Objects.requireNonNull(dataDirectories, "dataDirectories");
		this.executors = Objects.requireNonNull(executors, "executors");
	}

	@Override
	protected void startUp()
	{
		// A fresh identity every start, derived from nothing: not the account, profile, machine, or
		// any game state. It only lets a reader notice a restart.
		PublisherRunContext run = PublisherRunContext.begin(
			newestGeneration,
			new TelemetryState(instanceIds.get(), wallClockMillis, elapsedNanos),
			new TelemetrySnapshotWriter(dataDirectories.get()));
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
		if (run.submitFinalWrite(() -> publish(run, false)))
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
		}
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
		if (!loggedIn)
		{
			return;
		}

		// The first live sample of a session establishes its baselines. Reading on a tick rather
		// than on the logged-in transition means a transient zero cannot become one.
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
			if (skill == null || "OVERALL".equals(skill.name()))
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

	private void publish(PublisherRunContext run, boolean pluginActive)
	{
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
		}
		catch (IOException e)
		{
			// Logged without the payload. The sequence did not advance, so this is retried.
			log.warn("Unable to publish telemetry snapshot to {}", run.getWriter().getTarget(), e);
		}
	}

	private static Path runeLiteDataDirectory()
	{
		return new File(RuneLite.RUNELITE_DIR, DATA_SUBDIRECTORY).toPath();
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
