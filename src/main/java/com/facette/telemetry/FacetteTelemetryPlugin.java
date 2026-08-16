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
 * Exports a read-only view of the local player's live state to one local JSON file for the Facette
 * companion application to read independently. The flow is one way:
 *
 * RuneLite client state -&gt; TelemetryState -&gt; bounded TelemetrySnapshot -&gt; local file
 *
 * This class is the only part that touches RuneLite. It samples client state on the client thread,
 * where those reads are legal, and hands the values to {@link TelemetryState}. Publication runs on
 * a separate thread owned by each run, so a slow filesystem does not hold up sampling or the client
 * thread while the plugin is running. The one exception is {@link #shutDown()}, which waits a
 * bounded {@link #SHUTDOWN_TIMEOUT_SECONDS} for the final write before returning. Nothing flows
 * back: no command channel, no menu action, no synthesized input, and no network request.
 *
 * Schema 2 accepts only an NPC target, so an interacted-with player is discarded here at the point
 * of reading. Each plugin start owns its own {@link PublisherRunContext}, and only the newest run
 * may replace the file.
 */
@Slf4j
@PluginDescriptor(
	name = "Facette Companion",
	description = "Provides your own live game state to the separately installed Facette app "
		+ "through one local file. Read-only; no network requests.",
	tags = {"facette", "companion", "second screen", "local file", "telemetry"}
)
public class FacetteTelemetryPlugin extends Plugin
{
	/** How often the publisher wakes; also the floor on the interval between file replacements. */
	private static final long PUBLISH_INTERVAL_MILLIS = 250L;

	/**
	 * Age at which an unchanged snapshot is republished so a reader can tell a live plugin from a
	 * stale file. Kept below two seconds so that, including one publish interval, the gap between
	 * publications stays under the two-second heartbeat bound.
	 */
	private static final long HEARTBEAT_INTERVAL_MILLIS = 1_500L;

	private static final String DATA_SUBDIRECTORY = "facette";

	/** Bound on how long an orderly shutdown waits for an in-flight publication. */
	private static final long SHUTDOWN_TIMEOUT_SECONDS = 5L;

	/**
	 * The eleven visible equipment slots, in the order {@link TelemetrySnapshot#EQUIPMENT_SLOTS} names
	 * them, so the only slots this plugin can read are the ones the schema declares. RuneLite's
	 * enumeration also carries the player model's arms, hair, and jaw, which never hold an item. The
	 * correspondence is positional and a test pins that the two lists agree name for name.
	 */
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

	/**
	 * The combat-mode index at which the game's combat interface consults a second variable to pick
	 * between a staff's casting and defensive-casting entries. Only the index is written here.
	 */
	private static final int STAFF_CASTING_STYLE_INDEX = 4;

	/**
	 * The style name the game's data uses to mean "this weapon has no style in this position".
	 * Reported as no reading rather than as a style called "other".
	 */
	private static final String NO_ATTACK_STYLE = "other";

	/**
	 * Package-private so a same-package test can supply a stand-in without reflection. Still
	 * {@code @Inject}, so RuneLite's own field injection is unchanged.
	 */
	@Inject
	Client client;

	/**
	 * Every direct {@link Client} read has to happen on the client thread, and enabling the plugin from
	 * the configuration panel calls {@link #startUp()} on the AWT thread, so startup work is handed
	 * here rather than run on whichever thread happened to call.
	 */
	@Inject
	ClientThread clientThread;

	private final LongSupplier wallClockMillis;

	private final LongSupplier elapsedNanos;

	private final Supplier<String> instanceIds;

	private final Supplier<Path> dataDirectories;

	private final Supplier<ScheduledExecutorService> executors;

	/**
	 * Serializes the commit step of publications across runs, meaning the authority check and the
	 * target replacement and nothing else. It matters when a retired run's final write and a new run's
	 * publisher are alive at once. Staging is deliberately outside it, so a stalled write delays
	 * nobody.
	 */
	private final ReentrantLock publishLock = new ReentrantLock();

	/**
	 * Generation counter shared by every run of this plugin instance. A run may replace the target
	 * file only while it is still the newest generation started.
	 */
	private final AtomicLong newestGeneration = new AtomicLong();

	/**
	 * The run currently being published. Replaced, never mutated, on each start; the previous run is
	 * retired first and can never become current again.
	 */
	private volatile PublisherRunContext currentRun;

	/** The constructor RuneLite and Guice use, so collaborators still arrive by field injection. */
	public FacetteTelemetryPlugin()
	{
		this(
			System::currentTimeMillis,
			System::nanoTime,
			() -> UUID.randomUUID().toString(),
			FacetteTelemetryPlugin::runeLiteDataDirectory,
			FacetteTelemetryPlugin::newPublisherExecutor);
	}

	/**
	 * Test seam. Package-private, and not exposed as configuration, an environment variable, or a
	 * system property. Every argument the no-argument constructor passes is the real production
	 * implementation, so this changes no behavior, destination, or schema.
	 */
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
		// A fresh identity every start, derived from nothing: not the account, the profile, the
		// machine, or any game state. It only lets a reader notice a restart.
		PublisherRunContext run = PublisherRunContext.begin(
			newestGeneration,
			new TelemetryState(instanceIds.get(), wallClockMillis, elapsedNanos),
			new TelemetrySnapshotWriter(dataDirectories.get()));
		currentRun = run;

		// Nothing here touches the client. Enabling from the configuration panel runs this on the
		// AWT thread, where any Client read fails the client-thread assertion, so the reads are
		// deferred instead.
		clientThread.invoke(() -> initializeOnClientThread(run));
	}

	/**
	 * Completes startup on the client thread: sample, seed, then publish. It runs later than
	 * {@link #startUp()} when the plugin was enabled from the configuration panel, so a run retired in
	 * the meantime must do nothing rather than write over what a newer run already put on disk.
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
			// Seeding is folded into the sample rather than performed here, so a callback landing
			// during a world hop or loading screen, where there is no live session to read totals
			// from, leaves seeding to the first live sample instead of skipping it for the run.
			sampleClientState(run.getState());
			// Only now may anything publish: no snapshot can be built from a partly initialized run.
			run.markInitialized();
			startPublisher(run);
		}
		catch (RuntimeException | Error e)
		{
			// Leave nothing half-started. The run is retired so any straggler is inert, no publisher
			// is left behind, and no active snapshot is written. The file simply stops advancing and
			// reads as stale, which is honest about a failed start.
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
		// delay and can run before scheduleWithFixedDelay returns.
		if (!run.attachPublisherIfCurrent(executor))
		{
			// Disabled while this callback was sampling and seeding. Shutdown has already run and
			// found no publisher to stop, so nothing else will ever dispose of this executor.
			executor.shutdownNow();
			log.debug("Run was retired during startup; publisher discarded");
			return;
		}
		try
		{
			// The task is bound to this run for its whole life. It never reads a field, so a later
			// start cannot redirect it at a newer run's state or writer.
			ScheduledFuture<?> publishTask = executor.scheduleWithFixedDelay(
				() -> publishTick(run), 0L, PUBLISH_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
			run.attachPublishTask(publishTask);
		}
		catch (RejectedExecutionException e)
		{
			// shutDown() claimed the executor between adoption and scheduling; it already owns the
			// cleanup and the final write.
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

		// Retiring first means a pending startup callback, and any tick already waiting on the
		// publication lock, both become inert. Retirement is scoped to this context, so a rapid
		// re-enable creates a different run and cannot bring this one back.
		run.retire();

		if (!run.hasPublisher())
		{
			// Never got as far as publishing: a startup that failed, or one disabled before its
			// client-thread callback ran. Deliberately writes nothing, because a run that never
			// published has no state worth reporting.
			run.abandonPublisher();
			log.debug("Facette Telemetry stopped before it published; no final snapshot written");
			return;
		}

		// The final write goes to the run's own publisher thread, not this one. On the real client
		// this method runs on the client thread (or AWT), and the write can stall for as long as
		// the filesystem takes. So it is queued, and this thread waits only a bounded time.
		run.submitFinalWrite(() -> publish(run, false));

		if (run.awaitPublisherTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS))
		{
			log.debug("Facette Telemetry stopped");
			return;
		}

		// Returning here releases the caller on time. The write continues on a daemon thread and
		// will replace the target only if it still holds authority when it gets there.
		log.warn("Final telemetry snapshot did not complete within {}s; continuing off-thread. "
			+ "It will be abandoned if the plugin is re-enabled first.", SHUTDOWN_TIMEOUT_SECONDS);
	}

	/**
	 * The run currently publishing, or null when there is none or it has been retired. Called from the
	 * client thread only, which is also the thread that replaces the run, so a handler never observes a
	 * half-started one. Callers branch on {@link PublisherRunContext#isInitialized()}: dropping a game
	 * state, world, vitals, or inventory event before initialization loses nothing, since the first
	 * sample afterwards reads the live client, but experience is an event no later sample can
	 * reconstruct.
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
			// Still starting. The transition itself needs no handling, but a session ending does:
			// experience totals retained during startup belong to the session that ended, and if
			// startup spans a logout and a new login, seeding against them would measure one
			// character's total against another's.
			if (endsSession(gameState))
			{
				run.getState().discardPreInitialXp();
			}
			return;
		}
		// Applied as one call, not two, so a publication cannot observe half the transition.
		run.getState().updateSession(
			gameState.name(), gameState == GameState.LOGGED_IN, endsSession(gameState));
	}

	@Subscribe
	public void onGameTick(GameTick gameTick)
	{
		// Also the reconciliation point for anything that happened before initialization finished:
		// this reads the live client state, so events dropped in that window leave nothing stale.
		PublisherRunContext run = liveRun();
		if (run != null && run.isInitialized())
		{
			sampleClientState(run.getState());
		}
	}

	/**
	 * Records an experience total, as an observation once the run has initialized and otherwise as a
	 * total to measure against later. A gain is the difference between two totals, so discarding the
	 * earlier total loses the gain.
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
			// Retained rather than acted on: no baseline exists to measure it against yet.
			run.getState().recordPreInitialXp(skill.name(), statChanged.getXp());
			return;
		}
		// Only the skill and the increase are kept: the total is a comparison baseline and is never
		// exported. The enum position travels with the name so the exported collection can be
		// ordered deterministically without the state holding a RuneLite type.
		run.getState().observeXp(skill.name(), skill.ordinal(), statChanged.getXp());
	}

	/** Reads supported client state into {@code state}. Called from the client thread only. */
	private void sampleClientState(TelemetryState state)
	{
		GameState gameState = client.getGameState();
		boolean loggedIn = gameState == GameState.LOGGED_IN;
		state.updateSession(gameState.name(), loggedIn);
		if (!loggedIn)
		{
			return;
		}

		// The first live sample of a session establishes its experience baselines. Reading the
		// totals on a tick rather than on the logged-in transition means skill data is loaded, so a
		// transient zero cannot become a baseline.
		if (state.needsXpBaselineSeeding())
		{
			seedXpBaselines(state);
			state.markXpBaselinesSeeded();
		}

		state.updateWorld(client.getWorld());

		// The local player is read for its combat level and its interaction only. Its name, account
		// hash, and composition are never touched, and no other player is ever read.
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

		// Last, and only after every required value has been offered. It refuses while anything is
		// still missing, so the session is reported as live only with a complete player block.
		state.markPlayerStateComplete();
	}

	/**
	 * The label for the currently selected attack style, or null when no trustworthy reading exists.
	 * The label is the game's own: this walks the client's lookup path from weapon category to style
	 * list to style entry to name parameter, so nothing here maps a number onto a word. Every step
	 * that can fail yields null rather than a guess.
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
			// Staves use indexes 0..4, with a separate casting mode selecting the defensive casting
			// entry that follows. Both values come from the client; only the offset is written here.
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

	/**
	 * Reduces a blank name, or the game's own "no style here" marker, to no reading at all.
	 */
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
	 * The active prayers, as lowercase RuneLite prayer names in enum order. Each prayer is visited
	 * once, so the result is deterministic, cannot contain a duplicate, and is bounded by the
	 * enumeration rather than by play time.
	 *
	 * The upgraded ranged and magic prayers share a slot with the ones they replace, so the older
	 * prayer's variable reads as active when the newer is in use. Which of the pair is really active
	 * depends on whether the upgrade is unlocked, and unlocks are suspended inside Last Man Standing.
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

	/** Whether this member of an upgradable prayer pair is the one actually in use. */
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
	 * is discarded here and has no path further into the snapshot.
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

	/**
	 * The eleven visible equipment slots, or null when there is no equipment container. Null means
	 * "not read", so the state keeps what it last saw rather than reporting everything as unequipped.
	 */
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

	/** All twenty-eight inventory slots, or null when the client has no inventory container. */
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

	/**
	 * One slot's contents. A slot beyond the container's own array is empty rather than an error, since
	 * a schema-fixed slot the client does not carry holds nothing by definition.
	 */
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
		// The identity, the count, and the name. No price, examine text, tradeability, or aggregate
		// is looked up, and no icon or sprite is touched.
		return TelemetryItemSlot.of(item.getId(), item.getQuantity(), itemName(item.getId()));
	}

	/** Presentation only: identity is the item id passed in. */
	private String itemName(int itemId)
	{
		ItemComposition composition = client.getItemDefinition(itemId);
		if (composition == null)
		{
			return null;
		}
		// The members' name, so the same item reads the same on a free and a members world instead
		// of gaining a suffix that would look like a change to a reader.
		return composition.getMembersName();
	}

	/**
	 * Seeds the experience baselines from the client's current totals, for the case where the plugin is
	 * enabled while the player is already logged in and the login-time events have long since fired.
	 * Called from the first live sample of a session, so the totals belong to a real logged-in
	 * character.
	 */
	private void seedXpBaselines(TelemetryState state)
	{
		for (Skill skill : Skill.values())
		{
			// Defensive: this builds against latest.release, and the cost of being wrong is passing
			// a null or a non-skill to getSkillExperience. Checked on the entry and its name, never
			// on ordinal position or array length.
			if (skill == null || "OVERALL".equals(skill.name()))
			{
				continue;
			}
			// Read per skill rather than through getSkillExperiences(), whose array would have to
			// be mapped back by ordinal. The ordinal is passed alongside the name only to order the
			// exported collection, never to look anything up.
			state.seedXpBaseline(skill.name(), skill.ordinal(), client.getSkillExperience(skill));
		}
	}

	/**
	 * Whether reaching this state ends the play session, discarding the experience baselines. Every
	 * login passes through {@link GameState#LOGGING_IN}, so a world hop or loading screen keeps its
	 * baselines while a new login never does.
	 */
	private static boolean endsSession(GameState gameState)
	{
		return gameState == GameState.LOGIN_SCREEN
			|| gameState == GameState.LOGIN_SCREEN_AUTHENTICATOR
			|| gameState == GameState.LOGGING_IN;
	}

	/**
	 * One scheduled publication attempt, bound to the run that scheduled it. The currency check is an
	 * optimization, not the safeguard: the writer re-checks commit authority under the lock
	 * immediately before replacing the target. Unchecked failures are contained here, because a
	 * periodic task that throws is cancelled by its executor and would silently end publication.
	 */
	private void publishTick(PublisherRunContext run)
	{
		if (!run.isCurrent())
		{
			return;
		}
		try
		{
			// No lock is needed for the due-check: the commit decision is made later, inside the
			// writer. A change landing in between only costs a redundant publication.
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
	 * Publishes one snapshot through the run that owns it. Everything comes from {@code run}, so a
	 * publication can only reach the state, writer, and sequence of the run that issued it.
	 * {@link #publishLock} is handed to the writer, which takes it only across the commit step.
	 */
	private void publish(PublisherRunContext run, boolean pluginActive)
	{
		TelemetryState publishingState = run.getState();
		TelemetrySnapshot snapshot = publishingState.nextSnapshot(pluginActive);
		try
		{
			// The authority check runs inside the writer, immediately before it replaces the target,
			// rather than here where a slow write could make the answer stale before it mattered.
			int bytes = run.getWriter().write(snapshot, publishLock, run::isCommitAuthorized);
			// The sequence advances only for a snapshot that actually reached the file, and only on
			// the state that issued it.
			publishingState.recordPublished();
			log.debug("Published telemetry snapshot seq={} ({} bytes)", snapshot.getSeq(), bytes);
		}
		catch (TelemetrySnapshotWriter.CommitNotAuthorizedException e)
		{
			// Expected whenever a newer run started while this one was writing. The staged file has
			// been discarded and the target left alone, and the sequence and bookkeeping are
			// untouched because recordPublished was skipped.
			log.debug("Abandoned a telemetry snapshot superseded by a newer plugin run");
		}
		catch (IOException e)
		{
			// Logged without the payload, and without advancing the sequence: the next publication
			// retries the same sequence number.
			log.warn("Unable to publish telemetry snapshot to {}", run.getWriter().getTarget(), e);
		}
	}

	/** The production destination, and the only one the no-argument constructor supplies. */
	private static Path runeLiteDataDirectory()
	{
		return new File(RuneLite.RUNELITE_DIR, DATA_SUBDIRECTORY).toPath();
	}

	/** One daemon thread per run, named for a thread dump and daemon so it cannot hold the client open. */
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
