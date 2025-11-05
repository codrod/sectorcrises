package dev.codrod.starfarer.sectorcrises;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;

import dev.codrod.starfarer.sectorcrises.remnant.RemnantCrisisEventIntel;
import dev.codrod.starfarer.sectorcrises.threat.ThreatCrisisEventIntel;

public class ModPlugin extends BaseModPlugin {
  @Override
  public void onGameLoad(boolean newGame) {

    SectorCrisesManager manager = SectorCrisesManager.get();

    // Create crises manager if it does not already exist
    if (manager == null) {
      manager = new SectorCrisesManager();

      Global.getSector().addScript(manager);
      Global.getSector().getMemoryWithoutUpdate().set(SectorCrisesManager.memId, manager);
    }

    // Note that we reload settings on each game load
    manager.loadSettings();

    // If a crisis is currently running than reload settings because this is the best place to load settings
    // Note we don't care about the other crises since they are not being used yet
    if (manager.getCurrentCrisis() != null) {
      manager.getCurrentCrisis().loadSettings();
    }

    // Add vanilla crises
    manager.addCrisis(new RemnantCrisisEventIntel());
    manager.addCrisis(new ThreatCrisisEventIntel());
  }
}
