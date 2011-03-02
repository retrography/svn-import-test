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

package games.strategy.triplea.Dynamix_AI.Group;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.PlayerID;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.Constants;
import games.strategy.triplea.Dynamix_AI.DUtils;
import games.strategy.triplea.Dynamix_AI.Dynamix_AI;
import games.strategy.triplea.delegate.remote.IPurchaseDelegate;
import games.strategy.util.IntegerMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 *
 * @author Stephen
 */
public class PurchaseGroup
{
    private Collection<Unit> m_units = new ArrayList<Unit>();
    private IPurchaseDelegate m_purchaser = null;
    private GameData m_data = null;
    private PlayerID m_player = null;
    private IntegerMap<ProductionRule> m_generatedRules = new IntegerMap<ProductionRule>();
    private List<Unit> m_generatedSampleUnits = new ArrayList<Unit>();
    private int m_totalPurchasePrice = 0;
    public PurchaseGroup(Unit unit, IPurchaseDelegate purchaser, GameData data, PlayerID player)
    {
        m_units = Collections.singleton(unit);
        m_purchaser = purchaser;
        m_data = data;
        m_player = player;
        GenerateProductionRulesAndSampleUnits();
    }
    public PurchaseGroup(Collection<Unit> units, IPurchaseDelegate purchaser, GameData data, PlayerID player)
    {
        m_units = units;
        m_purchaser = purchaser;
        m_data = data;
        m_player = player;
        GenerateProductionRulesAndSampleUnits();
    }
    private void GenerateProductionRulesAndSampleUnits()
    {
        List<ProductionRule> rules = m_player.getProductionFrontier().getRules();
        int index = 0;
        m_generatedRules.clear();
        m_generatedSampleUnits.clear();
        m_totalPurchasePrice = 0;

        int totalUnitRulesCosts = 0;
        for (Unit unit : m_units)
        {
            for (ProductionRule rule : rules)
            {
                if (rule != null && rule.getResults() != null && rule.getResults().keySet() != null && rule.getResults().keySet().toArray() != null && rule.getResults().keySet().toArray().length > 0 && rule.getResults().keySet().toArray()[0] != null && rule.getResults().keySet().toArray()[0] instanceof UnitType && unit != null && unit.getUnitType() != null && ((UnitType) rule.getResults().keySet().toArray()[0]) == unit.getUnitType())
                {
                    int cost = rule.getCosts().getInt(m_data.getResourceList().getResource(Constants.PUS));
                    totalUnitRulesCosts += cost;
                }
            }
        }
        int timesUnitsCanBeBought = (m_maxPurchaseCost / totalUnitRulesCosts);
        int timesEachUnitShouldBeBought = Math.min(timesUnitsCanBeBought, m_maxPurchaseCount / m_units.size());
        timesEachUnitShouldBeBought = Math.max(timesEachUnitShouldBeBought, 1); //Buy at least one
        for (Unit unit : m_units)
        {
            for (ProductionRule rule : rules)
            {
                if (rule != null && rule.getResults() != null && rule.getResults().keySet() != null && rule.getResults().keySet().toArray() != null && rule.getResults().keySet().toArray().length > 0 && rule.getResults().keySet().toArray()[0] != null && rule.getResults().keySet().toArray()[0] instanceof UnitType && unit != null && unit.getUnitType() != null && ((UnitType) rule.getResults().keySet().toArray()[0]) == unit.getUnitType())
                {
                    int cost = rule.getCosts().getInt(m_data.getResourceList().getResource(Constants.PUS));
                    m_generatedRules.add(rule, timesEachUnitShouldBeBought);
                    m_totalPurchasePrice += cost * timesEachUnitShouldBeBought;
                    for (int i = 0; i < timesEachUnitShouldBeBought; i++)
                    {
                        m_generatedSampleUnits.add(unit.getType().create(m_player));
                    }
                    index++;
                    break;
                }
            }
        }
    }
    public int GetCost()
    {
        return m_totalPurchasePrice;
    }
    private int m_maxPurchaseCost = Integer.MAX_VALUE;
    private int m_maxPurchaseCount = 1;
    public void ApplyMaxValues(int maxPurchaseCost, int maxPurchaseCount)
    {
        if (maxPurchaseCost != m_maxPurchaseCost || maxPurchaseCount != m_maxPurchaseCount)
        {
            m_maxPurchaseCost = maxPurchaseCost;
            m_maxPurchaseCount = maxPurchaseCount;
            GenerateProductionRulesAndSampleUnits();
        }
    }
    public int Purchase()
    {
        Dynamix_AI.Pause();
        m_purchaser.purchase(m_generatedRules);
        DUtils.Log_Finest("      Purchase made. Units: {0}", GetSampleUnits());
        return m_totalPurchasePrice;
    }
    public List<Unit> GetSampleUnits()
    {
        return m_generatedSampleUnits;
    }
}