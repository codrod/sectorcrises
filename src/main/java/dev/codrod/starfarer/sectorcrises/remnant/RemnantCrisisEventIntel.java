package dev.codrod.starfarer.sectorcrises.remnant;

import java.io.IOException;
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
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.listeners.FleetEventListener;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.impl.campaign.intel.group.FGRaidAction.FGRaidType;
import com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI;
import com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI.GenericRaidParams;
import com.fs.starfarer.api.impl.campaign.intel.misc.RemnantNexusIntel;
import com.fs.starfarer.api.impl.campaign.missions.FleetCreatorMission.FleetStyle;
import com.fs.starfarer.api.impl.campaign.missions.hub.HubMissionWithTriggers.ComplicationRepImpact;
import com.fs.starfarer.api.impl.campaign.procgen.themes.MiscellaneousThemeGenerator;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD.BombardType;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

import dev.codrod.starfarer.sectorcrises.AttackInfo;
import dev.codrod.starfarer.sectorcrises.BaseSectorCrisisEventIntel;
import dev.codrod.starfarer.sectorcrises.SectorCrisesManager;
import dev.codrod.starfarer.sectorcrises.Utilities;

public class RemnantCrisisEventIntel extends BaseSectorCrisisEventIntel implements FleetEventListener {
	private int startYear = 215;
	private double escalationTime = 730f;
	private double attackStrength = 1f;

	List<CampaignFleetAPI> allNexuses = new LinkedList<CampaignFleetAPI>();

	private static final int PROGRESS_WARN = 0;
	private static final int PROGRESS_QUARTERSTRENGTH = 25;
	private static final int PROGRESS_HALFSTRENGTH = 50;
	private static final int PROGRESS_FULLSTRENGTH = 100;

	private static enum Stage {
		WARN,
		QUARTERSTRENGTH,
		HALFSTRENGTH
	}

	public RemnantCrisisEventIntel() {
		id = "sectorcrisis_remnant";
		attackWaveCoolDown = 90f;
	}

	@Override
	public void setup() {
		super.setup();
		loadSettings();

		allNexuses = MiscellaneousThemeGenerator.getRemnantStations(true, false);
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
		} catch (IOException | JSONException ex) {
			Global.getLogger(this.getClass()).error("Failed to load settings file", ex);
		}

		return;
	}

	@Override
	public boolean isDone() {
		return allNexuses.isEmpty();
	}

	@Override
	public boolean canHappen() {
		return Global.getSector().getClock().getCycle() >= startYear && !isDone();
	}

	@Override
	protected void advanceImpl(float deltaTime) {
		super.advanceImpl(deltaTime);

		float daysSinceStart = Global.getSector().getClock().getElapsedDaysSince(startTimeStamp);
		setProgress((int) (daysSinceStart / escalationTime * 100));
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

		return Math.max((int)(getProgress() / 100f * allNexuses.size()), 1);
	}

	private CampaignFleetAPI getRaidOrigin() {
		List<CampaignFleetAPI> nexuses = allNexuses;

		// Exclude origins which are already attacking other systems
		List<StarSystemAPI> ongoingAttackOrigins = getOngoingAttackOrigins();
		nexuses = nexuses.stream()
				.filter(nexus -> !ongoingAttackOrigins.contains(nexus.getStarSystem()))
				.collect(Collectors.toList());

		if (nexuses.size() <= 0) {
			return null;
		}

		return nexuses.get(random.nextInt(nexuses.size()));
	}

	private List<MarketAPI> getRaidTargets(CampaignFleetAPI raidOrigin) {
		// Generate list of raid targets excluding pirate/pather stations sorted by distance from raid origin
		List<MarketAPI> potentialRaidTargets = Utilities.getAllMarketsExcludingMakeshiftStations().stream()
				.sorted((m1, m2) -> (int) (Misc.getDistance(m1.getLocationInHyperspace(), raidOrigin.getLocationInHyperspace()) - Misc.getDistance(m2.getLocationInHyperspace(), raidOrigin.getLocationInHyperspace())))
				.collect(Collectors.toList());

		List<StarSystemAPI> ongoingAttackTargets = getOngoingAttackTargets();

		// Exclude systems which are already being attacked
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
		CampaignFleetAPI nexus = getRaidOrigin();

		if (nexus == null) {
			return null;
		}

		List<MarketAPI> targetMarkets = getRaidTargets(nexus);

		if (targetMarkets.isEmpty()) {
			return null;
		}

		StarSystemAPI targetSystem = targetMarkets.get(0).getStarSystem();

		GenericRaidParams params = new GenericRaidParams(random, false);
		params.factionId = nexus.getFaction().getId();

		// Create "fake" market since remnant nexuses don't count as markets (cant buy/sell)
		MarketAPI fakeMarket = Global.getFactory().createMarket(nexus.getId(), nexus.getName(), 3);
		fakeMarket.setPrimaryEntity(nexus);
		fakeMarket.setFactionId(params.factionId);
		fakeMarket.getStats().getDynamic().getMod(Stats.FLEET_QUALITY_MOD).modifyFlat("nexus_" + nexus.getId(), 1f);

		params.source = fakeMarket;
		params.remnant = true;
		params.makeFleetsHostile = true;
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
		params.forcesNoun = "remnant forces";
		params.style = FleetStyle.STANDARD;
		params.repImpact = ComplicationRepImpact.FULL;

		double fleetSizeMult = 1f * attackStrength;
		boolean damaged = nexus.getMemoryWithoutUpdate().getBoolean("$damagedStation");
		if (damaged) {
			fleetSizeMult = 0.5f;
		}

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
		addRemnantNexusIntelIfNeeded(nexus);

		return new AttackInfo(nexus.getStarSystem(), targetSystem, raid);
	}

	private void addRemnantNexusIntelIfNeeded(CampaignFleetAPI nexus) {
		if (RemnantNexusIntel.getNexusIntel(nexus) == null) {
			new RemnantNexusIntel(nexus); // adds the intel
		}
	}

	@Override
	public void reportFleetDespawnedToListener(CampaignFleetAPI fleet, FleetDespawnReason reason, Object param) {
		if (allNexuses.contains(fleet)) {
			Global.getLogger(this.getClass()).error("Remnant nexus despawned without a battle which shouldn't happen...");
			allNexuses.remove(fleet);
		}

		return;
	}

	@Override
	public void reportBattleOccurred(CampaignFleetAPI fleet, CampaignFleetAPI primaryWinner, BattleAPI battle) {
		if (fleet != null && !fleet.isAlive() && allNexuses.contains(fleet)) {
			allNexuses.remove(fleet);
		}
	}

	/******* GUI Code **********/

	@Override
	protected String getName() {
		return "Remnant Crisis";
	}

	@Override
	public String getIcon() {
		return Global.getSector().getFaction(Factions.REMNANTS).getCrest();
	}

	@Override
	public Set<String> getIntelTags(SectorMapAPI map) {
		Set<String> tags = super.getIntelTags(map);
		tags.add(Factions.REMNANTS);
		return tags;
	}

	@Override
	public void addStageDesc(TooltipMakerAPI info, Object stageId, float initPad, boolean forTooltip) {
		if (stageId == Stage.WARN) {
			info.addPara(
				"Rumours spread of AI warships, from the first AI war, attacking fleets on the edges of core-world space."
				+" Hegemony COMSEC officially denies the existence of the warships, claiming all AI warships were destroyed, but vocal \"agitators\" quietly disappear."
				,initPad);
		} else if (stageId == Stage.QUARTERSTRENGTH) {
			info.addPara(
				"Fleets of AI warships begin attacking colonies in the core-worlds forcing the Hegemony to publicly acknowledge their existence."
				+" The Tri-Tachyon Corporation denies any involvement despite the accusations of Hegemony and Luddic Church officials."
				+" The League remains publicly neutral and encourages an interfactional investigation."
				,initPad);
		} else if (stageId == Stage.HALFSTRENGTH) {
			info.addPara(
				"Attacks on the core-worlds increase in intensity leading to an official declaration of crisis by the Persean League Executive Council."
				+" The Hegemony continues to publicly accuse the Tri-Tachyon Corporation of covert involvement; despite attacks on corporate assets and holdings by AI warships."
				+" The sector is on the verge of a third AI war preventing the organization of a unified sector-wide front."
				,initPad);
		}
	}

	@Override
	protected void addCrisisHints(TooltipMakerAPI main) {
		main.addPara("Destroying all remnant nexus stations will likely end the crisis.", Misc.getHighlightColor(), 0);
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
