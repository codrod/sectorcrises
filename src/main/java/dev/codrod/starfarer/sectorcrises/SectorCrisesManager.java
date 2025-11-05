package dev.codrod.starfarer.sectorcrises;

import java.io.IOException;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import org.json.JSONArray;
import org.json.JSONException;
import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;

public class SectorCrisesManager implements EveryFrameScript {
    public static String memId = "$sectorcrises_manager";

    public static String settingsFile = "data/config/settings.json";
    public static String modId = "sectorcrises";

    public List<String> excludedCrises = new LinkedList<String>();
    private BaseSectorCrisisEventIntel currentCrisis = null;
    private List<String> previousCrises = new LinkedList<String>();
    private Dictionary<String, BaseSectorCrisisEventIntel> allCrises = new Hashtable<String, BaseSectorCrisisEventIntel>();

    private Random random = new Random();

    public SectorCrisesManager() {
    }

    public void loadSettings() {
        try {
            JSONArray excludedCrisesJson = Global.getSettings()
                    .loadJSON(SectorCrisesManager.settingsFile, SectorCrisesManager.modId)
                    .getJSONArray("excludedCrises");

            excludedCrises = new LinkedList<String>();

            for (int i = 0; i < excludedCrisesJson.length(); i++) {
                excludedCrises.add(excludedCrisesJson.getString(i));
            }
        } catch (IOException | JSONException ex) {
            Global.getLogger(this.getClass()).error("Failed to load settings file", ex);
        }
    }

    public static SectorCrisesManager get() {
        return (SectorCrisesManager) Global.getSector().getMemoryWithoutUpdate().get(SectorCrisesManager.memId);
    }

    public void addCrisis(BaseSectorCrisisEventIntel crisis) {
        allCrises.put(crisis.id, crisis);
        return;
    }

    public BaseSectorCrisisEventIntel getCurrentCrisis() {
        return currentCrisis;
    }

    @Override
    public void advance(float deltaTime) {
        if (currentCrisis != null) {
            if (currentCrisis.isDone()) {
                currentCrisis = null;
            }
        } else {
            // Invoke the setup method before selecting a crisis so settings are loaded
            List<BaseSectorCrisisEventIntel> availableCrises = Collections.list(allCrises.elements());
            availableCrises.forEach(crisis -> crisis.setup());

            availableCrises = availableCrises.stream()
                    .filter(crisis -> !excludedCrises.contains(crisis.id) && !previousCrises.contains(crisis.id)
                            && crisis.canHappen())
                    .collect(Collectors.toList());

            if (availableCrises.size() <= 0) {
                return;
            }

            currentCrisis = availableCrises.get(random.nextInt(availableCrises.size()));
            previousCrises.add(currentCrisis.id);

            Global.getSector().getIntelManager().queueIntel(currentCrisis);
        }
    }

    @Override
    public boolean isDone() {
        return false;
    }

    @Override
    public boolean runWhilePaused() {
        return false;
    }
}
