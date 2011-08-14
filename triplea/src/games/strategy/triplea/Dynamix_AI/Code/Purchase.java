/*
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */

package games.strategy.triplea.Dynamix_AI.Code;

import games.strategy.engine.data.Change;
import games.strategy.engine.data.ChangeFactory;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.PlayerID;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.RepairRule;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.Constants;
import games.strategy.triplea.Dynamix_AI.CommandCenter.CachedInstanceCenter;
import games.strategy.triplea.Dynamix_AI.CommandCenter.FactoryCenter;
import games.strategy.triplea.Dynamix_AI.CommandCenter.GlobalCenter;
import games.strategy.triplea.Dynamix_AI.DMatches;
import games.strategy.triplea.Dynamix_AI.DSettings;
import games.strategy.triplea.Dynamix_AI.DSorting;
import games.strategy.triplea.Dynamix_AI.DUtils;
import games.strategy.triplea.Dynamix_AI.Dynamix_AI;
import games.strategy.triplea.Dynamix_AI.Group.PurchaseGroup;
import games.strategy.triplea.Dynamix_AI.Others.NCM_TargetCalculator;
import games.strategy.triplea.Dynamix_AI.Others.NCM_Task;
import games.strategy.triplea.Dynamix_AI.Others.Purchase_UnitPlacementLocationSorter;
import games.strategy.triplea.Properties;
import games.strategy.triplea.TripleAUnit;
import games.strategy.triplea.attatchments.TerritoryAttachment;
import games.strategy.triplea.attatchments.UnitAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.remote.IPurchaseDelegate;
import games.strategy.triplea.oddsCalculator.ta.AggregateResults;
import games.strategy.util.IntegerMap;
import games.strategy.util.Match;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.logging.Level;
import javax.swing.SwingUtilities;

/**
 * This class really needs a rewrite...
 * @author Stephen
 */
public class Purchase
{
    public static void purchase(Dynamix_AI ai, boolean purchaseForBid, int PUsToSpend, IPurchaseDelegate purchaser, GameData data, PlayerID player)
    {
        if(DSettings.LoadSettings().AIC_disableAllUnitPurchasesAndPlacements)
        {
            final String message = ai.getName() + " is skipping it's purchase phase, as instructed.";
            DUtils.Log(Level.FINE, message);
            Runnable runner = new Runnable()
            {public void run(){CachedInstanceCenter.CachedDelegateBridge.getHistoryWriter().startEvent(message);}};
            try{SwingUtilities.invokeAndWait(runner);}
            catch (InterruptedException ex){}
            catch (InvocationTargetException ex){}
            Dynamix_AI.Pause();
            return;
        }

        if(!purchaseForBid && DSettings.LoadSettings().EnableResourceCollectionMultiplier && DSettings.LoadSettings().ResourceCollectionMultiplyPercent != 100)
        {
            if(GlobalCenter.PUsAtEndOfLastTurn == 0) //This will happen when the game was saved and reloaded since the end of this country's last turn
                GlobalCenter.PUsAtEndOfLastTurn = DUtils.GetTotalProductionOfTerritoriesInList(DUtils.ToList(data.getMap().getTerritoriesOwnedBy(player))); //This is a temp hack, should definately be changed
            int PUsCollectedLastTurn = player.getResources().getQuantity(GlobalCenter.GetPUResource()) - GlobalCenter.PUsAtEndOfLastTurn;
            //Since we already have the pu's we collected last turn, only add the extra
            int PUChange = (int)((float)PUsCollectedLastTurn * DUtils.ToFloat(DSettings.LoadSettings().ResourceCollectionMultiplyPercent)) - PUsCollectedLastTurn;
            if (PUChange > 0)
            {
                final int newPUs = player.getResources().getQuantity(GlobalCenter.GetPUResource()) + PUChange;

                final String message = ai.getName() + " use an RCM cheat, and increase their PUs from " + player.getResources().getQuantity(GlobalCenter.GetPUResource()) + " to " + newPUs;
                DUtils.Log(Level.FINE, message);
                Runnable runner = new Runnable()
                {public void run(){CachedInstanceCenter.CachedDelegateBridge.getHistoryWriter().startEvent(message);}};
                try{SwingUtilities.invokeAndWait(runner);}
                catch (InterruptedException ex){}
                catch (InvocationTargetException ex){}

                Change change = ChangeFactory.changeResourcesChange(player, GlobalCenter.GetPUResource(), PUChange);
                //data.getHistory().getHistoryWriter().addChange(change);
                //new ChangePerformer(data).perform(change);
                CachedInstanceCenter.CachedDelegateBridge.addChange(change);
                PUsToSpend = newPUs;
                Dynamix_AI.Pause();
            }
        }
        if(purchaseForBid)
        {
            if (DUtils.CanPlayerPlaceAnywhere(data, player) && PUsToSpend == 0)
            {
                DUtils.Log(Level.FINE, "  On this map, 'Place-Anywhere' countries can't get bid's, so skipping phase.");
                return;
            }

            DUtils.Log(Level.FINE, "  Purchasing bid factory repairs.");
            PUsToSpend = PUsToSpend - purchaseFactoryRepairs(ai, purchaseForBid, PUsToSpend, purchaser, data, player);

            Territory ourCap = TerritoryAttachment.getCapital(player, data);
            if (DMatches.territoryIsNotIsolated(data).match(ourCap))
            {
                FactoryCenter.get(data, player).ChosenFactoryTerritories.add(ourCap);
                DUtils.Log(Level.FINE, "  Purchasing bid units and going to place them all on: {0}", ourCap);
                int cost = purchaseFactoryUnits(ourCap, ai, purchaseForBid, PUsToSpend, purchaser, data, player);
                if (cost != 0)
                    PUsToSpend = PUsToSpend - cost;
                Dynamix_AI.Pause();
            }
            else
            {
                for(Territory ter : Match.getMatches(data.getMap().getTerritories(), DMatches.territoryIsNotIsolated(data)))
                {
                    FactoryCenter.get(data, player).ChosenFactoryTerritories.add(ter);
                    DUtils.Log(Level.FINE, "  Purchasing bid units and going to place them all on: {0}", ter);
                    int cost = purchaseFactoryUnits(ter, ai, purchaseForBid, PUsToSpend, purchaser, data, player);
                    if (cost != 0)
                        PUsToSpend = PUsToSpend - cost;
                    Dynamix_AI.Pause();
                    break;
                }
            }
        }
        else
        {
            if (DUtils.CanPlayerPlaceAnywhere(data, player) && PUsToSpend == 0)
            {
                DUtils.Log(Level.FINE, "  On this map, 'Place-Anywhere' countries can't purchase their units(engine does it for them), so skipping phase.");
                return;
            }

            int origR = player.getResources().getQuantity(data.getResourceList().getResource(Constants.PUS));
            calculateFactoriesToBuildOn(ai, purchaseForBid, data, player);
            DUtils.Log(Level.FINE, "  Factories to build on calculated. Ters with the factories: {0}", FactoryCenter.get(data, player).ChosenFactoryTerritories);

            PUsToSpend = PUsToSpend - purchaseFactoryRepairs(ai, purchaseForBid, PUsToSpend, purchaser, data, player);
            
            DUtils.Log(Level.FINE, "  Beginning purchases for factories phase.");
            for (Territory factoryTer : FactoryCenter.get(data, player).ChosenFactoryTerritories)
            {
                int cost = purchaseFactoryUnits(factoryTer, ai, purchaseForBid, PUsToSpend, purchaser, data, player);
                if (cost != 0)
                    PUsToSpend = PUsToSpend - cost;
                else
                    break;
                Dynamix_AI.Pause();
            }
            float percentageOfInitialPUsNeededForFactoryPurchase = DUtils.ToFloat(DSettings.LoadSettings().AA_resourcePercentageThatMustExistForFactoryBuy);
            if (PUsToSpend > (int)(origR * percentageOfInitialPUsNeededForFactoryPurchase)) //If we used less than X% our money (user set)
            {
                DUtils.Log(Level.FINE, "  We used less than " + DSettings.LoadSettings().AA_resourcePercentageThatMustExistForFactoryBuy + "% our money in purchases, so attempting to purchase a new factory.");
                Unit factory = null;
                int factoryCost = 0;
                for (ProductionRule rule : player.getProductionFrontier().getRules())
                {
                    if (UnitAttachment.get((UnitType) rule.getResults().keySet().toArray()[0]).isFactory())
                    {
                        factory = ((UnitType) rule.getResults().keySet().toArray()[0]).create(player);
                        factoryCost = rule.getCosts().getInt(data.getResourceList().getResource(Constants.PUS));
                        break;
                    }
                }
                if (factory != null && factoryCost <= PUsToSpend)
                {
                    boolean foundSafeBuildTer = false;
                    for (Territory ter : data.getMap().getTerritoriesOwnedBy(player))
                    {
                        if(ter.isWater())
                            continue;
                        if (ter.getUnits().someMatch(Matches.UnitIsFactory)) //It already has a factory
                            continue;
                        List<Unit> attackers = DUtils.GetSPNNEnemyWithLUnitsThatCanReach(data, ter, player, Matches.TerritoryIsLandOrWater);
                        List<Unit> defenders = new ArrayList<Unit>(ter.getUnits().getUnits());
                        AggregateResults results = DUtils.GetBattleResults(attackers, defenders, ter, data, 250, true);

                        if(results.getAttackerWinPercent() < .1F || attackers.isEmpty())
                            foundSafeBuildTer = true;
                    }
                    if (foundSafeBuildTer)
                    {
                        PurchaseGroup factoryPG = new PurchaseGroup(factory, purchaser, data, player); //Then buy a factory
                        factoryPG.Purchase();
                        FactoryCenter.get(data, player).FactoryPurchaseGroups.add(factoryPG);
                        Dynamix_AI.Pause();
                        DUtils.Log(Level.FINE, "    Factory purchased, location not yet determined.");
                    }
                }
            }
        }
    }

    public static int purchaseFactoryRepairs(Dynamix_AI ai, boolean purchaseForBid, int PUsToSpend, IPurchaseDelegate purchaser, GameData data, PlayerID player)
    {
        int origPUs = PUsToSpend;

        if (player.getRepairFrontier() != null) //Figure out if anything needs to be repaired
        {
            Territory ourCap = TerritoryAttachment.getCapital(player, data);
            List<Territory> ourTers = new ArrayList<Territory>(data.getMap().getTerritoriesOwnedBy(player));
            ourTers = DSorting.SortTerritoriesByLandThenNoCondDistance_A(ourTers, data, ourCap); //We want to repair the factories close to our capital first

            List<RepairRule> rrules = player.getRepairFrontier().getRules();
            HashMap<Unit, IntegerMap<RepairRule>> factoryRepairs = new HashMap<Unit, IntegerMap<RepairRule>>();
            int totalRepairCosts = 0;

            boolean madeRepairs = false;
            int maxPUsWeWantToSpendOnRepairs = origPUs / 2;
            boolean bombingDoneOnTerritories = Properties.getSBRAffectsUnitProduction(data);
            boolean bombingDoneOnUnitsDirectly = Properties.getDamageFromBombingDoneToUnitsInsteadOfTerritories(data);

            for (RepairRule rrule : rrules)
            {
                for (Territory fixTerr : ourTers)
                {
                    if (!Matches.territoryHasOwnedFactory(data, player).match(fixTerr))
                        continue;

                    TerritoryAttachment ta = TerritoryAttachment.get(fixTerr);
                    if (bombingDoneOnTerritories)
                    {
                        int repairAmount = ta.getProduction() - ta.getUnitProduction(); //Don't repair more of the factory than was damaged!
                        repairAmount = Math.min(repairAmount, origPUs / 4); //Never spend more than one-fourth of all the player's money on a factory repair
                        repairAmount = Math.min(repairAmount, maxPUsWeWantToSpendOnRepairs - totalRepairCosts); //Don't let the total repair costs equal more than the 'total max spend' amount that was set earlier to half of total PUs
                        repairAmount = Math.min(repairAmount, PUsToSpend); //Don't spend more PUs than we have!

                        if (repairAmount > 0)
                        {
                            DUtils.Log(Level.FINER, "    Purchasing repairs for a territory. Territory: {0} Repair Amount: {1}", fixTerr.getName(), repairAmount);
                            IntegerMap<RepairRule> repairMap = new IntegerMap<RepairRule>();
                            repairMap.add(rrule, repairAmount);
                            factoryRepairs.put(Match.getMatches(fixTerr.getUnits().getUnits(), Matches.UnitIsFactoryOrCanBeDamaged).iterator().next(), repairMap);
                            madeRepairs = true;
                            PUsToSpend -= repairAmount;
                            totalRepairCosts += repairAmount;
                        }
                    }
                    else if (bombingDoneOnUnitsDirectly) //If bombing in this map is done to units instead of territories, we figure repair amount based on [max - current] unit production capacity
                    {
                        for (Unit unitToFix : Match.getMatches(fixTerr.getUnits().getUnits(), Matches.UnitIsFactoryOrCanBeDamaged))
                        {
                        	if (unitToFix == null || !unitToFix.getType().equals((UnitType) rrule.getResults().keySet().iterator().next()))
    							continue;
                            int repairAmount = TripleAUnit.getHowMuchCanUnitProduce(unitToFix, fixTerr, player, data, false) - TripleAUnit.getHowMuchCanUnitProduce(unitToFix, fixTerr, player, data, true);
                            repairAmount = Math.min(repairAmount, origPUs / 4); //Never spend more than one-fourth of all the player's money on a factory repair
                            repairAmount = Math.min(repairAmount, maxPUsWeWantToSpendOnRepairs - totalRepairCosts); //Don't let the total repair costs equal more than the 'total max spend' amount that was set earlier to half of total PUs
                            repairAmount = Math.min(repairAmount, PUsToSpend); //Don't spend more PUs than we have!

                            if (repairAmount > 0)
                            {
                                DUtils.Log(Level.FINER, "    Purchasing repairs for a unit. Territory: {0} Unit: {1} Repair Amount: {2}", fixTerr.getName(), unitToFix, repairAmount);
                                IntegerMap<RepairRule> repairMap = new IntegerMap<RepairRule>();
                                repairMap.add(rrule, repairAmount);
                                factoryRepairs.put(unitToFix, repairMap);
                                madeRepairs = true;
                                PUsToSpend -= repairAmount;
                                totalRepairCosts += repairAmount;
                            }
                        }
                    }
                }
            }
            if (madeRepairs)
            {
                purchaser.purchaseRepair(factoryRepairs);
                Dynamix_AI.Pause();
                return totalRepairCosts;
            }
        }
        return 0;
    }

    public static void calculateFactoriesToBuildOn(Dynamix_AI ai, boolean purchaseForBid, GameData data, PlayerID player)
    {
        List<Territory> sortedLocations = Purchase_UnitPlacementLocationSorter.CalculateAndSortUnitPlacementLocations(ai, purchaseForBid, data, player);
        FactoryCenter.get(data, player).ChosenFactoryTerritories = sortedLocations;
    }

    public static int purchaseFactoryUnits(Territory ter, Dynamix_AI ai, boolean purchaseForBid, int PUsToSpend, IPurchaseDelegate purchaser, GameData data, PlayerID player)
    {
        DUtils.Log(Level.FINER, "    Purchasing units for territory. Ter: {0}", ter);
        int result = 0;
        if (!ter.isWater() && ter.getOwner().getName().equals(player.getName()) && ter.getUnits().someMatch(Matches.UnitIsFactory) && !ter.getUnits().someMatch(Matches.UnitIsAA) && TerritoryAttachment.get(ter) != null && DUtils.GetCheckedUnitProduction(ter) > 0)
        {
            Unit aa = null;
            for (ProductionRule rule : player.getProductionFrontier().getRules())
            {
                if (UnitAttachment.get((UnitType) rule.getResults().keySet().toArray()[0]).isAA())
                {
                    aa = ((UnitType) rule.getResults().keySet().toArray()[0]).create(player);
                    break;
                }
            }
            if (aa != null)
            {
                PurchaseGroup aaPG = new PurchaseGroup(aa, purchaser, data, player);
                int cost = aaPG.GetCost();
                if (cost > 0 && PUsToSpend - cost >= 0)
                {
                    aaPG.Purchase();
                    result += cost;
                    PUsToSpend -= cost;
                    FactoryCenter.get(data, player).ChosenAAPlaceTerritories.add(ter);
                }
            }
        }

        PurchaseGroup bestPurchaseGroup = CalculateBestPurchaseGroup(ter, data, player, purchaser, PUsToSpend, purchaseForBid);
        if(bestPurchaseGroup == null)
            return result;
        int testCost = bestPurchaseGroup.GetCost();
        if (testCost < 1) //Temporary work-around
            return result;
        
        int maxPurchaseCost = PUsToSpend;
        int maxPurchaseCount = DUtils.GetCheckedUnitProduction(ter);
        if(FactoryCenter.get(data, player).ChosenAAPlaceTerritories.contains(ter)) //If we're going to build an AA here
            maxPurchaseCount--;
        if (purchaseForBid)
            maxPurchaseCount = Integer.MAX_VALUE;
        if (DUtils.CanPlayerPlaceAnywhere(data, player))
            maxPurchaseCount = Integer.MAX_VALUE;
        bestPurchaseGroup.ApplyMaxValues(maxPurchaseCost, maxPurchaseCount);
        int cost = bestPurchaseGroup.GetCost();
        if (PUsToSpend - cost >= 0) //If we have enough money to buy this purchase group
        {           
            bestPurchaseGroup.Purchase();            
            FactoryCenter.get(data, player).TurnTerritoryPurchaseGroups.put(ter, bestPurchaseGroup);
            result += cost;
        }
        
        return result;
    }

    private static PurchaseGroup CalculateBestPurchaseGroup(Territory ter, GameData data, PlayerID player, IPurchaseDelegate purchaser, float PUsLeftToSpend, boolean purchaseForBid)
    {
        Territory ncmTarget = NCM_TargetCalculator.CalculateNCMTargetForTerritory(data, player, ter, DUtils.ToList(ter.getUnits().getUnits()), new ArrayList<NCM_Task>());
        if(ncmTarget == null) //No ncm target, so buy random units
        {
            if (data.getMap().getNeighbors(ter, DUtils.CompMatchAnd(Matches.TerritoryIsWater, Matches.territoryHasUnitsThatMatch(Matches.unitIsEnemyOf(data, player)).invert())).size() > 0) //Has a safe port
                return new PurchaseGroup(Collections.singleton(DUtils.GetRandomUnitForPlayerMatching(player, DUtils.CompMatchAnd(Matches.UnitIsNotAA, Matches.UnitIsNotFactory))), purchaser, data, player);
            else
                return new PurchaseGroup(Collections.singleton(DUtils.GetRandomUnitForPlayerMatching(player, DUtils.CompMatchAnd(Matches.UnitIsLand, Matches.UnitIsNotAA, Matches.UnitIsNotFactory))), purchaser, data, player);
        }

        Integer productionSpaceLeft = DUtils.GetCheckedUnitProduction(ter);
        if(FactoryCenter.get(data, player).ChosenAAPlaceTerritories.contains(ter)) //If we're going to build an AA here
            productionSpaceLeft--;
        if (purchaseForBid)
            productionSpaceLeft = Integer.MAX_VALUE;
        if (DUtils.CanPlayerPlaceAnywhere(data, player))
            productionSpaceLeft = Integer.MAX_VALUE;
        if(productionSpaceLeft <= 0)
            return null;

        HashSet<Unit> unitsOnTheWay = new HashSet<Unit>();
        List<Route> routes = DUtils.GetXClosestSimiliarLengthLandRoutesBetweenTers(data, 5, ter, ncmTarget);
        for(Route route : routes)
        {
            for (Territory rTer : route.getTerritories())
            {
                List<Unit> ourUnits = Match.getMatches(rTer.getUnits().getUnits(), Matches.unitIsOwnedBy(player));
                unitsOnTheWay.addAll(ourUnits);
            }
        }

        List<Unit> allUnits = new ArrayList<Unit>();
        List<ProductionRule> rules = player.getProductionFrontier().getRules();
        for(ProductionRule rule : rules)
        {
            UnitType ut = ((UnitType) rule.getResults().keySet().toArray()[0]);
            Unit unit = ut.create(player);
            allUnits.add(unit);
        }

        List<Unit> defendUnits = new ArrayList<Unit>(ncmTarget.getUnits().getUnits());

        List<Unit> unitsToBuy = new ArrayList<Unit>();
        for (int i = 0; i < Math.min(productionSpaceLeft, DSettings.LoadSettings().AA_maxUnitTypesForPurchaseMix); i++) //Do X(user-set) different unit types at most because we dont want this to take too long
        {
            Unit unit = DUtils.CalculateUnitThatWillHelpWinAttackOnArmyTheMostPerPU(ncmTarget, data, player, unitsOnTheWay, allUnits, defendUnits, Matches.UnitHasEnoughMovement(1), DSettings.LoadSettings().CA_Purchase_determinesUnitThatWouldHelpTargetInvasionMost);
            if(unit == null)
            {
                DUtils.Log(Level.FINER, "        No units found to select for purchasing!");
                return null;
            }
            unit = unit.getType().create(player); //Don't add the actual unit we created before, otherwise if we purchase the same unit type twice, we will end up doing calc's with multiples of the same unit, which is bad

            int cost = DUtils.GetTUVOfUnit(unit, GlobalCenter.GetPUResource());            
            if (PUsLeftToSpend - cost < 0) //If buying this unit will put us under
                break;

            PUsLeftToSpend -= cost;
            if (unit == null)
            {
                i--;
                continue;
            }
            unitsToBuy.add(unit);
            unitsOnTheWay.add(unit);
            if(unitsToBuy.size() >= productionSpaceLeft) //If we've already bought the most we can fit on this territory
                break;
            if(DSettings.LoadSettings().AllowCalcingDecrease && Dynamix_AI.GetTimeTillNextScheduledActionDisplay() == 0) //If we're taking longer than the user wanted...
                break; //Don't calculate more units to add to the mix...
        }
        if(unitsToBuy.isEmpty())
            return null;
        return new PurchaseGroup(unitsToBuy, purchaser, data, player);
    }
}
