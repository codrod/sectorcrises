package dev.codrod.starfarer.sectorcrises;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Entities;

public final class Utilities {
    public static List<MarketAPI> getAllMarketsExcludingMakeshiftStations() {
        return Global.getSector().getEconomy().getMarketsCopy().stream()
			.filter((market) -> !Entities.MAKESHIFT_STATION.equals(market.getPrimaryEntity().getCustomEntityType()))
			.collect(Collectors.toList());
    }

    public static List<MarketAPI> getAllMarketsInSystem(StarSystemAPI system) {
        return Global.getSector().getEconomy().getMarketsCopy().stream()
			.filter((market) -> market.getStarSystem().getName().equals(system.getName()))
			.collect(Collectors.toList());
    }

    public static PlanetAPI getFirstPlanet(StarSystemAPI system) {
        Optional<PlanetAPI> optionalPlanet = system.getPlanets().stream()
            .filter(planet -> !planet.isStar() && !planet.isBlackHole())
            .findFirst();

        if(optionalPlanet.isPresent()) {
            return optionalPlanet.get();
        }

        return null;
    }
}
