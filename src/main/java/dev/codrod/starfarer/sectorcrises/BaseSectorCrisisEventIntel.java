package dev.codrod.starfarer.sectorcrises;

import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;
import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.impl.campaign.intel.events.BaseEventIntel;
import com.fs.starfarer.api.impl.campaign.intel.events.BaseFactorTooltip;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI.TooltipCreator;
import com.fs.starfarer.api.util.Misc;

public class BaseSectorCrisisEventIntel extends BaseEventIntel {
	public String id = "";

	protected double daysBeforeNextWave = 0;
	protected double attackWaveCoolDown = 0;
	protected List<AttackInfo> ongoingAttacks;
	protected long startTimeStamp = 0;
	protected Object currentStage = null;

	public BaseSectorCrisisEventIntel() {
		id = "sectorcrisis_base";
	}

	public void setup() {
		factors.clear();
		stages.clear();

		ongoingAttacks = new LinkedList<AttackInfo>();
		startTimeStamp = Global.getSector().getClock().getTimestamp();

		return;
	}

	public void loadSettings() {
		return;
	}

	@Override
	public boolean isDone() {
		return true;
	}

	public boolean canHappen() {
		return false;
	}

	@Override
	protected void advanceImpl(float deltaTime) {
		super.advanceImpl(deltaTime);

		int maxAttacks = getMaxAttacks();
		int minAttacks = getMinAttacks();

		if (minAttacks > 0 && maxAttacks > 0 && minAttacks <= maxAttacks) {
			//Note that we use getOngoingAttackOrigins() because it filters out finished attacks
			int numOngoingAttacks = getOngoingAttackOrigins().size();

			//The attacks are triggered in waves with a cool-down period between waves
			if (numOngoingAttacks <= 0) {
				if(daysBeforeNextWave <= 0) {
					int newAttacks = random.nextInt(minAttacks, maxAttacks + 1);

					for (int count = 1; count <= newAttacks; count++) {
						AttackInfo attack = startAttack();

						//Should consider adding some sort of retry logic to this instead of just ignoring the failures
						if (attack != null) {
							ongoingAttacks.add(attack);
						}
					}

					daysBeforeNextWave = attackWaveCoolDown;
				} else {
					daysBeforeNextWave -= Global.getSector().getClock().convertToDays(deltaTime);
				}
			}
		}
	}

	@Override
	protected void notifyStageReached(EventStageData stage) {
		currentStage = stage.id;
		return;
	}

	public int getMinAttacks() {
		return 0;
	}

	public int getMaxAttacks() {
		return 0;
	}

	public AttackInfo startAttack() {
		return null;
	}

	public List<StarSystemAPI> getOngoingAttackOrigins() {
		// Filter out finished attacks
		ongoingAttacks = ongoingAttacks.stream()
			.filter((attack) -> !attack.script.isDone())
			.collect(Collectors.toList());

		// Return list of attack origins
		return ongoingAttacks.stream().map((attack) -> attack.origin).collect(Collectors.toList());
	}

	public List<StarSystemAPI> getOngoingAttackTargets() {
		// Filter out finished attacks
		ongoingAttacks = ongoingAttacks.stream()
			.filter((attack) -> !attack.script.isDone())
			.collect(Collectors.toList());

		// Return list of attack targets
		return ongoingAttacks.stream().map((attack) -> attack.target).collect(Collectors.toList());
	}

	/******* GUI Code **********/

	@Override
	protected String getName() {
		return "Sector Crisis";
	}

	@Override
	public String getIcon() {
		return Global.getSettings().getSpriteName("events", "stage_unknown_bad");
	}

	@Override
	public void addStageDescriptionText(TooltipMakerAPI info, float width, Object stageId) {
		float small = 0f;

		EventStageData stage = getDataFor(stageId);
		if (stage == null)
			return;

		if (isStageActive(stageId)) {
			addStageDesc(info, stageId, small, false);
		}
	}

	public void addStageDesc(TooltipMakerAPI info, Object stageId, float initPad, boolean forTooltip) {
		return;
	}

	@Override
	public void afterStageDescriptions(TooltipMakerAPI main) {
		main.addSpacer(20f);
		main.setBulletedListMode("    - ");
		addCrisisHints(main);
		main.setBulletedListMode(null);
	}

	protected void addCrisisHints(TooltipMakerAPI main) {
		return;
	}

	protected String getStageIconImpl(Object stageId) {
		return Global.getSettings().getSpriteName("events", "stage_unknown_bad");
	}

	@Override
	public TooltipCreator getStageTooltipImpl(Object stageId) {
		return new BaseFactorTooltip() {
			@Override
			public void createTooltip(TooltipMakerAPI tooltip, boolean expanded, Object tooltipParam) {
				tooltip.addPara("The crisis will become more dangerous in stages.", 0);
			}
		};
	}

	@Override
	public Color getBarColor() {
		Color color = Misc.getNegativeHighlightColor();
		color = Misc.interpolateColor(color, Color.black, 0.25f);
		return color;
	}

	@Override
	public boolean withMonthlyFactors() {
		return false;
	}

	@Override
	public boolean withOneTimeFactors() {
		return false;
	}

	@Override
	public TooltipCreator getBarTooltip() {
		return new TooltipCreator() {
			public boolean isTooltipExpandable(Object tooltipParam) {
				return false;
			}

			public float getTooltipWidth(Object tooltipParam) {
				return 450;
			}

			public void createTooltip(TooltipMakerAPI tooltip, boolean expanded, Object tooltipParam) {
				tooltip.addPara("The crisis will progress over time.", 0);
			}
		};
	}
}
