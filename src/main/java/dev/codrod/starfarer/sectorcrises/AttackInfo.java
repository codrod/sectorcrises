package dev.codrod.starfarer.sectorcrises;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.campaign.StarSystemAPI;

public class AttackInfo {
    EveryFrameScript script;
    StarSystemAPI origin;
    StarSystemAPI target;

    public AttackInfo(StarSystemAPI origin, StarSystemAPI target, EveryFrameScript script) {
        this.origin = origin;
        this.target = target;
        this.script = script;
    }
}
