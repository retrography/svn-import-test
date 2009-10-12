package games.strategy.triplea.strongAI;

/*
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

import games.strategy.engine.data.*;
import games.strategy.engine.gamePlayer.*;
import games.strategy.net.GUID;
import games.strategy.triplea.Constants;
import games.strategy.triplea.TripleAUnit;
import games.strategy.triplea.attatchments.*;
import games.strategy.triplea.baseAI.*;
import games.strategy.triplea.delegate.*;
import games.strategy.triplea.delegate.dataObjects.*;
import games.strategy.triplea.delegate.remote.*;
import games.strategy.triplea.player.ITripleaPlayer;
import games.strategy.triplea.Properties;
import games.strategy.util.*;

import java.util.*;
import java.util.logging.Logger;

/*
 *
 * A stronger AI, based on some additional complexity, used the weak AI as a blueprint.<p>
 * This still needs work. Known Issues:
 *    1) *fixed* if a group of fighters on water needs 1 to land on island, it doesn't...works correctly if there is only 1 fighter
 *    2) if Germany is winning, it doesn't switch to buying up transports
 *    3) if a transport is at an owned territory with a factory, it won't leave unless it has units
 *    4) Germany doesn't guard the shoreline well
 *    5) Ships are moving 1 territory too close to a large pack of ships (better analysis)
 *    6) Ships make themselves vulnerable to plane attack
 *    7) No submerging or retreating has been implemented
 *    8) Planes still occasionally stop in an unoccupied territory which is open to invasion with cheap units
 *    9) Need to analyze 1 territory further and delay attack if it brings a set of units under overwhelming odds
 *   10) Units are still loaded onto transports from Southern Europe/Western US even when enemy forces are in neighboring terr
 *   11) Still putting transport on a factory that is closest to enemy cap
 *   12) Transports should scope out available units and go to them
 *   13) When allied forces are nearby, occasionally attacks are with deficient forces
 *   14) Occasionally air is attacking large units without accompanying land forces
 *   15) AI needs to protect countries nearest its capital
 *   16) AI allows England to be invaded because purchases that focus on navy are not buying any infantry
 *   17) AI needs to keep fighters home when attackers are near the capital
 *
 *
 * @author Kevin Moore
 *         2008-2009
 */
public class StrongAI extends AbstractAI implements IGamePlayer, ITripleaPlayer
{

    private final static Logger s_logger = Logger.getLogger(StrongAI.class.getName());
    private Territory m_factTerr = null, m_seaTerr = null; //determine the target Territory during Purchase and Save it
    private boolean m_AE = false, m_transports_may_die = true, m_zero_combat_attack = true, m_cap_danger = false, m_natObjective = false;
    private boolean m_bought_Attack_Ships = false, m_keep_Ships_At_Base = false;

    /** Creates new TripleAPlayer */
    public StrongAI(String name)
    {
        super(name);

    }

	private void getEdition()
	{
		final GameData data = getPlayerBridge().getGameData();
		m_AE = games.strategy.triplea.Properties.getWW2V3(data);
		m_transports_may_die = !games.strategy.triplea.Properties.getTransportCasualtiesRestricted(data);
		m_zero_combat_attack = games.strategy.triplea.Properties.getHariKariUnits(data);
		m_natObjective = games.strategy.triplea.Properties.getNationalObjectives(data);
	}
/*
	private void setBattleTerritory(Territory bTerr)
	{
		m_battleTerr = bTerr;
	}
	
	private Territory getBattleTerritory()
	{
		return m_battleTerr;
	}
*/	
	private boolean transportsMayDieFirst()
	{
		return m_transports_may_die;
	}

	private void setAttackShipPurchase(boolean doBuyAttackShip)
	{
		m_bought_Attack_Ships = doBuyAttackShip;
	}
	
	private boolean getAttackShipPurchase()
	{
		return m_bought_Attack_Ships;
	}
	
	private void setSeaTerr(Territory seaTerr)
	{
		m_seaTerr = seaTerr;
	}
	
	private Territory getSeaTerr()
	{
		return m_seaTerr;
	}
	
	private void setKeepShipsAtBase(boolean keep)
	{
		m_keep_Ships_At_Base = keep;
	}
	
	private boolean getKeepShipsAtBase()
	{
		return m_keep_Ships_At_Base;
	}
	
	private boolean useProductionData()
	{
		return m_AE;
	}

	private void setFactory(Territory t)
	{
		m_factTerr = t;
	}

	private Territory getFactory()
	{
		return m_factTerr;
	}

	private void setCapDanger(Boolean danger)
	{
		m_cap_danger = danger; //use this to track when determined the capital is in danger
	}

	private boolean getCapDanger()
	{
		return m_cap_danger;
	}

	private boolean getNationalObjectives()
	{
		return m_natObjective;
	}

    protected void tech(ITechDelegate techDelegate, GameData data, PlayerID player)
    {}

    private Route getAmphibRoute(final PlayerID player)
    {
        if(!isAmphibAttack(player))
            return null;

        final GameData data = getPlayerBridge().getGameData();

        Territory ourCapitol = TerritoryAttachment.getCapital(player, data);
        Match<Territory> endMatch = new Match<Territory>()
        {
            @Override
            public boolean match(Territory o)
            {
                boolean impassable = TerritoryAttachment.get(o) != null &&  TerritoryAttachment.get(o) .isImpassible();
                return  !impassable && !o.isWater() && SUtils.hasLandRouteToEnemyOwnedCapitol(o, player, data);
            }

        };

        Match<Territory> routeCond = new CompositeMatchAnd<Territory>(Matches.TerritoryIsWater, Matches.territoryHasNoEnemyUnits(player, data));

        Route withNoEnemy = SUtils.findNearest(ourCapitol, endMatch, routeCond, getPlayerBridge().getGameData());
        if(withNoEnemy != null && withNoEnemy.getLength() > 0)
            return withNoEnemy;

        //this will fail if our capitol is not next to water, c'est la vie.
        Route route =  SUtils.findNearest(ourCapitol, endMatch, Matches.TerritoryIsWater, getPlayerBridge().getGameData());
        if(route != null && route.getLength() == 0) {
            return null;
        }
        return route;
    }

    private boolean isAmphibAttack(PlayerID player)
    {
        Territory capitol = TerritoryAttachment.getCapital(player, getPlayerBridge().getGameData());
        //we dont own our own capitol
        if(capitol == null || !capitol.getOwner().equals(player))
            return false;

        //find a land route to an enemy territory from our capitol
        Route invasionRoute = SUtils.findNearest(capitol, Matches.isTerritoryEnemyAndNotNeutral(player, getPlayerBridge().getGameData()),
                new CompositeMatchAnd<Territory> (Matches.TerritoryIsLand,new InverseMatch<Territory>(Matches.TerritoryIsNeutral)),
                getPlayerBridge().getGameData());

        return invasionRoute == null;
    }

    //determine danger to the capital and set the capdanger variable...other routines can just get the CapDanger var
    //should be called prior to combat, non-combat and purchasing units to assess the current situation
    private void determineCapDanger(PlayerID player, GameData data, float ourStrength, float enemyStrength)
    {
    	float threatFactor = 1.00F;
    	boolean capDanger = false;
        Territory myCapital = TerritoryAttachment.getCapital(player, data);
  	
        boolean tFirst = transportsMayDieFirst(); 
		enemyStrength = SUtils.getStrengthOfPotentialAttackers(myCapital, data, player, tFirst, false);
		ourStrength = SUtils.strength(myCapital.getUnits().getMatches(Matches.alliedUnit(player, data)), false, false, tFirst);
		ourStrength += 5.0F; //assume new units on the way...3 infantry ~= 8.1F

		if (enemyStrength > ourStrength*threatFactor) //we are in trouble
			capDanger = true;

		setCapDanger(capDanger); //save this for unit placement
    }
    
    protected void move(boolean nonCombat, IMoveDelegate moveDel, GameData data, PlayerID player)
    {
        if(nonCombat)
            doNonCombatMove(moveDel, player);
        else
            doCombatMove(moveDel, player);

//        pause();
    }

    private void doNonCombatMove(IMoveDelegate moveDel, PlayerID player)
    {
        float ourCapStrength = 0.0F, enemyCapStrength = 0.0F;
        GameData data = getPlayerBridge().getGameData();
        determineCapDanger(player, data, ourCapStrength, enemyCapStrength);

        List<Collection<Unit>> moveUnits = new ArrayList<Collection<Unit>>();
        List<Route> moveRoutes = new ArrayList<Route>();
        List<Collection<Unit>> transportsToLoad = new ArrayList<Collection<Unit>>();

        populateTransportLoad(true, data, moveUnits, moveRoutes, transportsToLoad, player);
        doMove(moveUnits, moveRoutes, transportsToLoad, moveDel);
        moveRoutes.clear();
        moveUnits.clear();
        transportsToLoad.clear();

        protectOurAllies(true, data, moveUnits, moveRoutes, player);
        doMove(moveUnits, moveRoutes, null, moveDel);
        moveRoutes.clear();
        moveUnits.clear();

        populateNonCombatSea(true, data, moveUnits, moveRoutes, player);
        doMove(moveUnits, moveRoutes, null, moveDel);
        moveUnits.clear();
        moveRoutes.clear();

        determineCapDanger(player, data, ourCapStrength, enemyCapStrength);

        populateNonComTransportMove(data, moveUnits, moveRoutes, player);
		doMove(moveUnits, moveRoutes, null, moveDel);
		moveRoutes.clear();
		moveUnits.clear();

		nonCombatPlanes(data, player, moveUnits, moveRoutes);
		doMove(moveUnits, moveRoutes, null, moveDel);
        moveUnits.clear();
        moveRoutes.clear();

        stopBlitzAttack(data, moveUnits, moveRoutes, player);
        doMove(moveUnits, moveRoutes, null, moveDel);
        moveUnits.clear();
        moveRoutes.clear();

        populateNonCombat(data, moveUnits, moveRoutes, player);
        doMove(moveUnits, moveRoutes, null, moveDel);
        moveUnits.clear();
        moveRoutes.clear();

        movePlanesHomeNonCom(moveUnits, moveRoutes, player, data); //combine this with checkPlanes at some point
        doMove(moveUnits, moveRoutes, null, moveDel);
        moveUnits.clear();
        moveRoutes.clear();

        //check to see if we have vulnerable planes
        CheckPlanes(data, moveUnits, moveRoutes, player);
        doMove(moveUnits, moveRoutes, null, moveDel);
        moveUnits.clear();
        moveRoutes.clear();
        transportsToLoad.clear();

        //check to see if we have missed any transports
        checkUnmovedTransports(data, moveUnits, moveRoutes, player);
        doMove(moveUnits, moveRoutes, null, moveDel);
        moveUnits.clear();
        moveRoutes.clear();

        populateTransportLoad(true, data, moveUnits, moveRoutes, transportsToLoad, player); //another pass on loading
        doMove(moveUnits, moveRoutes, transportsToLoad, moveDel);
        moveRoutes.clear();
        moveUnits.clear();
        transportsToLoad.clear();

        //unload the transports that can be unloaded
        populateTransportUnloadNonCom( true, data, moveUnits, moveRoutes, player);
        doMove(moveUnits, moveRoutes, null, moveDel);
    }


    private void doCombatMove(IMoveDelegate moveDel, PlayerID player)
    {
        GameData data = getPlayerBridge().getGameData();
        getEdition();

        List<Collection<Unit>> moveUnits = new ArrayList<Collection<Unit>>();
        List<Route> moveRoutes = new ArrayList<Route>();
        List<Collection<Unit>> transportsToLoad = new ArrayList<Collection<Unit>>();
        float ourCapStrength = 0.0F, enemyCapStrength = 0.0F;
        determineCapDanger(player, data, ourCapStrength, enemyCapStrength);

        //let sea battles occur before we load transports
        populateCombatMoveSea(data, moveUnits, moveRoutes, player);
        doMove(moveUnits, moveRoutes, null, moveDel);
        moveUnits.clear();
        moveRoutes.clear();

        populateTransportLoad(false, data, moveUnits, moveRoutes, transportsToLoad, player);
        doMove(moveUnits, moveRoutes, transportsToLoad, moveDel);
		moveUnits.clear();
		moveRoutes.clear();

		protectOurAllies(true, data, moveUnits, moveRoutes, player);
        doMove(moveUnits, moveRoutes, null, moveDel);
        moveRoutes.clear();
        moveUnits.clear();

		populateTransportMove(false, data, moveUnits, moveRoutes, player);
		doMove(moveUnits, moveRoutes, null, moveDel);
		moveRoutes.clear();
		moveUnits.clear();

		populateTransportUnload(data, moveUnits, moveRoutes, player);
		doMove(moveUnits, moveRoutes, null, moveDel);
        moveRoutes.clear();
        moveUnits.clear();

        //find second amphib target
        Route altRoute = getAlternativeAmphibRoute( player);
        if(altRoute != null)
           	moveCombatSea( data, moveUnits, moveRoutes, player, altRoute, 1);

        doMove(moveUnits, moveRoutes, null, moveDel);
        moveUnits.clear();
        moveRoutes.clear();
        transportsToLoad.clear();

        //we want to move loaded transports before we try to fight our battles
        populateNonCombatSea(false, data, moveUnits, moveRoutes, player);
		doMove(moveUnits, moveRoutes, null, moveDel);
        moveRoutes.clear();
        moveUnits.clear();

		populateTransportUnload(data, moveUnits, moveRoutes, player);
		doMove(moveUnits, moveRoutes, null, moveDel);
		moveUnits.clear();
		moveRoutes.clear();

        populateCombatMove(data, moveUnits, moveRoutes, player, ourCapStrength, enemyCapStrength);
        doMove(moveUnits, moveRoutes, null, moveDel);
		moveUnits.clear();
		moveRoutes.clear();

        //any planes left for an overwhelming attack?
//        specialPlaneAttack(data, moveUnits, moveRoutes, player);
//        doMove(moveUnits, moveRoutes, null, moveDel);

    }

    protected void battle(IBattleDelegate battleDelegate, GameData data, PlayerID player)
    {
        //generally all AI's will follow the same logic.  
        
        //loop until all battles are fought.
        //rather than try to analyze battles to figure out which must be fought before others
        //as in the case of a naval battle preceding an amphibious attack,
        //keep trying to fight every battle
        while (true)
        {
    
            BattleListing listing = battleDelegate.getBattles();

            //all fought
            if(listing.getBattles().isEmpty() && listing.getStrategicRaids().isEmpty())
                return;
            
            Iterator<Territory> raidBattles = listing.getStrategicRaids().iterator();

            //fight strategic bombing raids
            while(raidBattles.hasNext())
            {
                Territory current = raidBattles.next();
                String error = battleDelegate.fightBattle(current, true);
                if(error != null)
                    s_logger.fine(error);
            }
            
            
            Iterator<Territory> nonRaidBattles = listing.getBattles().iterator();

            //fight normal battles
            while(nonRaidBattles.hasNext())
            {
                Territory current = nonRaidBattles.next();
//                setBattleTerritory(current);
                String error = battleDelegate.fightBattle(current, false);
                if(error != null)
                    s_logger.fine(error);
            }
//            setBattleTerritory(null);
        }
    }

    private void checkUnmovedTransports(GameData data, List<Collection<Unit>> moveUnits, List<Route> moveRoutes, PlayerID player)
    {
        TransportTracker tracker = DelegateFinder.moveDelegate(data).getTransportTracker();
		//Simply: Move Transports Back Toward a Factory
		CompositeMatch<Unit> transUnit = new CompositeMatchAnd<Unit>(Matches.UnitIsTransport);
		CompositeMatch<Unit> ourTransUnit = new CompositeMatchAnd<Unit>(transUnit, Matches.unitIsOwnedBy(player), Matches.transportIsNotTransporting(), HasntMoved);
		List<Territory> transTerr = SUtils.findCertainShips(data, player, ourTransUnit);
		List<Territory> ourFactories = SUtils.findCertainShips(data, player, Matches.UnitIsFactory);
		List<Territory> ourSeaSpots = new ArrayList<Territory>();
		CompositeMatch<Unit> ourLandUnit = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player), Matches.UnitIsLand, Matches.UnitIsNotFactory);
		CompositeMatch<Unit> escortUnit1 = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player), Matches.UnitIsDestroyer);
		CompositeMatch<Unit> escortUnit2 = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player), Matches.UnitIsTwoHit);
		CompositeMatch<Unit> escortUnit = new CompositeMatchOr<Unit>(escortUnit1, escortUnit2);
		List<Unit> alreadyMoved = new ArrayList<Unit>();
		/**
		 * First determine if attack ships have been purchased and limit moves at that factory
		 */
		markBaseShips(data, player, alreadyMoved);

		Route amphibRoute = getAmphibRoute(player);
		Territory firstSeaZoneOnAmphib = null;
		Territory lastSeaZoneOnAmphib = null;
		if (amphibRoute != null)
		{
			firstSeaZoneOnAmphib = amphibRoute.getStart();
			lastSeaZoneOnAmphib = amphibRoute.getTerritories().get(amphibRoute.getLength() - 1);
		}

		if (lastSeaZoneOnAmphib == null)
			return;
		IntegerMap <Territory> ourFacts = new IntegerMap<Territory>();
		HashMap <Territory, Territory> connectTerr = new HashMap<Territory, Territory>();
		for (Territory xT : ourFactories)
		{
			int numUnits = xT.getUnits().getMatches(ourLandUnit).size() + TerritoryAttachment.get(xT).getProduction();
			Set<Territory> factNeighbors = data.getMap().getNeighbors(xT, Matches.TerritoryIsWater);
			ourFacts.put(xT, numUnits);
			for (Territory factTest : factNeighbors)
				connectTerr.put(factTest, xT);
			ourSeaSpots.addAll(factNeighbors);
		}
		//check for locations with units and no threat or real way to use them
		List<Territory> allTerr = SUtils.allAlliedTerritories(data, player);
		allTerr.removeAll(ourFactories);
		List<Territory> otherNonSeaSpots = new ArrayList<Territory>();
		for (Territory xT2 : allTerr)
		{
			boolean hasWater = SUtils.isWaterAt(xT2, data);
			Route nearestRoute = SUtils.findNearest(xT2, Matches.territoryHasEnemyUnits(player, data), Matches.isTerritoryAllied(player, data), data);
			if (hasWater && (nearestRoute == null || nearestRoute.getLength() > 4 || nearestRoute.crossesWater())) //bad guys are far away...
				otherNonSeaSpots.add(xT2);
		}

		int minDist = 100;
		Territory closestT = null;
		for (Territory t : transTerr)
		{
			List<Unit> ourLandUnits = t.getUnits().getMatches(ourLandUnit);
			List<Unit> specUnits = new ArrayList<Unit>();
			List<Unit> ourTransports = t.getUnits().getMatches(ourTransUnit);
			ourTransports.removeAll(alreadyMoved);
			if (ourTransports.size() == 0)
				continue;
/*			if (ourLandUnits.size() > 0) //we have loaded transports which have not been moved
			{
				Route loadedRoute = SUtils.getMaxSeaRoute(data, t, lastSeaZoneOnAmphib, player);
				specUnits.addAll(ourLandUnits);
				specUnits.addAll(ourTransports);
				List<Unit> escortUnits = t.getUnits().getMatches(escortUnit);
				if (escortUnits.size() > 0)
				{
					moveUnits.add(escortUnits);
					moveRoutes.add(loadedRoute);
				}
				moveUnits.add(specUnits);
				moveRoutes.add(loadedRoute);
				continue;
			}
*/
			IntegerMap<Territory> distMap = new IntegerMap<Territory>();
			IntegerMap<Territory> unitsMap = new IntegerMap<Territory>(); 
			if (!ourSeaSpots.contains(t))
			{
				for (Territory t2 : ourSeaSpots)
				{
					int thisDist = data.getMap().getWaterDistance(t, t2);
					int numUnits = t2.getUnits().getMatches(ourLandUnit).size()+6; //assume new units on the factory
					distMap.put(t2, thisDist);
					unitsMap.put(t2, numUnits);
				}
			}
			for (Territory checkAnother : otherNonSeaSpots)
			{
				int thisDist = 0;
				int numUnits = checkAnother.getUnits().getMatches(ourLandUnit).size();
				Territory qTerr = SUtils.getClosestWaterTerr(checkAnother, t, thisDist, data, player);
				connectTerr.put(qTerr, checkAnother);
				distMap.put(qTerr, thisDist);
				unitsMap.put(qTerr, numUnits);
			}
			Set<Territory> allWaterTerr = distMap.keySet();
			int score = 0, bestScore = 0;
			for (Territory waterTerr : allWaterTerr)
			{
				//figure out the best Territory to send it to
				score = unitsMap.getInt(waterTerr) - distMap.getInt(waterTerr);
				int numTrans = waterTerr.getUnits().getMatches(ourTransUnit).size();
				if (waterTerr == t)
				{
					Territory landTerr = connectTerr.get(t);
					int moveNum = (numTrans*2 - unitsMap.getInt(waterTerr))/2;
					int score2 = 0, bestScore2 = 0;
					Territory newTerr = null;
					for (Territory waterTerr2 : allWaterTerr)
					{
						if (waterTerr2 == waterTerr || landTerr == connectTerr.get(waterTerr2))
							continue;
						score2 = unitsMap.getInt(waterTerr2)-distMap.getInt(waterTerr2);
						if (score2 > bestScore2)
						{
							bestScore2 = score2;
							newTerr = waterTerr2;
						}
					}
					Route goRoute = SUtils.getMaxSeaRoute(data, t, newTerr, player);
					if (goRoute == null)
						continue;
					List<Unit> tmpTrans = new ArrayList<Unit>();
					for (int i=0; i < Math.min(moveNum, numTrans); i++)
					{
						Unit trans2 = ourTransports.get(i);
						tmpTrans.add(trans2);
					}
					if (moveNum > 0)
					{
						moveUnits.add(tmpTrans);
						moveRoutes.add(goRoute);
						alreadyMoved.addAll(tmpTrans);
					}
					continue;
				}
				if (score > bestScore)
				{
					bestScore = score;
					closestT = waterTerr;
				}
			}
			if (closestT != null && t != closestT)
			{
				Route ourRoute = SUtils.getMaxSeaRoute(data, t, closestT, player);
				List<Unit> escortUnits = t.getUnits().getMatches(escortUnit);
				escortUnits.removeAll(alreadyMoved);
				if (escortUnits.size() > 0)
				{
					moveUnits.add(escortUnits);
					moveRoutes.add(ourRoute);
					alreadyMoved.addAll(escortUnits);
				}
				moveUnits.add(ourTransports);
				moveRoutes.add(ourRoute);
			}
			minDist = 100;
			closestT = null;
		}
	}

    private void populateTransportLoad(boolean nonCombat, GameData data,
    		List<Collection<Unit>> moveUnits, List<Route> moveRoutes, List<Collection<Unit>> transportsToLoad, PlayerID player)
    {

        TransportTracker tracker = DelegateFinder.moveDelegate(data).getTransportTracker();

		Route amphibRoute = getAmphibRoute(player);
		Territory firstSeaZoneOnAmphib = null;
		Territory lastSeaZoneOnAmphib = null;
		if (amphibRoute != null)
		{
			firstSeaZoneOnAmphib = amphibRoute.getTerritories().get(0);
			lastSeaZoneOnAmphib = amphibRoute.getTerritories().get(amphibRoute.getLength() - 1);
		}

        CompositeMatch<Unit> owned = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player));

        CompositeMatch<Unit> landUnit = new CompositeMatchAnd<Unit>(owned, Matches.UnitCanBeTransported, Matches.UnitIsNotAA, Matches.UnitIsNotFactory);
        CompositeMatch<Unit> transUnit = new CompositeMatchAnd<Unit>(owned, Matches.UnitIsTransport);
        CompositeMatch<Unit> factoryUnit = new CompositeMatchAnd<Unit>(owned, Matches.UnitIsFactory);
        List<Territory> myTerritories = SUtils.allAlliedTerritories(data, player);

        Territory capitol = TerritoryAttachment.getCapital(player, data);
        boolean ownMyCapitol = capitol.getOwner().equals(player);
        Unit transport = null;

		int badGuyDist = 0;
        //start at our factories
        List<Territory> factTerr = SUtils.findUnitTerr(data, player, factoryUnit);
        if (!factTerr.contains(capitol) && ownMyCapitol)
        	factTerr.add(capitol);
        List<Unit> transportsFilled = new ArrayList<Unit>();
        for (Territory factory : factTerr)
        {
			Set<Territory> myNeighbors = data.getMap().getNeighbors(factory, Matches.TerritoryIsWater);
			List<Unit> unitsTmp = factory.getUnits().getMatches(landUnit);
			List<Unit> unitsToLoad = SUtils.sortTransportUnits(unitsTmp);
			for (Territory seaFactTerr : myNeighbors)
			{
				List<Unit> transportUnits = seaFactTerr.getUnits().getMatches(transUnit);
				if (seaFactTerr.isWater() && transportUnits.size()>0)
				{
					List<Unit> units = new ArrayList<Unit>();
 		            List<Unit> finalTransUnits = new ArrayList<Unit>();
					int transCount = transportUnits.size();
					List<Unit> transCopy = new ArrayList<Unit>(transportUnits);
	            	for (int j=transCount-1; j >= 0; j--)
					{
						transport = transCopy.get(j);
						int free = tracker.getAvailableCapacity(transport);
						if (free <=0)
						{
							transportUnits.remove(j);
							continue;
						}
						Iterator<Unit> iter = unitsToLoad.iterator();
						boolean addOne = false;
						while(iter.hasNext() && free > 0)
						{
							Unit current = iter.next();
							UnitAttachment ua = UnitAttachment.get(current.getType());
							if (ua.isAir())
								continue;
							if (ua.getTransportCost() <= free)
							{
								iter.remove();
								free -= ua.getTransportCost();
								units.add(current);
								addOne = true;
							}
						}
    					if (addOne)
    					    finalTransUnits.add(transport);
					}
					if (units.size() > 0)
					{
						Route route = data.getMap().getRoute(factory, seaFactTerr);
						moveUnits.add(units);
						moveRoutes.add(route);
						transportsToLoad.add( finalTransUnits);
						transportsFilled.addAll( finalTransUnits);
						unitsToLoad.removeAll(units);
					}
				}
			}
		} //done with factories
		myTerritories.removeAll(factTerr);
		for (Territory checkThis : myTerritories)
		{
		   Route xRoute = null;
		   boolean landRoute = SUtils.landRouteToEnemyCapital(checkThis, xRoute, data, player);
		   boolean isLand = SUtils.doesLandExistAt(checkThis, data);
           List<Unit> unitsTmp = checkThis.getUnits().getMatches(landUnit);
           List<Unit> unitsToLoad = SUtils.sortTransportUnits(unitsTmp);
           if (unitsToLoad.size() == 0)
              continue;
           List<Territory> blockThese = new ArrayList<Territory>();
		   List<Territory> xNeighbors = SUtils.getExactNeighbors(checkThis, 1, data, false);
		   if (xNeighbors.size() == 1 && xNeighbors.get(0).isWater())
		   {
              Territory myIsland = xNeighbors.get(0);
              List<Unit> units = new ArrayList<Unit>();
		      List<Unit> transportUnits = myIsland.getUnits().getMatches(transUnit);
              List<Unit> finalTransUnits = new ArrayList<Unit>();
		      if (transportUnits.size() == 0)
		        continue;
		      int tCount = transportUnits.size();
		      for (int t1=tCount-1; t1>=0; t1--)
		      {
		 	    Unit trans1 = transportUnits.get(t1);
		 	    if (transportsFilled.contains(trans1))
		 	    	continue;
		        int tFree = tracker.getAvailableCapacity(trans1);
		        if (tFree <=0)
		        {
				   transportUnits.remove(t1);
				   tCount--;
				   continue;
		        }
		        Iterator<Unit> tIter = unitsToLoad.iterator();
		        boolean moveOne = false;
		        while (tIter.hasNext())
		        {
		           Unit current = tIter.next();
		           UnitAttachment ua = UnitAttachment.get(current.getType());
		           int howMuch = ua.getTransportCost();
		           if (ua.isAir() || tFree < howMuch)
		              continue;
		           tIter.remove();
		           tFree -= howMuch;
		           units.add(current);
		           moveOne = true;
		        }
		        if (moveOne)
		           finalTransUnits.add(trans1);
			  }
	          if(units.size() > 0)
	          {
			     Route route = data.getMap().getRoute(checkThis, myIsland);
	             moveUnits.add(units );
	             moveRoutes.add(route);
	             transportsToLoad.add( finalTransUnits);
	             transportsFilled.addAll( finalTransUnits);
	             unitsToLoad.removeAll(units);
	          }
	          continue;
		   }
		   if (isLand)
		   {
              Route badGuyDR = SUtils.findNearest(checkThis, Matches.isTerritoryEnemyAndNotNeutral(player, data),
                                               Matches.isTerritoryAllied(player, data), data);
              boolean noWater = true;
              if (badGuyDR == null)
                 badGuyDist = 0;
              else
              {
                 noWater = SUtils.RouteHasNoWater(badGuyDR);
                 badGuyDist = badGuyDR.getLength();
              }
              if (landRoute)
                 badGuyDist--; //less likely if we have a land Route to Capital from here
              if (badGuyDist <= 3 && noWater)
                 continue;
		   }
           for(Territory neighbor : xNeighbors)
           {
			  if (!neighbor.isWater())
			     continue;
              List<Unit> units = new ArrayList<Unit>();
              List<Unit> transportUnits = neighbor.getUnits().getMatches(transUnit);
              List<Unit> finalTransUnits = new ArrayList<Unit>();

              if (transportUnits.size()==0)
                 continue;
		      int transCount = transportUnits.size();
              for (int j=transCount-1; j >= 0; j--)
              {
		         transport = transportUnits.get(j);
		         if (transportsFilled.contains(transport))
		            continue; //we should actually figure out which only have 1 and if we can fit a unit on it (track num loaded)
                 int free = tracker.getAvailableCapacity(transport);
                 if (free <= 0)
                 {
		            transportUnits.remove(j);
					transCount--;
           	        continue;
		         }
                 Iterator<Unit> iter = unitsToLoad.iterator();
                 boolean addOne = false;
                 while(iter.hasNext() && free > 0)
                 {
                    Unit current = iter.next();
           	        UnitAttachment ua = UnitAttachment.get(current.getType());
           	        if(ua.isAir())
           	            continue;
           	        if(ua.getTransportCost() <= free)
           	        {
                       iter.remove();
                       free -= ua.getTransportCost();
                       units.add(current);
                       addOne = true;
           	        }
                 }
                 if (addOne)
					 finalTransUnits.add(transport);
		      }

	          if(units.size() > 0)
	          {
			     Route route = data.getMap().getRoute(checkThis, neighbor);
	             moveUnits.add(units );
	             moveRoutes.add(route);
	             transportsToLoad.add( finalTransUnits);
	             transportsFilled.addAll( finalTransUnits);
	             unitsToLoad.removeAll(units);
	          }
		   }
        }
    }

    private void populateNonComTransportMove(GameData data, List<Collection<Unit>> moveUnits, List<Route> moveRoutes, PlayerID player)
    {
//		openFile();
    	boolean tFirst = transportsMayDieFirst();
        TransportTracker tracker = DelegateFinder.moveDelegate(data).getTransportTracker();
        CompositeMatch<Unit> ourFactories = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player), Matches.UnitIsFactory);
		CompositeMatch<Unit> enemyUnit = new CompositeMatchAnd<Unit>(Matches.enemyUnit(player, data));
		CompositeMatch<Unit> landAndEnemy = new CompositeMatchAnd<Unit>(Matches.UnitIsLand, enemyUnit);
		CompositeMatch<Unit> airEnemyUnit = new CompositeMatchAnd<Unit>(enemyUnit, Matches.UnitIsAir);
		CompositeMatch<Unit> landOrAirEnemy = new CompositeMatchOr<Unit>(landAndEnemy, airEnemyUnit);
		CompositeMatch<Unit> transUnit = new CompositeMatchAnd<Unit>(Matches.UnitIsTransport);
		CompositeMatch<Unit> ourTransUnit = new CompositeMatchAnd<Unit>(transUnit, Matches.unitIsOwnedBy(player), HasntMoved);
		CompositeMatch<Unit> landUnit = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player), Matches.UnitIsLand, Matches.UnitIsNotAA, Matches.UnitIsNotFactory);
		CompositeMatch<Unit> escortUnit1 = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player), Matches.UnitIsDestroyer);
		CompositeMatch<Unit> escortUnit2 = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player), Matches.UnitIsTwoHit);
		CompositeMatch<Unit> carrierUnit = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player), Matches.UnitIsCarrier);
		CompositeMatch<Unit> fighterUnit = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player), Matches.UnitCanLandOnCarrier, HasntMoved);
		CompositeMatch<Unit> escortUnits = new CompositeMatchOr<Unit>(escortUnit1, escortUnit2, carrierUnit, fighterUnit);
		CompositeMatch<Unit> transportingUnit = new CompositeMatchAnd<Unit>(Matches.UnitIsTransport, Matches.unitIsOwnedBy(player), Transporting, HasntMoved);
		CompositeMatch<Territory> enemyTerritory = new CompositeMatchAnd<Territory>(Matches.TerritoryIsWater, Matches.territoryHasEnemyUnits(player, data));
		CompositeMatch<Territory> noEnemyTerritory = new CompositeMatchAnd<Territory>(Matches.TerritoryIsWater, Matches.territoryHasNoEnemyUnits(player, data));
		CompositeMatch<Territory> waterUnOccupied = new CompositeMatchAnd<Territory>(Matches.TerritoryIsWater, Matches.territoryHasNoEnemyUnits(player, data));
		List<Territory> transTerr2 = SUtils.findCertainShips(data, player, Matches.UnitIsTransport);
		List<Territory> occTransTerr = SUtils.findCertainShips(data, player, transportingUnit);
//		List<Territory> ourFactories = SUtils.findCertainShips(data, player, Matches.UnitIsFactory);
		List<Unit> unitsAlreadyMoved = new ArrayList<Unit>();
		List<Unit> transMoved = new ArrayList<Unit>();
		List<Territory>enemyCaps = SUtils.getEnemyCapitals(data, player);
		List<Territory>eCapsCopy = new ArrayList<Territory>(enemyCaps);
	    Territory goHere = null;
	    /**
	     * First determine if attack ships have been purchased and limit moves at that factory
	     */
	    boolean attackShipsBought = getKeepShipsAtBase();
	    Territory baseFactory = getSeaTerr();
	    if (attackShipsBought && baseFactory != null)
	    {
	    	Set<Territory> baseTerrs = data.getMap().getNeighbors(baseFactory, Matches.TerritoryIsWater);
	    	for (Territory baseTerr : baseTerrs)
	    	{
	    		unitsAlreadyMoved.addAll(baseTerr.getUnits().getMatches(Matches.unitIsOwnedBy(player)));
	    		transMoved.addAll(baseTerr.getUnits().getMatches(transUnit));
	    	}
	    }

		for (Territory eC : eCapsCopy) //build a list of allied neighbors of enemy capitols
		{
			Set<Territory> eCNeighbors = data.getMap().getNeighbors(eC, Matches.isTerritoryAllied(player, data));
			if (eCNeighbors != null)
				enemyCaps.addAll(eCNeighbors);
		}
		enemyCaps.removeAll(eCapsCopy);

		Route goRoute = null;
		boolean includeTransports = false; //don't borrow transports to make this move worthwhile
		for (Territory t : occTransTerr)
		{
			for (Territory eC2 : enemyCaps)
			{
				for (Territory eCWater : data.getMap().getNeighbors(eC2, noEnemyTerritory))
				{
					Route xRoute = SUtils.getMaxSeaRoute(data, t, eCWater, player);
					if (xRoute == null)
						continue;
					if (goRoute == null || goRoute.getLength() > xRoute.getLength())
					{
						goRoute = xRoute;
						goHere = eCWater;
					}
				}
			}

			if (goHere == null)
			{
				goRoute = SUtils.findNearest(t, Matches.isTerritoryEnemy(player, data), waterUnOccupied, data) ;
				if (goRoute != null && goRoute.getLength() > 0)
				{
					goHere = goRoute.getTerritories().get(goRoute.getLength()-1);
					includeTransports = false;
				}
				else
					continue;
			}
			if (goRoute == null && goHere != null)
				goRoute = SUtils.getMaxSeaRoute(data, t, goHere, player);
			if (goRoute == null || goHere == null)
				continue;
			Route r = goRoute;
			boolean doGoThere = true;
            List<Unit> mytrans = t.getUnits().getMatches(ourTransUnit);
            mytrans.removeAll(unitsAlreadyMoved);
			List<Unit> escorts = t.getUnits().getMatches(escortUnits);
			escorts.removeAll(unitsAlreadyMoved);
			List<Unit> unitsToLoad = new ArrayList<Unit>();
			boolean doEscorts = escorts.size() > 0;
			float seaStrength = SUtils.getStrengthOfPotentialAttackers(goHere, data, player, tFirst, false);
			float myGroupStrength = SUtils.strength(escorts, false, true, tFirst);
			myGroupStrength += SUtils.strength(mytrans, false, true, tFirst);
			if (myGroupStrength > seaStrength) //the path is fine, but how about the end?
			{//do we have a place to dump units?
				if (SUtils.getNeighboringEnemyLandTerritories(data, player, true).size() == 0 && seaStrength > 4.0F)
					doGoThere = false;
			}
			float strengthNeeded = (seaStrength*1.75F + 4.00F) - myGroupStrength;
			float xStrengthNeeded = strengthNeeded;
			//we need more ships
			List<Collection<Unit>> xMoveUnits = new ArrayList<Collection<Unit>>();
			List<Route> xMoveRoutes = new ArrayList<Route>();
			List<Unit> xAlreadyMoved = new ArrayList<Unit>(unitsAlreadyMoved);
			xAlreadyMoved.addAll(escorts);
			myGroupStrength+= (strengthNeeded - xStrengthNeeded);
			SUtils.inviteShipAttack(goHere, xStrengthNeeded, xAlreadyMoved, xMoveUnits, xMoveRoutes, data, player, false, tFirst, includeTransports);
			if (xStrengthNeeded <= 0.0F || (doGoThere && myGroupStrength > seaStrength*1.05F))
				doGoThere = true;
			else if (!doGoThere && myGroupStrength > (seaStrength*1.25F + 4.00F)) //we'll end up with loaded transports on open water
				doGoThere = true;
			else //we don't have the strength to make the preferred move
				doGoThere = false;
				
            if(doGoThere && r.getLength() > 0)
            {
				for (Unit transport : mytrans)
				{
					if (!unitsAlreadyMoved.contains(transport) && tracker.isTransporting(transport))
					{
						unitsToLoad.addAll(tracker.transporting(transport));
						unitsToLoad.add(transport);
						transMoved.add(transport);
					}
				}
			   	if(unitsToLoad.size() > 0)
           		{
           		    if (doEscorts)
           		    	unitsToLoad.addAll(escorts);
           		    moveUnits.add(unitsToLoad);
           		    moveRoutes.add(r);
				}
			   	moveUnits.addAll(xMoveUnits);
			   	moveRoutes.addAll(xMoveRoutes);
			   	unitsAlreadyMoved.addAll(xAlreadyMoved);
            }
            if (!doGoThere) //see if there are some ships we can bring to here
            {
            	SUtils.inviteShipAttack(t, strengthNeeded, unitsAlreadyMoved, moveUnits, moveRoutes, data, player, false, tFirst, false);
            }
            
            goRoute = null;
/*            if (amphibRoute != null)
                goHere = lastSeaZoneOnAmphib;
            else
            	goHere = null;
*/		}
		List<Territory> factTerr = SUtils.findUnitTerr(data, player, ourFactories);
		List<Territory> waterFactTerrs = new ArrayList<Territory>();
		float eStrength = 0.0F;
		for (Territory fT : factTerr)
		{
			Territory newTerr = SUtils.findASeaTerritoryToPlaceOn(fT, eStrength, data, player, tFirst);
			if (newTerr != null)
				waterFactTerrs.add(newTerr);
		}
		for (Territory t2 : transTerr2)
		{
			List<Unit> mytrans2 = t2.getUnits().getMatches(ourTransUnit);
			List<Unit> unitsT = new ArrayList<Unit>();
			for (Unit transport : mytrans2)
			{
				if (!unitsAlreadyMoved.contains(transport) && !(tracker.isTransporting(transport) || tracker.hasTransportUnloadedInPreviousPhase(transport)))
					unitsT.add(transport);
			}
			Route r = null;
			if (unitsT.size() > 0)
			{
				int minDist = 100;
				for (Territory fT2 : waterFactTerrs)
				{
					int thisDist = data.getMap().getWaterDistance(t2, fT2);
					if (thisDist < minDist)
					{
						minDist = thisDist;
						r = data.getMap().getWaterRoute(t2, fT2);
					}
				}
				if (r != null)
				{
					moveUnits.add(unitsT);
					moveRoutes.add(r);
				}
			}
		}
	}


    private void populateTransportMove(boolean nonCombat, GameData data, List<Collection<Unit>> moveUnits, List<Route> moveRoutes, PlayerID player)
    {
//		openFile();
/*	Hard to differentiate the Non-combat from the combat
	The decisions are similar and to leave the transport loaded
	for the non-combat move, a decision needs to be made that
	weights the non-combat over the combat.
*/
		boolean tFirst = transportsMayDieFirst();
        TransportTracker tracker = DelegateFinder.moveDelegate(data).getTransportTracker();
		CompositeMatch<Unit> enemyUnit = new CompositeMatchAnd<Unit>(Matches.enemyUnit(player, data));
		CompositeMatch<Unit> landAndEnemy = new CompositeMatchAnd<Unit>(Matches.UnitIsLand, enemyUnit);
		CompositeMatch<Unit> airEnemyUnit = new CompositeMatchAnd<Unit>(enemyUnit, Matches.UnitIsAir);
		CompositeMatch<Unit> landOrAirEnemy = new CompositeMatchOr<Unit>(landAndEnemy, airEnemyUnit);
		CompositeMatch<Unit> transUnit = new CompositeMatchAnd<Unit>(Matches.UnitIsTransport);
		CompositeMatch<Unit> escortUnit1 = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player), Matches.UnitIsDestroyer);
		CompositeMatch<Unit> escortUnit2 = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player), Matches.UnitIsTwoHit);
		CompositeMatch<Unit> escortUnits = new CompositeMatchOr<Unit>(escortUnit1, escortUnit2);
		CompositeMatch<Unit> transportingUnit = new CompositeMatchAnd<Unit>(transUnit, Matches.unitIsOwnedBy(player), Transporting, HasntMoved);
		CompositeMatch<Unit> ourFighters = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player), Matches.UnitCanLandOnCarrier, HasntMoved);
		CompositeMatch<Unit> ourBombers = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player), Matches.unitCanBombard(player), HasntMoved);
		List<Territory> dontMoveFrom = new ArrayList<Territory>();
		List<Unit> unitsAlreadyMoved = new ArrayList<Unit>();
		/**
		 * First determine if attack ships have been purchased and limit moves at that factory
		 */
		boolean attackShipsBought = getKeepShipsAtBase();
		Territory baseFactory = getSeaTerr();
		if (attackShipsBought && baseFactory != null)
		{
			Set<Territory> baseTerrs = data.getMap().getNeighbors(baseFactory, Matches.TerritoryIsWater);
			dontMoveFrom.addAll(baseTerrs);
			for (Territory baseTerr : baseTerrs)
			{
				unitsAlreadyMoved.addAll(baseTerr.getUnits().getMatches(Matches.unitIsOwnedBy(player)));
			}	
		}

		List<Territory> occTransTerr = SUtils.findCertainShips(data, player, transportingUnit);
		occTransTerr.removeAll(dontMoveFrom);
		if (occTransTerr == null || occTransTerr.size()==0)
			return;
		List<Territory> ourFactories = SUtils.findCertainShips(data, player, Matches.UnitIsFactory);
		List<Unit> transMoved = new ArrayList<Unit>();
		Territory rankTerr[] = new Territory[data.getMap().getTerritories().size()];
		float rankStrength[] = new float[data.getMap().getTerritories().size()];
		float ranking[] = new float[data.getMap().getTerritories().size()];
		List<Territory> bomberTerr = SUtils.findCertainShips(data, player, ourBombers);
		List<Territory> fighterTerr = SUtils.findCertainShips(data, player, ourFighters);
		fighterTerr.removeAll(dontMoveFrom);

		List<Territory>enemyCaps = SUtils.getEnemyCapitals(data, player);
		List<Territory>enemyCaps2 = new ArrayList<Territory>(); //allied next door neighbors to enemyCaps

		List<Territory>eCopy = new ArrayList<Territory>(enemyCaps);
		for (Territory eSCap : eCopy)
		{
			List<Territory> neighborCaps = SUtils.getNeighboringEnemyLandTerritories(data, player, eSCap);
			if (neighborCaps != null && neighborCaps.size() > 0)
			{
				List<Territory> tNCaps = new ArrayList<Territory>(neighborCaps);
				for (Territory tNtemp : tNCaps)
				{
					Set<Territory> waterTerr = data.getMap().getNeighbors(tNtemp, Matches.TerritoryIsWater);
					if (waterTerr == null || waterTerr.size()==0)
						neighborCaps.remove(tNtemp); //eliminate interior territories
				}
				if (neighborCaps.size() > 0)
					enemyCaps.addAll(neighborCaps);
			}
			List<Territory> neighborCaps2 = SUtils.getNeighboringLandTerritories(data, player, eSCap);
			if (neighborCaps2 == null || neighborCaps2.size() == 0)
				continue;
			List<Territory> tNCaps2 = new ArrayList<Territory>(neighborCaps2);
			for (Territory tNtemp2 : tNCaps2)
			{
				Set<Territory> waterTerr2 = data.getMap().getNeighbors(tNtemp2, Matches.TerritoryIsWater);
				if (waterTerr2 == null || waterTerr2.size()==0)
					neighborCaps2.remove(tNtemp2);
			}
			if (neighborCaps2 != null && neighborCaps2.size() > 0)
				enemyCaps2.addAll(neighborCaps2); //these are the ones that we own
		}
		List<Territory>alliedTerr = new ArrayList<Territory>(); //find an occupied territory with access to enemy caps
		for (Territory alliedCheck : enemyCaps) //are there allied territories along the pathway to an enemy cap?
		{
			for (Territory myFact : ourFactories)
			{
				Route aroute = data.getMap().getRoute(myFact, alliedCheck);
				if (aroute != null)
				{
					int aRouteLen = aroute.getLength();
					for (int AStep = 1; AStep <aRouteLen; AStep++)
					{
						Territory NextTerr = aroute.getTerritories().get(AStep);
						if (Matches.TerritoryIsLand.match(NextTerr) && Matches.isTerritoryAllied(player, data).match(NextTerr)
							&& SUtils.isWaterAt(NextTerr, data) && SUtils.hasLandRouteToEnemyOwnedCapitol(NextTerr, player, data))
						{
							alliedTerr.add(NextTerr);
							List<Territory> neighbors2 = SUtils.getNeighboringLandTerritories(data, player, NextTerr);
							for (Territory AnotherCheck : neighbors2)
							{
								if (SUtils.isWaterAt(AnotherCheck, data))
									alliedTerr.add(AnotherCheck);
							}
						}
					}
				}
			}
		}
		if (alliedTerr.size() > 0)
			enemyCaps2.addAll(alliedTerr);

		//build a profile list of every enemy territory
		LinkedHashSet<Territory> badGuyTerr = new LinkedHashSet<Territory>(SUtils.allEnemyTerritories(data, player));
		if (enemyCaps2.size() > 0)
			badGuyTerr.addAll(enemyCaps2); //let's add-in allied cap neighbors
		List<Territory> badGuyTemp = new ArrayList<Territory>(badGuyTerr);
		for (Territory badGuy1 : badGuyTemp)
		{
			if (SUtils.isWaterAt(badGuy1, data))
				continue;
			badGuyTerr.remove(badGuy1);
		}
		if (badGuyTerr.size() == 0)
			return;
		int i=0;
		for (Territory bGTerr : badGuyTerr)
		{
			if (bGTerr == null || bGTerr.isWater() || Matches.TerritoryIsNeutral.match(bGTerr))
				continue;
			float bGStrength = SUtils.strength(bGTerr.getUnits().getMatches(landOrAirEnemy), false, false, tFirst);
			rankStrength[i]=bGStrength;
			rankTerr[i]=bGTerr;
			bGStrength -= (float)TerritoryAttachment.get(bGTerr).getProduction();
			if (enemyCaps2.contains(bGTerr)) //bonus if territory is a capital or next to capital
				bGStrength -= 7.00F;
			if (enemyCaps.contains(bGTerr))
				bGStrength -= 5.00F; //give preference to proximity to caps
			if (SUtils.hasLandRouteToEnemyOwnedCapitol(bGTerr, player, data))
				bGStrength -= 3.50F;
			bGStrength -= (float)SUtils.getNeighboringEnemyLandTerritories(data, player, bGTerr).size();
			if (Matches.territoryHasEnemyFactory(data, player).match(bGTerr))
				bGStrength -= 4.00F;
/*			if (getNationalObjectives)
			{
				if (
*/
			ranking[i]=bGStrength;
			i++;
		}
		i--;
		boolean doEscorts = false;
		// transports are running all over the pacific for the Japanese
		// not putting enough on Asia...working better now
		// works great for Allies...they crush
		// this would work if we had a factory on mainland
		for (Territory invadeTerr : occTransTerr)
		{
			Territory invadeHere = null;
			int goDist=0;
			float minRank=500.0F, minStrength = 100.0F;
			for (int k=0; k < badGuyTerr.size(); k++)
			{
				Territory invadeMe = rankTerr[k];
				if (invadeMe == null)
					continue;
				Territory goTerr = SUtils.getClosestWaterTerr(invadeMe, invadeTerr, goDist, data, player);
				if (goTerr == null || dontMoveFrom.contains(goTerr))
					continue;
				if (goDist <= 2)
					goDist = 0;
				float totRank = ranking[k]+(float) 2.5F*goDist;
				float airStrength = 0.0F;
				for (Territory bTerr : bomberTerr)
				{
					int bDist = data.getMap().getDistance(bTerr, invadeMe);
					int bomberNum = bTerr.getUnits().countMatches(ourBombers);
					if (bDist<=3)
						airStrength+= (float) bomberNum*4.7F;
				}
				for (Territory fTerr : fighterTerr)
				{
					int fDist = data.getMap().getDistance(fTerr, invadeMe);
					int fighterNum = fTerr.getUnits().countMatches(ourFighters);
					if (fDist<=3)
						airStrength+= (float) fighterNum*3.7F;
				}
				totRank -= airStrength;
				if (totRank < minRank)
				{
					minStrength=rankStrength[k];
					invadeHere=goTerr;
					minRank=totRank;
				}
			}
			//check near Neighbors
			List<Territory> nearNeighborTerr = SUtils.getNeighboringLandTerritories(data, player, invadeTerr);
			boolean leaveHere = false;
			for (Territory checkOtherTerr : nearNeighborTerr)
			{ //see if we are next to a good reinforceable Territory
				float otherStrength = 0.0F;
				if (SUtils.hasLandRouteToEnemyOwnedCapitol(checkOtherTerr, player, data))
					otherStrength -=2.00F;
				if (otherStrength < minRank)
					invadeHere=invadeTerr;
			}
			if (invadeHere == null || invadeHere == invadeTerr)
				continue;
			Route rr1=SUtils.getMaxSeaRoute(data, invadeTerr, invadeHere, player);
			if (rr1 != null)
			{
				List<Unit> escorts = invadeTerr.getUnits().getMatches(escortUnits);
				escorts.removeAll(unitsAlreadyMoved);
				doEscorts = escorts.size() > 0;
				List<Unit> itransUnits = invadeTerr.getUnits().getMatches(transportingUnit);
				itransUnits.removeAll(unitsAlreadyMoved);
				List<Unit> iMyUnits= new ArrayList<Unit>();
				int iUnitCount = 0;
				for (Unit iTransport : itransUnits)
				{
					if (transMoved.contains(iTransport))
						continue;
					if (tracker.isTransporting(iTransport))
					{
						iMyUnits.addAll(tracker.transporting(iTransport));
						iMyUnits.add(iTransport);
						transMoved.add(iTransport);
						iUnitCount++;
					}
				}
				if (iUnitCount > 0)
				{
					moveUnits.add(iMyUnits);
					moveRoutes.add(rr1);
					unitsAlreadyMoved.addAll(iMyUnits);
					if (doEscorts)
					{
						moveUnits.add(escorts);
						moveRoutes.add(rr1);
						unitsAlreadyMoved.addAll(iMyUnits);
					}
				}
			}
		}

	}


    private void populateTransportUnloadNonCom(boolean nonCombat, GameData data, List<Collection<Unit>> moveUnits, List<Route> moveRoutes, PlayerID player)
    {
    	CompositeMatch<Unit> transUnit = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player), Matches.UnitIsTransport);
    	CompositeMatch<Unit> landUnit = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player), Matches.UnitIsLand);
		List<Territory> transTerr = SUtils.findCertainShips(data, player, transUnit);
        Territory capitol = TerritoryAttachment.getCapital(player, data);
//        List<Territory> factTerr=SUtils.findCertainShips(data, player, Matches.UnitIsFactory);
        boolean tFirst = transportsMayDieFirst();
        List<Territory> threats = new ArrayList<Territory>();
        boolean alliedCapDanger = SUtils.threatToAlliedCapitals(data, player, threats, tFirst);
		for (Territory t: transTerr)
		{
			List<Unit> ourUnits = t.getUnits().getMatches(landUnit);
			Territory landOn = null;
			if (ourUnits.size() == 0)
				continue;
			List<Territory> ourTerr = SUtils.getNeighboringLandTerritories(data, player, t);
			if (ourTerr.size() == 0)
				continue;
			if (ourTerr.size() == 1)
				landOn = ourTerr.get(0);
			else
				landOn = SUtils.closestToEnemyCapital(ourTerr, data, player);
			if (landOn == null && ourTerr.contains(capitol))
				landOn = capitol;
			if (landOn == null)
				landOn = ourTerr.get(0);
			if (alliedCapDanger)
			{
				for (Territory testTerr : ourTerr)
				{
					if (threats.contains(testTerr))
						landOn = testTerr;
				}
						
			}
			if (landOn != null)
			{
            	Route route = new Route();
            	route.setStart(t);
            	route.add(landOn);
            	moveUnits.add(ourUnits);
            	moveRoutes.add(route);
			}
		}

    }

    private void populateTransportUnload(GameData data, List<Collection<Unit>> moveUnits, List<Route> moveRoutes, PlayerID player)
    {//We need to track the planes better...probably counting them in more than 1 attack
		boolean tFirst = transportsMayDieFirst();
		TransportTracker tTracker = DelegateFinder.moveDelegate(data).getTransportTracker();
		Territory eTerr[] = new Territory[data.getMap().getTerritories().size()] ; //revised game has 79 territories and 64 sea zones
		float eStrength[] = new float[data.getMap().getTerritories().size()];
		float eS = 0.00F;

		CompositeMatch<Unit> enemyUnit = new CompositeMatchAnd<Unit>(Matches.enemyUnit(player, data));
		CompositeMatch<Unit> landAndOwned = new CompositeMatchAnd<Unit>(Matches.UnitIsLand, Matches.unitIsOwnedBy(player));
		CompositeMatch<Unit> landAndEnemy = new CompositeMatchAnd<Unit>(Matches.UnitIsLand, enemyUnit);
		CompositeMatch<Unit> airEnemyUnit = new CompositeMatchAnd<Unit>(enemyUnit, Matches.UnitIsAir);
		CompositeMatch<Unit> landOrAirEnemy = new CompositeMatchOr<Unit>(landAndEnemy, airEnemyUnit);
		CompositeMatch<Unit> transportingUnit = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player), Matches.UnitIsTransport, Transporting);
		CompositeMatch<Unit> enemyfactories = new CompositeMatchAnd<Unit>(Matches.UnitIsFactory, enemyUnit);

        float remainingStrength = 100.0F;

		List<Territory>transTerr = SUtils.findCertainShips(data, player, Matches.UnitIsTransport);
		List<Territory>enemyCaps = SUtils.findUnitTerr(data, player, enemyfactories);
		List<Territory>tempECaps = new ArrayList<Territory>(enemyCaps);
		List<Unit> unitsAlreadyMoved = new ArrayList<Unit>();
		
		for (Territory qT : tempECaps) //add all neighbors
		{
			Set<Territory>nTerr = data.getMap().getNeighbors(qT, Matches.isTerritoryEnemyAndNotNuetralWater(player, data));
			if (nTerr.size() > 0)
				enemyCaps.addAll(nTerr);
		}
		int maxCap = enemyCaps.size() - 2;
//		if (maxPasses < maxCap)
//			maxPasses=1;
		Territory tempTerr=null, tempTerr2 = null;
		for (int j=0; j <= maxCap; j++) //sort the caps & neighbors by their production value
		{
			for (int iCap=0; iCap < maxCap; iCap++)
			{
				tempTerr = enemyCaps.get(iCap);
				tempTerr2 = enemyCaps.get(iCap + 1);
				if (TerritoryAttachment.get(tempTerr).getProduction() < TerritoryAttachment.get(tempTerr2).getProduction())
				{ //switch i & i+1
					enemyCaps.remove(iCap);
					enemyCaps.remove(iCap);
					enemyCaps.add(iCap, tempTerr);
					enemyCaps.add(iCap, tempTerr2);
				}
			}
		}
		/*
		 * Search list: Production Value...capitals all have high values
		 * if friendly, dump there first
		 * if enemy, but we are stronger...dump next
		 * enemy capital will always get the first look
		 */
		List<Unit> xAlreadyMoved = new ArrayList<Unit>();
		List<Collection<Unit>> xMoveUnits = new ArrayList<Collection<Unit>>();
		List<Route> xMoveRoutes = new ArrayList<Route>();
		for (Territory eC : enemyCaps) //priority...send units into capitals & capneighbors when possible
		{
			Set<Territory> neighborTerr = data.getMap().getNeighbors(eC, Matches.TerritoryIsWater);
			List<Unit> capUnits = eC.getUnits().getMatches(landOrAirEnemy);
			float capStrength = SUtils.strength(capUnits, false, false, tFirst);
			if (Matches.isTerritoryFriendly(player, data).match(eC))
			{
				for (Territory nF : neighborTerr)
				{
					List<Unit> quickLandingUnits = nF.getUnits().getMatches(landAndOwned);
					Route quickLandRoute = new Route();
					quickLandRoute.setStart(nF);
					quickLandRoute.add(eC);
					if (quickLandRoute != null)
					{
						moveUnits.add(quickLandingUnits);
						moveRoutes.add(quickLandRoute);
						unitsAlreadyMoved.addAll(quickLandingUnits);
						if (transTerr.contains(nF))
							transTerr.remove(nF);
					}
				}
			}
			for (Territory nT : neighborTerr)
			{
				if (nT.getUnits().someMatch(transportingUnit))
				{
					List<Unit> specialLandUnits = nT.getUnits().getMatches(landAndOwned);
					specialLandUnits.removeAll(unitsAlreadyMoved);
					if (specialLandUnits.isEmpty())
						continue;
					float invadeStrength = SUtils.strength(specialLandUnits, true, false, tFirst);
					Set<Territory> attackNeighbors = data.getMap().getNeighbors(eC, Matches.isTerritoryFriendly(player, data));
					float localStrength = 0.0F;
					for (Territory aN : attackNeighbors)
					{
						if (aN.isWater()) //don't count anything from water
							continue;
						List<Unit> localUnits = aN.getUnits().getMatches(landAndOwned);
						localUnits.removeAll(unitsAlreadyMoved);
						localStrength += SUtils.strength(localUnits, true, false, tFirst);
						xMoveUnits.add(localUnits);
						Route localRoute = data.getMap().getLandRoute(aN, eC);
						xMoveRoutes.add(localRoute);
						xAlreadyMoved.addAll(localUnits);
					}
					float ourStrength = invadeStrength + localStrength;
					remainingStrength = (capStrength*2.20F + 5.00F) - ourStrength;
					float xRemainingStrength = remainingStrength;
					xAlreadyMoved.addAll(unitsAlreadyMoved);
					SUtils.inviteBlitzAttack(false, eC, remainingStrength, xAlreadyMoved, xMoveUnits, xMoveRoutes, data, player, true, true);
					float blitzStrength = xRemainingStrength - remainingStrength;
					xRemainingStrength = remainingStrength;
					SUtils.invitePlaneAttack(false, false, eC, remainingStrength, xAlreadyMoved, xMoveUnits, xMoveRoutes, data, player);
					float planeStrength = xRemainingStrength - remainingStrength; 					
					if ((invadeStrength + localStrength + blitzStrength + planeStrength) >= 0.92F*capStrength)
					{
						Route specialRoute = data.getMap().getRoute(nT, eC);
						moveUnits.add(specialLandUnits);
						moveRoutes.add(specialRoute);
						unitsAlreadyMoved.addAll(specialLandUnits);
						moveUnits.addAll(xMoveUnits);
						moveRoutes.addAll(xMoveRoutes);
						for (Collection<Unit> xCollect : xMoveUnits)
							unitsAlreadyMoved.addAll(xCollect);
						if (transTerr.contains(nT))
							transTerr.remove(nT);
					}
				}
				xMoveUnits.clear();
				xMoveRoutes.clear();
				xAlreadyMoved.clear();
			}
		}

		for (Territory t : transTerr) //complete check
		{
			List<Unit> transUnits = t.getUnits().getMatches(transportingUnit);
			List<Unit> units = t.getUnits().getMatches(landAndOwned);
			float ourStrength = SUtils.strength(units, true, false, tFirst);
			if (units.size() == 0)
				continue;
			List<Territory> enemy=SUtils.getNeighboringEnemyLandTerritories(data, player, t);
			List<Territory> enemyCopy = new ArrayList<Territory>(enemy);
			List<Unit> alreadyOut = new ArrayList<Unit>();
			//quick check for empty territories
			Map<Unit, Collection<Unit>> transMap = tTracker.transporting(transUnits);

			int i=0;
			for (Territory t2 : enemy) //find strength of all enemy terr (defensive)
			{
				eTerr[i]=t2;
				eStrength[i]=SUtils.strength(t2.getUnits().getMatches(landOrAirEnemy), false, false, tFirst);
				i++;
			}
			float tmpStrength = 0.0F;
			Territory tmpTerr = null;
			for (int j2=0; j2<i-1; j2++) //sort the territories by strength
			{
				tmpTerr = eTerr[j2];
				tmpStrength = eStrength[j2];
				Set<Territory>badFactTerr = data.getMap().getNeighbors(tmpTerr, Matches.territoryHasEnemyFactory(data, player));
				if ((badFactTerr.size() > 0) && (tmpStrength*1.10F + 5.00F) <= eStrength[j2+1])
					continue; //if it is next to a factory, don't move it down
				if (tmpStrength < eStrength[j2+1])
				{
					eTerr[j2]=eTerr[j2+1];
					eStrength[j2]=eStrength[j2+1];
					eTerr[j2+1]=tmpTerr;
					eStrength[j2+1]=tmpStrength;
				}
			}
			// Consideration: There might be a land based invasion of an empty terr available
			for (Territory x : enemyCopy)
			{
				if (Matches.isTerritoryEnemy(player, data).match(x) && Matches.territoryIsEmptyOfCombatUnits(data, player).match(x))
				{
					float topStrength = eStrength[0];
					float winStrength = 0.0F;
					float newStrength = ourStrength;
					for (int jC = 0; jC < enemy.size()-1; jC++)
					{
						if (!enemy.contains(eTerr[jC]))
							continue;
						topStrength = eStrength[jC];
						if (newStrength > topStrength && winStrength == 0.0F) //what we can currently win
							winStrength = topStrength;
					}
					Iterator<Unit> transIter = transUnits.iterator();
					boolean gotOne = false;
					while (transIter.hasNext() && !gotOne)
					{
						Unit transport = transIter.next();
						if (!tTracker.isTransporting(transport) || transport == null)
							continue;
						Collection<Unit> transportUnits = transMap.get(transport);
						if (transportUnits == null)
							continue;
						float minusStrength = SUtils.strength(transportUnits, true, false, tFirst);
						if ((newStrength - minusStrength) > winStrength )
						{
							Route xRoute = data.getMap().getRoute(t, x);
							moveUnits.add(transportUnits);
							moveRoutes.add(xRoute);
							unitsAlreadyMoved.addAll(transportUnits);
							enemy.remove(x);
							ourStrength -= minusStrength;
							gotOne = true;
						}
					}
					
				}
			}

			for (int j=0; j<i; j++) //just find the first terr we can invade
			{
				units.removeAll(unitsAlreadyMoved);
				xAlreadyMoved.addAll(unitsAlreadyMoved);
				float ourStrength2 = ourStrength;
                eS = eStrength[j];
				Territory invadeTerr = eTerr[j];
				if (!enemy.contains(invadeTerr))
					continue;
                float strengthNeeded = 2.15F*eS + 3.00F;
 				float airStrength = 0.0F;
				ourStrength2 += airStrength;
				float rStrength = strengthNeeded - ourStrength2;
				SUtils.invitePlaneAttack(true, false, invadeTerr, rStrength, xAlreadyMoved, xMoveUnits, xMoveRoutes, data, player);
				SUtils.inviteBlitzAttack(false, invadeTerr, rStrength, xAlreadyMoved, xMoveUnits, xMoveRoutes, data, player, true, false);
				List<Unit> aBattleUnit = new ArrayList<Unit>(units);
				for (Collection<Unit> qUnits : xMoveUnits)
					aBattleUnit.addAll(qUnits);
				List<Unit> dBattleUnit = invadeTerr.getUnits().getMatches(landOrAirEnemy);
				IntegerMap<UnitType> aMap = SUtils.convertListToMap(aBattleUnit);
				IntegerMap<UnitType> dMap = SUtils.convertListToMap(dBattleUnit);
				boolean weWin = SUtils.quickBattleEstimator(aMap, dMap, player, invadeTerr.getOwner());
				s_logger.fine("Attacker: " + player.getName());
				s_logger.fine("Attacker: " + aMap.toString());
				s_logger.fine("Defender: " + dMap.toString());
				s_logger.fine(weWin ? "Attacker Wins" : "Defender Wins");

				/**
				 * Invade if we should win...or we should barely lose (enemy projected to only have 1 remaining defender)
				 */
				if (weWin || (dMap.totalValues()==1 && dBattleUnit.size() > 3)) 
                {
					Route route = new Route();
					route.setStart(t);
					route.add(invadeTerr);
					moveUnits.add(units);
					moveRoutes.add(route);
					unitsAlreadyMoved.addAll(units);
					moveUnits.addAll(xMoveUnits);
					moveRoutes.addAll(xMoveRoutes);
					for (Collection<Unit> xCollect : xMoveUnits)
						unitsAlreadyMoved.addAll(xCollect);
				}
				xAlreadyMoved.clear();
				xMoveUnits.clear();
				xMoveRoutes.clear();
			}
		}
	}


    private List<Unit> load2Transports( boolean reload, GameData data, List<Unit> transportsToLoad, Territory loadFrom, PlayerID player)
    {
        TransportTracker tracker = DelegateFinder.moveDelegate(data).getTransportTracker();
    	List<Unit> units = new ArrayList<Unit>();
        for(Unit transport : transportsToLoad)
        {
        	Collection<Unit> landunits = tracker.transporting(transport);
        	for(Unit u : landunits) {
        		units.add(u);
        	}
        }
    	return units;
    }

    private void doMove(List<Collection<Unit>> moveUnits, List<Route> moveRoutes, List<Collection<Unit>> transportsToLoad, IMoveDelegate moveDel)
    {

        for(int i =0; i < moveRoutes.size(); i++)
        {
            pause();

            if(moveRoutes.get(i) == null || moveRoutes.get(i).getEnd() == null || moveRoutes.get(i).getStart() == null)
            {
                s_logger.fine("Route not valid" + moveRoutes.get(i) + " units:" + moveUnits.get(i));
                continue;
            }

            String result;

            if(transportsToLoad == null)
            {
                result = moveDel.move(moveUnits.get(i), moveRoutes.get(i)  );
			}
            else
                result = moveDel.move(moveUnits.get(i), moveRoutes.get(i) , transportsToLoad.get(i) );

            if(result != null)
            {
                s_logger.fine("could not move " + moveUnits.get(i) + " over " + moveRoutes.get(i) + " because : " + result+"\n");
            }
        }
    }
    /**
     * Add all ships around a factory into a group which is not be moved
     * @param data
     * @param player
     * @param alreadyMoved - List of units to be modified
     */
    private void markBaseShips(final GameData data, PlayerID player, List<Unit> alreadyMoved)
    {
    	if (getKeepShipsAtBase() && getSeaTerr() != null)
    	{
    		Set<Territory> baseTerrs = data.getMap().getNeighbors(getSeaTerr(), Matches.TerritoryIsWater);
    		for (Territory bT : baseTerrs)
    		{
    			alreadyMoved.addAll(bT.getUnits().getMatches(Matches.unitIsOwnedBy(player)));
    		}
    	}
    }

    private void moveCombatSea( final GameData data, List<Collection<Unit>> moveUnits, List<Route> moveRoutes, PlayerID player, Route amphibRoute, int maxTrans)
    {
		Match<Unit> ownedAndNotMoved = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player), HasntMoved, Transporting);
		CompositeMatch<Unit> escortUnit1 = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player), Matches.UnitIsDestroyer);
		CompositeMatch<Unit> escortUnit2 = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player), Matches.UnitIsTwoHit);
		CompositeMatch<Unit> escortUnit3 = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player), Matches.UnitIsCruiser);
		CompositeMatch<Unit> escortUnits = new CompositeMatchOr<Unit>(escortUnit1, escortUnit2, escortUnit3);
		List<Unit> unitsAlreadyMoved = new ArrayList<Unit>();
		if (moveRoutes.size() == 2) {
			moveRoutes.remove(1);
			moveUnits.remove(1);
		}
		markBaseShips(data, player, unitsAlreadyMoved);
		Territory firstSeaZoneOnAmphib = null;
		Territory lastSeaZoneOnAmphib = null;
		if (amphibRoute != null)
		{
			firstSeaZoneOnAmphib = amphibRoute.getTerritories().get(0);
			lastSeaZoneOnAmphib = amphibRoute.getTerritories().get(amphibRoute.getLength() - 1);
		}

		List<Unit> unitsToMove = new ArrayList<Unit>();
		List<Unit> transports = firstSeaZoneOnAmphib.getUnits().getMatches(ownedAndNotMoved);
		List<Unit> escorts = firstSeaZoneOnAmphib.getUnits().getMatches(escortUnits);

		if(transports.size() <= maxTrans)
		    unitsToMove.addAll(transports);
 	    else
		    unitsToMove.addAll(transports.subList(0, maxTrans));

	    if (escorts.size() > 0)
	    	unitsToMove.add(escorts.get(0));
	    unitsToMove.removeAll(unitsAlreadyMoved);
	    
		List<Unit> landUnits = load2Transports(true, data, unitsToMove, firstSeaZoneOnAmphib, player);
		Route r = SUtils.getMaxSeaRoute(data, firstSeaZoneOnAmphib, lastSeaZoneOnAmphib, player);
		moveRoutes.add(r);
		unitsToMove.addAll(landUnits);
		moveUnits.add(unitsToMove);
    }

    private void specialPlaneAttack(final GameData data, List<Collection<Unit>> moveUnits, List<Route> moveRoutes, PlayerID player)
    {
        final Collection<Unit> alreadyMoved = new HashSet<Unit>();
        Territory myCapital = TerritoryAttachment.getCapital(player, data);

		boolean tFirst = transportsMayDieFirst();
        Match<Unit> notAlreadyMoved =new CompositeMatchAnd<Unit>(new Match<Unit>()
		   {
				public boolean match(Unit o)
				{
					return !alreadyMoved.contains(o);
				}
			});
		Match<Unit> ownedUnit = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player));
		Match<Unit> HasntMoved2 = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player), HasntMoved, notAlreadyMoved);
       	Match<Unit> ownedAndNotMoved = new CompositeMatchAnd<Unit>(ownedUnit, HasntMoved);
       	Match<Unit> airAttackUnit = new CompositeMatchAnd<Unit>(ownedAndNotMoved, Matches.UnitIsAir);
       	Match<Unit> enemySubUnit = new CompositeMatchAnd<Unit>(Matches.enemyUnit(player, data), Matches.UnitIsSub);
       	Match<Unit> fighterUnit = new CompositeMatchAnd<Unit>(Matches.UnitCanLandOnCarrier, HasntMoved2);
       	Match<Unit> bomberUnit = new CompositeMatchAnd<Unit>(Matches.UnitIsStrategicBomber, HasntMoved2);
       	Match<Unit> destroyerUnit = new CompositeMatchAnd<Unit>(ownedUnit, Matches.UnitIsDestroyer);


       	//Check to see if we have total air superiority...4:1 or greater...if so, let her rip

       	List<Territory> myAirTerr = SUtils.findUnitTerr(data, player, airAttackUnit);
       	List<Unit> unitsAlreadyMoved = new ArrayList<Unit>();
       	for (Territory AttackFrom : myAirTerr)
       	{
			List<Unit> myFighters = AttackFrom.getUnits().getMatches(fighterUnit);
			int fighterCount = myFighters.size();
			float myFighterStrength = SUtils.strength(myFighters, true, false, false);
			List<Unit> myBombers = AttackFrom.getUnits().getMatches(bomberUnit);
			float myBomberStrength = SUtils.strength(myBombers, true, false, false);
			int bomberCount = myBombers.size();
			float myTotalStrength = myFighterStrength + myBomberStrength;
			Set<Territory> myNeighbors = data.getMap().getNeighbors(AttackFrom);
			Set<Territory> enemyNeighbors = data.getMap().getNeighbors(AttackFrom, Matches.territoryHasEnemyUnits(player, data));
			for (Territory check2 : myNeighbors)
			{
				Set<Territory> check2Terr = data.getMap().getNeighbors(check2, Matches.territoryHasEnemyUnits(player, data));
				if (check2Terr != null && check2Terr.size() > 0)
				{
					for (Territory enemyOnly : check2Terr)
					{
						if (!enemyNeighbors.contains(enemyOnly))
							enemyNeighbors.add(enemyOnly);
					}
				}
			}
			List<Territory> waterEnemies = new ArrayList<Territory>();
			for (Territory w : enemyNeighbors)
			{
				if (w.isWater())
				{
					waterEnemies.add(w);
					List<Unit> eUnits = w.getUnits().getMatches(Matches.enemyUnit(player, data));
					float waterStrength = SUtils.strength(eUnits, false, true, tFirst);
					float ourWaterStrength = 0.0F;
					if (w.getUnits().allMatch(enemySubUnit) && games.strategy.triplea.Properties.getAirAttackSubRestricted(data))
					{ //need a destroyer
						List<Territory> destroyerTerr = SUtils.findOurShips(w, data, player, destroyerUnit);
						boolean dAttacked = false;
						float dStrength = 0.0F;
						if (destroyerTerr.size() > 0)
						{
							for (Territory dT : destroyerTerr)
							{
								List<Unit> destroyers = dT.getUnits().getMatches(destroyerUnit);
								Route dRoute = SUtils.getMaxSeaRoute(data, dT, w, player);
								if (dRoute == null || dRoute.getLength() > 2)
									continue;
								List<Unit> dUnits = new ArrayList<Unit>();
								for (Unit d : destroyers)
								{
									if (MoveValidator.hasEnoughMovement(d, dRoute))
									{
										dUnits.add(d);
									}
								}
								if (dUnits.size() > 0)
								{
									moveUnits.add(dUnits);
									moveRoutes.add(dRoute);
									unitsAlreadyMoved.addAll(dUnits);
									dAttacked = true;
									ourWaterStrength += SUtils.strength(dUnits, true, false, tFirst);
								}
								
							}
						}
						if (dAttacked)
						{
							float stillNeeded = waterStrength*2.25F + 4.00F - ourWaterStrength;
							SUtils.invitePlaneAttack(false, false, w, stillNeeded, unitsAlreadyMoved, moveUnits, moveRoutes, data, player);
						}
					}
					else
					{
						float stillNeeded = waterStrength*2.25F + 4.00F;
						List<Collection<Unit>> xMoveUnits = new ArrayList<Collection<Unit>>();
						List<Route> xMoveRoutes = new ArrayList<Route>();
						List<Unit> xMoved = new ArrayList<Unit>(unitsAlreadyMoved);
						SUtils.invitePlaneAttack(false, false, w, stillNeeded, xMoved, xMoveUnits, xMoveRoutes, data, player);
						SUtils.inviteShipAttack(w, stillNeeded, xMoved, xMoveUnits, xMoveRoutes, data, player, true, tFirst, false);
						if (stillNeeded <= 1.0F)
						{
							moveUnits.addAll(xMoveUnits);
							moveRoutes.addAll(xMoveRoutes);
							for (Collection<Unit> qUnits : xMoveUnits)
								unitsAlreadyMoved.addAll(qUnits);
						}
					}
				}
				
			}
			enemyNeighbors.removeAll(waterEnemies);	
			if (enemyNeighbors != null)
			{ 
				for (Territory badGuys : enemyNeighbors)
				{
					List<Unit> enemyUnits = badGuys.getUnits().getMatches(Matches.enemyUnit(player, data));
					float badGuyStrength = 0.0F;
					if (badGuys.isWater())
						badGuyStrength = SUtils.strength(enemyUnits, false, true, tFirst);
					else
						badGuyStrength = SUtils.strength(enemyUnits, false, false, tFirst);
					int badGuyCount = enemyUnits.size();
					float needStrength = 2.4F*badGuyStrength + 3.00F;
					float actualStrength = 0.0F;
					List<Unit> myAttackers = new ArrayList<Unit>();
					List<Unit> myAttackers2 = new ArrayList<Unit>();
					if (myTotalStrength > needStrength && (fighterCount+bomberCount > badGuyCount+1) )
					{
						int actualAttackers = 0;
						Route myRoute = data.getMap().getRoute(AttackFrom, badGuys);
						for (Unit f : myFighters)
						{
							if (actualStrength < needStrength)
							{
								myAttackers.add(f);
								actualStrength += SUtils.airstrength(f, true);
								actualAttackers++;
							}
						}
						if (actualAttackers > 0 && myRoute != null && actualStrength > needStrength)
						{
							moveUnits.add(myAttackers);
							moveRoutes.add(myRoute);
							alreadyMoved.addAll(myAttackers);
							myFighters.removeAll(myAttackers);
						}
						if (actualStrength > needStrength && (actualAttackers > badGuyCount+1) )
							continue;
						for (Unit b : myBombers)
						{
							if (actualStrength < needStrength || (actualAttackers <= badGuyCount+1) )
							{
								myAttackers2.add(b);
								actualStrength += SUtils.airstrength(b, true);
							}
						}
						if (myAttackers.size() > 0 && myRoute != null)
						{
							moveUnits.add(myAttackers2);
							moveRoutes.add(myRoute);
							alreadyMoved.addAll(myAttackers2);
							myBombers.removeAll(myAttackers2);
						}
					}
				}
			}
		}
	}

    private void protectOurAllies(boolean nonCombat, final GameData data, List<Collection<Unit>> moveUnits, List<Route> moveRoutes, PlayerID player)
    {
 		CompositeMatch<Unit> landUnit = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player), Matches.UnitIsLand, Matches.UnitIsNotAA, Matches.UnitIsNotFactory);
 		CompositeMatch<Unit> carrierUnit = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player), Matches.UnitIsCarrier);
 		CompositeMatch<Unit> fighterUnit = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player), Matches.UnitCanLandOnCarrier);
        List<Territory> threats = new ArrayList<Territory>();
        boolean tFirst = transportsMayDieFirst(), noncombat = true;
        List<Unit> unitsAlreadyMoved = new ArrayList<Unit>();
        boolean capDanger = getCapDanger();
    	Territory myCapital = TerritoryAttachment.getCapital(player, data);
        if (capDanger) //don't use cap units if a threat to cap
        {
        	unitsAlreadyMoved.addAll(myCapital.getUnits().getMatches(Matches.unitIsOwnedBy(player)));
        }
        if (SUtils.shipThreatToTerr(myCapital, data, player, tFirst) > 2)
        {
        	//don't use fighters on AC near capital if there is a strong threat to ships
        	List<Territory> fighterTerr = SUtils.findOnlyMyShips(myCapital, data, player, carrierUnit);
        	for (Territory fT : fighterTerr)
        	{
        		unitsAlreadyMoved.addAll(fT.getUnits().getMatches(fighterUnit));
        	}
        	List<Territory> transportTerr = SUtils.findOnlyMyShips(myCapital, data, player, Matches.UnitIsTransport);
        	if (tFirst) //if transports have no value in ship fight...let them go...we can catch up to them in nonCombat
        	{
        		for (Territory tranTerr : transportTerr)
        		{
        			unitsAlreadyMoved.addAll(tranTerr.getUnits().getMatches(Matches.UnitIsTransport));
        		}
        	}
        
        }
        boolean alliedCapDanger = SUtils.threatToAlliedCapitals(data, player, threats, tFirst);
        if (alliedCapDanger)
        {
        	for (Territory testCap : threats)
        	{
        		float remainingStrengthNeeded = 1000.0F;
        		SUtils.invitePlaneAttack(true, false, testCap, remainingStrengthNeeded, unitsAlreadyMoved, moveUnits, moveRoutes, data, player);
        		SUtils.inviteBlitzAttack(true, testCap, remainingStrengthNeeded, unitsAlreadyMoved, moveUnits, moveRoutes, data, player, false, true);
        		Set<Territory> copyOne = data.getMap().getNeighbors(testCap, 1);
        		for (Territory moveFrom : copyOne)
        		{
        			if (!moveFrom.isWater() && moveFrom.getUnits().someMatch(landUnit))
        			{
        				List<Unit> helpUnits = moveFrom.getUnits().getMatches(landUnit);
        				Route aRoute = data.getMap().getRoute(moveFrom, testCap);
        				if (aRoute != null)
        				{
        					if (MoveValidator.hasEnoughMovement(helpUnits, aRoute))
        					{
        						moveUnits.add(helpUnits);
        						moveRoutes.add(aRoute);
        						unitsAlreadyMoved.addAll(helpUnits);
        					}
        					else
        					{
        						List<Unit> workList = new ArrayList<Unit>();
        						for (Unit goUnit : helpUnits)
        						{
        							if (MoveValidator.hasEnoughMovement(goUnit, aRoute))
        								workList.add(goUnit);
        						}
        						if (workList.size() > 0)
        						{
        							moveUnits.add(workList);
        							moveRoutes.add(aRoute);
        							unitsAlreadyMoved.addAll(workList);
        						}
        					}
        				}
        			}
        		}
        		if (SUtils.isWaterAt(testCap, data))
        			SUtils.inviteTransports(noncombat, testCap, remainingStrengthNeeded, unitsAlreadyMoved, moveUnits, moveRoutes, data, player);
        		else
        		{
        			Set<Territory> testCapNeighbors = data.getMap().getNeighbors(testCap, Matches.isTerritoryAllied(player, data));
        			for (Territory tCN : testCapNeighbors)
        			{
        				SUtils.inviteTransports(noncombat, tCN, remainingStrengthNeeded, unitsAlreadyMoved, moveUnits, moveRoutes, data, player);
        			}
        		}
          	}
        }
     }


    /**
	 * prepares nonCombat Moves for Sea
	 *
	 * @param nonCombat
	 * @param data
	 * @param moveUnits
	 * @param moveRoutes
	 * @param player
	 * @param amphibRoute
	 * @param maxTrans -
	 *            if -1 unlimited
	 */
    private void populateNonCombatSea(boolean nonCombat, final GameData data, List<Collection<Unit>> moveUnits, List<Route> moveRoutes, PlayerID player)
    {

       boolean tFirst = transportsMayDieFirst();
	   Territory eTerr[] = new Territory[data.getMap().getTerritories().size()];
	   float eStrength[] = new float[data.getMap().getTerritories().size()];


       final Collection<Unit> alreadyMoved = new HashSet<Unit>();
       Territory myCapital = TerritoryAttachment.getCapital(player, data);

	   Match<Unit> notAlreadyMoved =new CompositeMatchAnd<Unit>(new Match<Unit>()
		   {
				public boolean match(Unit o)
				{
					return !alreadyMoved.contains(o);
				}
			});
	   Match<Unit> ownedUnit = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player));
	   Match<Unit> ownedAC = new CompositeMatchAnd<Unit>(ownedUnit, Matches.UnitIsCarrier);
	   Match<Unit> HasntMoved2 = new CompositeMatchOr<Unit>(HasntMoved, notAlreadyMoved);
       Match<Unit> ownedAndNotMoved = new CompositeMatchAnd<Unit>(ownedUnit, HasntMoved2);
       Match<Unit> seaOwnedAndNotMoved = new CompositeMatchAnd<Unit>(ownedUnit, Matches.UnitIsNotLand, HasntMoved2);
       Match<Unit> enemySeaUnit = new CompositeMatchAnd<Unit>(Matches.UnitIsSea, Matches.enemyUnit(player, data));
       Match<Unit> enemyAirUnit = new CompositeMatchAnd<Unit>(Matches.UnitIsAir, Matches.enemyUnit(player, data));
       Match<Unit> enemyLandUnit = new CompositeMatchAnd<Unit>(Matches.UnitIsLand, Matches.enemyUnit(player, data));
       Match<Unit> landOrAirEnemy = new CompositeMatchOr<Unit>(enemyAirUnit, enemyLandUnit);
       Match<Unit> seaAttackUnit = new CompositeMatchAnd<Unit>(ownedUnit, Matches.UnitIsSea, Matches.UnitIsNotTransport);
       Match<Unit> airAttackUnit = new CompositeMatchAnd<Unit>(ownedUnit, Matches.UnitIsAir);
       Match<Unit> subUnit = new CompositeMatchAnd<Unit>(ownedUnit, Matches.UnitIsSub);
       Match<Unit> seaAirAttackUnit = new CompositeMatchOr<Unit>(seaAttackUnit, airAttackUnit);
	   Match<Unit> seaAirAttackUnitNotMoved = new CompositeMatchAnd<Unit>(seaAirAttackUnit, HasntMoved2);
       Match<Unit> fighterUnit = new CompositeMatchAnd<Unit>(Matches.UnitCanLandOnCarrier, ownedUnit, HasntMoved2);
       Match<Unit> fighterUnit2 = new CompositeMatchAnd<Unit>(Matches.UnitCanLandOnCarrier, ownedUnit);
       Match<Unit> bomberUnit = new CompositeMatchAnd<Unit>(Matches.UnitIsStrategicBomber, ownedUnit, HasntMoved2);

       Match<Unit> alliedSeaAttackUnit = new CompositeMatchAnd<Unit>(Matches.alliedUnit(player, data), Matches.UnitIsSea);
       Match<Unit> alliedAirAttackUnit = new CompositeMatchAnd<Unit>(Matches.alliedUnit(player, data), Matches.UnitIsAir);
       Match<Unit> alliedSeaAirAttackUnit = new CompositeMatchOr<Unit>(alliedSeaAttackUnit, alliedAirAttackUnit);


       List <Territory> seaAttackTerr = SUtils.findCertainShips(data, player, seaAttackUnit);
       List <Territory> enemySeaTerr = SUtils.findUnitTerr(data, player, enemySeaUnit);
       List <Territory> mySubTerr = SUtils.findUnitTerr(data, player, subUnit);
       List <Territory> myFighterTerr = SUtils.findUnitTerr(data, player, fighterUnit2);
       List <Territory> skippedTerr = new ArrayList<Territory>();
       /**
        * First determine if attack ships have been purchased and limit moves at that factory
        */
       List<Unit> xMoved = new ArrayList<Unit>();
       markBaseShips(data, player, xMoved);
       if (xMoved.size() > 0)
       { //we have locked down ships around capitol, can we combine them?
    	   Set<Territory> neighborList = data.getMap().getNeighbors(myCapital, Matches.TerritoryIsWater);
    	   List<Route> xR = new ArrayList<Route>();
    	   List<Collection<Unit>> xM = new ArrayList<Collection<Unit>>();
    	   List<Unit> xAM = new ArrayList<Unit>();
    	   int maxShips = 0;
    	   Territory maxShipTerr = null, maxStrengthTerr = null;
    	   float maxStrength = 0.0F;
    	   float goStrength = 1000.0F;
    	   for (Territory nT : neighborList)
    	   {
    		   SUtils.inviteShipAttack(nT, goStrength, xAM, xM, xR, data, player, false, tFirst, true);
    		   int unitCount = xAM.size();
    		   if (unitCount > maxShips)
    		   {
    			   maxShipTerr = nT;
    			   maxShips = unitCount;
    		   }
    		   float thisStrength = SUtils.strength(xAM, false, true, tFirst);
    		   if (thisStrength > maxStrength)
    		   {
    			   maxStrengthTerr = nT;
    			   maxStrength = thisStrength;
    		   }
    		   xAM.clear();
    		   xM.clear();
    		   xR.clear();
    	   }
//TODO: incorporate intelligence between maxStrength & maxShip
    	   if (maxStrengthTerr != null)
    		   SUtils.inviteShipAttack(maxStrengthTerr, goStrength, alreadyMoved, moveUnits, moveRoutes, data, player, false, tFirst, true);
    	   else if (maxShipTerr != null)
    		   SUtils.inviteShipAttack(maxShipTerr, goStrength, alreadyMoved, moveUnits, moveRoutes, data, player, false, tFirst, true);
    	
    		   
       }
       alreadyMoved.addAll(xMoved);
       Territory goHere = null, seaTarget = null;
       float getStrength = 0.0F, badGuyStrength = 0.0F;
       //first check our attack ship territories
       for (Territory myTerr : seaAttackTerr)
       {
		  List <Unit> myAttackUnits = myTerr.getUnits().getMatches(seaAirAttackUnit);
		  List <Unit> alliedAttackUnits = myTerr.getUnits().getMatches(alliedSeaAirAttackUnit);
	      Set <Territory> Check1 = data.getMap().getNeighbors(myTerr, Matches.territoryHasEnemyUnits(player, data));
	      boolean keepGoing = true;
	      badGuyStrength = SUtils.getStrengthOfPotentialAttackers(myTerr, data, player, tFirst, false);
		  getStrength = SUtils.strength(myAttackUnits, false, true, tFirst);
		  getStrength += SUtils.strength(alliedAttackUnits, false, true, tFirst);
		  if ((getStrength + 3.00F) > badGuyStrength && (badGuyStrength > 2.00F))
			  keepGoing = false;
		  if (!keepGoing)
		  {
		    skippedTerr.add(myTerr);
		  	continue;
		  }
		  //are there fighters over water that we can help land?
		  if (myTerr.getUnits().someMatch(ownedAC))
		  {
			  List<Unit> ACUnits = myTerr.getUnits().getMatches(ownedAC);
			  for (Territory f : myFighterTerr)
			  {
				  if (f == myTerr || !f.isWater())
					  continue;
				  Route fACRoute = SUtils.getMaxSeaRoute(data, myTerr, f, player);
				  if (fACRoute == null)
					  continue;
				  List<Unit> myFighters = f.getUnits().getMatches(fighterUnit2);
				  int fACDist = fACRoute.getLength();
				  int fightMove = MoveValidator.getMaxMovement(myFighters);
				  if (MoveValidator.canLand(myFighters, myTerr, player, data))
				  {
					  Route fACRoute2 = data.getMap().getRoute(f, myTerr, Matches.TerritoryIsNotImpassable);
					  if (fACRoute2 != null)
					  {
						  moveUnits.add(myFighters);
						  moveRoutes.add(fACRoute2);
						  alreadyMoved.addAll(myFighters);
						  alreadyMoved.addAll(ACUnits);
					  }
					  continue;
				  }
				  int ACMove = MoveValidator.getMaxMovement(ACUnits);
				  if (fACDist <= (fightMove + ACMove)) //move everything except transports
				  {
					  List<Unit> ourUnits = myTerr.getUnits().getMatches(seaAirAttackUnit);
					  Territory targetTerr = fACRoute.getTerritories().get(fACRoute.getLength() - 1);
					  if (data.getMap().getWaterDistance(myTerr, targetTerr) <= ACMove)
					  {
						  Route fACRoute3 = SUtils.getMaxSeaRoute(data, myTerr, targetTerr, player);
						  boolean shipMoved = false;
						  if (fACRoute3 != null && MoveValidator.hasEnoughMovement(ourUnits, fACRoute3))
						  {
							  moveUnits.add(ourUnits);
							  moveRoutes.add(fACRoute3);
							  alreadyMoved.addAll(ourUnits);
						  }
						  if (shipMoved)
						  {
							  Route fACRoute4 = data.getMap().getRoute(f, targetTerr, Matches.TerritoryIsNotImpassable);
							  if (fACRoute4 != null && MoveValidator.hasEnoughMovement(myFighters, fACRoute4))
							  {
								  moveUnits.add(myFighters);
								  moveRoutes.add(fACRoute4);
								  alreadyMoved.addAll(myFighters);
							  }
						  }
					  }
					  
				  }
			  }
		  }
 //This overrides everything below, but it gets the ships moving...obviously we may be sacrificing them...
		  Route quickRoute = null;
		  int minSeaDist = 100;
		  for (Territory badSeaTerr : enemySeaTerr)
		  {
		    Route seaCheckRoute = SUtils.getMaxSeaRoute(data, myTerr, badSeaTerr, player);
		    if (seaCheckRoute == null)
		       continue;
		    int newDist = seaCheckRoute.getLength();
		    if (newDist > minSeaDist)
		    {
		       goHere = badSeaTerr;
		       minSeaDist = newDist;
		       quickRoute = seaCheckRoute;
		    }
		  }
		  myAttackUnits.removeAll(alreadyMoved);
		  if (goHere != null && quickRoute != null)
		  {
		     moveUnits.add(myAttackUnits);
		     moveRoutes.add(quickRoute);
		     alreadyMoved.addAll(myAttackUnits);
		  }
	      goHere=null;
	   }
	   //check the skipped Territories...see if there are ships we can combine
	   List<Territory> dontMoveFrom = new ArrayList<Territory>();
	   for (Territory check1 : skippedTerr)
	   {
		   for (Territory check2 : skippedTerr)
		   {
			   if (check1==check2 || dontMoveFrom.contains(check2))
			      continue;
			   int checkDist = data.getMap().getDistance(check1, check2);
			   if (checkDist <= 2)
			   {
				   List<Unit> swapUnits = check2.getUnits().getMatches(seaAirAttackUnitNotMoved);
				   swapUnits.removeAll(alreadyMoved);
				   Route swapRoute = SUtils.getMaxSeaRoute(data, check2, check1, player);
				   if (swapRoute != null)
				   {
					   moveUnits.add(swapUnits);
					   moveRoutes.add(swapRoute);
					   alreadyMoved.addAll(swapUnits);
					   dontMoveFrom.add(check1); //make sure check1 is blocked on the 2nd pass...ships are moving to it
				   }
			   }
		   }
	   }


//Get planes off of the capital and moving toward the enemy
	   List<Territory> enemy=SUtils.allEnemyTerritories(data, player);
	   int i=0;
	   for (Territory t2 : enemy) //find strength of all enemy terr (defensive)
	   {
	       eTerr[i]=t2;
	       eStrength[i]=SUtils.strength(t2.getUnits().getMatches(landOrAirEnemy), false, false, tFirst);
		   i++;
	   }
	   float tmpStrength = 0.0F;
	   Territory tmpTerr = null;
	   for (int j2=0; j2<i-1; j2++) //sort the territories by strength
	   {
	       tmpTerr = eTerr[j2];
	       tmpStrength = eStrength[j2];
	       Set<Territory>badFactTerr = data.getMap().getNeighbors(tmpTerr, Matches.territoryHasEnemyFactory(data, player));
	       if (badFactTerr.contains(tmpTerr) && tmpStrength*1.10F <= eStrength[j2+1])
	           continue; //if it is a factory, don't move it down
	       if (tmpStrength*1.03 >= eStrength[j2+1])
	       {
	           eTerr[j2]=eTerr[j2+1];
	           eStrength[j2]=eStrength[j2+1];
	           eTerr[j2+1]=tmpTerr;
	           eStrength[j2+1]=tmpStrength;
	       }
	   }
	   List<Territory> fTerr = SUtils.findUnitTerr(data, player, fighterUnit);
	   List<Territory> bTerr = SUtils.findUnitTerr(data, player, bomberUnit);
	   List<Territory> allTerr = new ArrayList<Territory>();
	   if (fTerr != null)
	   	  allTerr.addAll(fTerr);
	   if (bTerr != null)
	      allTerr.addAll(bTerr);

 	   if (nonCombat)
	   {
		  for (Territory newTerr : allTerr)
		  {
			 boolean enemyFound = false;
			 Set<Territory> sNewTerr = data.getMap().getNeighbors(newTerr, 2);
			 for (Territory cEnemyTerr : sNewTerr)
			 {
				 if (Matches.territoryHasEnemyUnits(player, data).match(cEnemyTerr))
				 	enemyFound = true;
			 }
			 if (enemyFound)
			     continue;
			 Territory goPoint = eTerr[0];
			 Route capRoute = data.getMap().getRoute(newTerr, goPoint);
		     if (capRoute == null)
		     	continue;

		  	 int cRLen = capRoute.getLength();
		  	 boolean foundit = false;
			 Territory BtargetTerr = null;
			 Territory FtargetTerr = null;
			 if (cRLen > 3) //make sure there is reasonable distance
			 {
				boolean safeBFly = false;
				int jj=4;
				while (!safeBFly && jj>0)
				{
					BtargetTerr = capRoute.getTerritories().get(jj);
					if (BtargetTerr.getUnits().someMatch(Matches.unitIsEnemyAA(player, data)))
					{
					   jj--;
					   continue;
				    }
				    safeBFly=true;
				}

				boolean safeFFly = false;
				jj=3;
				while (!safeFFly && jj>0)
				{
					FtargetTerr = capRoute.getTerritories().get(jj);
					if (FtargetTerr.getUnits().someMatch(Matches.unitIsEnemyAA(player, data)))
					{
					   jj--;
					   continue;
				    }
				    safeFFly=true;
				}

//				FtargetTerr = capRoute.getTerritories().get(2);
				if (safeFFly || safeBFly)
					foundit = true;
			}
			if (foundit)
			{
				List <Unit> fAirUnits = newTerr.getUnits().getMatches(fighterUnit);
				List <Unit> bombUnits = newTerr.getUnits().getMatches(bomberUnit);
				Route BcapRoute = data.getMap().getRoute(newTerr, BtargetTerr);
				Route FcapRoute = data.getMap().getRoute(newTerr, FtargetTerr);
				if (BcapRoute != null && bombUnits.size() > 0)
				{
					boolean canLand = true;
					for (Unit b1 : bombUnits)
					{
						if (canLand)
							canLand = SUtils.airUnitIsLandable(b1, newTerr, BtargetTerr, player, data);
					}
					if (canLand)
					{
						moveRoutes.add(BcapRoute);
						moveUnits.add(bombUnits);
						alreadyMoved.addAll(bombUnits);
					}
				}
				if (FcapRoute != null && fAirUnits.size() > 0 && !newTerr.getUnits().someMatch(ownedAC))
				{
					boolean canLand = true;
					for (Unit f1 : fAirUnits)
					{
						if (canLand)
							canLand = SUtils.airUnitIsLandable(f1, newTerr, FtargetTerr, player, data);
					}
					if (canLand)
					{
						moveRoutes.add(FcapRoute);
						moveUnits.add(fAirUnits);
						alreadyMoved.addAll(fAirUnits);
					}
				}
			}
		  }
	   }
	   //other planes...move toward the largest enemy mass of units


      for (Territory subTerr : mySubTerr)
      {
		if (!subTerr.isWater() || seaTarget == null || subTerr == seaTarget)
			continue;
		List<Unit> allMyUnits = subTerr.getUnits().getMatches(ownedUnit);
		allMyUnits.removeAll(alreadyMoved);
		Route myRoute = SUtils.getMaxSeaRoute(data, subTerr, seaTarget, player);
		moveUnits.add(allMyUnits);
		moveRoutes.add(myRoute);
	  }
    }

	private void nonCombatPlanes(final GameData data, final PlayerID player, List<Collection<Unit>> moveUnits, List<Route> moveRoutes)
	{
	  //specifically checks for available Carriers and finds a place for plane
	    Match<Unit> ownedUnit = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player));
        Match<Unit> ACOwned = new CompositeMatchAnd<Unit>(ownedUnit, Matches.UnitIsCarrier);
        Match<Unit> ACAllied = new CompositeMatchAnd<Unit>(Matches.alliedUnit(player, data), Matches.UnitIsCarrier);
        Match<Unit> fighterAndAllied = new CompositeMatchAnd<Unit>(Matches.alliedUnit(player, data), Matches.UnitCanLandOnCarrier);
        Match<Unit> fighterAndOwned = new CompositeMatchAnd<Unit>(ownedUnit, Matches.UnitCanLandOnCarrier);
        Match<Unit> alliedUnit = new CompositeMatchAnd<Unit>(Matches.alliedUnit(player, data));
        List<Unit> unitsAlreadyMoved = new ArrayList<Unit>();
        Territory myCapital = TerritoryAttachment.getCapital(player, data);
        boolean capDanger = getCapDanger();
        boolean tFirst = transportsMayDieFirst();
        List<Territory> fighterTerr = SUtils.findCertainShips(data, player, fighterAndOwned);
        List<Territory> alliedThreats = new ArrayList<Territory>();
        boolean alliedDanger = SUtils.threatToAlliedCapitals(data, player, alliedThreats, tFirst);
        if (capDanger)
        {
        	unitsAlreadyMoved.addAll(myCapital.getUnits().getMatches(fighterAndOwned)); 
        }
        if (alliedDanger)
        {
        	for (Territory aThreat : alliedThreats)
        	{
        		if (aThreat.getUnits().someMatch(fighterAndOwned))
        			unitsAlreadyMoved.addAll(aThreat.getUnits().getMatches(fighterAndOwned));
        	}
        }
        List <Territory> acTerr = SUtils.ACTerritory(player, data);
		for (Territory t : data.getMap())
		{
		   List<Unit> tPlanes = t.getUnits().getMatches(fighterAndOwned);
		   if (tPlanes.size() <= 0 || acTerr.contains(t))
		       continue;
		   if (acTerr.size() > 0)
		   {
		   	   for (Territory acT : acTerr)
		       {
				   List <Unit> acUnits = acT.getUnits().getMatches(ACOwned);
				   List <Unit> alliedACUnits = acT.getUnits().getMatches(ACAllied);
				   List <Unit> airOnAC = acT.getUnits().getMatches(fighterAndAllied);
				   int airOnACCount = airOnAC.size()-alliedACUnits.size()*2;
				   int availSpace = acUnits.size()*2 - airOnACCount;
				   if (availSpace > 0)
				   {
					   float remainingStrengthNeeded = 1000.0F;
					   SUtils.invitePlaneAttack(true, true, acT, remainingStrengthNeeded, unitsAlreadyMoved, moveUnits, moveRoutes, data, player);
/*				   	   Route airToCarrier = data.getMap().getRoute(t, acT);
				   	   int tDist = data.getMap().getDistance(t, acT);
				   	   if (availSpace <= tPlanes.size())
					   {
						   int numPlanes = tPlanes.size();
				           for (int ac1 = (numPlanes-1); ac1>= availSpace; ac1--)
				               tPlanes.remove(ac1);
					   }
					   List<Unit> tPCopy = new ArrayList<Unit>(tPlanes);
					   int numPlanes = tPlanes.size();
					   for (Unit tP : tPCopy)
					   {
						   if (!MoveValidator.hasEnoughMovement(tP, tDist))
						   {
						       tPlanes.remove(tP);
						       numPlanes--;
						   }
					   }
					   if (numPlanes > 0)
					   {
					       moveRoutes.add(airToCarrier);
					       moveUnits.add(tPlanes);
					   }
*/				   }
				   else if ( availSpace < 0) //need to move something off
				   {
					   List<Unit> alreadyMoved = new ArrayList<Unit>();
					   List<Unit> myFighters = acT.getUnits().getMatches(fighterAndOwned);
					   int maxPass = 0;
					   while (availSpace < 0 && maxPass <= myFighters.size())
					   {
						   int max = 0;
						   maxPass++;
						   Iterator<Unit> iter = myFighters.iterator();
						   Unit moveIt = null;
						   while(iter.hasNext())
						   {
						       Unit unit = (Unit) iter.next();
							   if (alreadyMoved.contains(unit))
							      continue;
						       int left = TripleAUnit.get(unit).getMovementLeft();
						       if (left >= max)
						       {
								   max = left;
								   moveIt = unit;
							   }
						   }
						   if (moveIt == null) //no planes can move!!!
						       continue;
						   Set<Territory> checkTerrs = data.getMap().getNeighbors(acT);
						   Territory bestTerr = null;
						   int maxFriendly = 0;
						   for (Territory checkTerr : checkTerrs)
						   {
							   if (checkTerr == acT)
							       continue;
							   if (checkTerr.isWater() && max > 1)
							   {
								   Set<Territory> checkTerrs2 = data.getMap().getNeighbors(checkTerr);
								   for (Territory checkTerr2 : checkTerrs2)
								   {
									   if (Matches.TerritoryIsLand.match(checkTerr2) && Matches.isTerritoryAllied(player, data).match(checkTerr2))
									       bestTerr = checkTerr2;
								   }
							   }
							   else if (Matches.TerritoryIsLand.match(checkTerr) && Matches.isTerritoryAllied(player, data).match(checkTerr))
							   {
							       List <Unit> aUnits = checkTerr.getUnits().getMatches(alliedUnit);
							       if (aUnits.size() >= maxFriendly)
							       {
									   maxFriendly = aUnits.size();
									   bestTerr = checkTerr;
								   }
							   }
						   }
						   if (bestTerr != null) //otherwise it is sunk
						   {
							   List<Unit> unitsToMove = new ArrayList<Unit>();
							   unitsToMove.add(moveIt);
							   Route bestRoute = SUtils.getMaxSeaRoute(data, acT, bestTerr, player);
							   moveUnits.add(unitsToMove);
							   moveRoutes.add(bestRoute);
							   alreadyMoved.add(moveIt);
							   availSpace++; //one less to worry about
						   }
					   }
				   }
			   }
			}
		}
	}

    private void populateCombatMoveSea(GameData data, List<Collection<Unit>> moveUnits, List<Route> moveRoutes, PlayerID player)
    {

    	boolean attackShipsPurchased = getAttackShipPurchase();
        TransportTracker tracker = DelegateFinder.moveDelegate(data).getTransportTracker();
		boolean tFirst = transportsMayDieFirst();
        final Collection<Unit> unitsAlreadyMoved = new HashSet<Unit>();
        final Collection<Unit> planesAlreadyMoved = new HashSet<Unit>();

        List<Collection<Unit>> attackUnits = new ArrayList<Collection<Unit>>();
        List<Collection<Unit>> planesMoved = new ArrayList<Collection<Unit>>();
        Collection <Unit> allSeaUnits = new ArrayList<Unit>();
        Collection <Territory> allBomberTerr = new ArrayList<Territory>();

	    Match<Unit> notAlreadyMoved =new CompositeMatchAnd<Unit>(new Match<Unit>()
		    {
				public boolean match(Unit o)
				{
					return !unitsAlreadyMoved.contains(o);
				}
		 	});
	    Match<Unit> ownedUnit = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player));
		CompositeMatch<Unit> seaUnit = new CompositeMatchAnd<Unit>(ownedUnit, Matches.UnitIsSea, notAlreadyMoved);
		CompositeMatch<Unit> airUnit = new CompositeMatchAnd<Unit>(ownedUnit, Matches.UnitIsAir, notAlreadyMoved);
		CompositeMatch<Unit> seaAirUnit = new CompositeMatchOr<Unit>(seaUnit, airUnit);
		CompositeMatch<Unit> alliedSeaUnit = new CompositeMatchAnd<Unit>(Matches.alliedUnit(player, data), Matches.UnitIsSea);
		CompositeMatch<Unit> alliedAirUnit = new CompositeMatchAnd<Unit>(Matches.alliedUnit(player, data),Matches.UnitIsAir);
		CompositeMatch<Unit> alliedSeaAirUnit = new CompositeMatchOr<Unit>(alliedAirUnit, alliedSeaUnit);
        CompositeMatch<Unit> attackable = new CompositeMatchAnd<Unit>(ownedUnit, notAlreadyMoved);
        CompositeMatch<Unit> enemySeaUnit = new CompositeMatchAnd<Unit>(Matches.enemyUnit(player, data), Matches.UnitIsSea);
        CompositeMatch<Unit> enemyAirUnit = new CompositeMatchAnd<Unit>(Matches.enemyUnit(player, data), Matches.UnitIsAir);
        CompositeMatch<Unit> enemyAirSeaUnit = new CompositeMatchOr<Unit>(enemySeaUnit, enemyAirUnit);
        CompositeMatch<Unit> enemyNonTransport = new CompositeMatchAnd<Unit>(enemySeaUnit, Matches.UnitIsNotTransport);
        CompositeMatch<Unit> carrierUnit = new CompositeMatchAnd<Unit>(ownedUnit, Matches.UnitIsCarrier);
        CompositeMatch<Unit> carrierGroup = new CompositeMatchOr<Unit>(carrierUnit, airUnit);

        List<Route> attackRoute = new ArrayList<Route>();
        List<Route> planeRoute = new ArrayList<Route>();
        Route thisRoute = new Route();

		int rDist=0;
		float attackFactor = 1.13F; //adjust to make attacks more or less likely (1.05 is too low)
		Territory sortTerritories[] = new Territory[data.getMap().getTerritories().size()] ; //revised game has 79 territories and 64 sea zones
		float sortStrength[] = new float[data.getMap().getTerritories().size()];
		int numTerr = 0;
		float xStrength = 0.0F;

		for (Territory t: data.getMap())
		{
			if (!t.isWater() || !t.getUnits().someMatch(enemySeaUnit))
				continue;
			sortTerritories[numTerr] = t;
			sortStrength[numTerr] = SUtils.strength(t.getUnits().getMatches(enemyAirSeaUnit), false, true, tFirst);
			numTerr++;
		}
		for (int i2=1; i2 <=(numTerr - 2); i2++)
		{
			for (int i=0; i<= (numTerr - 2); i++)
			{
				if (sortStrength[i] < sortStrength[i+1])
				{
					Territory xTerr = sortTerritories[i];
					xStrength = sortStrength[i];
					sortTerritories[i] = sortTerritories[i+1];
					sortStrength[i] = sortStrength[i+1];
					sortTerritories[i+1] = xTerr;
					sortStrength[i+1] = xStrength;
				}
			}
		}
        //Find Bombers
        for (Territory bTerr: data.getMap())
        {
			if (bTerr.getUnits().someMatch(Matches.UnitIsStrategicBomber))
				allBomberTerr.add(bTerr);
		}
		int numCarriers = 0, numTransports = 0, unitCount = 0, maxShipCount = 0;
		Territory maxShipsTerr = null, seaPlaceFact = null;
		boolean seaTerrSet = false;
		Territory myCapital = TerritoryAttachment.getCapital(player, data);
		HashMap <Territory, Float> checkForMorePlanes = new HashMap<Territory, Float>();
		List<Collection<Unit>> xMoves = new ArrayList<Collection<Unit>>();
		List<Route> xRoutes = new ArrayList<Route>();
		List<Unit> xAlreadyMoved = new ArrayList<Unit>();
		/**
		 * If ships were purchased because of large ship disadvantage, bring close ships to the spot
		 * unless we can take out the ships at a point next to our capital
		 */
		if (attackShipsPurchased)
		{
			seaPlaceFact = getSeaTerr();
			if (seaPlaceFact == null)
			{ //if purchasing didn't specify the factory, figure it out
				for (Territory bestTarget : SUtils.findCertainShips(data, player, Matches.UnitIsFactory))
				{
					int thisShipCount = SUtils.shipThreatToTerr(bestTarget, data, player, tFirst);
					if (thisShipCount > maxShipCount)
					{
						seaPlaceFact = bestTarget;
						maxShipCount = thisShipCount;
					}
				}
			}
			else
			{
				maxShipCount = SUtils.shipThreatToTerr(seaPlaceFact, data, player, tFirst);
				seaTerrSet = true;
			}
			boolean attackGroup = false;
			if (myCapital != null && seaPlaceFact == myCapital)
			{ 
				/**
				 * Can we take out the largest group and reduce enemy to a manageable size?
				 * Steps: 1) Find largest group
				 *        2) Figure out the remaining Strength
				 *        3) See if units purchased is > remaining Strength {attack!}
				 *        4)     if NOT, see how many units are remaining if we win battle
				 *        5) Add those to purchased set and see if > remaining Enemy Strength {attack!}
				 */
				List<Territory> eSTerr = SUtils.findUnits(myCapital, data, enemySeaUnit, 3);
				float totStrengthEnemyShips = 0.0F;
				int terrCounter = eSTerr.size();
				int maxUnitCount = 0, totUnitCount = 0;
				float maxStrength = 0.0F;
				Territory largestGroupTerr = null;
				List<Unit> largestGroup = new ArrayList<Unit>();
				for (Territory eST : eSTerr)
				{
					List<Unit> enemyGroup = eST.getUnits().getMatches(enemySeaUnit);
					totUnitCount += enemyGroup.size();
					float thisGroupStrength = SUtils.strength(enemyGroup, false, true, tFirst );
					totStrengthEnemyShips += thisGroupStrength;
					if (thisGroupStrength > maxStrength)
					{
						maxStrength = thisGroupStrength;
						largestGroupTerr = eST;
						maxUnitCount = enemyGroup.size();
						largestGroup.addAll(enemyGroup);
					}
				}
				if (largestGroupTerr != null)
				{
					float remainingStrength = totStrengthEnemyShips;
					SUtils.inviteShipAttack(largestGroupTerr, remainingStrength, xAlreadyMoved, xMoves, xRoutes, data, player, true, tFirst, true);
					SUtils.invitePlaneAttack(false, false, largestGroupTerr, remainingStrength, xAlreadyMoved, xMoves, xRoutes, data, player);
					float thisAttackStrength = (totStrengthEnemyShips - remainingStrength);
					if (thisAttackStrength > maxStrength) //what happens if we knock out the biggest group?
					{
						float newStrength = totStrengthEnemyShips - thisAttackStrength;
						int remainingUnitCount = totUnitCount - maxUnitCount;
						List<Unit> ourShips = new ArrayList<Unit>();
						for (Collection<Unit> shipGroup : xMoves)
						{
							ourShips.addAll(shipGroup);
						}
						IntegerMap<UnitType> ourUnits = SUtils.convertListToMap(ourShips);
						int ourOriginalCount = ourUnits.totalValues();
						IntegerMap<UnitType> enemyUnits = SUtils.convertListToMap(largestGroup);
						PlayerID ePlayer = largestGroupTerr.getOwner();
						if (ePlayer == null)
						{
							int xP = 0;
							while (ePlayer == null && xP < largestGroup.size())
								largestGroup.get(xP).getOwner();
						}
						boolean weWin = SUtils.quickBattleEstimator(ourUnits, enemyUnits, player, ePlayer);
						float adjustedStrength = SUtils.strength(player.getUnits().getMatches(seaAirUnit), true, true, tFirst);
						if (newStrength < adjustedStrength) //ATTACK!
							attackGroup = true;
						else if (weWin)
						{
							int remainingShips = totUnitCount - ourUnits.totalValues() + player.getUnits().getMatches(seaAirUnit).size();
							if (remainingShips <= 2) //ATTACK!
								attackGroup = true;
						}
						if (attackGroup)
						{
							moveUnits.addAll(xMoves);
							moveRoutes.addAll(xRoutes);
							unitsAlreadyMoved.addAll(xAlreadyMoved);
						}
					}
				}
				int localMax = 0, localShipCount = 0;
				for (Territory x : data.getMap().getNeighbors(seaPlaceFact, Matches.TerritoryIsWater))
				{
					localShipCount = x.getUnits().countMatches(alliedSeaUnit);
					if (localShipCount > localMax)
					{
						maxShipsTerr = x;
						localMax = localShipCount;
					}
				}
				List<Territory> shipTerrAtCapitol = SUtils.findOnlyMyShips(seaPlaceFact, data, player, Matches.UnitIsSea);
				for (Territory xT : shipTerrAtCapitol)
				{
					Route localShipRoute = SUtils.getMaxSeaRoute(data, xT, maxShipsTerr, player);
					if (localShipRoute != null && localShipRoute.getLength() <= 2)
					{
						List<Unit> myShips = xT.getUnits().getMatches(Matches.unitIsOwnedBy(player));
						if (myShips.size() > 0)
						{
							moveUnits.add(myShips);
							moveRoutes.add(localShipRoute);
							unitsAlreadyMoved.addAll(myShips);
						}
					}
				}
				
			}
			if (!attackGroup && seaPlaceFact != null)
			{
				setKeepShipsAtBase(true);
				if (!seaTerrSet)
					setSeaTerr(seaPlaceFact);
				List<Unit> xMoved = new ArrayList<Unit>();
				markBaseShips(data, player, xMoved);
				unitsAlreadyMoved.addAll(xMoved);
			}
			if (!attackGroup)
				s_logger.fine("Player: " + player.getName()+"; Attack Ships Purchased. Limiting ship movement at: " + seaPlaceFact.getName());
		}
		xMoves.clear();
		xRoutes.clear();
		xAlreadyMoved.clear();
        for (int i=0; i< numTerr; i++)
        {
			Territory t = sortTerritories[i];
            if(!t.isWater())
                continue;
            List<Unit> thisAttackPlanes = new ArrayList<Unit>();
            float enemyStrength = sortStrength[i];
            float strengthNeeded = attackFactor*enemyStrength + 3.0F;
			if (enemyStrength==0)
                continue;

            Territory enemy = t;
            unitCount=0;
            numTransports = 0; //need to make sure we don't only attack with transports

            float ourStrength = 0.0F;
            float maxStrengthNeeded = 2.4F * enemyStrength + 3.0F;
            float minStrengthNeeded = Math.min(enemyStrength + 10.0F, maxStrengthNeeded);
            if (!tFirst)
            {
            	minStrengthNeeded = 3.0F;
            	maxStrengthNeeded = 3.0F;
            }
            float starterStrength = minStrengthNeeded;

            Collection<Territory> attackFrom = data.getMap().getNeighbors(enemy, 2);

            attackUnits.clear();
            attackRoute.clear();
            boolean shipsAttacked = false;
            boolean AttackShipsPresent = t.getUnits().someMatch(enemyNonTransport);
            /**
             * If only transports:
             * 1) What is the potential Attack @ t
             * 2) Do we have a ship unit block large enough to take it out?
             * 3) If not, can we send planes without moving there and stay away from danger where we are?
             * Remember that this will be low on the strength list, so already looked at major attacks
             */
            if (!AttackShipsPresent) //all transports
            { 
            	xAlreadyMoved.addAll(unitsAlreadyMoved);
    			SUtils.invitePlaneAttack(false, false, enemy, minStrengthNeeded, xAlreadyMoved, xMoves, xRoutes, data, player);
    			maxStrengthNeeded -= (starterStrength - minStrengthNeeded);
    			if (minStrengthNeeded > 0.0F)
    			{
//    				int thisTerrThreat = SUtils.shipThreatToTerr(enemy, data, player, tFirst);
//					float realStrength = SUtils.getStrengthOfPotentialAttackers(enemy, data, player, tFirst, true);
					float strengthNow = maxStrengthNeeded;
					shipsAttacked = SUtils.inviteShipAttack(enemy, maxStrengthNeeded, xAlreadyMoved, xMoves, xRoutes, data, player, true, tFirst, tFirst);
					minStrengthNeeded -= strengthNow - maxStrengthNeeded;
    			}
    			if (minStrengthNeeded < 0.0F)
    			{
    				moveRoutes.addAll(xRoutes);
    				for (Collection<Unit> xUnits : xMoves)
    					moveUnits.add(xUnits);
    				unitsAlreadyMoved.addAll(xAlreadyMoved);
    				if (maxStrengthNeeded > 0.0F)
    					checkForMorePlanes.put(enemy, maxStrengthNeeded);
    			}
    			xRoutes.clear();
    			xMoves.clear();
    			xAlreadyMoved.clear();
    			continue;
            }
            xRoutes.clear();
            xMoves.clear();
            xAlreadyMoved.clear();
            boolean planesWorked = false;
            starterStrength = minStrengthNeeded;
            SUtils.invitePlaneAttack(false, false, enemy, minStrengthNeeded, xAlreadyMoved, xMoves, xRoutes, data, player);
            float planeStrength = starterStrength - minStrengthNeeded;
            if (minStrengthNeeded < 0.0F && !(planeStrength > starterStrength*2.0F) ) //good chance of losing a plane
            {
            	starterStrength += 8.0F;
            	minStrengthNeeded = 8.0F;
            	planesWorked = true;
            }
            shipsAttacked = SUtils.inviteShipAttack(enemy, minStrengthNeeded, xAlreadyMoved, xMoves, xRoutes, data, player, true, tFirst, tFirst);
 			float alliedStrength = ourStrength + (starterStrength - minStrengthNeeded);
			if (minStrengthNeeded > 0.0F && shipsAttacked) //discourage planes attacking when outnumbered
			{
				Set<Territory> alliedCheck = data.getMap().getNeighbors(enemy, 2);
				for (Territory qAlliedCheck : alliedCheck)
				{
					List<Unit> qAlliedUnits = qAlliedCheck.getUnits().getMatches(alliedSeaAirUnit);
					alliedStrength += SUtils.strength(qAlliedUnits, true, true, tFirst);
				}
			   if (alliedStrength > enemyStrength*1.12F)
				  minStrengthNeeded -= strengthNeeded - strengthNeeded*0.85F;
			}
			if (minStrengthNeeded > 0.0F || (!shipsAttacked && (planeStrength < enemyStrength*2.0F))) //make another look at planes
			{
				float newStrength = Math.max(minStrengthNeeded, enemyStrength*2.0F);
				SUtils.invitePlaneAttack(false, false, enemy, newStrength, xAlreadyMoved, xMoves, xRoutes, data, player);
				if (newStrength < 0.0F)
				{
					planeStrength += Math.max(minStrengthNeeded, enemyStrength*2.0F) - newStrength;
					planesWorked = true;
				}
				
			}
			if (minStrengthNeeded > 0.0F && (shipsAttacked || (planesWorked && planeStrength > enemyStrength*2.0F)))
            {
				moveRoutes.addAll(xRoutes);
				for (Collection<Unit> xUnits : xMoves)
					moveUnits.add(xUnits);
				unitsAlreadyMoved.addAll(xAlreadyMoved);
				if (maxStrengthNeeded > 0.0F)
					checkForMorePlanes.put(enemy, maxStrengthNeeded);
                s_logger.fine("Attacking : " + enemy + " our strength:" + ourStrength + " enemy strength" + enemyStrength );
            }
            shipsAttacked = false;
            xMoves.clear();
            xRoutes.clear();
            xAlreadyMoved.clear();
        }
    }

    // searches for amphibious attack on empty territory
    private Route getAlternativeAmphibRoute(final PlayerID player)
    {
        if(!isAmphibAttack(player))
            return null;

        final GameData data = getPlayerBridge().getGameData();
        Match<Territory> routeCondition = new CompositeMatchAnd<Territory>(Matches.TerritoryIsWater, Matches.territoryHasNoEnemyUnits(player, data));

        // should select all territories with loaded transports
        Match<Territory> transportOnSea = new CompositeMatchAnd<Territory>(Matches.TerritoryIsWater, Matches.territoryHasLandUnitsOwnedBy(player));
        Route altRoute = null;
        int length=Integer.MAX_VALUE;
        for(Territory t : data.getMap())
        {
        	if(! transportOnSea.match(t)) continue;
            CompositeMatchAnd<Unit> ownedTransports = new CompositeMatchAnd<Unit>(Matches.UnitCanTransport, Matches.unitIsOwnedBy(player),HasntMoved);
            CompositeMatchAnd<Territory> enemyTerritory = new CompositeMatchAnd<Territory>(Matches.isTerritoryEnemy(player, data), Matches.TerritoryIsLand,new InverseMatch<Territory>(Matches.TerritoryIsNeutral),Matches.TerritoryIsEmpty);
        	int trans = t.getUnits().countMatches(ownedTransports);
        	if(trans>0) {
        		Route newRoute = SUtils.findNearest(t, enemyTerritory, routeCondition, data);
        		if(newRoute != null && length > newRoute.getLength()) {
        			altRoute = newRoute;
        		}
        	}
        }
        return altRoute;
	}

    /**
     * check for planes that need to land
     */
	private void CheckPlanes(GameData data, List<Collection<Unit>> moveUnits, List<Route> moveRoutes, PlayerID player)
	//check for planes that need to move
	//don't let planes stay in territory alone if it can be attacked
	//we've already check Carriers in moveNonComPlanes
	{
        final BattleDelegate delegate = DelegateFinder.battleDelegate(data);
		Match<Territory> canLand = new CompositeMatchAnd<Territory>(
                Matches.isTerritoryAllied(player, data),
                new Match<Territory>()
                {
                    @Override
                    public boolean match(Territory o)
                    {
                        return !delegate.getBattleTracker().wasConquered(o);
                    }
                });
		Match<Unit> bomberUnit = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player), Matches.UnitIsStrategicBomber);
        Match<Territory> routeCondition = new CompositeMatchAnd<Territory>(
                Matches.territoryHasEnemyAA(player, data).invert(), Matches.TerritoryIsImpassable.invert());
        Match<Unit> fighterUnit = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player), Matches.UnitCanLandOnCarrier);

        Territory myCapital = TerritoryAttachment.getCapital(player, data);
		List <Territory> planeTerr = new ArrayList<Territory>();
		planeTerr = SUtils.TerritoryOnlyPlanes(data, player);
		planeTerr.remove(myCapital);
		if (planeTerr.size() == 0) //skip...no loner planes
			return;

		for (Territory t : planeTerr)
		{
			List<Unit> airUnits = t.getUnits().getMatches(fighterUnit);
			List<Unit> bombUnits = t.getUnits().getMatches(bomberUnit);
			List<Unit> sendFighters = new ArrayList<Unit>();
			List<Unit> sendBombers = new ArrayList<Unit>();

			Route route2 = SUtils.findNearestNotEmpty(t, canLand, routeCondition, data);
			Territory endTerr = route2.getTerritories().get(route2.getLength());
			int tDist = data.getMap().getDistance(t, endTerr);
			int sendNum = 0;
			for (Unit f : airUnits)
			{
				if (MoveValidator.hasEnoughMovement(f, tDist))
				{
					sendFighters.add(f);
					sendNum++;
				}
			}

			if (sendNum>0)
			{
				moveUnits.add(sendFighters);
				moveRoutes.add(route2);
			}
			sendNum = 0;
			for (Unit b : bombUnits)
			{
				if (MoveValidator.hasEnoughMovement(b, tDist))
				{
					sendBombers.add(b);
					sendNum++;
				}
			}

			if (sendNum > 0)
			{
				moveUnits.add(sendBombers);
				moveRoutes.add(route2);
			}
		}
	}

	private void stopBlitzAttack(GameData data, List<Collection<Unit>> moveUnits, List<Route> moveRoutes, PlayerID player)
	{
		CompositeMatch<Unit> myUnit = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player), Matches.UnitIsInfantry, HasntMoved);
		CompositeMatch<Unit> alliedUnit = new CompositeMatchAnd<Unit>(Matches.alliedUnit(player, data), Matches.UnitIsLand);
        boolean capDanger = getCapDanger();
        boolean tFirst = transportsMayDieFirst();
        Territory myCapital = TerritoryAttachment.getCapital(player, data);
        List<Territory> blitzTerr = new ArrayList<Territory>();
		float enemyStrength = SUtils.getStrengthOfPotentialAttackers(myCapital, data, player, tFirst, false);
		float ourStrength = SUtils.strength(myCapital.getUnits().getUnits(), false, false, tFirst);
		float blitzStrength = SUtils.determineEnemyBlitzStrength(myCapital, blitzTerr, data, player);
		if (enemyStrength - blitzStrength < ourStrength) //removing blitzers eliminates the threat to cap
			capDanger = false; //do everything to clear them out
		if (blitzStrength == 0.0F)
			return;
        List<Territory> capNeighbors = SUtils.getNeighboringLandTerritories(data, player, myCapital);
        List<Unit> alreadyMoved = new ArrayList<Unit>();

		List<Territory> capNCopy = new ArrayList<Territory>(capNeighbors);
		if (!capDanger) //allow units to come from Capitol
		{
			for (Territory tx4 : capNCopy)
			{
				Set<Territory> n1 = data.getMap().getNeighbors(tx4, Matches.territoryHasEnemyLandUnits(player, data));
                List<Unit> sendIn = new ArrayList<Unit>();
				if (n1.size() > 0 && !tx4.getUnits().someMatch(alliedUnit))
				{
					boolean needOne = false;
					for (Territory n2 : n1)
					{
						int qDist = data.getMap().getLandDistance(n2, myCapital);
						if (qDist == 2)
						    needOne = true;
					}
					if (!needOne)
					    continue;
                    boolean movedOne = false;
					List<Unit> infUnit = myCapital.getUnits().getMatches(myUnit);
					Route qRoute = null;
					Iterator<Unit> unitIter = infUnit.iterator();
					while (!movedOne && unitIter.hasNext())
					{
						Unit iUnit = unitIter.next();
						if (alreadyMoved.contains(iUnit) || !MoveValidator.hasEnoughMovement(iUnit, 1))
						    continue;
						qRoute = data.getMap().getLandRoute(myCapital, tx4);
						sendIn.add(iUnit);
						alreadyMoved.add(iUnit);
						movedOne = true;
					}
                    if (!movedOne)
                    {
						Set<Territory> aNeighbors = data.getMap().getNeighbors(tx4, Matches.territoryHasLandUnitsOwnedBy(player));
						aNeighbors.remove(myCapital);
						if (aNeighbors != null && aNeighbors.size() > 0)
						{
							Iterator <Territory> aNiter = aNeighbors.iterator();
							while (!movedOne && aNiter.hasNext())
							{
								Territory aN = aNiter.next();
								List<Unit> infUnit2 = aN.getUnits().getMatches(myUnit);
								if (infUnit2.size() <= 1)
									continue;
								Iterator<Unit> unitIter2 = infUnit2.iterator();
								while (!movedOne && unitIter2.hasNext())
		                        {
									Unit iUnit2 = unitIter2.next();
									if (alreadyMoved.contains(iUnit2))
									    continue;
                                    qRoute = data.getMap().getLandRoute(aN, tx4);
                                    sendIn.add(iUnit2);
                                    alreadyMoved.add(iUnit2);
                                    movedOne = true;
								}
							}
						}
					}
					if (movedOne && qRoute != null)
					{
						moveUnits.add(sendIn);
						moveRoutes.add(qRoute);
						capNeighbors.remove(tx4);
					}
				}
			}
		}
		else
		{
			for (Territory tx4 : capNCopy)
			{
				Set<Territory> n1 = data.getMap().getNeighbors(tx4, Matches.territoryHasEnemyLandUnits(player, data));
                List<Unit> sendIn = new ArrayList<Unit>();
				if (n1.size() > 0 && !tx4.getUnits().someMatch(alliedUnit))
				{
					boolean needOne = false;
					for (Territory n2 : n1)
					{
						int qDist = data.getMap().getLandDistance(n2, myCapital);
						if (qDist == 2)
						    needOne = true;
					}
					if (!needOne)
					    continue;
                    boolean movedOne = false;
					Set<Territory> aNeighbors = data.getMap().getNeighbors(tx4, Matches.territoryHasLandUnitsOwnedBy(player));
					aNeighbors.remove(myCapital);
					Route qRoute = null;
					if (aNeighbors != null && aNeighbors.size() > 0)
					{
						Iterator <Territory> aNiter = aNeighbors.iterator();
						while (!movedOne && aNiter.hasNext())
						{
							Territory aN = aNiter.next();
							List<Unit> infUnit2 = aN.getUnits().getMatches(myUnit);
							Iterator<Unit> unitIter2 = infUnit2.iterator();
							while (!movedOne && unitIter2.hasNext())
	                        {
								Unit iUnit2 = unitIter2.next();
								if (alreadyMoved.contains(iUnit2) && !MoveValidator.hasEnoughMovement(iUnit2, 1))
								    continue;
                                qRoute = data.getMap().getLandRoute(aN, tx4);
                                sendIn.add(iUnit2);
                                alreadyMoved.add(iUnit2);
                                movedOne = true;
							}
						}
					}
					if (movedOne && qRoute != null)
					{
						moveUnits.add(sendIn);
						moveRoutes.add(qRoute);
						capNeighbors.remove(tx4);
					}
				}
			}
		}
		if (capNeighbors.size() > 0) //see if we can blitz, transport or fly a unit in
		{
			for (Territory tx5 : capNeighbors)
			{
				float sNeeded = 3.0F; //should be about 1 tank
				SUtils.inviteBlitzAttack(true, tx5, sNeeded, alreadyMoved, moveUnits, moveRoutes, data, player, true, true);
				if (sNeeded == 3.0F) //no success
				{
					SUtils.inviteTransports(true, tx5, sNeeded, alreadyMoved, moveUnits, moveRoutes, data, player);
				}
				if (sNeeded == 3.0F) //last resort is a plane TODO: verify the strength of blitz group before sacrificing plane
				{
					SUtils.invitePlaneAttack(true, false, tx5, sNeeded, alreadyMoved, moveUnits, moveRoutes, data, player);
				}
			}
		}
	}

    private void populateNonCombat(GameData data, List<Collection<Unit>> moveUnits, List<Route> moveRoutes, PlayerID player)
    {
		float ourStrength = 0.0F, attackerStrength=0.0F;
		float totalInvasion = 0.0F, ourCapStrength=0.0F;
		boolean capDanger = false;
		boolean tFirst = transportsMayDieFirst();
        Collection<Territory> territories = data.getMap().getTerritories();
        List<Territory> emptiedTerr = new ArrayList<Territory>();
        List<Territory> fortifiedTerr = new ArrayList<Territory>();
        List<Territory> alliedTerr = SUtils.allAlliedTerritories(data, player);

        Territory myCapital = TerritoryAttachment.getCapital(player, data);
		List<Territory> movedInto = new ArrayList<Territory>();
        List<Unit> alreadyMoved = new ArrayList<Unit>();

        CompositeMatchAnd<Territory> moveThrough = new CompositeMatchAnd<Territory>(new InverseMatch<Territory>(Matches.TerritoryIsImpassable),
            new InverseMatch<Territory>(Matches.TerritoryIsNeutral), Matches.TerritoryIsLand);

		CompositeMatch<Unit> landUnit = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player), Matches.UnitIsNotSea, Matches.UnitIsNotAA, Matches.UnitIsNotFactory, HasntMoved);
		CompositeMatch<Unit> alliedUnit = new CompositeMatchAnd<Unit>(Matches.alliedUnit(player, data), Matches.UnitIsLand);

		//Look at Capital before anything else
		determineCapDanger(player, data, ourCapStrength, totalInvasion);
		capDanger = getCapDanger();
		List<Territory> myNeighbors = SUtils.getNeighboringLandTerritories(data, player, myCapital);
		HashMap<Territory, Float> addStrength = new HashMap<Territory, Float>();
		for (Territory qT : alliedTerr)
		{
			addStrength.put(qT, 0.0F);
		}
		if (capDanger) //borrow some units
		{
			for (Territory tx3 : myNeighbors)
			{
				if (ourCapStrength > (totalInvasion + 5.0F))
					continue;
				List<Unit> allUnits = tx3.getUnits().getMatches(landUnit);
				List<Unit> sendIn = new ArrayList<Unit>();
				for (Unit x : allUnits)
				{
					if (ourCapStrength <= (totalInvasion + 5.0F))
					{
						ourCapStrength += SUtils.uStrength(x, false, false, tFirst);
						sendIn.add(x);
					}
				}
				float remainingStrengthNeeded = totalInvasion - ourCapStrength + 3.00F;
				if (remainingStrengthNeeded > 0.0F)
					SUtils.inviteBlitzAttack(true, myCapital, remainingStrengthNeeded, alreadyMoved, moveUnits, moveRoutes, data, player, false, true);
				if (remainingStrengthNeeded > 0.0F)
					SUtils.invitePlaneAttack(true, false, myCapital, remainingStrengthNeeded, alreadyMoved, moveUnits, moveRoutes, data, player);
				if (remainingStrengthNeeded > 0.0F)
					SUtils.inviteTransports(true, myCapital, remainingStrengthNeeded, alreadyMoved, moveUnits, moveRoutes, data, player);
				Route quickRoute = data.getMap().getLandRoute(tx3, myCapital);
				moveUnits.add(sendIn);
				moveRoutes.add(quickRoute);
				alreadyMoved.addAll(sendIn);
			}
			List<Unit> myCapUnits = myCapital.getUnits().getMatches(landUnit);
			alreadyMoved.addAll(myCapUnits);
		}

        for(Territory t : alliedTerr)
        {
            if(Matches.TerritoryIsWater.match(t) || !Matches.territoryHasLandUnitsOwnedBy(player).match(t))
                continue;

            //these are the units we can move
            CompositeMatch<Unit> moveOfType = new CompositeMatchAnd<Unit>();

            moveOfType.add(Matches.unitIsOwnedBy(player));
            moveOfType.add(Matches.UnitIsNotAA);

            moveOfType.add(Matches.UnitIsNotFactory);
            moveOfType.add(Matches.UnitIsLand);

            List<Unit> units = t.getUnits().getMatches(moveOfType);
            if(units.size() == 0)
                continue;

            int minDistance = Integer.MAX_VALUE;
            Territory to = null;
            Collection<Unit> unitsHere = t.getUnits().getMatches(moveOfType);
//          realStrength = SUtils.strength(unitsHere, false, false, tFirst) - SUtils.allairstrength(unitsHere, false);

            ourStrength = SUtils.strength(unitsHere, false, false, tFirst) + addStrength.get(t);
            attackerStrength = SUtils.getStrengthOfPotentialAttackers( t, data, player, tFirst, false);

            if ((t.getUnits().someMatch(Matches.UnitIsFactory) || t.getUnits().someMatch(Matches.UnitIsAA)) && t != myCapital)
            { //protect factories...rather than just not moving, plan to move some in if necessary
              //Don't worry about units that have been moved toward capital
                if (attackerStrength > (ourStrength*1.18F + 5.0F))
				{
					List<Territory> myNeighbors2 = SUtils.getNeighboringLandTerritories(data, player, t);
					if (capDanger)
						myNeighbors2.remove(myCapital);
					for (Territory t3 : myNeighbors2)
					{ //get everything
						List<Unit> allUnits = t3.getUnits().getMatches(landUnit);
						List<Unit> sendIn2 = new ArrayList<Unit>();
						for (Unit x2 : allUnits)
						{
							if ((ourStrength*1.18F + 5.0F) < attackerStrength && !alreadyMoved.contains(x2))
							{
								ourStrength += SUtils.uStrength(x2, false, false, tFirst);
								sendIn2.add(x2);
							}
						}

						Route quickRoute = data.getMap().getLandRoute(t3, t);
						addStrength.put(t, addStrength.get(t) + SUtils.strength(sendIn2, false, false, tFirst));
						moveUnits.add(sendIn2);
						moveRoutes.add(quickRoute);
						alreadyMoved.addAll(sendIn2);
						movedInto.add(t);
					}
				}
			}
			//if an emminent attack on capital, pull back toward capital
            List <Territory> myENeighbors = SUtils.getNeighboringEnemyLandTerritories(data, player, t);
            float s1 = 0.0F, s2 = 0.0F;
//            e1 = 0.0F;
//            e2 = 0.0F;
//            SUtils.getEnemyStrengthAt(e1, e2, data, player, myCapital, false, true, tFirst);
            SUtils.getStrengthAt(s1, s2, data, player, t, false, true, tFirst);
            Route retreatRoute = null;
            if (myENeighbors.size() == 0 && ((ourCapStrength*1.22F + 5.0F) < totalInvasion))
            { //this territory has no enemy neighbors...pull back toward capital
            	if (data.getMap().getLandRoute(t, myCapital) != null)
            	{ 
            		List<Territory> myNeighbors3 = SUtils.getNeighboringLandTerritories(data, player, t);
            		int minCapDist = 100;
            		Territory targetTerr = null;
            		for (Territory myTerr : myNeighbors3)
           		{
						int thisCapDist = data.getMap().getLandDistance(myTerr, myCapital);
						if (thisCapDist < minCapDist)
						{
							minCapDist = thisCapDist;
							targetTerr = myTerr;
							retreatRoute = data.getMap().getLandRoute(t, myTerr);
						}
					}
					if (retreatRoute != null)
					{
						List<Unit> myMoveUnits = t.getUnits().getMatches(landUnit);
						myMoveUnits.removeAll(alreadyMoved);
						int totUnits2 = myMoveUnits.size();
						List<Unit> myRealMoveUnits = new ArrayList<Unit>();
						for (int i3=0; i3 < totUnits2; i3++)
						{
							if ((ourCapStrength*1.22F + 5.0F) < totalInvasion)
							{
								Unit moveThisUnit = myMoveUnits.get(i3);
								ourCapStrength += 0.5F*SUtils.uStrength(moveThisUnit, true, false, tFirst);
								myRealMoveUnits.add(moveThisUnit);
							}
						}
						if (myRealMoveUnits.size() > 0)
						{
							addStrength.put(targetTerr, addStrength.get(targetTerr) + SUtils.strength(myRealMoveUnits, false, false, tFirst));
							moveRoutes.add(retreatRoute);
							moveUnits.add(myRealMoveUnits);
							alreadyMoved.addAll(myRealMoveUnits);
							movedInto.add(targetTerr);
						}
					}
				}
			}
			if (fortifiedTerr.contains(t)) //don't move...we've joined units
			    continue;
//			float newInvasion = SUtils.getStrengthOfPotentialAttackers(t, data, player, tFirst, true);
			if (attackerStrength > (ourStrength*1.85F + 6.0F) && !movedInto.contains(t)) //overwhelming attack...look to retreat
			{
				List<Territory> myFriendTerr = SUtils.getNeighboringLandTerritories(data, player, t);
				int maxUnits = 0, thisUnits = 0;
				Territory mergeTerr = null;
				for (Territory fTerr : myFriendTerr)
				{
					if (emptiedTerr.contains(fTerr))
					    continue; //don't move somewhere we have already retreated from
					List<Territory> badGuysTerr = SUtils.getNeighboringEnemyLandTerritories(data, player, fTerr);
					if (badGuysTerr.size() > 0) //give preference to the front
					    thisUnits += 4;
					float fTerrAttackers = SUtils.getStrengthOfPotentialAttackers(fTerr, data, player, tFirst, true);
					SUtils.getStrengthAt(s1, s2, data, player, fTerr, false, true, tFirst);
					if (fTerrAttackers > 8.0F && fTerrAttackers < (s1 + ourStrength)*1.05F)
					    thisUnits += 4;
					else if (fTerrAttackers > 0.0F && fTerrAttackers < (s1 + ourStrength)*1.05F)
					    thisUnits += 1;
					thisUnits += fTerr.getUnits().getMatches(alliedUnit).size();
					if (thisUnits > maxUnits)
					{
						maxUnits = thisUnits;
						mergeTerr = fTerr;
					}
				}
				if (mergeTerr != null)
				{
					Route myRetreatRoute = data.getMap().getLandRoute(t, mergeTerr);
					List <Unit> myRetreatUnits = t.getUnits().getMatches(landUnit);
					int totUnits3 = myRetreatUnits.size();
					for (int i4=totUnits3-1; i4>=0; i4--)
					{
						Unit u4 = myRetreatUnits.get(i4);
						if (alreadyMoved.contains(u4))
							myRetreatUnits.remove(u4);
					}
					addStrength.put(mergeTerr, addStrength.get(mergeTerr) + SUtils.strength(myRetreatUnits, false, false, tFirst));
					moveUnits.add(myRetreatUnits);
					moveRoutes.add(myRetreatRoute);
					alreadyMoved.addAll(myRetreatUnits);
					fortifiedTerr.add(mergeTerr);
					movedInto.add(mergeTerr);
					emptiedTerr.add(t);
				}
			}

            //find the nearest enemy owned capital or factory
            List<Territory> allBadTerr = SUtils.allEnemyTerritories(data, player);
            List<Territory> enemyCapTerr = SUtils.getEnemyCapitals(data, player);
            // Territory bestCapitol = null;
            // Territory bestFactory = null;
            for (Territory capitol : enemyCapTerr)
            {
                Route route = data.getMap().getRoute(t, capitol, moveThrough);
                if(route != null)
                {
                    int distance = route.getLength();
                    if(distance != 0 && distance < minDistance)
                    {
                        minDistance = distance;
                        to = capitol;
                    }
                }
            }
            // bestCapitol = to;
            for (Territory badFactTerr : allBadTerr)
            {
				if (!Matches.territoryHasEnemyFactory(data, player).match(badFactTerr) || enemyCapTerr.contains(badFactTerr))
				    continue;
				Route badRoute = data.getMap().getRoute(t, badFactTerr, moveThrough);
				if (badRoute != null)
				{
					int badDist = badRoute.getLength();
					if (badDist > 0 && badDist < minDistance)
					{
						minDistance = badDist;
						to = badFactTerr;
					}
				}
			}
//            CompositeMatchAnd<Territory> routeCondition = new CompositeMatchAnd<Territory>(Matches.isTerritoryAllied(player, data), Matches.TerritoryIsLand);
            Route newRoute = SUtils.findNearest(t, Matches.territoryHasEnemyLandUnits(player, data ), moveThrough, data);
            // move to any enemy territory
            if(newRoute == null)
            {
              	newRoute = SUtils.findNearest(t, Matches.isTerritoryEnemy(player, data), moveThrough, data);
            }
            if(newRoute == null)
            {
                continue;
            }
            int newDistance = newRoute.getLength();
            Territory firstStep = null;
            Route route = null;
            if (to != null && minDistance <= (newDistance + 1))
            {
                if(units.size() > 0)
                {
                    Route routeToCapitol = data.getMap().getRoute(t, to, moveThrough);
                    firstStep = routeToCapitol.getTerritories().get(1);
                    route = new Route();
                    route.setStart(t);
                    route.add(firstStep);
                }

            }
            //if we can't move to a capitol, move towards the enemy
            else
            {
                if(newRoute != null && units.size() > 0)
                {
                    firstStep = newRoute.getTerritories().get(1);
                    route = new Route();
                    route.setStart(t);
                    route.add(firstStep);
                }
            }
            if (firstStep != null && Matches.isTerritoryAllied(player, data).match(firstStep) && route != null)
            {
                moveUnits.add(units);
            	moveRoutes.add(route);
            	movedInto.add(firstStep);
            	addStrength.put(firstStep, addStrength.get(firstStep) + SUtils.strength(units, false, false, tFirst));
            }
        }
    }


    @SuppressWarnings("unchecked")
    private void movePlanesHomeNonCom(List<Collection<Unit>> moveUnits, List<Route> moveRoutes, final PlayerID player, GameData data)
    {
		// planes are doing silly things like landing in territories that can be invaded with cheap units
		// we want planes to find an aircraft carrier
        IMoveDelegate delegateRemote = (IMoveDelegate) getPlayerBridge().getRemote();
        CompositeMatch<Unit> fighterUnit = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player), Matches.UnitCanLandOnCarrier);
        CompositeMatch<Unit> bomberUnit = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player), Matches.UnitIsStrategicBomber);
        CompositeMatch<Unit> carrierUnit = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player), Matches.UnitIsCarrier);
        List<Unit> alreadyMoved = new ArrayList<Unit>();
        List<Territory> alreadyCheck = new ArrayList<Territory>();

        final BattleDelegate delegate = DelegateFinder.battleDelegate(data);

        Match<Territory> canLand = new CompositeMatchAnd<Territory>(

                Matches.isTerritoryAllied(player, data),
                new Match<Territory>()
                {
                    @Override
                    public boolean match(Territory o)
                    {
                        return !delegate.getBattleTracker().wasConquered(o);
                    }
                }
                );

        Match<Territory> routeCondition = new CompositeMatchAnd<Territory>(Matches.territoryHasEnemyAA(player, data).invert(),
                Matches.TerritoryIsImpassable.invert());

        Territory myCapital = TerritoryAttachment.getCapital(player, data);

        for (Territory tBomb : delegateRemote.getTerritoriesWhereAirCantLand()) //move bombers to capital first
        {
			List<Unit> bomberUnits = tBomb.getUnits().getMatches(bomberUnit);
	        List<Unit> sendBombers = new ArrayList<Unit>();
			alreadyCheck.add(tBomb);
			for (Unit bU : bomberUnits)
			{
				boolean landable = SUtils.airUnitIsLandable(bU, tBomb, myCapital, player, data);
				if (landable)
					sendBombers.add(bU);
			}
			if (sendBombers.size() > 0 && tBomb != myCapital)
			{
				Route bomberRoute = data.getMap().getRoute(tBomb, myCapital);
				moveRoutes.add(bomberRoute);
				moveUnits.add(sendBombers);
				alreadyMoved.addAll(sendBombers);
			}
			bomberUnits.removeAll(sendBombers); //see if there are any left
			for (Unit bU : bomberUnits)
			{
				Route bomberRoute2 = SUtils.findNearestNotEmpty(tBomb, canLand, routeCondition, data);
				if (bomberRoute2 != null)
				{
					List<Unit> qAdd = new ArrayList<Unit>();
					qAdd.add(bU);
				    moveRoutes.add(bomberRoute2);
				    moveUnits.add(qAdd);
				    alreadyMoved.add(bU);
				}

				Route bomberRoute3 = SUtils.findNearest(tBomb, canLand, Matches.TerritoryIsImpassable.invert(), data);
				if (bomberRoute3 != null)
				{
					List<Unit> qAdd = new ArrayList<Unit>();
					qAdd.add(bU);
				    moveRoutes.add(bomberRoute3);
				    moveUnits.add(qAdd);
				    alreadyMoved.add(bU);
				}
			}
		}
		for (Territory tFight : delegateRemote.getTerritoriesWhereAirCantLand())
		{
			List<Unit> fighterUnits = new ArrayList<Unit>(tFight.getUnits().getMatches(fighterUnit));
			List<Unit> ACUnits = new ArrayList<Unit>(tFight.getUnits().getMatches(carrierUnit));
			int maxUnits = ACUnits.size()*2;
			for (int i=1; i <= maxUnits; i++)
			{
				List<Unit> newFUnits = new ArrayList<Unit>();
				newFUnits.addAll(fighterUnits);
				Unit removeFighter = null;
				int movMin=7;
				for (Unit f : newFUnits)
				{
					int thisMove =TripleAUnit.get(f).getMovementLeft();
					if (thisMove < movMin)
					{
						movMin = thisMove;
						removeFighter = f;
					}
				}
				if (removeFighter != null)
					fighterUnits.remove(removeFighter);
			} //fighterUnits has the fighters with greatest movement remaining
			//check for other carriers first
			IntegerMap<Territory> alreadyAddedFighter = new IntegerMap<Territory>();
			for (Unit f2 : fighterUnits)
			{
				int fMove = TripleAUnit.get(f2).getMovementLeft();
				for (Territory checkThese : data.getMap().getNeighbors(tFight, fMove))
				{
					if (checkThese.isWater() && checkThese.getUnits().someMatch(carrierUnit))
					{
						List<Unit> checkCarrier = checkThese.getUnits().getMatches(carrierUnit);
						List<Unit> checkFighters = checkThese.getUnits().getMatches(fighterUnit);
						int availSpace = checkCarrier.size()*2 - checkFighters.size() - alreadyAddedFighter.getInt(checkThese);
						if (availSpace > 0)
						{
							Route f2Route = data.getMap().getRoute(tFight, checkThese);
							if (f2Route != null)
							{
								moveUnits.add(Collections.singleton(f2));
								moveRoutes.add(f2Route);
								alreadyMoved.add(f2);
								alreadyAddedFighter.put(checkThese, alreadyAddedFighter.getInt(checkThese)+1);
							}
						}
					}
				}
			}
			fighterUnits.removeAll(alreadyMoved);
			for (Unit f1 : fighterUnits)
			{
				Route fighterRoute2 = SUtils.findNearestNotEmpty(tFight, canLand, routeCondition, data);
				if (fighterRoute2 != null)
				{
					List<Unit> qAdd = new ArrayList<Unit>();
					qAdd.add(f1);
					moveRoutes.add(fighterRoute2);
					moveUnits.add(qAdd);
					alreadyMoved.add(f1);
				}
				Route fighterRoute3 = SUtils.findNearest(tFight, canLand, Matches.TerritoryIsImpassable.invert(), data);
				if (fighterRoute3 != null)
				{
					List<Unit> qAdd = new ArrayList<Unit>();
					qAdd.add(f1);
					moveRoutes.add(fighterRoute3);
					moveUnits.add(qAdd);
					alreadyMoved.add(f1);
				}
			}
		}
    }

    private void populateCombatMove(GameData data, List<Collection<Unit>> moveUnits, List<Route> moveRoutes, PlayerID player, float ourCapStrength2, float enemyCapStrength)
    {
		/* Priorities:
		** 1) We lost our capital
		** 2) Units placed next to our capital
		** 3) Enemy players capital
		** 4) Enemy players factories
		*/

    	boolean capDanger = getCapDanger();
        float attackFactor = 1.22F;
        float attackFactor2 = 1.08F; //emergency attack...weaken enemy
        final Collection<Unit> unitsAlreadyMoved = new HashSet<Unit>();
		List<Territory> enemyOwned = SUtils.getNeighboringEnemyLandTerritories(data, player, true);
		boolean tFirst = transportsMayDieFirst();
		List<Territory> alreadyAttacked = new ArrayList<Territory>();
        Territory myCapital = TerritoryAttachment.getCapital(player, data);
        List<Territory> capitalNeighbors = SUtils.getNeighboringLandTerritories(data, player, myCapital);
        boolean ownMyCapital = myCapital.getOwner() == player;
        List<Territory> emptyBadTerr= new ArrayList<Territory>();
		float remainingStrengthNeeded = 0.0F;
		List<Territory>enemyCaps = SUtils.getEnemyCapitals(data, player);
		int rDist;

        CompositeMatch<Unit> attackable = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player), Matches.UnitIsLand,
            Matches.UnitIsNotAA, Matches.UnitIsNotFactory,
                new Match<Unit>()
                {
                    public boolean match(Unit o)
                    {
                        return !unitsAlreadyMoved.contains(o);
                    }
                 });

        CompositeMatch<Unit> airUnit = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player), Matches.UnitIsAir);
        CompositeMatch<Unit> alliedAirUnit = new CompositeMatchAnd<Unit>(Matches.alliedUnit(player, data), Matches.UnitIsAir);
        CompositeMatch<Unit> alliedLandUnit = new CompositeMatchAnd<Unit>(Matches.alliedUnit(player, data),
        											Matches.UnitIsLand, Matches.UnitIsNotAA, Matches.UnitIsNotFactory);
        CompositeMatch<Unit> alliedAirLandUnit = new CompositeMatchOr<Unit>(alliedAirUnit, alliedLandUnit);
        CompositeMatch<Unit> blitzUnit = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player), Matches.UnitCanBlitz);
        CompositeMatch<Unit> enemyLandAirUnit = new CompositeMatchAnd<Unit>(Matches.UnitIsNotSea, Matches.UnitIsNotAA, Matches.UnitIsNotFactory);
        CompositeMatch<Territory> emptyEnemyTerr = new CompositeMatchAnd<Territory>(Matches.isTerritoryEnemyAndNotNeutral(player, data));

        if (!ownMyCapital) //We lost our capital
        {
			attackFactor = 0.72F; //attack like a maniac, maybe we will win
			enemyOwned.remove(myCapital); //handle the capital directly
		}

        List<Territory> bigProblem2 = SUtils.getNeighboringEnemyLandTerritories(data, player, myCapital); //attack these guys first

		Territory sortTerritories[] = new Territory[data.getMap().getTerritories().size()] ; //revised game has 79 territories and 64 sea zones
		float sortStrength[] = new float[data.getMap().getTerritories().size()];
		Territory sortProblems[] = new Territory[16] ; //maximum neighboring territories > 15???
		float sortStrengthProblems[] = new float[16];
		int numTerr = 0, numTerrProblem = 0, realProblems = 0;
		float xStrength = 0.0F;
		Territory xTerr = null;
		for (Territory t: enemyOwned) //rank the problems
		{
			sortTerritories[numTerr] = t;
            if(!t.getUnits().someMatch(Matches.enemyUnit(player, data) ) )
				sortStrength[numTerr] = 0.0F;
			else
				sortStrength[numTerr] = SUtils.strength(t.getUnits().getUnits(), false, false, tFirst);
			numTerr++;
		}
		for (int i2=1; i2 <=(numTerr - 2); i2++)
		{
			for (int i=0; i<= (numTerr - 2); i++)
			{
				if (sortStrength[i] < sortStrength[i+1])
				{
					xTerr = sortTerritories[i];
					xStrength = sortStrength[i];
					sortTerritories[i] = sortTerritories[i+1];
					sortStrength[i] = sortStrength[i+1];
					sortTerritories[i+1] = xTerr;
					sortStrength[i+1] = xStrength;
				}
			}
		}
		float aggregateStrength = 0.0F;
 		for (Territory tProb: bigProblem2) //rank the problems
		{
			sortProblems[numTerrProblem] = tProb;
            if(!tProb.getUnits().someMatch(Matches.enemyUnit(player, data) ) )
				sortStrengthProblems[numTerrProblem] = 0.0F;
			else
				sortStrengthProblems[numTerrProblem] = SUtils.strength(tProb.getUnits().getUnits(), false, false, tFirst);
			aggregateStrength += sortStrengthProblems[numTerrProblem];
			if (sortStrengthProblems[numTerrProblem] > 8.0F)
				realProblems++;
			numTerrProblem++;
		}
		for (int j2=1; j2 <=(numTerrProblem - 2); j2++)
		{
			for (int j=0; j<= (numTerrProblem - 2); j++)
			{
				if (sortStrengthProblems[j] < sortStrengthProblems[j+1])
				{
					xTerr = sortProblems[j];
					xStrength = sortStrengthProblems[j];
					sortProblems[j] = sortProblems[j+1];
					sortStrengthProblems[j] = sortStrengthProblems[j+1];
					sortProblems[j+1] = xTerr;
					sortStrengthProblems[j+1] = xStrength;
				}
			}
		}

        List<Collection<Unit>> xMoveUnits = new ArrayList<Collection<Unit>>();
        List<Route> xMoveRoutes = new ArrayList<Route>();
        List<Unit> xAlreadyMoved = new ArrayList<Unit>();

		if (!ownMyCapital) //attack the capital with everything we have
		{
            Collection<Territory> attackFrom = SUtils.getNeighboringLandTerritories(data, player, myCapital);
            float badCapStrength = SUtils.strength(myCapital.getUnits().getUnits(), false, false, tFirst);
            float capStrength = 0.0F;
            for (Territory checkCap : attackFrom)
            {
                List<Unit> units = checkCap.getUnits().getMatches(attackable);
                capStrength += SUtils.strength(units, true, false, tFirst);
			}
            float xRS = 1000.0F;
            SUtils.inviteBlitzAttack(false, myCapital, xRS, xAlreadyMoved, xMoveUnits, xMoveRoutes, data, player, true, true);
            boolean groundUnits = ((1000.0F - xRS) > 1.0F);
            SUtils.invitePlaneAttack(false, false, myCapital, xRS, xAlreadyMoved, xMoveUnits, xMoveRoutes, data, player);
            SUtils.inviteTransports(false, myCapital, xRS, xAlreadyMoved, xMoveUnits, xMoveRoutes, data, player);
            capStrength += 1000.0F - xRS;
			if (capStrength > badCapStrength*0.78F) //give us a chance...
			{
				for(Territory owned : attackFrom )
            	{
            	    List<Unit> units = owned.getUnits().getMatches(attackable);
            	    if(units.size() > 0)
            	    {
            	        unitsAlreadyMoved.addAll(units);
            	        moveUnits.add(units);
            	        moveRoutes.add(data.getMap().getRoute(owned, myCapital));
            	        alreadyAttacked.add(myCapital);
            	        groundUnits = true;
					}
				}
				if (groundUnits)
				{
					moveUnits.addAll(xMoveUnits);
					moveRoutes.addAll(xMoveRoutes);
					unitsAlreadyMoved.addAll(xAlreadyMoved);
				}
			}
		}
		xMoveUnits.clear();
		xMoveRoutes.clear();
		xAlreadyMoved.clear();
		for (Territory badCapitol : enemyCaps)
		{
			Collection <Unit> badCapUnits = badCapitol.getUnits().getUnits();
			float badCapStrength = SUtils.strength(badCapUnits, false, false, tFirst);
			float alliedCapStrength = 0.0F;
			float ourXStrength = 0.0F;
			List <Territory> alliedCapTerr = SUtils.getNeighboringLandTerritories(data, player, badCapitol);
			enemyOwned.remove(badCapitol); //no need to attack later
			if (alliedCapTerr == null || alliedCapTerr.size() == 0)
				continue;
			List<Unit> alliedCUnits = new ArrayList<Unit>();
			List<Unit> ourCUnits = new ArrayList<Unit>();
			for (Territory aT : alliedCapTerr)
			{
				alliedCUnits.addAll(aT.getUnits().getMatches(alliedAirLandUnit));
				ourCUnits.addAll(aT.getUnits().getMatches(attackable));
			}
			alliedCapStrength += SUtils.strength(alliedCUnits, true, false, tFirst);
			ourXStrength += SUtils.strength(ourCUnits, true, false, tFirst);
			remainingStrengthNeeded = 1000.0F; //bring everything to get the capital
			SUtils.invitePlaneAttack(false, false, badCapitol, remainingStrengthNeeded, xAlreadyMoved, xMoveUnits, xMoveRoutes, data, player);
			SUtils.inviteBlitzAttack(false, badCapitol, remainingStrengthNeeded, xAlreadyMoved, xMoveUnits, xMoveRoutes, data, player, true, true);
			SUtils.inviteTransports(false, badCapitol, remainingStrengthNeeded, xAlreadyMoved, xMoveUnits, xMoveRoutes, data, player);
			float additionalStrength = 1000.0F - remainingStrengthNeeded;
			alliedCapStrength += additionalStrength;
			ourXStrength += additionalStrength;
			if ((alliedCapStrength > (badCapStrength*1.05F + 3.0F)) && (ourXStrength > (0.80F*badCapStrength + 3.0F)))
			{ //attack
				for (Territory aT2 : alliedCapTerr)
				{
					List <Unit> ourCUnits2 = aT2.getUnits().getMatches(attackable);
					moveUnits.add(ourCUnits2);
					Route aR = data.getMap().getLandRoute(aT2, badCapitol);
					moveRoutes.add(aR);
					unitsAlreadyMoved.addAll(ourCUnits2);
				}
				moveUnits.addAll(xMoveUnits);
				moveRoutes.addAll(xMoveRoutes);
				unitsAlreadyMoved.addAll(xAlreadyMoved);
				alreadyAttacked.add(badCapitol);
			}
		}
		xMoveUnits.clear();
		xMoveRoutes.clear();
		xAlreadyMoved.clear();

        //find the territories we can just walk into
        for(Territory enemy : enemyOwned)
        {
			if (alreadyAttacked.contains(enemy))
				continue;
			float eStrength = SUtils.strength(enemy.getUnits().getUnits(), true, false, tFirst);
            if( eStrength < 0.50F)
            {
                //only take it with 1 unit
                boolean taken = false;
                Set<Territory> nextTerrs = data.getMap().getNeighbors(enemy, emptyEnemyTerr);
                HashMap<Territory, Float> canBeTaken = new HashMap<Territory, Float>();
                for (Territory nextOne : nextTerrs)
                {
                	List<Territory> myGoodNeighbors = SUtils.getNeighboringLandTerritories(data, player, nextOne);
                	if (myGoodNeighbors.size()>0) //we own the neighbors...let them handle bringing blitz units in
                		continue;
                	List<Unit> totUnits = nextOne.getUnits().getMatches(enemyLandAirUnit);
                	float thisStrength = SUtils.strength(totUnits, false, false, tFirst);
                	canBeTaken.put(nextOne, thisStrength);
                }
                Set<Territory> blitzTerrs = canBeTaken.keySet();
                for(Territory attackFrom : data.getMap().getNeighbors(enemy, Matches.territoryHasLandUnitsOwnedBy(player)))
                {
                    if(taken)
                        break;
					//just get an infantry at the top of the queue
                    List<Unit> blitzUnits = attackFrom.getUnits().getMatches(blitzUnit);
                    Territory findOne = null;
                    if (canBeTaken.size() > 0) //we have another terr we can take
                    {
                    	for (Territory attackTo : blitzTerrs)
                    	{
                    		if (canBeTaken.get(attackTo) < 1.0F)
                    			findOne = attackTo;
                    	}
                    }
                    if (findOne != null && !blitzUnits.isEmpty()) //use a tank
                    {
                    	for (Territory bT : blitzTerrs)
                    	{
                    		if (canBeTaken.get(bT) < 4.0F)
                    		{
                            	Route newRoute = new Route();
                    			newRoute.setStart(attackFrom);
                    			newRoute.add(enemy);
                    			newRoute.add(bT);
                    			Unit deleteThisOne = null;
                    			for (Unit tank : blitzUnits)
                    			{
                    				if (deleteThisOne == null)
                    				{
                    					moveUnits.add(Collections.singleton(tank));
                    					moveRoutes.add(newRoute);
                    					unitsAlreadyMoved.add(tank);
                    					deleteThisOne = tank;
                    					alreadyAttacked.add(enemy);
                    					emptyBadTerr.remove(enemy);
                    				}
                    			}
                    			blitzUnits.remove(deleteThisOne);
                    		}
                    	}
                    }
                    else //use an infantry
                    {
                    	List<Unit> unitsSorted = SUtils.sortTransportUnits(attackFrom.getUnits().getMatches(attackable));
                    	for(Unit unit : unitsSorted)
                    	{
                    		moveRoutes.add(data.getMap().getRoute(attackFrom, enemy));

                    		if(attackFrom.isWater())
                    		{
                    			List<Unit> units2 = attackFrom.getUnits().getMatches(Matches.unitIsLandAndOwnedBy(player));
                    			moveUnits.add( Util.difference(units2, unitsAlreadyMoved) );
                    			unitsAlreadyMoved.addAll(units2);
                    		}
                    		else
                    			moveUnits.add(Collections.singleton(unit));

                    		unitsAlreadyMoved.add(unit);
                    		alreadyAttacked.add(enemy);
                    		emptyBadTerr.add(enemy);
                    		taken = true;
                    		break;
                    	}
                    }
                }
            }
        }
        float ourStrength = 0.0F, badStrength = 0.0F;
 		boolean weAttacked = false, weAttacked2 = false;
 		int EinfArtCount = 0, OinfArtCount = 0;
		for (Territory badTerr : bigProblem2)
		{
			weAttacked = false;
			Collection<Unit> enemyUnits = badTerr.getUnits().getUnits();
			badStrength = SUtils.strength(enemyUnits, false, false, tFirst);

			if (alreadyAttacked.contains(badTerr))
				continue;
            if(badStrength > 0.0F)
            {
                ourStrength = 0.0F;
                List<Territory> capitalAttackTerr = new ArrayList<Territory>(data.getMap().getNeighbors(badTerr, Matches.territoryHasLandUnitsOwnedBy(player)));
                List<Unit> capSaverUnits = new ArrayList<Unit>();
                for(Territory capSavers : capitalAttackTerr )
                {
                	capSaverUnits.addAll(capSavers.getUnits().getMatches(attackable));
                }
            	capSaverUnits.removeAll(unitsAlreadyMoved);
                ourStrength += SUtils.strength(capSaverUnits, true, false, tFirst);
                xAlreadyMoved.addAll(capSaverUnits);
                xAlreadyMoved.addAll(unitsAlreadyMoved);
                float xRS = 1000.0F;
				SUtils.inviteBlitzAttack(false, badTerr, xRS, xAlreadyMoved, xMoveUnits, xMoveRoutes, data, player, true, false);
				SUtils.inviteTransports(false, badTerr, xRS, xAlreadyMoved, xMoveUnits, xMoveRoutes, data, player);
				weAttacked = (1000.0F - xRS) > 0.0F; //land Units confirmed
				SUtils.invitePlaneAttack(false, false, badTerr, xRS, xAlreadyMoved, xMoveUnits, xMoveRoutes, data, player);
				ourStrength += 1000.0F - xRS;
				List<Unit> allMyUnits = new ArrayList<Unit>(capSaverUnits);
				for (Collection<Unit> xUnits : xMoveUnits)
					allMyUnits.addAll(xUnits);
				IntegerMap<UnitType> attacker = SUtils.convertListToMap(allMyUnits);
				IntegerMap<UnitType> defender = SUtils.convertListToMap(enemyUnits);
				Set<UnitType> aList = attacker.keySet();
				Set<UnitType> dList = defender.keySet();
				int aCount1 = 0, dCount1 = 0;
				for (UnitType aType : aList)
				{
					if (Matches.UnitTypeIsAir.match(aType))
						aCount1++;
				}
				for (UnitType dType : dList)
				{
					if (Matches.UnitTypeIsAir.match(dType))
						dCount1++;
				}
				boolean weWin = SUtils.quickBattleEstimator(attacker, defender, player, badTerr.getOwner());
				s_logger.fine("Attacker: " + player.getName());
				s_logger.fine("Attacker: " + attacker.toString());
				s_logger.fine("Defender: " + defender.toString());
				s_logger.fine(weWin ? "Attacker Wins" : "Defender Wins");
				int aCount2 = 0, dCount2 = 0;
				for (UnitType aType : aList)
				{
					if (Matches.UnitTypeIsAir.match(aType))
						aCount2++;
				}
				for (UnitType dType : dList)
				{
					if (Matches.UnitTypeIsAir.match(dType))
						dCount2++;
				}
				int dFightersLost = dCount2 - dCount1;
				int aFightersLost = aCount2 - aCount1;
                if((weWin &&  attacker.totalValues() > 2) || (weWin && enemyUnits.size() <= 3 && attacker.totalValues() == 2) && dFightersLost >= aFightersLost) 
                {
                    float maxAttackFactor = 2.25F;
                    if (bigProblem2.size() > 1)
                    	maxAttackFactor = 1.57F;//concerned about overextending if more than 1 territory
                    remainingStrengthNeeded = (maxAttackFactor * badStrength) + 3.0F;
                    for (Territory owned : capitalAttackTerr )
                    {
						if(remainingStrengthNeeded < 0.0F)
                            continue;

				        List<Unit> capAttackUnits = new ArrayList<Unit>();
                        List<Unit> units3 = owned.getUnits().getMatches(attackable);
                        units3.removeAll(unitsAlreadyMoved);
						for (Unit capAttackUnit : units3)
						{
							if (remainingStrengthNeeded < 0.0F)
								continue;
							remainingStrengthNeeded -= SUtils.uStrength(capAttackUnit, true, false, tFirst);
							capAttackUnits.add(capAttackUnit);
						}

                        if(capAttackUnits.size() > 0)
                        {
							weAttacked = true;
							Route newRoute = data.getMap().getRoute(owned, badTerr);
							alreadyAttacked.add(badTerr);
                            unitsAlreadyMoved.addAll(capAttackUnits);
                            moveUnits.add(capAttackUnits);
                            moveRoutes.add(newRoute);
                            remainingStrengthNeeded = (maxAttackFactor * badStrength) + 3.0F - SUtils.strength(capAttackUnits, true, false, tFirst);
                        }
                    }

                    s_logger.fine("Attacking : " + xTerr + " our strength:" + ourStrength + " enemy strength" + xStrength + " remaining strength needed " + remainingStrengthNeeded);
                }

	            remainingStrengthNeeded += 8.0F;
	            if (weAttacked && remainingStrengthNeeded > 0.0F)
	            {
					SUtils.inviteBlitzAttack(false, badTerr, remainingStrengthNeeded, unitsAlreadyMoved, moveUnits, moveRoutes, data, player, true, false);
					SUtils.invitePlaneAttack(false, false, badTerr, remainingStrengthNeeded, unitsAlreadyMoved, moveUnits, moveRoutes, data, player);
					SUtils.inviteTransports(false, badTerr, remainingStrengthNeeded, unitsAlreadyMoved, moveUnits, moveRoutes, data, player);
	            }
			}
            xMoveUnits.clear();
            xMoveRoutes.clear();
            xAlreadyMoved.clear();
		}

        //find the territories we can reasonably expect to take
 		float s1 = 0.0F, s2 = 0.0F, e1 = 0.0F, e2 = 0.0F, alliedStrength = 0.0F;
    	SUtils.getEnemyStrengthAt(e1, e2, data, player, myCapital, false, true, tFirst);
    	SUtils.getStrengthAt(s1, s2, data, player, myCapital, false, true, tFirst);
    	boolean useCapNeighbors = true;
    	if (capDanger) //TODO: really evaluate the territories around the capitol
    		useCapNeighbors = false; //don't use the capital neighbors to attack terr which are not adjacent to cap
        for(Territory enemy : enemyOwned)
        {
			if (alreadyAttacked.contains(enemy))
				continue;
			List<Unit> eUnits = enemy.getUnits().getMatches(Matches.enemyUnit(player, data));
            float enemyStrength = SUtils.strength(eUnits, false, false, tFirst);
            if(enemyStrength > 0.0F)
            {
                ourStrength = 0.0F;
                alliedStrength = 0.0F;
				Set<Territory> attackFrom = data.getMap().getNeighbors(enemy, Matches.territoryHasLandUnitsOwnedBy(player));
				List<Unit> myAUnits = new ArrayList<Unit>();
                for (Territory checkTerr2 : attackFrom)
                {
                	List<Unit> goodUnits = checkTerr2.getUnits().getMatches(attackable);
                	ourStrength += SUtils.strength(goodUnits, true, false, tFirst);
                	alliedStrength += SUtils.strength(checkTerr2.getUnits().getMatches(alliedAirLandUnit), true, false, tFirst);
                	myAUnits.addAll(goodUnits);
				}
				float xRS = 1000.0F;
				xAlreadyMoved.addAll(unitsAlreadyMoved);
				SUtils.inviteBlitzAttack(false, enemy, xRS, xAlreadyMoved, xMoveUnits, xMoveRoutes, data, player, true, false);
            	SUtils.invitePlaneAttack(false, false, enemy, xRS, xAlreadyMoved, xMoveUnits, xMoveRoutes, data, player);
            	ourStrength += (1000.0F - xRS);
				List<Unit> allMyUnits = new ArrayList<Unit>(myAUnits);
				for (Collection<Unit> xUnits : xMoveUnits)
					allMyUnits.addAll(xUnits);
				IntegerMap<UnitType> attacker = SUtils.convertListToMap(allMyUnits);
				IntegerMap<UnitType> defender = SUtils.convertListToMap(eUnits);
				boolean weWin = SUtils.quickBattleEstimator(attacker, defender, player, enemy.getOwner());
				s_logger.fine("Attacker: " + player.getName());
				s_logger.fine("Attacker: " + attacker.toString());
				s_logger.fine("Defender: " + defender.toString());
				s_logger.fine(weWin ? "Attacker Wins" : "Defender Wins");
                if (!weWin || (weWin && eUnits.size() < 3 && attacker.totalValues() < 2) || (weWin && eUnits.size() >=3 && attacker.totalValues() < 3))
                	continue;

                remainingStrengthNeeded = (2.20F * enemyStrength) + 5.0F; //limit the attackers
				if (attackFrom.size() == 1) //if we have 1 big attacker
				{
					for (Territory xyz : attackFrom)
						xTerr = xyz;
					List<Territory> enemyLandTerr = SUtils.getNeighboringEnemyLandTerritories(data, player, xTerr);
					if (enemyLandTerr.size() == 1) //the only enemy territory is the one we are attacking
					    remainingStrengthNeeded = (2.85F * enemyStrength) + 5.0F; //blow it away

				}

                for(Territory owned : attackFrom )
                {
					if(remainingStrengthNeeded < 0.0F || (!useCapNeighbors && capitalNeighbors.contains(owned)))
                        continue;

					List<Unit> ourUnits = owned.getUnits().getMatches(attackable);
					List<Unit> attackUnits = new ArrayList<Unit>();
					for (Unit AttackUnit : ourUnits)
					{
						if (remainingStrengthNeeded < 0.0F)
							continue;
						remainingStrengthNeeded -= SUtils.uStrength(AttackUnit, true, false, tFirst);
						attackUnits.add(AttackUnit);
					}
                    if(attackUnits.size() > 0)
                    {
    					SUtils.inviteBlitzAttack(false, enemy, remainingStrengthNeeded, unitsAlreadyMoved, moveUnits, moveRoutes, data, player, true, false);
    	            	SUtils.invitePlaneAttack(false, false, enemy, remainingStrengthNeeded, unitsAlreadyMoved, moveUnits, moveRoutes, data, player);
						weAttacked2 = true;
						alreadyAttacked.add(enemy);
                        unitsAlreadyMoved.addAll(attackUnits);
                        moveUnits.add(attackUnits);
                        moveRoutes.add(data.getMap().getRoute(owned, enemy));
                    }

                    s_logger.fine("Attacking : " + enemy + " our strength:" + ourStrength + " enemy strength" + enemyStrength + " remaining strength needed " + remainingStrengthNeeded);
                }
            }

            weAttacked2 = false;
            xMoveUnits.clear();
            xMoveRoutes.clear();
            xAlreadyMoved.clear();
        }
        /* By now, we have completed:
           1) Sea Attacks with air support
           2) Land Attacks with needed air support
           Check to see if we have air support available...might as well use it somewhere
        */
/*        if (alreadyAttacked.size() > 0)
        {
			for (Territory checkPlanes : alreadyAttacked)
			{
				if (emptyBadTerr.contains(checkPlanes))
					continue;
				float unlimitedStrength = 100.0F;
				SUtils.invitePlaneAttack(false, false, checkPlanes, unlimitedStrength, unitsAlreadyMoved, moveUnits, moveRoutes, data, player);
			}
		}
*/
        populateBomberCombat(data, moveUnits, moveRoutes, player);
    }

    private void populateBomberCombat(GameData data, List<Collection<Unit>> moveUnits, List<Route> moveRoutes, PlayerID player)
    {
		//bomber may have been used in attack...move validator will catch it though
		//bombers will be more involved in attacks...if they are still available, then bomb
        Match<Territory> enemyFactory = Matches.territoryHasEnemyFactory(getPlayerBridge().getGameData(), player);
        Match<Unit> ownBomber = new CompositeMatchAnd<Unit>(Matches.UnitIsStrategicBomber, Matches.unitIsOwnedBy(player), HasntMoved);

        for(Territory t: data.getMap().getTerritories())
        {
            Collection<Unit> bombers = t.getUnits().getMatches(ownBomber);
            if(bombers.isEmpty())
                continue;
            Match<Territory> routeCond = new InverseMatch<Territory>(Matches.territoryHasEnemyAA(player, data));
            Route bombRoute = SUtils.findNearest(t, enemyFactory, routeCond, data);

            moveUnits.add(bombers);
            moveRoutes.add(bombRoute);
        }
    }


    private int countTransports(GameData data, PlayerID player)
    {
        CompositeMatchAnd<Unit> ownedTransport = new CompositeMatchAnd<Unit>(Matches.UnitIsTransport, Matches.unitIsOwnedBy(player));
        int sum = 0;
        for(Territory t : data.getMap())
        {
            sum += t.getUnits().countMatches(ownedTransport );
        }
        return sum;
    }

    private int countLandUnits(GameData data, PlayerID player)
    {
        CompositeMatchAnd<Unit> ownedLandUnit = new CompositeMatchAnd<Unit>(Matches.UnitIsLand, Matches.unitIsOwnedBy(player));
        int sum = 0;
        for(Territory t : data.getMap())
        {
            sum += t.getUnits().countMatches(ownedLandUnit );
        }
        return sum;
    }

    private int countSeaUnits(GameData data, PlayerID player)
    {
        CompositeMatchAnd<Unit> ownedSeaUnit = new CompositeMatchAnd<Unit>(Matches.UnitIsSea, Matches.unitIsOwnedBy(player));
        int sum = 0;
        for(Territory t : data.getMap())
        {
            sum += t.getUnits().countMatches(ownedSeaUnit );
        }
        return sum;
    }

    protected void purchase(boolean purcahseForBid, int PUsToSpend, IPurchaseDelegate purchaseDelegate, GameData data, PlayerID player)
    {   //TODO: lot of tweaks have gone into this routine without good organization...need to cleanup
        //breakdown Rules by type and cost
		int highPrice = 0;
        List<ProductionRule> rules = player.getProductionFrontier().getRules();
        IntegerMap<ProductionRule> purchase = new IntegerMap<ProductionRule>();
		List<ProductionRule> landProductionRules = new ArrayList<ProductionRule>();
		List<ProductionRule> airProductionRules = new ArrayList<ProductionRule>();
		List<ProductionRule> seaProductionRules = new ArrayList<ProductionRule>();
		IntegerMap<ProductionRule> bestAttack = new IntegerMap<ProductionRule>();
		IntegerMap<ProductionRule> bestDefense = new IntegerMap<ProductionRule>();
		IntegerMap<ProductionRule> bestTransport = new IntegerMap<ProductionRule>();
		IntegerMap<ProductionRule> bestMaxUnits = new IntegerMap<ProductionRule>();
        ProductionRule highRule = null;
        Resource PUs = data.getResourceList().getResource(Constants.PUS);

        for (ProductionRule ruleCheck : rules)
		{
			int costCheck = ruleCheck.getCosts().getInt(PUs);
			UnitType x = (UnitType) ruleCheck.getResults().keySet().iterator().next();
			if (Matches.UnitTypeIsAir.match(x))
			{
			    airProductionRules.add(ruleCheck);
			}
			else if (Matches.UnitTypeIsSea.match(x))
			{
				seaProductionRules.add(ruleCheck);
			}
			else if (!Matches.UnitTypeIsAAOrFactory.match(x))
			{
				if (costCheck > highPrice)
				{
					highPrice = costCheck;
					highRule = ruleCheck;
				}
				landProductionRules.add(ruleCheck);
			}
		}

        if (purcahseForBid)
        {
            int buyLimit = PUsToSpend / 2;
            boolean bestMix = false;
            if (PUsToSpend < 25)
            	bestMix = SUtils.findPurchaseMix(bestAttack, bestDefense, bestTransport, bestMaxUnits, landProductionRules, PUsToSpend, buyLimit, data, player, 2);
            else
            {
            	landProductionRules.addAll(airProductionRules);
            	bestMix = SUtils.findPurchaseMix(bestAttack, bestDefense, bestTransport, bestMaxUnits, landProductionRules, PUsToSpend, buyLimit, data, player, 2);
            }
            if (bestMix)
            {
            	boolean buyAttack = Math.random() > 0.10; //just mix it up
            	int buyThese = 0;
				for (ProductionRule rule1 : landProductionRules)
				{
					if (buyAttack)
						buyThese = bestAttack.getInt(rule1);
					else
						buyThese = bestDefense.getInt(rule1);
					purchase.add(rule1, buyThese);
				}
            }
	        purchaseDelegate.purchase(purchase);
	        return;
            
        }

//        pause();
 		boolean tFirst = transportsMayDieFirst();
 		boolean shipCapitalThreat = false;
        CompositeMatch<Unit> enemyUnit = new CompositeMatchAnd<Unit>(Matches.enemyUnit(player, data));
        CompositeMatch<Unit> attackShip = new CompositeMatchOr<Unit>(Matches.UnitIsDestroyer, Matches.UnitIsSub, Matches.UnitIsTwoHit, Matches.UnitIsCarrier);
        CompositeMatch<Unit> enemyAttackShip = new CompositeMatchAnd<Unit>(enemyUnit, attackShip, Matches.UnitCanLandOnCarrier);
        CompositeMatch<Unit> enemyFighter = new CompositeMatchAnd<Unit>(enemyUnit, Matches.UnitCanLandOnCarrier);
        CompositeMatch<Unit> ourAttackShip = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player), attackShip);
        CompositeMatch<Unit> enemyTransport = new CompositeMatchAnd<Unit>(enemyUnit, Matches.UnitIsTransport);
        CompositeMatch<Unit> ourFactories = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player), Matches.UnitIsFactory);
        CompositeMatch<Unit> transUnit = new CompositeMatchAnd<Unit>(Matches.UnitIsTransport, Matches.unitIsOwnedBy(player));
        CompositeMatch<Unit> fighter = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player), Matches.UnitCanLandOnCarrier);

        Territory myCapital = TerritoryAttachment.getCapital(player, data);
        boolean isAmphib = isAmphibAttack(player), factPurchased = false;
		boolean isLand = SUtils.doesLandExistAt(myCapital, data); //gives different info than isamphib
	    boolean skipShips = false, buyTransports = true;
		boolean buyPlanesOnly = false, buyOnePlane=false, buyBattleShip = false, buySub = false;
		Territory factNeedShip = null;

		List<Territory> factories = SUtils.findUnitTerr(data, player, ourFactories);
        List<Territory> enemyAttackShipTerr = SUtils.findUnitTerr(data, player, enemyAttackShip);
        List<Territory> ourAttackShipTerr = SUtils.findUnitTerr(data, player, ourAttackShip);
        List<Territory> enemyTransportTerr = SUtils.findUnitTerr(data, player, enemyTransport);
        int EASCount = 0, OASCount = 0, ETTCount = 0;
        int factoryCount = factories.size();

        //negative of this is that it assumes all ships in same general area
        //Brits and USA start with ships in two theaters
        for (Territory EAST : enemyAttackShipTerr)
        {
			EASCount += EAST.getUnits().countMatches(enemyAttackShip);
			EASCount += EAST.getUnits().countMatches(enemyFighter);
        }
		for (Territory OAST : ourAttackShipTerr)
		{
			OASCount += OAST.getUnits().countMatches(ourAttackShip);
			OASCount += OAST.getUnits().countMatches(fighter);
		}
		for (Territory ETT : enemyTransportTerr)
			ETTCount += ETT.getUnits().countMatches(enemyTransport); //# of enemy transports

		boolean doBuyAttackShips = isAmphib ? ((EASCount + (tFirst ? ETTCount*0.5 : 0) ) > (OASCount + 1) || (tFirst ? OASCount < ETTCount*0.75 : false)) : false ;
		if (EASCount == 0 && ETTCount > 0 && OASCount == 0)
			buySub = true;

        int totPU = 0, totProd = 0, PUSea = 0, PULand = 0;
		float purchaseT;
		String error = null;
		boolean localShipThreat = false;
		int maxShipThreat = 0, currShipThreat = 0;
		Territory localShipThreatTerr = null;
        for (Territory fT : factories)
        {
			totPU += TerritoryAttachment.get(fT).getProduction();
			totProd += TerritoryAttachment.get(fT).getUnitProduction();
			if (!useProductionData())
				totProd = totPU;
			if (isAmphib)
			{
				 currShipThreat = SUtils.shipThreatToTerr(fT, data, player, tFirst);
				 if (currShipThreat > 1) //TODO: Emphasis is exclusively on capital: needs to be expanded to handle pacific Jap fleet
				 {
					 localShipThreat = true;
					 if (fT == myCapital)
					 {
				       	setSeaTerr(myCapital);
				       	shipCapitalThreat = true;
					 }
				 }
				 s_logger.fine("Player: " + player.getName()+ "; Factory: " + fT.getName() + "; ShipThreat: " + localShipThreat + "; Threat: " + currShipThreat);
				 if (currShipThreat > maxShipThreat)
				 {
					 maxShipThreat = currShipThreat;
					 localShipThreatTerr = fT;
				 }
			}
			currShipThreat = 0;
		} 
        //maximum # of units
		int unitCount=0;

/*        float enemySeaStrength = 0.0F, ourSeaStrength = 0.0F;
        for (Territory eT : enemyAttackShipTerr)
        	enemySeaStrength += SUtils.strength(eT.getUnits().getMatches(enemyAttackShip), true, true, tFirst);

		for (Territory oT : ourAttackShipTerr)
			ourSeaStrength += SUtils.strength(oT.getUnits().getMatches(ourAttackShip), true, true, tFirst);
*/
        int leftToSpend = player.getResources().getQuantity(PUs );
		purchaseT=1.00F;

        List<RepairRule> rrules = Collections.emptyList();
        if(player.getRepairFrontier() != null) // figure out if anything needs to be repaired
        {
            rrules = player.getRepairFrontier().getRules();
            IntegerMap<RepairRule> repairMap = new IntegerMap<RepairRule>();
            HashMap<Territory, IntegerMap<RepairRule>> repair = new HashMap<Territory, IntegerMap<RepairRule>>();
            Boolean repairs = false;
            int diff = 0;
		    for (RepairRule rrule : rrules)
            {
                for (Territory fixTerr : factories)
                {
                    if (!Matches.territoryHasOwnedFactory(data, player).match(fixTerr))
            	 	    continue;
        		    TerritoryAttachment ta = TerritoryAttachment.get(fixTerr);
            	    diff = ta.getProduction() - ta.getUnitProduction();
            	    diff = Math.min(diff, totPU/2);
                    if(diff > 0)
                    {
                        repairMap.add(rrule, diff);
                        repair.put(fixTerr, repairMap);
                        repairs = true;
					}
				}
        	}
            if (repairs)
            {
                error = purchaseDelegate.purchaseRepair(repair);
                leftToSpend -= diff;
            }
    	}

        //determine current risk to the capitol
        float ourSea1 = 0.0F, ourLand1 = 0.0F, ourSea2 = 0.0F, ourLand2 = 0.0F;
        float enemySea1 = 0.0F, enemyLand1 = 0.0F, enemySea2 = 0.0F, enemyLand2 = 0.0F, riskFactor = 1.0F;
        float realSeaThreat = 0.0F, realLandThreat = 0.0F;
        determineCapDanger(player, data, ourLand2, enemyLand2);
        boolean capDanger = getCapDanger();
 
		int fighterPresent = 0;
		if (capDanger) //focus on Land Units and buy before any other decisions are made
		{
        	landProductionRules.addAll(airProductionRules); //just in case we have a lot of PU
			if (SUtils.findPurchaseMix(bestAttack, bestDefense, bestTransport, bestMaxUnits, landProductionRules, leftToSpend, totProd, data, player, 0))
			{
				for (ProductionRule rule1 : landProductionRules)
				{
					int buyThese = bestDefense.getInt(rule1);
					purchase.add(rule1, buyThese);
					int cost = rule1.getCosts().getInt(PUs);
					leftToSpend -= cost*buyThese;
					unitCount += buyThese;
				}
			}
	        purchaseDelegate.purchase(purchase);
			return;
		}
        SUtils.getStrengthAt(ourSea1, ourSea2, data, player, myCapital, true, true, tFirst);
        SUtils.getStrengthAt(ourLand1, ourLand2, data, player, myCapital, false, true, tFirst);
        SUtils.getEnemyStrengthAt(enemySea1, enemySea2, data, player, myCapital, true, true, tFirst);
        SUtils.getEnemyStrengthAt(enemyLand1, enemyLand2, data, player, myCapital, false, true, tFirst);
        float PUNeeded = 0.0F;
        if (shipCapitalThreat) //don't panic on a small advantage
			PUNeeded = (float) maxShipThreat*7.0F; 	//Every 10.0F advantage needs about 7 PU to stop (TODO: Build function for PU needed for ships)

//        realLandThreat = enemyLand2*1.20F - ourLand2;
//        realSeaThreat = enemySea2*1.20F - ourSea2;
		realLandThreat = SUtils.getStrengthOfPotentialAttackers(myCapital, data, player, tFirst, false);
		realLandThreat -= SUtils.strength(myCapital.getUnits().getMatches(Matches.isUnitAllied(player, data)), false, false, tFirst);
		if (isAmphib && shipCapitalThreat && realLandThreat < 12.0F)
		{ //want to buy ships when we are overwhelmed
			if (Math.random() > 0.80)
				buyOnePlane = true;
			buyBattleShip=true;
			doBuyAttackShips = true;
			buyTransports=false;
		}
		else if (!isAmphib && realLandThreat < 2.0F)
		{
			if (Math.random() > 0.85)
			{
				buyOnePlane = true;
			}
			buyBattleShip = false;
		}
		if (isAmphib && isLand)
		{
			purchaseT =0.58F;
			if (maxShipThreat > 2 || Math.random() < 0.08 || (doBuyAttackShips && !tFirst))
				buyTransports=false;
		}
//		List<Territory> enemyTrans = SUtils.findUnits(myCapital, data, enemyTransport, 3);
//		realLandThreat += ((float) enemyTrans.size())*2.25F; //now let's add in the enemyTrans within range of capitol
        if (isAmphib && doBuyAttackShips && realLandThreat < 15.0F) //we are overwhelmed on sea
			purchaseT = 0.00F;
        else if (isAmphib && !isLand) //fix Japan if USA has massed a mess of attack ships
			purchaseT =0.58F;
		if (isAmphib && (PUNeeded > leftToSpend)) //they have major advantage, let's wait another turn
		{
			leftToSpend = Math.min(leftToSpend, (int) realLandThreat/2);
			purchaseT = 1.00F;
		}
		if (PUNeeded > 0.75F*leftToSpend)
			buyTransports=false;
		if (!isAmphib)
		{
			boolean noWater = !SUtils.isWaterAt(myCapital, data);
			purchaseT = 0.84F;
			if (Math.random() < 0.35)
				purchaseT = 1.00F;
			if (noWater)
			{
				purchaseT =1.00F;
				skipShips = true;
			}
		}
		float fSpend = (float) leftToSpend;
        PUSea = (int) (fSpend*(1.00F-purchaseT));
        PULand = leftToSpend - PUSea;
        int minCost = Integer.MAX_VALUE;

		//Test for how badly we want transports
		//If we have a land route to enemy capital...forget about it
		//If we have land units close to us...forget about it
		//If we have a ton of units in our capital, then let's buy transports
	    int transConstant = 2;

    	List <Territory> xy = SUtils.findOnlyMyShips(myCapital, data, player, Matches.UnitIsTransport);
    	List <Unit> capTrans = new ArrayList<Unit>();
    	for (Territory xyz : xy)
    		capTrans.addAll(xyz.getUnits().getMatches(transUnit));
    	List <Unit> capUnits = myCapital.getUnits().getMatches(Matches.UnitCanBeTransported);
	    if (isAmphib && (capUnits.size() > capTrans.size()*2) && !shipCapitalThreat && maxShipThreat < 2)
	    {
			transConstant=1; //we can accelerate transport buying
			buyTransports = true;
	    	if (!isLand)
	    	{
	    		if (PULand > 8) //assume cost of transport is 8
	    		{
					PULand -=8;
					PUSea +=8;
				}
			}
			if (capUnits.size() > capTrans.size()*3)
			{
				PULand = PULand/2;
				PUSea = leftToSpend - PULand;
			}
			if (capUnits.size() > capTrans.size()*4) //put all the money on PUSea
			{
				PUSea = leftToSpend;
				PULand = 0;
			}
		}
		else if (isAmphib && (capUnits.size()*2 < capTrans.size()) && realSeaThreat < 2.0F)
		{
			PULand=leftToSpend;
			PUSea=0;
			skipShips = true;
			buyTransports=false;
		}

		//Purchase land units first
		int landConstant = 2; //we want to loop twice to spread out our purchase
		boolean highPriceLandUnits = false;
		if (leftToSpend > 10*(totProd-2) && !doBuyAttackShips) //if not buying ships, buy planes
		{
			buyPlanesOnly = true;
			buyBattleShip = true;
		}
		else if (leftToSpend > 5*(totProd-1))
			buyOnePlane = true;

		boolean extraPUonPlanes = false;
        if (capDanger) //capital in trouble...purchase units accordingly...most expensive available
        {
        	if (!isLand && !doBuyAttackShips) //try to balance the need for Naval units here
        	{
        		PULand = leftToSpend;
        		PUSea = 0;
        	}
	        
	        extraPUonPlanes = true;
	        buyTransports = false;
        }

        highPriceLandUnits = (highPrice*totProd) < PULand;
		if (highPriceLandUnits && !doBuyAttackShips && !buyPlanesOnly)
		{
			if (!buyOnePlane)
			{
				leftToSpend -= highPrice*totProd;
				PULand -= highPrice*totProd;
				purchase.add(highRule, totProd);
				unitCount = totProd;
			}
			else
			{
				leftToSpend -= highPrice*(totProd-1);
				PULand -= highPrice*(totProd-1);
				purchase.add(highRule, totProd-1);
				unitCount = totProd-1;
				buyPlanesOnly=true;
			}
		}

		if (((isAmphib && isLand) && (realLandThreat > 8.00F)) || (!isAmphib && realLandThreat > 12.00F))
		{
			landConstant = 1; //if we have a strong threat to our capitol, focus on cheapest units
			PULand = leftToSpend;
			PUSea = 0;
		}
		int maxPurch = PULand/3;
		if (maxPurch > (totPU + 4)) //more money than places to put units...buy more expensive units
		{
			landConstant = 2;
			buyOnePlane = true;
			highPriceLandUnits=true;
		}
		if (realLandThreat < 0.0F && !capDanger)
			highPriceLandUnits = true;

		boolean buyfactory = false, buyExtraLandUnits = true; //fix this later...we might want to save PU
		if (landConstant !=1)
		{
			buyfactory = true;
			int numFactory = 0;
			
			for (Territory fT2 : factories)
			{
				if (SUtils.hasLandRouteToEnemyOwnedCapitol(fT2, player, data))
					numFactory++;
			}
			if (numFactory >= 2 || capDanger)
				buyfactory = false; //allow 2 factories on the same continent
		}
		if (!doBuyAttackShips && realLandThreat < 12.0F && buyfactory) //determine factory first to make sure enough PU...doesn't count toward units
		{
			for (ProductionRule factoryRule : rules)
			{
				int cost = factoryRule.getCosts().getInt(PUs);
				UnitType factoryType = (UnitType) factoryRule.getResults().keySet().iterator().next();
				if (Matches.UnitTypeIsFactory.match(factoryType))
				{
					if (leftToSpend >= cost && !factPurchased && buyfactory)
					{
						Territory factTerr = SUtils.findFactoryTerritory(data, player, riskFactor, buyfactory);

						if (factTerr != null)
						{
							setFactory(factTerr);
							purchase.add(factoryRule, 1);
							leftToSpend -=cost;
							PULand -= cost;
							factPurchased = true;
							if (PULand < 0)
								PUSea = leftToSpend;
						}
					}
				}
			}
		} //done buying factories...only buy 1
		int maxBuy = totProd;
		if (buyOnePlane)
			maxBuy--;

		if (PUSea > 0 && doBuyAttackShips && !buyTransports) //attack oriented sea units
		{
			if (isAmphib && !capDanger &&  maxShipThreat > 2)
				PUSea = leftToSpend;
			if (unitCount < 2)
				setAttackShipPurchase(true);
			fighterPresent = myCapital.getUnits().countMatches(Matches.UnitCanLandOnCarrier);
			if (PUSea > 0 && SUtils.findPurchaseMix(bestAttack, bestDefense, bestTransport, bestMaxUnits, seaProductionRules, PUSea, maxBuy, data, player, fighterPresent))
			{
				int buyThese = 0;
				int AttackType = 1; //bestAttack
				if (Math.random() <= 0.90) //for ships, focus on defense set most of the time
					AttackType = 2;
				if (Math.random() >= 0.50 && factoryCount == 1 && PUNeeded > 0.75*leftToSpend) //50% maxUnits when need a lot of ships 
					AttackType = 3;
				for (ProductionRule rule1 : seaProductionRules)
				{
					switch (AttackType)
					{
					case 1: 
						buyThese = bestAttack.getInt(rule1);
						break;
					case 2:
						buyThese = bestDefense.getInt(rule1);
						break;
					case 3:
						buyThese = bestMaxUnits.getInt(rule1);
						break;
					}
					purchase.add(rule1, buyThese);
					int cost = rule1.getCosts().getInt(PUs);
					leftToSpend -= cost*buyThese;
					PUSea -= cost*buyThese;
					unitCount += buyThese;
				}
			}
			bestAttack.clear();
			bestDefense.clear();
			bestTransport.clear();
			bestMaxUnits.clear();
		}
		if (!capDanger && !buyPlanesOnly) //attack oriented land units
		{
			if (SUtils.findPurchaseMix(bestAttack, bestDefense, bestTransport, bestMaxUnits, landProductionRules, PULand, maxBuy, data, player, fighterPresent))
			{
				int buyThese = 0;
				int AttackType = 1; //bestAttack
				if (Math.random() <= 0.15) //just to switch it up...every once in a while, buy the defensive set
					AttackType = 2;
				if (Math.random() >= 0.18 && factoryCount > 1) //if we have a lot of factories, use the max Unit set most of the time
					AttackType = 3; //maxUnits
				for (ProductionRule rule1 : landProductionRules)
				{
					switch (AttackType)
					{
					case 1: 
						buyThese = bestAttack.getInt(rule1);
						break;
					case 2:
						buyThese = bestDefense.getInt(rule1);
						break;
					case 3:
						buyThese = bestMaxUnits.getInt(rule1);
						break;
					}
					purchase.add(rule1, buyThese);
					int cost = rule1.getCosts().getInt(PUs);
					leftToSpend -= cost*buyThese;
					PULand -= cost*buyThese;
					unitCount += buyThese;
				}
			}
			bestAttack.clear();
			bestDefense.clear();
			bestTransport.clear();
			bestMaxUnits.clear();
		}
		for (int i=0; i<=3; i++)
		{
			if (unitCount >= totProd)
				continue;
            for(ProductionRule rule : rules)
            {
                int cost = rule.getCosts().getInt(PUs);

                if(minCost == Integer.MAX_VALUE)
                    minCost = cost;

                if(minCost > cost)
                    minCost = cost;

                if (leftToSpend < cost || unitCount >= totProd)
                	continue;

                UnitType results = (UnitType) rule.getResults().keySet().iterator().next();
				if (Matches.UnitTypeIsSea.match(results) || Matches.UnitTypeIsFactory.match(results))
					continue;

                if(Matches.UnitTypeIsAA.match(results))
                {
                    continue; //do we have an unguarded factory?
                }
                if (Matches.UnitTypeIsAir.match(results))
                {
					if (buyPlanesOnly && unitCount < totProd)
					{
						int planePurchase = (totPU-unitCount)/2;
						if (planePurchase == 0)
							planePurchase=1;
						purchase.add(rule, planePurchase);
						unitCount +=planePurchase;
						leftToSpend -= planePurchase*cost;
					}
					if (buyOnePlane && unitCount < totProd)
					{
						purchase.add(rule, 1);
						buyOnePlane= false;
						leftToSpend -= cost;
						unitCount++;
					}

					continue;
				}
/*				if (cost <=PUcLand && !buyPlanesOnly)
				{
					int numLand = (int) (PULand / cost);
					if (numLand == 1)
						numLand = landConstant;
					if (highPriceLandUnits && cost == highPrice)
						numLand = numLand*3;
					while (cost*(numLand/landConstant) > PULand)
						numLand--;

					int numPLand = numLand / landConstant;
					while (totPU < (unitCount + numPLand))
						numPLand--;
					if (numPLand > 0)
					{
						PULand -= cost * numPLand;
						leftToSpend -= cost * numPLand;
						purchase.add(rule, numPLand);
						unitCount += numPLand;
					}
					continue;
				}
*/			}
		}
		if (isAmphib)
			PUSea = leftToSpend; //go ahead and make them available
		for (int j=0; j<=3; j++) //make 3 passes on sea purchases
		{
			if (skipShips)
				continue;
            for(ProductionRule rule : rules)
            {
                int cost = rule.getCosts().getInt(PUs);

                if(minCost == Integer.MAX_VALUE)
                    minCost = cost;
                if(minCost > cost)
                    minCost = cost;

                if ((leftToSpend < cost || PUSea < cost || unitCount >= totProd))
                	continue;

                UnitType results = (UnitType) rule.getResults().keySet().iterator().next();
                if (Matches.UnitTypeIsNotSea.match(results))
 					continue;
                int transportCapacity = UnitAttachment.get(results).getTransportCapacity();
				if (j==0 && transportCapacity <=0)
					continue; //the first purchase is only for transports if buyTransports is true
				if (transportCapacity > 0 &&  buyTransports && !doBuyAttackShips)
				{
					if (PUSea >= cost)
					{
						int buyTrans = (int) (PUSea/cost);
						if (transConstant == 1)
						{
							if (buyTrans > 0)
							{
								if ((unitCount + buyTrans) > totPU)
									buyTrans= totPU - unitCount;
								while (cost*(buyTrans)>PUSea)
									buyTrans--;

								purchase.add(rule, buyTrans);
								unitCount += buyTrans;
								PUSea -= cost*buyTrans;
								leftToSpend -= cost*buyTrans;
								buyTransports = false;
							}
						}
						else
						{
							if (realSeaThreat <=5.0F )
							{
								if (buyTrans ==1)
								{
									PUSea-=cost;
									leftToSpend -= cost;
									purchase.add(rule, 1);
									unitCount += 1;
									continue;
								}
								else
								{
									buyTrans = buyTrans - (unitCount + buyTrans - totPU);
									buyTrans = buyTrans/transConstant;
									while (cost*(buyTrans) > PUSea)
										buyTrans--;
									if (buyTrans > 0)
									{
										PUSea-=cost*buyTrans;
										leftToSpend-=cost*buyTrans;
										purchase.add(rule, buyTrans);
										unitCount += buyTrans;
									}
								}
							}
						}

					}
					continue;
				}
				if (buyBattleShip && PUSea >= cost && cost >=20)
				{
					PUSea-=cost;
					leftToSpend-=cost;
					purchase.add(rule, 1);
					unitCount++;
				}
				if (doBuyAttackShips && unitCount < totProd && PUSea >= cost && transportCapacity > 0 && buyTransports)
				{
					if ((cost< 13 && Math.random() <0.75) || (cost < 20 && Math.random() < 0.48) || (cost >= 20 && Math.random() > 0.65))
					{
						PUSea-=cost;
						leftToSpend-=cost;
						purchase.add(rule, 1);
						unitCount++;
					}
				} //let it roll through for another purchase because doBuyAttackShips is on
				if (buySub && leftToSpend > cost && unitCount < totProd && Matches.UnitTypeIsSub.match(results))
				{
					leftToSpend-=cost;
					PUSea-=cost;
					purchase.add(rule,1);
					unitCount++;
				} //go ahead and see if it wants to buy another one

				if (PUSea >= cost && cost < 13 && unitCount < totProd && Math.random() < 0.28 && (doBuyAttackShips==(transportCapacity < 0)))
				{
					PUSea-=cost;
					leftToSpend-=cost;
					purchase.add(rule, 1);
					unitCount++;
					continue;
				}
				if (PUSea >= cost && (cost >= 13 && cost < 20) && unitCount < totProd && Math.random() < 0.18)
				{
					PUSea-=cost;
					leftToSpend-=cost;
					purchase.add(rule, 1);
					unitCount++;
					continue;
				}
				if (PUSea >= cost && Matches.UnitTypeIsBB.match(results) && unitCount < totProd && Math.random() >= 0.82)

				{
					PUSea-=cost;
					leftToSpend-=cost;
					purchase.add(rule, 1);
					unitCount++;
				}
            }
		}
		if ((unitCount < totProd) && buyExtraLandUnits)
		{
			for (ProductionRule quickProd : rules)
			{
                int quickCost = quickProd.getCosts().getInt(PUs);

                if (leftToSpend < quickCost || unitCount >= totProd)
                	continue;

                UnitType intResults = (UnitType) quickProd.getResults().keySet().iterator().next();
                if (Matches.UnitTypeIsSeaOrAir.match(intResults) || Matches.UnitTypeIsAAOrFactory.match(intResults))
					continue;

				if (quickCost <= leftToSpend)
				{
					int purchaseNum = totProd - unitCount;
					int numLand = (int) (leftToSpend / quickCost);
					int actualPNum = Math.min(purchaseNum, numLand);

					if (actualPNum > 0)
					{
						leftToSpend -= quickCost * actualPNum;
						purchase.add(quickProd, actualPNum);
						unitCount += actualPNum;
					}
				}
			}
		}
		if (leftToSpend > 0 && (unitCount < totProd) && extraPUonPlanes)
		{
			for (ProductionRule planeProd : rules)
			{
				int planeCost = planeProd.getCosts().getInt(PUs);
				if (leftToSpend < planeCost || unitCount >= totProd)
					continue;
				UnitType plane = (UnitType) planeProd.getResults().keySet().iterator().next();
				if (Matches.UnitTypeIsAir.match(plane))
				{
					if (capDanger && !Matches.unitTypeCanBombard(player).match(plane)) //buy best defensive plane
					{
						int maxPlanes = totProd - unitCount;
						int costPlanes = leftToSpend/planeCost;
						if (costPlanes <= maxPlanes)
							purchase.add(planeProd, costPlanes);
						else
							purchase.add(planeProd, maxPlanes);
					}
					else
					{
						purchase.add(planeProd, 1);
						leftToSpend -= planeCost;
						unitCount++;
					}
				}
			}
		}
							
        purchaseDelegate.purchase(purchase);
    }


    protected void place(boolean bid, IAbstractPlaceDelegate placeDelegate, GameData data, PlayerID player)
    {
        //if we have purchased a factory, it will be a priority for placing units
        //should place most expensive on it
        //need to be able to handle AA purchase
        if (player.getUnits().size() == 0)
            return;

        final BattleDelegate delegate = DelegateFinder.battleDelegate(data);

		boolean tFirst = transportsMayDieFirst();
        CompositeMatch<Unit> ownedUnit = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(player));
        CompositeMatch<Unit> attackUnit = new CompositeMatchOr<Unit>(Matches.UnitIsSea, Matches.UnitIsNotTransport);
        CompositeMatch<Unit> transUnit = new CompositeMatchAnd<Unit>(Matches.UnitIsTransport);
        CompositeMatch<Unit> enemyUnit = new CompositeMatchAnd<Unit>(Matches.enemyUnit(player, data));
        CompositeMatch<Unit> enemyAttackUnit = new CompositeMatchAnd<Unit>(attackUnit, enemyUnit);
        CompositeMatch<Unit> enemyTransUnit = new CompositeMatchAnd<Unit>(transUnit, enemyUnit);
        CompositeMatch<Unit> ourFactory = new CompositeMatchAnd<Unit>(ownedUnit, Matches.UnitIsFactory);
        CompositeMatch<Unit> landUnit = new CompositeMatchAnd<Unit>(ownedUnit, Matches.UnitIsLand, Matches.UnitIsNotFactory);
        CompositeMatch<Territory> ourLandTerr = new CompositeMatchAnd<Territory>(Matches.isTerritoryOwnedBy(player), Matches.TerritoryIsLand);
        Territory capitol =  TerritoryAttachment.getCapital(player, data);
        List<Territory> factoryTerritories = SUtils.findUnitTerr(data, player, ourFactory);

        /**
         * Bid place with following criteria:
         * 1) Has an enemy Neighbor
         * 2) Has the largest combination value:
         *    a) enemy Terr
         *    b) our Terr
         *    c) other Terr neighbors to our Terr
         *    d) + 2 for each of these which are victory cities
         */
        if (bid)
        {
        	
        	List<Territory> ourTerrs = new ArrayList<Territory>();
        	for (Territory t : data.getMap().getTerritoriesOwnedBy(player))
        	{
        		
        		Set<Territory> testT = data.getMap().getNeighbors(t,
        		    new CompositeMatchAnd<Territory>(
        		        Matches.isTerritoryEnemy(player, data),
        		        Matches.TerritoryIsLand,
        		        Matches.TerritoryIsNotNeutral        		        
        		        ));
           		if (testT.size() > 0)
           		{
        			ourTerrs.add(t);
           		}
           		else
        			s_logger.fine("Player: " + player.getName() + "; Territory Removed: " + t.getName());

        	}
        	int maxTotValue = 0;
        	Territory bidLandTerr = null;
        	for (Territory possBidTerr : ourTerrs)
        	{ 
        		
        		int thisTerrValue = TerritoryAttachment.get(possBidTerr).getProduction();
       			Set<Territory> otherNeighbors = data.getMap().getNeighbors(possBidTerr, Matches.TerritoryIsLand);
       			for (Territory addThese : otherNeighbors)
        		{
        			thisTerrValue += TerritoryAttachment.get(addThese).getProduction();
        			thisTerrValue += TerritoryAttachment.get(addThese).isVictoryCity() ? 2 : 0;
       			}
       			thisTerrValue += TerritoryAttachment.get(possBidTerr).isVictoryCity() ? 2 : 0; 
       			if (thisTerrValue >= maxTotValue)
       			{
       				bidLandTerr = possBidTerr;
       				maxTotValue = thisTerrValue;
        		}
        		s_logger.fine("Player: " + player.getName() + "; Terr: " + possBidTerr.getName() + "; Total Score: " + thisTerrValue);
        	}
        	if (bidLandTerr == null)
        		bidLandTerr = capitol;
        	Territory bidSeaTerr = null;
        	float eStrength = 0.0F;
        	for (Territory factCheck : factoryTerritories)
        	{
        		if (bidSeaTerr != null)
        			continue;
        		bidSeaTerr = SUtils.findASeaTerritoryToPlaceOn(factCheck, eStrength, data, player, tFirst);
        	}
	        placeAllWeCanOn(bid, data, bidSeaTerr, bidSeaTerr, bidLandTerr, placeDelegate, player);
        	return;
        }
        Territory specSeaTerr = getSeaTerr();
        boolean capDanger = getCapDanger();
        boolean amphib = isAmphibAttack(player);
        //maybe we bought a factory
        Territory factTerr = getFactory();
        if (factTerr != null)
        	placeAllWeCanOn(bid, data, factTerr, factTerr, factTerr, placeDelegate, player);

        //check for no factories, but still can place
        RulesAttachment ra = (RulesAttachment) player.getAttachment(Constants.RULES_ATTATCHMENT_NAME);
        if ((ra != null && ra.getPlacementAnyTerritory()) || bid) //make them all available for placing
           factoryTerritories.addAll(SUtils.allOurTerritories(data, player));
        List<Territory> cloneFactTerritories = new ArrayList<Territory>(factoryTerritories);
        for (Territory deleteBad : cloneFactTerritories)
        {
			if (delegate.getBattleTracker().wasConquered(deleteBad))
				factoryTerritories.remove(deleteBad);
		}

        int minDist = 100;
        if (!factoryTerritories.contains(capitol))
        	factoryTerritories.add(capitol);
        /*
        Here is plan: Place units at the factory which is closest to a bad guy Territory
        			  Place transports at the factory which has the most land units
                      Place attack sea units at the factory closest to attack sea units
                      Tie goes to the capitol
        */
        Territory seaPlaceAtTrans = null, seaPlaceAtAttack = null, landFactTerr = null;
		float eStrength = 0.0F;
		IntegerMap<Territory> landUnitFactories = new IntegerMap<Territory>();
		IntegerMap<Territory> transportFactories = new IntegerMap<Territory>();
		IntegerMap<Territory> seaAttackUnitFactories = new IntegerMap<Territory>();
		Route goRoute = new Route();
		
		int landUnitCount = player.getUnits().countMatches(landUnit);
		int transUnitCount = player.getUnits().countMatches(transUnit);
		int seaAttackUnitCount = player.getUnits().countMatches(attackUnit);
		int fighterUnitCount = player.getUnits().countMatches(Matches.UnitCanLandOnCarrier);
		int carrierUnitCount = player.getUnits().countMatches(Matches.UnitIsCarrier);
		for (Territory unitFact : factoryTerritories)
		{
			if (SUtils.landRouteToEnemyCapital(unitFact, goRoute, data, player))
				landUnitFactories.put(unitFact, 1);
			else
			{
				landUnitFactories.put(unitFact, 0);
				transportFactories.put(unitFact, 0);
				seaAttackUnitFactories.put(unitFact, 0);
			}
		}
		//case for Russia and Germany in WW2V2 and Italians in WW2V3
		if (transportFactories.size() == 0)
		{
			for (Territory unitFact2 : factoryTerritories)
			{
				if (SUtils.isWaterAt(unitFact2, data))
					transportFactories.put(unitFact2, 0);
			}
		}
		if (seaAttackUnitFactories.size() == 0)
		{
			for (Territory unitFact3 : factoryTerritories)
			{
				if (SUtils.isWaterAt(unitFact3, data))
					seaAttackUnitFactories.put(unitFact3, 0);
			}
		}
		Collection<Territory> landFactories = landUnitFactories.keySet();
		List<Territory> landRouteFactories = new ArrayList<Territory>();
		for (Territory landCheck : landFactories)
		{
			/*
			 * Rank by: 1) Threat 2) Proximity to enemy factories 3) Proximity to enemy capital 4) Proximity to enemy
			 */
			float totThreat = SUtils.getStrengthOfPotentialAttackers(landCheck, data, player, tFirst, true);
			float myStrength = SUtils.strength(landCheck.getUnits().getMatches(Matches.UnitIsNotSea), false, false, tFirst);
			boolean landRoute = SUtils.landRouteToEnemyCapital(landCheck, goRoute, data, player);
			if (landCheck == capitol && totThreat > myStrength)
			{
				landUnitFactories.put(landCheck, 4);
			}
			else if (totThreat > (myStrength + 5.0F))
				landUnitFactories.put(landCheck, 3);
			else if (totThreat - myStrength > -10.0F && totThreat > 8.0F) //only have a marginal advantage
			{
				landUnitFactories.put(landCheck, 1);
				landRouteFactories.add(landCheck);
			}
			else if (landRoute)
				landRouteFactories.add(landCheck);
		}
		Territory minTerr = null;
//		List<Territory> landRouteFactories2 = new ArrayList<Territory>();
		//check territories which have a land route to a capital but don't have a strong local threat
		for (Territory landCheck2 : landRouteFactories)
		{
			boolean landRoute2 = SUtils.landRouteToEnemyCapital(landCheck2, goRoute, data, player);
			goRoute = null;
			goRoute = SUtils.findNearest(landCheck2, Matches.territoryHasEnemyFactory(data, player), SUtils.TerritoryIsNotImpassableToLandUnits, data);
			if ((landRoute2 && goRoute != null))
			{
				int lRDist =  goRoute.getLength();
				if (lRDist < minDist)
				{
					landUnitFactories.put(landCheck2, 1);
					minDist = lRDist;
					minTerr = landCheck2;
				}
			}
			else if (goRoute != null)
				landUnitFactories.put(landCheck2, 1);
/*			Set<Territory> enemyLandNeighbors = data.getMap().getNeighbors(landCheck2, 3);
			for (Territory landCheck3 : enemyLandNeighbors)
			{
				if (Matches.isTerritoryEnemyAndNotNuetralWater(player, data).match(landCheck3))
				{
					if (Matches.isTerritoryEnemy(player, data).match(landCheck3) && Matches.TerritoryIsEmpty.match(landCheck3))
						landUnitFactories.put(landCheck2, 1);
					if (!Matches.TerritoryIsEmpty.match(landCheck3))
						landUnitFactories.put(landCheck2, 0);
				}
			}
*/		}
		if (minTerr != null)
			landUnitFactories.put(minTerr, 3);
/*		float maxStrength = 0.0F;
		minTerr = null;
		for (Territory landCheck4 : landRouteFactories2)
		{
			float testStrength = SUtils.getStrengthOfPotentialAttackers(landCheck4, data, player, tFirst, true);
			if (testStrength > maxStrength)
			{
				minTerr = landCheck4;
				maxStrength = testStrength;
			}
		}
		if (minTerr != null)
			landUnitFactories.put(minTerr, 3);
*/		float strengthToOvercome = 0.0F;
		Set<Territory> transFactories = transportFactories.keySet();
		float carrierFighterAddOn = 0.0F;
		if (carrierUnitCount > 0)
		{
			if (fighterUnitCount > 0)
			{
				carrierFighterAddOn += fighterUnitCount*3.5F;
			}
		}
		if (transFactories.size() == 1 && seaAttackUnitFactories.size() == 1)
		{
			for (Territory oneFact : transFactories)
			{
				seaPlaceAtTrans = SUtils.findASeaTerritoryToPlaceOn(oneFact, eStrength, data, player, tFirst);
				seaPlaceAtAttack = seaPlaceAtTrans;
			}
		}
		else if (transFactories.size() > 0)
		{
			for (Territory transCheck : transFactories)
			{
				int unitsHere = transCheck.getUnits().countMatches(landUnit);
				Territory dropHere = SUtils.findASeaTerritoryToPlaceOn(transCheck, strengthToOvercome, data, player, tFirst);
				if (dropHere == null)
					continue;
				float eSeaStrength = SUtils.getStrengthOfPotentialAttackers(dropHere, data, player, tFirst, true);
				if ((eSeaStrength == 0.0F && unitsHere > transUnitCount*2) || (eSeaStrength > 5.0F && dropHere.getUnits().someMatch(Matches.UnitIsSea)))
				{
					seaPlaceAtTrans = dropHere;
					seaPlaceAtAttack = dropHere;
				}
/*				if (strengthToOvercome > 0.0F)
				{
					strengthToOvercome -= transUnitCount*0.5F;
					strengthToOvercome -= seaAttackUnitCount*3.5F - carrierFighterAddOn;
				}
				if (strengthToOvercome <= 5.0F && unitsHere >= transUnitCount*2)
				{
					seaPlaceAtTrans = dropHere;
					if (eSeaStrength > 5.0F)
						seaPlaceAtAttack = dropHere; //want to drop trans with sea units if reasonable
				}
*/			}
		}
		Territory tempTerr = null;
		if (seaPlaceAtAttack == null) //TODO: Mixed searching sea locations between purchase and place...merge
		{
			Set<Territory> seaAttackFactories = seaAttackUnitFactories.keySet();
			for (Territory checkAgain : seaAttackFactories)
			{
				int attackAdv = SUtils.shipThreatToTerr(checkAgain, data, player, tFirst);
				if (attackAdv > 0)
				{
					tempTerr = SUtils.findASeaTerritoryToPlaceOn(checkAgain, eStrength, data, player, tFirst);
					if (tempTerr != null && (attackAdv - 1) < seaAttackUnitCount + tempTerr.getUnits().getMatches(attackUnit).size())
						seaPlaceAtAttack = tempTerr;
				}
			}
		}
		Territory tmpSeaLoc = null;
		if (specSeaTerr != null)
		{//purchasing had a special place in mind
			tmpSeaLoc = SUtils.findASeaTerritoryToPlaceOn(specSeaTerr, eStrength, data, player, tFirst);
		}
		if (tmpSeaLoc != null)
			seaPlaceAtAttack = tmpSeaLoc;
		if (!bid && capDanger)
			landUnitFactories.put(capitol, 3);

		landFactories.clear();
		landFactories.addAll(landUnitFactories.keySet());
		if (landUnitFactories.size() == 1)
		{
			for (Territory theOne : landFactories)
			{
				landFactTerr = theOne;
				if (seaPlaceAtTrans == null)
					seaPlaceAtTrans = landFactTerr;
				if (seaPlaceAtAttack == null)
					seaPlaceAtAttack = landFactTerr;
		        placeAllWeCanOn(bid, data, seaPlaceAtTrans, seaPlaceAtAttack, landFactTerr, placeDelegate, player);
			}	
		}
		else
		{
			for (int i=4; i >= 0; i--)
			{
				for (Territory whichOne : landFactories)
				{
					if (landUnitFactories.getInt(whichOne) == i)
					{
						landFactTerr = whichOne;
						if (seaPlaceAtTrans == null)
							seaPlaceAtTrans = landFactTerr;
						if (seaPlaceAtAttack == null)
							seaPlaceAtAttack = landFactTerr;
				        placeAllWeCanOn(bid, data, seaPlaceAtTrans, seaPlaceAtAttack, landFactTerr, placeDelegate, player);
					}
				}
			}
		}
		

		//if we have some that we haven't placed
        Collections.shuffle(factoryTerritories);
        for(Territory t : factoryTerritories)
        {
			Territory seaPlaceAt = SUtils.findASeaTerritoryToPlaceOn(t, eStrength, data, player, tFirst);
			if (seaPlaceAt == null)
				seaPlaceAt = seaPlaceAtTrans;
			if (seaPlaceAt == null)
				seaPlaceAt = t; //just put something...maybe there are no sea factories
            placeAllWeCanOn(bid, data, seaPlaceAt, seaPlaceAt, t, placeDelegate, player);
        }
    }


    private void placeAllWeCanOn(boolean bid, GameData data, Territory seaPlaceAt1, Territory seaPlaceAt2, Territory placeAt, IAbstractPlaceDelegate placeDelegate, PlayerID player)
    {
        CompositeMatch<Unit> attackUnit = new CompositeMatchAnd<Unit>(Matches.UnitIsSea, Matches.UnitIsNotTransport);
        CompositeMatch<Unit> transUnit = new CompositeMatchAnd<Unit>(Matches.UnitIsTransport);
        CompositeMatch<Unit> landOrAir = new CompositeMatchOr<Unit>(Matches.UnitIsAir, Matches.UnitIsLand);

		if (seaPlaceAt1 == placeAt && seaPlaceAt2 == placeAt ) //place a factory?
		{
			Collection<Unit> toPlace = new ArrayList<Unit>(player.getUnits().getMatches(Matches.UnitIsFactory));
			if (toPlace.size() == 1) //only 1 may have been purchased...anything greater is wrong
			{
				doPlace(placeAt, toPlace, placeDelegate);
				return;
			}
			else if (toPlace.size() > 1)
				return;
		}

        List<Unit> aSeaUnits = player.getUnits().getMatches(attackUnit);
        List<Unit> tSeaUnits = player.getUnits().getMatches(transUnit);
        List<Unit> landUnits = player.getUnits().getMatches(landOrAir);

        if (seaPlaceAt1 == null && seaPlaceAt2 != null)
        	seaPlaceAt1 = seaPlaceAt2;
        if (seaPlaceAt1 == null && seaPlaceAt2 == null)
        { //no choice
        	seaPlaceAt1 = placeAt;
        	seaPlaceAt2 = placeAt;
        }
        PlaceableUnits pu1 = placeDelegate.getPlaceableUnits(tSeaUnits , seaPlaceAt1);
        PlaceableUnits pu2 = placeDelegate.getPlaceableUnits(aSeaUnits , seaPlaceAt2);
        PlaceableUnits pu3 = placeDelegate.getPlaceableUnits(landUnits , placeAt);
        if(pu1.getErrorMessage() != null || pu2.getErrorMessage() != null || pu3.getErrorMessage() != null)
            return;
        int placementLeft1 =  pu1.getMaxUnits();
        if(placementLeft1 == -1)
            placementLeft1 = Integer.MAX_VALUE;
        int placementLeft2 =  pu2.getMaxUnits();
        if(placementLeft2 == -1)
            placementLeft2 = Integer.MAX_VALUE;
        int placementLeft3 =  pu3.getMaxUnits();
        if(placementLeft3 == -1)
            placementLeft3 = Integer.MAX_VALUE;
        if (bid)
        {
        	placementLeft1 = 1000;
        	placementLeft2 = 1000;
        	placementLeft3 = 1000;
        }

        if(!tSeaUnits.isEmpty())
        {
            int seaPlacement = Math.min(placementLeft1, tSeaUnits.size());
            placementLeft1 -= seaPlacement;
            Collection<Unit> toPlace = tSeaUnits.subList(0, seaPlacement);
            doPlace(seaPlaceAt1, toPlace, placeDelegate);
        }
        if (seaPlaceAt2 == seaPlaceAt1)
        	placementLeft2 = placementLeft1;
        if(!aSeaUnits.isEmpty())
        {
            int seaPlacement = Math.min(placementLeft2, aSeaUnits.size());
            placementLeft2 -= seaPlacement;
            Collection<Unit> toPlace = aSeaUnits.subList(0, seaPlacement);

            doPlace(seaPlaceAt2, toPlace, placeDelegate);
        }
        if (seaPlaceAt2 == seaPlaceAt1)
        	placementLeft1 = placementLeft2; //update for changes on the 2nd placement
        if (seaPlaceAt1 == placeAt)
        	placementLeft3 = placementLeft1;
        if (seaPlaceAt2 == placeAt)
        	placementLeft3 = placementLeft2;

        if(!landUnits.isEmpty())
        {
            int landPlaceCount = Math.min(placementLeft3, landUnits.size());
            placementLeft3 -= landPlaceCount;
            Collection<Unit> toPlace = landUnits.subList(0, landPlaceCount);

            doPlace(placeAt, toPlace, placeDelegate);
        }

    }

    private void doPlace(Territory where, Collection<Unit> toPlace, IAbstractPlaceDelegate del)
    {
        String message = del.placeUnits(new ArrayList<Unit>(toPlace), where);
        if(message != null)
        {
            s_logger.fine(message);
            s_logger.fine("Attempt was at:" + where + " with:" + toPlace);
        }
        pause();
    }

    /*
     *
     *
     * @see games.strategy.triplea.player.ITripleaPlayer#selectCasualties(java.lang.String,
     *      java.util.Collection, java.util.Map, int, java.lang.String,
     *      games.strategy.triplea.delegate.DiceRoll,
     *      games.strategy.engine.data.PlayerID, java.util.List)
     */

    public CasualtyDetails selectCasualties(Collection<Unit> selectFrom, Map<Unit, Collection<Unit>> dependents, int count, String message, DiceRoll dice, PlayerID hit, List<Unit> defaultCasualties, GUID battleID)
    {
        final GameData data = getPlayerBridge().getGameData();
        TransportTracker tracker = DelegateFinder.moveDelegate(data).getTransportTracker();

        List<Unit> rDamaged = new ArrayList<Unit>();
        List<Unit> rKilled = new ArrayList<Unit>();
        List<Unit> workUnits = new ArrayList<Unit>();
		int xCount = count; //how many the game is saying we should have

		for (Unit unitBB : selectFrom)
		{
			if (Matches.UnitIsTwoHit.match(unitBB))
			{
				if (unitBB.getHits()==0 && xCount > 0)
				{
					rDamaged.add(unitBB);
					xCount--;
				}
			}
		}
		if (xCount == 0)
		{
			return new CasualtyDetails(rKilled, rDamaged, false);
		}
		if (xCount >= selectFrom.size())
		{
			rKilled.addAll(selectFrom);
			CasualtyDetails m4 = new CasualtyDetails(rKilled, rDamaged, false);
			return m4;
		}

		if (xCount > 0)
		{
			for (Unit unitx : selectFrom)
			{
				if (Matches.UnitIsArtillerySupportable.match(unitx))
					workUnits.add(unitx);
			}
			for (Unit unitx : selectFrom)
			{
				if (Matches.UnitIsArtillery.match(unitx))
					workUnits.add(unitx);
			}
			for (Unit unitx : selectFrom)
			{
				if (Matches.UnitCanBlitz.match(unitx))
					workUnits.add(unitx);
			}
			for (Unit unitx : selectFrom) //empty transport
			{
				if (!Properties.getTransportCasualtiesRestricted(data) && Matches.UnitIsTransport.match(unitx) && !tracker.isTransporting(unitx) )
					workUnits.add(unitx);
			}
			for (Unit unitx : selectFrom)
			{
				if (Matches.UnitIsSub.match(unitx))
					workUnits.add(unitx);
			}
			for (Unit unitx : selectFrom)
			{
				if (Matches.UnitIsDestroyer.match(unitx))
					workUnits.add(unitx);
			}
			for (Unit unitx : selectFrom)
			{
				if (Matches.UnitIsStrategicBomber.match(unitx))
					workUnits.add(unitx);
			}
			for (Unit unitx : selectFrom)
			{
				if (Matches.UnitIsAir.match(unitx) && !Matches.UnitIsStrategicBomber.match(unitx))
					workUnits.add(unitx);
			}
			for (Unit unitx : selectFrom) //loaded transport
			{
				if (!Properties.getTransportCasualtiesRestricted(data) && Matches.UnitIsTransport.match(unitx) && tracker.isTransporting(unitx) )
					workUnits.add(unitx);
			}
			for (Unit unitx : selectFrom)
			{
				if (Matches.UnitIsCarrier.match(unitx))
					workUnits.add(unitx);
			}
			for (Unit unitx : selectFrom) //any other unit, but make sure trannys are last if necessary
			{
				if (Matches.UnitIsNotTransport.match(unitx) && !workUnits.contains(unitx) && !Matches.UnitIsTwoHit.match(unitx))
				    workUnits.add(unitx);
			}
			for (Unit unitx : selectFrom)
			{
				if (Matches.UnitIsTwoHit.match(unitx))
					workUnits.add(unitx);
			}

			//add anything not selected above
			Set<Unit> remainder =new HashSet<Unit>(selectFrom);
			remainder.removeAll(workUnits);
			workUnits.addAll(remainder);
		}
			/* Order:
				SEA:
				0) 1st hit battleship
				1) Empty Transport
				2) Submarine
				3) Destroyer
				4) Bomber
				5) Fighter
				6) Loaded Transport
				7) Carrier (Fighter is better than 1 Carrier...need to check score in battle) implement later...
				8) Battleship
				9) anything else
			*/

		for (int j=0; j < xCount; j++)
		{
			rKilled.add(workUnits.get(j));
		}

        CasualtyDetails m2 = new CasualtyDetails(rKilled, rDamaged, false);
        return m2;
    }

    /*
     * @see games.strategy.triplea.player.ITripleaPlayer#shouldBomberBomb(games.strategy.engine.data.Territory)
     */
    public boolean shouldBomberBomb(Territory territory)
    {
		//only if not needed in a battle
        return true;

    }

    /*
     * @see games.strategy.triplea.player.ITripleaPlayer#getNumberOfFightersToMoveToNewCarrier(java.util.Collection, games.strategy.engine.data.Territory)
     */
    public Collection<Unit> getNumberOfFightersToMoveToNewCarrier(Collection<Unit> fightersThatCanBeMoved, Territory from)
    {
        List<Unit> rVal = new ArrayList<Unit>();
        for(Unit fighter : fightersThatCanBeMoved)
            rVal.add(fighter);
        return rVal;
    }

    /*
     * @see games.strategy.triplea.player.ITripleaPlayer#selectTerritoryForAirToLand(java.util.Collection, java.lang.String)
     */
    public Territory selectTerritoryForAirToLand(Collection candidates)
    {
		//need to land in territory with infantry, especially if bomber
       return (Territory) candidates.iterator().next();
    }

    public boolean confirmMoveInFaceOfAA(Collection aaFiringTerritories)
    {
        return true;
    }

    /**
     * Select the territory to bombard with the bombarding capable unit (eg battleship)
     *
     * @param unit - the bombarding unit
     * @param unitTerritory - where the bombarding unit is
     * @param territories - territories where the unit can bombard
     * @param noneAvailable
     * @return the Territory to bombard in, null if the unit should not bombard
     */
    public Territory selectBombardingTerritory(Unit unit,Territory unitTerritory, Collection<Territory> territories, boolean noneAvailable )
    {
		if (noneAvailable || territories.size() == 0)
			return null;
		else
		{
			for (Territory t : territories)
				return t;
		}
		return null;
	}

    public Territory retreatQuery(GUID battleID, boolean submerge, Collection<Territory> possibleTerritories, String message)
    {
		//retreat anytime only air units are remaining
		//submerge anytime only subs against air units
		//don't understand how to use this routine
        final GameData data = getPlayerBridge().getGameData();
/*        TransportTracker tracker = DelegateFinder.moveDelegate(data).getTransportTracker();
        BattleTracker tracker = DelegateFinder.battleDelegate(data).getBattleTracker();
        Territory attackTerr = getBattleTerritory();
        Battle thisBattle = tracker.getPendingBattle(attackTerr, false);
        thisBattle.
*/       
        return null;
    }

    /* (non-Javadoc)
     * @see games.strategy.triplea.player.ITripleaPlayer#selectFixedDice(int, java.lang.String)
     */
    public int[] selectFixedDice(int numRolls, int hitAt, boolean hitOnlyIfEquals, String message)
    {
        int[] dice = new int[numRolls];
        for (int i=0; i<numRolls; i++)
        {
            dice[i] = (int)Math.ceil(Math.random() * 6);
        }
        return dice;
    }

    // some additional matches
    public static final Match<Unit> HasntMoved = new Match<Unit>()
    {
    	public boolean match(Unit o)
        {
            return TripleAUnit.get(o).getAlreadyMoved() == 0;
        }
    };


    public static final Match<Unit> Transporting = new Match<Unit>()
    {
    	public boolean match(Unit o)
        {
            return (TripleAUnit.get(o).getTransporting().size() > 0);
        }
    };

}
