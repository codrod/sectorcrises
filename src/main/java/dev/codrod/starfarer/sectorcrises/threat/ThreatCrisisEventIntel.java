package dev.codrod.starfarer.sectorcrises.threat;

import java.io.IOException;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.json.JSONException;
import org.json.JSONObject;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignEventListener.FleetDespawnReason;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.listeners.FleetEventListener;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.intel.group.FGRaidAction.FGRaidType;
import com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI;
import com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI.GenericRaidParams;
import com.fs.starfarer.api.impl.campaign.missions.FleetCreatorMission.FleetStyle;
import com.fs.starfarer.api.impl.campaign.missions.hub.HubMissionWithTriggers.ComplicationRepImpact;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD.BombardType;
import com.fs.starfarer.api.impl.combat.threat.DisposableThreatFleetManager;
import com.fs.starfarer.api.impl.combat.threat.DisposableThreatFleetManager.FabricatorEscortStrength;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

import dev.codrod.starfarer.sectorcrises.AttackInfo;
import dev.codrod.starfarer.sectorcrises.BaseSectorCrisisEventIntel;
import dev.codrod.starfarer.sectorcrises.SectorCrisesManager;
import dev.codrod.starfarer.sectorcrises.Utilities;

public class ThreatCrisisEventIntel extends BaseSectorCrisisEventIntel implements FleetEventListener {
	private int startYear = 215;
	private double escalationTime = 720f;
	private double attackStrength = 1f;

	// The max number of motherfleets (will be spawned linearly determined by escalationTime)
	// Note that the maximum number of fleets is also limited by unoccupied fringe systems so the actual number may be lower in extreme scenarios
	private int maxMotherFleets = 8;

	private float daysSinceLastSpawn = 0f;
	private int numMotherFleetsSpawned = 0;

	private static final int PROGRESS_WARN = 0;
	private static final int PROGRESS_QUARTERSTRENGTH = 25;
	private static final int PROGRESS_HALFSTRENGTH = 50;
	private static final int PROGRESS_FULLSTRENGTH = 100;

	private static enum Stage {
		WARN,
		QUARTERSTRENGTH,
		HALFSTRENGTH
	}

	private List<StarSystemAPI> allFringeSystems = new LinkedList<StarSystemAPI>();
	private Dictionary<CampaignFleetAPI, StarSystemAPI> motherFleets = new Hashtable<CampaignFleetAPI, StarSystemAPI>();

	public ThreatCrisisEventIntel() {
		id = "sectorcrisis_threat";
		attackWaveCoolDown = 90f;
	}

	@Override
	public void setup() {
		super.setup();
		loadSettings();

		// Get all systems on the west side of the map which have at least one planet and don't have any remnants
		allFringeSystems = Global.getSector().getStarSystems().stream()
				.filter(system -> system.getLocation().x < 0
						&& system.isProcgen()
						&& !system.hasTag(Tags.THEME_REMNANT)
						&& Utilities.getFirstPlanet(system) != null)
				.collect(Collectors.toList());

		if (allFringeSystems.isEmpty()) {
			Global.getLogger(this.getClass()).error("Cannot find any fringe systems");
		}

		// The number of motherfleets is limited by the available space for them to hide at least for now
		maxMotherFleets = Math.min(maxMotherFleets, allFringeSystems.size() / 2);

		currentStage = Stage.WARN;

		setMaxProgress(PROGRESS_FULLSTRENGTH);
		addStage(Stage.WARN, PROGRESS_WARN);
		addStage(Stage.QUARTERSTRENGTH, PROGRESS_QUARTERSTRENGTH);
		addStage(Stage.HALFSTRENGTH, PROGRESS_HALFSTRENGTH);

		return;
	}

	@Override
	public void loadSettings() {
		try {
			JSONObject settings = Global.getSettings().loadJSON(SectorCrisesManager.settingsFile, SectorCrisesManager.modId).getJSONObject(id);

			startYear = settings.optInt("startYear", startYear);
			attackWaveCoolDown = settings.optDouble("attackWaveCoolDown", attackWaveCoolDown);
			escalationTime = settings.optDouble("escalationTime", escalationTime);
			attackStrength = settings.optDouble("attackStrength", attackStrength);
			maxMotherFleets = settings.optInt("maxMotherFleets", maxMotherFleets);
		} catch (IOException | JSONException ex) {
			Global.getLogger(this.getClass()).error("Failed to load settings file", ex);
		}

		return;
	}

	@Override
	public boolean isDone() {
		return numMotherFleetsSpawned == maxMotherFleets && motherFleets.isEmpty();
	}

	@Override
	public boolean canHappen() {
		return Global.getSector().getClock().getCycle() >= startYear && !isDone();
	}

	@Override
	protected void advanceImpl(float deltaTime) {
		super.advanceImpl(deltaTime);

		daysSinceLastSpawn += Global.getSector().getClock().convertToDays(deltaTime);

		if (numMotherFleetsSpawned < maxMotherFleets && daysSinceLastSpawn >= (escalationTime / maxMotherFleets)) {
			// Spawn "motherfleets" which will act as stations for organizing raids
			if (spawnMotherFleet()) {
				daysSinceLastSpawn = 0f;
				numMotherFleetsSpawned++;
			}

			setProgress(Math.round(PROGRESS_FULLSTRENGTH / maxMotherFleets * numMotherFleetsSpawned));
		} else if (numMotherFleetsSpawned >= maxMotherFleets) {
			setProgress(PROGRESS_FULLSTRENGTH);
		}
	}

	@Override
	public int getMinAttacks() {
		if (currentStage == Stage.WARN) {
			return 0;
		}

		return Math.max(getMaxAttacks() / 2, 1);
	}

	@Override
	public int getMaxAttacks() {
		if (currentStage == Stage.WARN) {
			return 0;
		}

		return Math.max((int)(getProgress() / 100f * motherFleets.size()), 1);
	}

	private boolean spawnMotherFleet() {
		List<StarSystemAPI> fringeSystems = getUnoccupiedFringeSystems();

		if (fringeSystems.size() <= 0) {
			return false;
		}

		StarSystemAPI spawningSystem = null;
		StarSystemAPI travelTarget = null;

		// If at least one motherfleet exists spawn in that system (and travel to a new system) otherwise spawn in random system
		if (motherFleets.size() == 0) {
			spawningSystem = fringeSystems.get(random.nextInt(fringeSystems.size()));
		} else {
			spawningSystem = motherFleets.elements().nextElement();
			travelTarget = getTravelTarget(spawningSystem);

			if (travelTarget == null) {
				return false;
			}
		}

		CampaignFleetAPI motherFleet = DisposableThreatFleetManager.createThreatFleet(3, 0, 0, FabricatorEscortStrength.MAXIMUM, random);

		// Give the fleet a special name and mark as important
		motherFleet.setName("Matrifabricator 0x" + Integer.toHexString(numMotherFleetsSpawned));
		motherFleet.getMemoryWithoutUpdate().set(MemFlags.ENTITY_MISSION_IMPORTANT, true);

		// Give the player an actual chance to find the fleet (best idea for now)
		motherFleet.getDetectedRangeMod().modifyMult("threat_fleet_stealth", 8f, "Too big to hide");
		motherFleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_ALLOW_LONG_PURSUIT, false);
		motherFleet.getMemoryWithoutUpdate().set(MemFlags.MAY_GO_INTO_ABYSS, false);

		motherFleet.addEventListener(this);
		spawningSystem.spawnFleet(Utilities.getFirstPlanet(spawningSystem), 0, 0, motherFleet);

		// Give travel orders to other system
		if (travelTarget != null) {
			motherFleet.addAssignment(FleetAssignment.GO_TO_LOCATION, travelTarget.getJumpPoints().get(0), 365f, "traveling", null);
			motherFleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, Utilities.getFirstPlanet(travelTarget), 9999f, "fabricating", null);
			motherFleets.put(motherFleet, travelTarget);
		} else {
			motherFleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, Utilities.getFirstPlanet(spawningSystem), 9999f, "fabricating", null);
			motherFleets.put(motherFleet, spawningSystem);
		}

		return true;
	}

	private StarSystemAPI getTravelTarget(StarSystemAPI originSystem) {
		// Get all unoccupied systems and sort by distance from origin system
		List<StarSystemAPI> potentialTravelTargets = getUnoccupiedFringeSystems().stream()
				.sorted((s1, s2) -> (int) (Misc.getDistance(s1.getLocation(), originSystem.getLocation()) - Misc.getDistance(s2.getLocation(), originSystem.getLocation())))
				.collect(Collectors.toList());

		if (potentialTravelTargets.size() == 0) {
			return null;
		}

		// Note that the travel targets are sorted by distance from originSystem so this selects from the 3 (or less) closest systems
		return potentialTravelTargets.get(random.nextInt(Math.min(potentialTravelTargets.size(), 4)));
	}

	private List<StarSystemAPI> getUnoccupiedFringeSystems() {
		List<StarSystemAPI> motherFleetSystems = Collections.list(motherFleets.elements());
		List<MarketAPI> allMarkets = Global.getSector().getEconomy().getMarketsCopy();

		// Exclude systems which are already staging attacks, systems which are occupied by motherfleets, and any systems containing markets
		List<StarSystemAPI> availableSystems = allFringeSystems.stream()
				.filter(system -> !getOngoingAttackOrigins().stream().anyMatch(origin -> origin.getName().equals(system.getName())))
				.filter(system -> !motherFleetSystems.contains(system))
				.filter(system -> !allMarkets.stream().anyMatch(market -> market.getStarSystem().getName().equals(system.getName())))
				.collect(Collectors.toList());

		return availableSystems;
	}

	private CampaignFleetAPI getRaidOrigin() {
		// Exclude motherfleets which are currently traveling
		List<CampaignFleetAPI> potentialOrigins = Collections.list(motherFleets.keys()).stream()
				.filter(fleet -> fleet.getStarSystem() != null && motherFleets.get(fleet).getName().equals(fleet.getStarSystem().getName()))
				.collect(Collectors.toList());

		if (potentialOrigins.size() <= 0) {
			return null;
		}

		return potentialOrigins.get(random.nextInt(potentialOrigins.size()));
	}

	private List<MarketAPI> getRaidTargets(CampaignFleetAPI raidOrigin) {
		// Generate list of raid targets excluding pirate/pather stations sorted by distance from raid origin
		List<MarketAPI> potentialRaidTargets = Utilities.getAllMarketsExcludingMakeshiftStations().stream()
				.sorted((m1, m2) -> (int) (Misc.getDistance(m1.getLocationInHyperspace(), raidOrigin.getLocationInHyperspace()) - Misc.getDistance(m2.getLocationInHyperspace(), raidOrigin.getLocationInHyperspace())))
				.collect(Collectors.toList());

		// Exclude systems which are already being attacked
		List<StarSystemAPI> ongoingAttackTargets = getOngoingAttackTargets();
		potentialRaidTargets = potentialRaidTargets.stream()
				.filter((target) -> !ongoingAttackTargets.contains(target.getStarSystem()))
				.collect(Collectors.toList());

		if (potentialRaidTargets.size() <= 0) {
			return potentialRaidTargets;
		}

		// Note since the raid targets are sorted by distance this selects from the 3 (or less) closest targets
		StarSystemAPI raidTargetSystem = potentialRaidTargets.get(random.nextInt(Math.min(4, potentialRaidTargets.size()))).getStarSystem();

		// Attacks target the entire system so once we select a market we then extend the attack to all other markets in the same system
		List<MarketAPI> raidTargetMarkets = Utilities.getAllMarketsInSystem(raidTargetSystem);

		return raidTargetMarkets;
	}

	@Override
	public AttackInfo startAttack() {
		CampaignFleetAPI motherFleet = getRaidOrigin();

		if (motherFleet == null) {
			return null;
		}

		StarSystemAPI travelTarget = getTravelTarget(motherFleet.getStarSystem());

		if (travelTarget == null) {
			return null;
		}

		List<MarketAPI> targetMarkets = getRaidTargets(motherFleet);

		if (targetMarkets.isEmpty()) {
			return null;
		}

		StarSystemAPI targetSystem = targetMarkets.get(0).getStarSystem();

		GenericRaidParams params = new GenericRaidParams(random, false);
		params.factionId = Factions.THREAT;

		// Create "fake" market based on the first planet in the motherfleet's current system
		PlanetAPI planet = Utilities.getFirstPlanet(motherFleet.getStarSystem());
		MarketAPI fakeMarket = Global.getFactory().createMarket(planet.getId(), planet.getName(), 3);
		fakeMarket.setPrimaryEntity(planet);
		fakeMarket.setFactionId(params.factionId);
		fakeMarket.getStats().getDynamic().getMod(Stats.FLEET_QUALITY_MOD).modifyFlat("motherfleet_" + motherFleet.getId(), 1f);
		//This prevents the GenericRaid info text from refering to the planet as a colony
		planet.setMarket(null);

		params.source = fakeMarket;
		params.makeFleetsHostile = true;
		params.noun = "attack";
		params.prepDays = 30f + 14f * random.nextFloat();
		params.payloadDays = 27f + 7f * random.nextFloat();
		params.raidParams.where = targetSystem;
		params.raidParams.type = FGRaidType.CONCURRENT;
		params.raidParams.tryToCaptureObjectives = false;
		params.raidParams.doNotGetSidetracked = false;
		params.raidParams.allowedTargets.addAll(targetMarkets);
		params.raidParams.allowNonHostileTargets = true;
		params.raidParams.setBombardment(BombardType.SATURATION);
		params.raidParams.raidsPerColony = 1;
		params.forcesNoun = "THREATS";
		params.style = FleetStyle.STANDARD;
		params.repImpact = ComplicationRepImpact.FULL;

		double fleetSizeMult = 1f * attackStrength;
		double totalDifficulty = fleetSizeMult * 50f;
		totalDifficulty -= 10;
		params.fleetSizes.add(10);

		while (totalDifficulty > 0) {
			int min = 6;
			int max = 10;

			int diff = min + random.nextInt(max - min + 1);

			params.fleetSizes.add(diff);
			totalDifficulty -= diff;
		}

		GenericRaidFGI raid = new GenericRaidFGI(params);
		Global.getSector().getIntelManager().addIntel(raid);

		// Once the raid has begun the motherfleet moves to another system so the player doesn't know where it is
		motherFleet.clearAssignments();
		motherFleet.addAssignment(FleetAssignment.GO_TO_LOCATION, travelTarget.getJumpPoints().get(0), 365f, "traveling", null);
		motherFleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, Utilities.getFirstPlanet(travelTarget), 9999f, "fabricating", null);
		motherFleets.put(motherFleet, travelTarget);

		return new AttackInfo(fakeMarket.getStarSystem(), targetSystem, raid);
	}

	@Override
	public void reportFleetDespawnedToListener(CampaignFleetAPI fleet, FleetDespawnReason reason, Object param) {
		if (motherFleets.get(fleet) != null) {
			Global.getLogger(this.getClass()).error("THREAT motherfleet despawned without a battle which shouldn't happen...");
			motherFleets.remove(fleet);
		}

		return;
	}

	@Override
	public void reportBattleOccurred(CampaignFleetAPI fleet, CampaignFleetAPI primaryWinner, BattleAPI battle) {
		if (fleet != null && !fleet.isAlive()) {
			motherFleets.remove(fleet);
		}
	}

	/******* GUI Code **********/

	@Override
	protected String getName() {
		return "THREAT Crisis";
	}

	@Override
	public String getIcon() {
		return Global.getSector().getFaction(Factions.THREAT).getCrest();
	}

	@Override
	public Set<String> getIntelTags(SectorMapAPI map) {
		Set<String> tags = super.getIntelTags(map);
		tags.add(Factions.THREAT);
		return tags;
	}

	@Override
	public void addStageDesc(TooltipMakerAPI info, Object stageId, float initPad, boolean forTooltip) {
		if (stageId == Stage.WARN) {
			info.addPara(
				"Reports of ships of unknown, seemingly archaic, designs attacking fleets along the fringe of the Orion-perseus Abyss arrive in the core worlds."
				+ " Investigations into the identity of the mysterious vessels turn up nothing but incomplete records buried deep within backups of ancient DOMAIN archives; though the records are heavily redacted and refer to the ships by the code-name \"THREAT\"."
				+ " Expeditions to the fringe are organized by every major faction in the sector."
				,initPad);
		} else if (stageId == Stage.QUARTERSTRENGTH) {
			info.addPara(
				"The \"THREATS\" begin attacking the core-worlds from seemingly empty systems on the fringe of the sector."
				+ " After-action reports confirm the THREAT is a form of primitive AI seemingly animalistic in behavior."
				+ " The Tri-Tachyon Special Acquisitions division claims that the ships are capable of self-replication and speculate that the attacks are in search of resources for \"reproduction\" but they refuse to disclose the source of their information."
				,initPad);
		} else if (stageId == Stage.HALFSTRENGTH) {
			info.addPara(
				"Attacks on the core-worlds increase in intensity straining local-defense forces."
				+ " The Tri-Tachyon Corporation denies any connection to the THREAT but long-standing suspicions cause many to doubt them."
				+ " The Luddic Church claims the THREAT are spawns of Moloch inflaming tensions in the sector."
				+ " An emergency interfactional committee fails to organize an unified sector-wide front."
				,initPad);
		}
	}

	@Override
	protected void addCrisisHints(TooltipMakerAPI main) {
		main.addPara("Exploring the fringes of the sector will uncover the source of the crisis.", Misc.getHighlightColor(), 0);
	}

	@Override
	protected String getStageIconImpl(Object stageId) {
		if (stageId == Stage.WARN) {
			return Global.getSettings().getSpriteName("sector_crisis", "hostile_activity_1");
		} else if (stageId == Stage.QUARTERSTRENGTH) {
			return Global.getSettings().getSpriteName("sector_crisis", "hostile_activity_2");
		} else if (stageId == Stage.HALFSTRENGTH) {
			return Global.getSettings().getSpriteName("sector_crisis", "hostile_activity_4");
		}

		return Global.getSettings().getSpriteName("events", "stage_unknown_bad");
	}
}
