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

/*
 * TechAdvance.java
 * 
 * Created on November 25, 2001, 4:22 PM
 */

package games.strategy.triplea.delegate;

import games.strategy.engine.data.*;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.Constants;

import java.util.*;
import java.util.logging.*;
import java.util.logging.Logger;

/**
 * @author Sean Bridges
 * @version 1.0
 *  
 */
public abstract class TechAdvance implements java.io.Serializable
{
    private static List<TechAdvance> s_3rdEditionAdvances;
    private static List<TechAdvance> s_4thEditionAdvances;

    public static final TechAdvance JET_POWER = new JetPowerAdvance();
    public static final TechAdvance SUPER_SUBS = new SuperSubsAdvance();
    public static final TechAdvance LONG_RANGE_AIRCRAFT = new LongRangeAircraftAdvance();
    public static final TechAdvance ROCKETS = new RocketsAdvance();
    public static final TechAdvance INDUSTRIAL_TECHNOLOGY = new IndustrialTechnologyAdvance();
    public static final TechAdvance HEAVY_BOMBER = new HeavyBomberAdvance();
    public static final TechAdvance DESTROYER_BOMBARD = new DestroyerBombardTechAdvance();

    public static List<TechAdvance> getTechAdvances(GameData data)
    {
        boolean fourthEdition = data.getProperties().get(Constants.FOURTH_EDITION, false);
        
        if(fourthEdition)
            return s_4thEditionAdvances;
        else
            return s_3rdEditionAdvances;
    }

    //initialize the advances, note s_advances is made unmodifiable
    static
    {
        s_3rdEditionAdvances = new ArrayList<TechAdvance>();

        s_3rdEditionAdvances.add(JET_POWER);
        s_3rdEditionAdvances.add(SUPER_SUBS);
        s_3rdEditionAdvances.add(LONG_RANGE_AIRCRAFT);
        s_3rdEditionAdvances.add(ROCKETS);
        s_3rdEditionAdvances.add(INDUSTRIAL_TECHNOLOGY);
        s_3rdEditionAdvances.add(HEAVY_BOMBER);

        s_3rdEditionAdvances = Collections.unmodifiableList(s_3rdEditionAdvances);
        

        s_4thEditionAdvances = new ArrayList<TechAdvance>();

        s_4thEditionAdvances.add(JET_POWER);
        s_4thEditionAdvances.add(SUPER_SUBS);
        s_4thEditionAdvances.add(LONG_RANGE_AIRCRAFT);
        s_4thEditionAdvances.add(ROCKETS);
        s_4thEditionAdvances.add(DESTROYER_BOMBARD);
        s_4thEditionAdvances.add(HEAVY_BOMBER);

        s_4thEditionAdvances = Collections.unmodifiableList(s_4thEditionAdvances);

        
        
    }

    public abstract String getName();
    public abstract String getProperty();
    public abstract void perform(PlayerID id, IDelegateBridge bridge, GameData data);

    public boolean equals(Object o)
    {
        if (!(o instanceof TechAdvance))
            return false;

        TechAdvance ta = (TechAdvance) o;

        if (ta.getName() == null || getName() == null)
            return false;

        return getName().equals(ta.getName());
    }

    public int hashCode()
    {
        if (getName() == null)
            return super.hashCode();

        return getName().hashCode();
    }

    public String toString()
    {
        return getName();
    }
}



class SuperSubsAdvance extends TechAdvance
{
    public String getName()
    {
        return "Super subs";
    }

    public String getProperty()
    {
        return "superSub";
    }

    public void perform(PlayerID id, IDelegateBridge bridge, GameData data)
    {
    }
}



class HeavyBomberAdvance extends TechAdvance
{
    public String getName()
    {
        return "Heavy Bomber";
    }

    public String getProperty()
    {
        return "heavyBomber";
    }

    public void perform(PlayerID id, IDelegateBridge bridge, GameData data)
    {
    }

}



class IndustrialTechnologyAdvance extends TechAdvance
{
    public String getName()
    {
        return "Industrial Technology";
    }

    public String getProperty()
    {
        return "industrialTechnology";
    }

    public void perform(PlayerID id, IDelegateBridge bridge, GameData data)
    {
        ProductionFrontier current = id.getProductionFrontier();
        //they already have it
        if(current.getName().endsWith("IndustrialTechnology"))
            return;
        
        String industrialTechName = current.getName() + "IndustrialTechnology";
        
        ProductionFrontier advancedTech = data.getProductionFrontierList().getProductionFrontier(industrialTechName);
        
        //it doesnt exist, dont crash
        if(advancedTech == null)
        {
            Logger.getLogger(TechAdvance.class.getName()).log(Level.WARNING, "No tech named:" + industrialTechName + " not adding tech");
            return;
        }
        
        Change prodChange = ChangeFactory.changeProductionFrontier(id, advancedTech);
        bridge.addChange(prodChange);
    }
}



class JetPowerAdvance extends TechAdvance
{
    public String getName()
    {
        return "Jet Power";
    }

    public String getProperty()
    {
        return "jetPower";
    }

    public void perform(PlayerID id, IDelegateBridge bridge, GameData data)
    {
    }

}



class RocketsAdvance extends TechAdvance
{
    public String getName()
    {
        return "Rockets Advance";
    }

    public String getProperty()
    {
        return "rocket";
    }

    public void perform(PlayerID id, IDelegateBridge bridge, GameData data)
    {
    }

}

class DestroyerBombardTechAdvance extends TechAdvance
{
    public String getName()
    {
        return "Destroyer Bombard";
    }

    public String getProperty()
    {
        return "destroyerBombard";
    }

    public void perform(PlayerID id, IDelegateBridge bridge, GameData data)
    {
    }
}



class LongRangeAircraftAdvance extends TechAdvance
{
    public String getName()
    {
        return "Long Range Aircraft";
    }

    public String getProperty()
    {
        return "longRangeAir";
    }

    public void perform(PlayerID id, IDelegateBridge bridge, GameData data)
    {
    }
}