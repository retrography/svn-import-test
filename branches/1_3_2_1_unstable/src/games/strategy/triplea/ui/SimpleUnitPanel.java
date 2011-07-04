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


package games.strategy.triplea.ui;

import games.strategy.engine.data.*;
import games.strategy.triplea.attatchments.UnitTypeComparator;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.util.UnitCategory;
import games.strategy.util.IntegerMap;

import java.util.*;

import javax.swing.*;

/**
 *
 * A Simple panel that displays a list of units.
 *
 */

public class SimpleUnitPanel extends JPanel
{
  private final UIContext m_uiContext;  
    
  public SimpleUnitPanel(UIContext uiContext)
  {
    m_uiContext = uiContext;  
    setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
  }

  /**
   *
   * @param units a HashMap in the form ProductionRule -> number of units
   * assumes that each production rule has 1 result, which is simple the number of units
   */
  public void setUnitsFromProductionRuleMap(IntegerMap<ProductionRule> units, PlayerID player, GameData data)
  {
    removeAll();


    TreeSet<ProductionRule> productionRules = new TreeSet<ProductionRule>(productionRuleComparator);
    productionRules.addAll(units.keySet());
    Iterator<ProductionRule> iter = productionRules.iterator();
    while (iter.hasNext())
    {
      ProductionRule productionRule = iter.next();

      int quantity = units.getInt(productionRule);

      UnitType unit = (UnitType) productionRule.getResults().keySet().
        iterator().next();

      addUnits(player, data, quantity, unit, false, false);

    }
  }
  /**
  *
  * @param units a HashMap in the form RepairRule -> number of units
  * assumes that each repair rule has 1 result, which is simply the number of units
  */
  public void setUnitsFromRepairRuleMap(HashMap<Unit, IntegerMap<RepairRule>> units, PlayerID player, GameData data)
  {
    removeAll();
    
    Set<Unit> entries = units.keySet();
    Iterator<Unit> iter = entries.iterator();
    while (iter.hasNext())
    {
    	Unit unit = (Unit) iter.next();
        IntegerMap<RepairRule> rules = units.get(unit);

        TreeSet<RepairRule> repairRules = new TreeSet<RepairRule>(repairRuleComparator);
        repairRules.addAll(rules.keySet());
        Iterator<RepairRule> ruleIter = repairRules.iterator();
        while (ruleIter.hasNext())
        {
            RepairRule repairRule = ruleIter.next();
            int quantity = rules.getInt(repairRule);
            if (games.strategy.triplea.Properties.getSBRAffectsUnitProduction(data))
            {
            	addUnits(player, data, quantity, unit.getType(), true, false);
            }
            else //if (games.strategy.triplea.Properties.getDamageFromBombingDoneToUnitsInsteadOfTerritories(data))
            {
            	//check to see if the repair rule matches the damaged unit
            	if (unit.getType().equals(((UnitType) repairRule.getResults().keySet().iterator().next())))
            		addUnits(player, data, quantity, unit.getType(), Matches.UnitHasSomeUnitDamage().match(unit), Matches.UnitIsDisabled().match(unit));
            }
        }
    }
  }
  /**
   *
   * @param categories a collection of UnitCategories
   */
  public void setUnitsFromCategories(Collection categories, GameData data)
  {
    removeAll();

    Iterator iter = categories.iterator();
    while (iter.hasNext())
    {
      UnitCategory category = (UnitCategory) iter.next();
      //TODO Kev determine if we need to identify if the unit is hit/disabled
      addUnits(category.getOwner(), data, category.getUnits().size(), category.getType(), category.getDamaged(), category.getDisabled());
    }
  }

  private void addUnits(PlayerID player, GameData data, int quantity, UnitType unit, boolean damaged, boolean disabled)
  {
      //TODO Kev determine if we need to identify if the unit is hit/disabled
    JLabel label = new JLabel();
    label.setText(" x " + quantity);
    label.setIcon(m_uiContext.getUnitImageFactory().getIcon(unit, player,
        data, damaged, disabled));
    add(label);
  }

  Comparator<ProductionRule> productionRuleComparator = new Comparator<ProductionRule>()
  {
      UnitTypeComparator utc = new UnitTypeComparator();

      public int compare(ProductionRule o1, ProductionRule o2)
      {
          UnitType u1 = (UnitType)  o1.getResults().keySet().iterator().next();
          UnitType u2 = (UnitType)  o2.getResults().keySet().iterator().next();
          return utc.compare(u1, u2);
      }
  };

  Comparator<RepairRule> repairRuleComparator = new Comparator<RepairRule>()
  {
      UnitTypeComparator utc = new UnitTypeComparator();

      public int compare(RepairRule o1, RepairRule o2)
      {
          UnitType u1 = (UnitType)  o1.getResults().keySet().iterator().next();
          UnitType u2 = (UnitType)  o2.getResults().keySet().iterator().next();
          return utc.compare(u1, u2);
      }
  };
  
}