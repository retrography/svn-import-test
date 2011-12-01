package games.strategy.triplea.attatchments;

import games.strategy.engine.data.Change;
import games.strategy.engine.data.ChangeFactory;
import games.strategy.engine.data.CompositeChange;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GameParseException;
import games.strategy.engine.data.PlayerID;
import games.strategy.engine.data.ProductionFrontier;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.RelationshipType;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.TechnologyFrontier;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.TerritoryEffect;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.delegate.IDelegate;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.Constants;
import games.strategy.triplea.Properties;
import games.strategy.triplea.delegate.DelegateFinder;
import games.strategy.triplea.delegate.EndRoundDelegate;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.TechAdvance;
import games.strategy.triplea.delegate.TechTracker;
import games.strategy.triplea.formatter.MyFormatter;
import games.strategy.triplea.player.ITripleaPlayer;
import games.strategy.triplea.ui.NotificationMessages;
import games.strategy.util.IntegerMap;
import games.strategy.util.Match;
import games.strategy.util.Tuple;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 
 * @author SquidDaddy and Veqryn [Mark Christopher Duncan]
 * 
 */
public class TriggerAttachment extends AbstractTriggerAttachment implements IConditions
{
	/**
	 * 
	 */
	private static final long serialVersionUID = -3327739180569606093L;
	
	private ProductionFrontier m_frontier = null;
	private List<String> m_productionRule = null;
	private final List<TechAdvance> m_tech = new ArrayList<TechAdvance>();
	private Map<String, Map<TechAdvance, Boolean>> m_availableTech = null;
	private Map<Territory, IntegerMap<UnitType>> m_placement = null;
	private IntegerMap<UnitType> m_purchase = null;
	private String m_resource = null;
	private int m_resourceCount = 0;
	private Map<UnitSupportAttachment, Boolean> m_support = null;
	private final List<String> m_relationshipChange = new ArrayList<String>(); // List of relationshipChanges that should be executed when this trigger hits.
	private String m_victory = null;
	
	// raw property changes below:
	private final List<UnitType> m_unitType = new ArrayList<UnitType>(); // really m_unitTypes, but we are not going to rename because it will break all existing maps
	private Tuple<String, String> m_unitAttachmentName = null; // covers UnitAttachment, UnitSupportAttachment
	private List<Tuple<String, String>> m_unitProperty = null;
	private final List<Territory> m_territories = new ArrayList<Territory>();
	private Tuple<String, String> m_territoryAttachmentName = null; // covers TerritoryAttachment, CanalAttachment
	private List<Tuple<String, String>> m_territoryProperty = null;
	private final List<PlayerID> m_players = new ArrayList<PlayerID>();
	private Tuple<String, String> m_playerAttachmentName = null; // covers PlayerAttachment, TriggerAttachment, RulesAttachment, TechAttachment
	private List<Tuple<String, String>> m_playerProperty = null;
	private final List<RelationshipType> m_relationshipTypes = new ArrayList<RelationshipType>();
	private Tuple<String, String> m_relationshipTypeAttachmentName = null; // covers RelationshipTypeAttachment
	private List<Tuple<String, String>> m_relationshipTypeProperty = null;
	private final List<TerritoryEffect> m_territoryEffects = new ArrayList<TerritoryEffect>();
	private Tuple<String, String> m_territoryEffectAttachmentName = null; // covers TerritoryEffectAttachment
	private List<Tuple<String, String>> m_territoryEffectProperty = null;
	
	public TriggerAttachment()
	{
	}
	
	/**
	 * This will collect all triggers for the desired players, based on a match provided,
	 * and then it will gather all the conditions necessary, then test all the conditions,
	 * and then it will fire all the conditions which are satisfied.
	 * 
	 * @param players
	 * @param triggerMatch
	 * @param aBridge
	 */
	public static void collectAndFireTriggers(final HashSet<PlayerID> players, final Match<TriggerAttachment> triggerMatch, final IDelegateBridge aBridge)
	{
		final GameData data = aBridge.getData();
		final HashSet<TriggerAttachment> toFirePossible = new HashSet<TriggerAttachment>();
		for (final PlayerID player : players)
		{
			toFirePossible.addAll(TriggerAttachment.getTriggers(player, data, triggerMatch));
		}
		if (toFirePossible.isEmpty())
			return;
		final HashSet<IConditions> allConditionsNeeded = RulesAttachment.getAllConditionsRecursive(new HashSet<IConditions>(toFirePossible), null);
		final HashMap<IConditions, Boolean> testedConditions = RulesAttachment.testAllConditions(allConditionsNeeded, data);
		final List<TriggerAttachment> toFireTestedAndSatisfied = Match.getMatches(toFirePossible, TriggerAttachment.isSatisfiedMatch(testedConditions, data));
		if (toFireTestedAndSatisfied.isEmpty())
			return;
		TriggerAttachment.fireTriggers(new HashSet<TriggerAttachment>(toFireTestedAndSatisfied), aBridge, null, null);
	}
	
	/**
	 * This will fire all triggers, and it will not test to see if they are satisfied or not first. Please use collectAndFireTriggers instead of using this directly.
	 * To see if they are satisfied, first create the list of triggers using Matches + AbstractTriggerAttachment.getTriggers.
	 * Then test the triggers using RulesAttachment.getAllConditionsRecursive, and RulesAttachment.testAllConditions
	 * 
	 * @param triggersToBeFired
	 * @param aBridge
	 * @param beforeOrAfter
	 * @param stepName
	 */
	private static void fireTriggers(final HashSet<TriggerAttachment> triggersToBeFired, final IDelegateBridge aBridge, final String beforeOrAfter, final String stepName)
	{
		// Order: Notifications, Attachment Property Changes (Player, Relationship, Territory, TerritoryEffect, Unit),
		// Relationship, AvailableTech, Tech, ProductionFrontier, ProductionEdit, Purchase, Support, UnitPlacement, Resource, Victory
		triggerNotifications(triggersToBeFired, aBridge, beforeOrAfter, stepName);
		
		triggerPlayerPropertyChange(triggersToBeFired, aBridge, beforeOrAfter, stepName);
		triggerRelationshipTypePropertyChange(triggersToBeFired, aBridge, beforeOrAfter, stepName);
		triggerTerritoryPropertyChange(triggersToBeFired, aBridge, beforeOrAfter, stepName);
		triggerTerritoryEffectPropertyChange(triggersToBeFired, aBridge, beforeOrAfter, stepName);
		triggerUnitPropertyChange(triggersToBeFired, aBridge, beforeOrAfter, stepName);
		
		triggerRelationshipChange(triggersToBeFired, aBridge, beforeOrAfter, stepName);
		triggerAvailableTechChange(triggersToBeFired, aBridge, beforeOrAfter, stepName);
		triggerTechChange(triggersToBeFired, aBridge, beforeOrAfter, stepName);
		triggerProductionChange(triggersToBeFired, aBridge, beforeOrAfter, stepName);
		triggerProductionFrontierEditChange(triggersToBeFired, aBridge, beforeOrAfter, stepName);
		
		triggerPurchase(triggersToBeFired, aBridge, beforeOrAfter, stepName);
		triggerSupportChange(triggersToBeFired, aBridge, beforeOrAfter, stepName);
		triggerUnitPlacement(triggersToBeFired, aBridge, beforeOrAfter, stepName);
		triggerResourceChange(triggersToBeFired, aBridge, beforeOrAfter, stepName);
		
		triggerVictory(triggersToBeFired, aBridge, beforeOrAfter, stepName);
	}
	
	public void setFrontier(final String s) throws GameParseException
	{
		final ProductionFrontier front = getData().getProductionFrontierList().getProductionFrontier(s);
		if (front == null)
			throw new GameParseException("Triggers: Could not find frontier. name:" + s);
		m_frontier = front;
	}
	
	public ProductionFrontier getFrontier()
	{
		return m_frontier;
	}
	
	/**
	 * Adds to, not sets. Anything that adds to instead of setting needs a clear function as well.
	 * 
	 * @param prop
	 * @throws GameParseException
	 */
	public void setProductionRule(final String prop) throws GameParseException
	{
		final String[] s = prop.split(":");
		if (s.length != 2)
			throw new GameParseException("Triggers: Invalid productionRule declaration: " + prop);
		if (m_productionRule == null)
			m_productionRule = new ArrayList<String>();
		if (getData().getProductionFrontierList().getProductionFrontier(s[0]) == null)
			throw new GameParseException("Triggers: Could not find frontier. name:" + s[0]);
		String rule = s[1];
		if (rule.startsWith("-"))
			rule = rule.replaceFirst("-", "");
		if (getData().getProductionRuleList().getProductionRule(rule) == null)
			throw new GameParseException("Triggers: Could not find production rule. name:" + rule);
		m_productionRule.add(prop);
	}
	
	public List<String> getProductionRule()
	{
		return m_productionRule;
	}
	
	public void clearProductionRule()
	{
		m_productionRule.clear();
	}
	
	public int getResourceCount()
	{
		return m_resourceCount;
	}
	
	public void setResourceCount(final String s)
	{
		m_resourceCount = getInt(s);
	}
	
	public String getVictory()
	{
		return m_victory;
	}
	
	public void setVictory(final String s)
	{
		m_victory = s;
	}
	
	/**
	 * Adds to, not sets. Anything that adds to instead of setting needs a clear function as well.
	 * 
	 * @param techs
	 * @throws GameParseException
	 */
	public void setTech(final String techs) throws GameParseException
	{
		for (final String subString : techs.split(":"))
		{
			TechAdvance ta = getData().getTechnologyFrontier().getAdvanceByProperty(subString);
			if (ta == null)
				ta = getData().getTechnologyFrontier().getAdvanceByName(subString);
			if (ta == null)
				throw new GameParseException("Triggers: Technology not found :" + subString);
			m_tech.add(ta);
		}
	}
	
	public List<TechAdvance> getTech()
	{
		return m_tech;
	}
	
	public void clearTech()
	{
		m_tech.clear();
	}
	
	/**
	 * Adds to, not sets. Anything that adds to instead of setting needs a clear function as well.
	 * 
	 * @param techs
	 * @throws GameParseException
	 */
	public void setAvailableTech(final String techs) throws GameParseException
	{
		final String[] s = techs.split(":");
		if (s.length < 2)
			throw new GameParseException("Triggers: Invalid tech availability: " + techs + " should be category:techs");
		final String cat = s[0];
		final Map<TechAdvance, Boolean> tlist = new LinkedHashMap<TechAdvance, Boolean>();
		for (int i = 1; i < s.length; i++)
		{
			boolean add = true;
			if (s[i].startsWith("-"))
			{
				add = false;
				s[i] = s[i].substring(1);
			}
			TechAdvance ta = getData().getTechnologyFrontier().getAdvanceByProperty(s[i]);
			if (ta == null)
				ta = getData().getTechnologyFrontier().getAdvanceByName(s[i]);
			if (ta == null)
				throw new GameParseException("Triggers: Technology not found :" + s[i]);
			tlist.put(ta, add);
		}
		if (m_availableTech == null)
			m_availableTech = new HashMap<String, Map<TechAdvance, Boolean>>();
		if (m_availableTech.containsKey(cat))
			tlist.putAll(m_availableTech.get(cat));
		m_availableTech.put(cat, tlist);
	}
	
	public Map<String, Map<TechAdvance, Boolean>> getAvailableTech()
	{
		return m_availableTech;
	}
	
	public void clearAvailableTech()
	{
		m_availableTech.clear();
	}
	
	/**
	 * Adds to, not sets. Anything that adds to instead of setting needs a clear function as well.
	 * 
	 * @param sup
	 * @throws GameParseException
	 */
	public void setSupport(final String sup) throws GameParseException
	{
		final String[] s = sup.split(":");
		for (int i = 0; i < s.length; i++)
		{
			boolean add = true;
			if (s[i].startsWith("-"))
			{
				add = false;
				s[i] = s[i].substring(1);
			}
			boolean found = false;
			for (final UnitSupportAttachment support : UnitSupportAttachment.get(getData()))
			{
				if (support.getName().equals(s[i]))
				{
					found = true;
					if (m_support == null)
						m_support = new LinkedHashMap<UnitSupportAttachment, Boolean>();
					m_support.put(support, add);
					break;
				}
			}
			if (!found)
				throw new GameParseException("Triggers: Could not find unitSupportAttachment. name:" + s[i]);
		}
	}
	
	public Map<UnitSupportAttachment, Boolean> getSupport()
	{
		return m_support;
	}
	
	public void clearSupport()
	{
		m_support.clear();
	}
	
	public String getResource()
	{
		return m_resource;
	}
	
	public void setResource(final String s) throws GameParseException
	{
		final Resource r = getData().getResourceList().getResource(s);
		if (r == null)
			throw new GameParseException("Triggers: Invalid resource: " + s);
		else
			m_resource = s;
	}
	
	/**
	 * Adds to, not sets. Anything that adds to instead of setting needs a clear function as well.
	 * 
	 * @param relChange
	 * @throws GameParseException
	 */
	public void setRelationshipChange(final String relChange) throws GameParseException
	{
		final String[] s = relChange.split(":");
		if (s.length != 4)
			throw new GameParseException("Triggers: Invalid relationshipChange declaration: " + relChange + " \n Use: player1:player2:oldRelation:newRelation\n");
		if (getData().getPlayerList().getPlayerID(s[0]) == null)
			throw new GameParseException("Triggers: Invalid relationshipChange declaration: " + relChange + " \n player: " + s[0] + " unknown in: " + getName());
		if (getData().getPlayerList().getPlayerID(s[1]) == null)
			throw new GameParseException("Triggers: Invalid relationshipChange declaration: " + relChange + " \n player: " + s[1] + " unknown in: " + getName());
		if (!(s[2].equals(Constants.RELATIONSHIP_CONDITION_ANY_NEUTRAL) || s[2].equals(Constants.RELATIONSHIP_CONDITION_ANY) || s[2].equals(Constants.RELATIONSHIP_CONDITION_ANY_ALLIED)
					|| s[2].equals(Constants.RELATIONSHIP_CONDITION_ANY_WAR) || Matches.isValidRelationshipName(getData()).match(s[2])))
			throw new GameParseException("Triggers: Invalid relationshipChange declaration: " + relChange + " \n relationshipType: " + s[2] + " unknown in: " + getName());
		if (Matches.isValidRelationshipName(getData()).invert().match(s[3]))
			throw new GameParseException("Triggers: Invalid relationshipChange declaration: " + relChange + " \n relationshipType: " + s[3] + " unknown in: " + getName());
		m_relationshipChange.add(relChange);
	}
	
	public List<String> getRelationshipChange()
	{
		return m_relationshipChange;
	}
	
	public void clearRelationshipChange()
	{
		m_relationshipChange.clear();
	}
	
	/**
	 * Adds to, not sets. Anything that adds to instead of setting needs a clear function as well.
	 * 
	 * @param names
	 * @throws GameParseException
	 */
	public void setUnitType(final String names) throws GameParseException
	{
		final String[] s = names.split(":");
		for (int i = 0; i < s.length; i++)
		{
			final UnitType type = getData().getUnitTypeList().getUnitType(s[i]);
			if (type == null)
				throw new GameParseException("Triggers: Could not find unitType. name:" + s[i]);
			m_unitType.add(type);
		}
	}
	
	public List<UnitType> getUnitType()
	{
		return m_unitType;
	}
	
	public void clearUnitType()
	{
		m_unitType.clear();
	}
	
	public void setUnitAttachmentName(final String name) throws GameParseException
	{
		final String[] s = name.split(":");
		if (s.length != 2)
			throw new GameParseException("Triggers: unitAttachmentName must have 2 entries, the type of attachment and the name of the attachment.");
		// covers UnitAttachment, UnitSupportAttachment
		if (!(s[1].equals("UnitAttachment") || s[1].equals("UnitSupportAttachment")))
			throw new GameParseException("Triggers: unitAttachmentName value must be UnitAttachment or UnitSupportAttachment");
		// TODO validate attachment exists?
		if (s[0].length() < 1)
			throw new GameParseException("Triggers: unitAttachmentName count must be a valid attachment name");
		if (s[1].equals("UnitAttachment") && !s[0].startsWith(Constants.UNIT_ATTACHMENT_NAME))
			throw new GameParseException("Triggers: attachment incorrectly named:" + s[0]);
		if (s[1].equals("UnitSupportAttachment") && !s[0].startsWith(Constants.SUPPORT_ATTACHMENT_PREFIX))
			throw new GameParseException("Triggers: attachment incorrectly named:" + s[0]);
		m_unitAttachmentName = new Tuple<String, String>(s[1], s[0]);
	}
	
	public Tuple<String, String> getUnitAttachmentName()
	{
		if (m_unitAttachmentName == null)
			return new Tuple<String, String>("UnitAttachment", Constants.UNIT_ATTACHMENT_NAME);
		return m_unitAttachmentName;
	}
	
	/**
	 * Adds to, not sets. Anything that adds to instead of setting needs a clear function as well.
	 * 
	 * @param prop
	 * @throws GameParseException
	 */
	public void setUnitProperty(final String prop) throws GameParseException
	{
		final String[] s = prop.split(":");
		if (m_unitProperty == null)
			m_unitProperty = new ArrayList<Tuple<String, String>>();
		final String property = s[s.length - 1]; // the last one is the property we are changing, while the rest is the string we are changing it to
		m_unitProperty.add(new Tuple<String, String>(property, getValueFromStringArrayForAllExceptLastSubstring(s)));
	}
	
	public List<Tuple<String, String>> getUnitProperty()
	{
		return m_unitProperty;
	}
	
	public void clearUnitProperty()
	{
		m_unitProperty.clear();
	}
	
	/**
	 * Adds to, not sets. Anything that adds to instead of setting needs a clear function as well.
	 * 
	 * @param names
	 * @throws GameParseException
	 */
	public void setTerritories(final String names) throws GameParseException
	{
		final String[] s = names.split(":");
		for (int i = 0; i < s.length; i++)
		{
			final Territory terr = getData().getMap().getTerritory(s[i]);
			if (terr == null)
				throw new GameParseException("Triggers: Could not find territory. name:" + s[i]);
			m_territories.add(terr);
		}
	}
	
	public List<Territory> getTerritories()
	{
		return m_territories;
	}
	
	public void clearTerritories()
	{
		m_territories.clear();
	}
	
	public void setTerritoryAttachmentName(final String name) throws GameParseException
	{
		final String[] s = name.split(":");
		if (s.length != 2)
			throw new GameParseException("Triggers: territoryAttachmentName must have 2 entries, the type of attachment and the name of the attachment.");
		// covers TerritoryAttachment, CanalAttachment
		if (!(s[1].equals("TerritoryAttachment") || s[1].equals("CanalAttachment")))
			throw new GameParseException("Triggers: territoryAttachmentName value must be TerritoryAttachment or CanalAttachment");
		// TODO validate attachment exists?
		if (s[0].length() < 1)
			throw new GameParseException("Triggers: territoryAttachmentName count must be a valid attachment name");
		if (s[1].equals("TerritoryAttachment") && !s[0].startsWith(Constants.TERRITORY_ATTACHMENT_NAME))
			throw new GameParseException("Triggers: attachment incorrectly named:" + s[0]);
		if (s[1].equals("CanalAttachment") && !s[0].startsWith(Constants.CANAL_ATTACHMENT_PREFIX))
			throw new GameParseException("Triggers: attachment incorrectly named:" + s[0]);
		m_territoryAttachmentName = new Tuple<String, String>(s[1], s[0]);
	}
	
	public Tuple<String, String> getTerritoryAttachmentName()
	{
		if (m_territoryAttachmentName == null)
			return new Tuple<String, String>("TerritoryAttachment", Constants.TERRITORY_ATTACHMENT_NAME);
		return m_territoryAttachmentName;
	}
	
	/**
	 * Adds to, not sets. Anything that adds to instead of setting needs a clear function as well.
	 * 
	 * @param prop
	 * @throws GameParseException
	 */
	public void setTerritoryProperty(final String prop) throws GameParseException
	{
		final String[] s = prop.split(":");
		if (m_territoryProperty == null)
			m_territoryProperty = new ArrayList<Tuple<String, String>>();
		final String property = s[s.length - 1]; // the last one is the property we are changing, while the rest is the string we are changing it to
		m_territoryProperty.add(new Tuple<String, String>(property, getValueFromStringArrayForAllExceptLastSubstring(s)));
	}
	
	public List<Tuple<String, String>> getTerritoryProperty()
	{
		return m_territoryProperty;
	}
	
	public void clearTerritoryProperty()
	{
		m_territoryProperty.clear();
	}
	
	/**
	 * Adds to, not sets. Anything that adds to instead of setting needs a clear function as well.
	 * 
	 * @param names
	 * @throws GameParseException
	 */
	public void setPlayers(final String names) throws GameParseException
	{
		final String[] s = names.split(":");
		for (int i = 0; i < s.length; i++)
		{
			final PlayerID player = getData().getPlayerList().getPlayerID(s[i]);
			if (player == null)
				throw new GameParseException("Triggers: Could not find player. name:" + s[i]);
			m_players.add(player);
		}
	}
	
	public List<PlayerID> getPlayers()
	{
		if (m_players.isEmpty())
			return Collections.singletonList((PlayerID) getAttatchedTo());
		else
			return m_players;
	}
	
	public void clearPlayers()
	{
		m_players.clear();
	}
	
	public void setPlayerAttachmentName(final String name) throws GameParseException
	{
		final String[] s = name.split(":");
		if (s.length != 2)
			throw new GameParseException("Triggers: playerAttachmentName must have 2 entries, the type of attachment and the name of the attachment.");
		// covers PlayerAttachment, TriggerAttachment, RulesAttachment, TechAttachment
		if (!(s[1].equals("PlayerAttachment") || s[1].equals("RulesAttachment") || s[1].equals("TriggerAttachment") || s[1].equals("TechAttachment") || s[1].equals("PoliticalActionAttachment")))
			throw new GameParseException("Triggers: playerAttachmentName value must be PlayerAttachment or RulesAttachment or TriggerAttachment or TechAttachment or PoliticalActionAttachment");
		// TODO validate attachment exists?
		if (s[0].length() < 1)
			throw new GameParseException("Triggers: playerAttachmentName count must be a valid attachment name");
		if (s[1].equals("PlayerAttachment") && !s[0].startsWith(Constants.PLAYER_ATTACHMENT_NAME))
			throw new GameParseException("Triggers: attachment incorrectly named:" + s[0]);
		if (s[1].equals("RulesAttachment")
					&& !(s[0].startsWith(Constants.RULES_ATTACHMENT_NAME) || s[0].startsWith(Constants.RULES_OBJECTIVE_PREFIX) || s[0].startsWith(Constants.RULES_CONDITION_PREFIX)))
			throw new GameParseException("Triggers: attachment incorrectly named:" + s[0]);
		if (s[1].equals("TriggerAttachment") && !s[0].startsWith(Constants.TRIGGER_ATTACHMENT_PREFIX))
			throw new GameParseException("Triggers: attachment incorrectly named:" + s[0]);
		if (s[1].equals("TechAttachment") && !s[0].startsWith(Constants.TECH_ATTACHMENT_NAME))
			throw new GameParseException("Triggers: attachment incorrectly named:" + s[0]);
		if (s[1].equals("PoliticalActionAttachment") && !s[0].startsWith(Constants.POLITICALACTION_ATTACHMENT_PREFIX))
			throw new GameParseException("Triggers: attachment incorrectly named:" + s[0]);
		m_playerAttachmentName = new Tuple<String, String>(s[1], s[0]);
	}
	
	public Tuple<String, String> getPlayerAttachmentName()
	{
		if (m_playerAttachmentName == null)
			return new Tuple<String, String>("PlayerAttachment", Constants.PLAYER_ATTACHMENT_NAME);
		return m_playerAttachmentName;
	}
	
	/**
	 * Adds to, not sets. Anything that adds to instead of setting needs a clear function as well.
	 * 
	 * @param prop
	 * @throws GameParseException
	 */
	public void setPlayerProperty(final String prop) throws GameParseException
	{
		final String[] s = prop.split(":");
		if (m_playerProperty == null)
			m_playerProperty = new ArrayList<Tuple<String, String>>();
		final String property = s[s.length - 1]; // the last one is the property we are changing, while the rest is the string we are changing it to
		m_playerProperty.add(new Tuple<String, String>(property, getValueFromStringArrayForAllExceptLastSubstring(s)));
	}
	
	public List<Tuple<String, String>> getPlayerProperty()
	{
		return m_playerProperty;
	}
	
	public void clearPlayerProperty()
	{
		m_playerProperty.clear();
	}
	
	/**
	 * Adds to, not sets. Anything that adds to instead of setting needs a clear function as well.
	 * 
	 * @param names
	 * @throws GameParseException
	 */
	public void setRelationshipTypes(final String names) throws GameParseException
	{
		final String[] s = names.split(":");
		for (int i = 0; i < s.length; i++)
		{
			final RelationshipType relation = getData().getRelationshipTypeList().getRelationshipType(s[i]);
			if (relation == null)
				throw new GameParseException("Triggers: Could not find relationshipType. name:" + s[i]);
			m_relationshipTypes.add(relation);
		}
	}
	
	public List<RelationshipType> getRelationshipTypes()
	{
		return m_relationshipTypes;
	}
	
	public void clearRelationshipTypes()
	{
		m_relationshipTypes.clear();
	}
	
	public void setRelationshipTypeAttachmentName(final String name) throws GameParseException
	{
		final String[] s = name.split(":");
		if (s.length != 2)
			throw new GameParseException("Triggers: relationshipTypeAttachmentName must have 2 entries, the type of attachment and the name of the attachment.");
		// covers RelationshipTypeAttachment
		if (!(s[1].equals("RelationshipTypeAttachment")))
			throw new GameParseException("Triggers: relationshipTypeAttachmentName value must be RelationshipTypeAttachment");
		// TODO validate attachment exists?
		if (s[0].length() < 1)
			throw new GameParseException("Triggers: relationshipTypeAttachmentName count must be a valid attachment name");
		if (s[1].equals("RelationshipTypeAttachment") && !s[0].startsWith(Constants.RELATIONSHIPTYPE_ATTACHMENT_NAME))
			throw new GameParseException("Triggers: attachment incorrectly named:" + s[0]);
		m_relationshipTypeAttachmentName = new Tuple<String, String>(s[1], s[0]);
	}
	
	public Tuple<String, String> getRelationshipTypeAttachmentName()
	{
		if (m_relationshipTypeAttachmentName == null)
			return new Tuple<String, String>("RelationshipTypeAttachment", Constants.RELATIONSHIPTYPE_ATTACHMENT_NAME);
		return m_relationshipTypeAttachmentName;
	}
	
	/**
	 * Adds to, not sets. Anything that adds to instead of setting needs a clear function as well.
	 * 
	 * @param prop
	 * @throws GameParseException
	 */
	public void setRelationshipTypeProperty(final String prop) throws GameParseException
	{
		final String[] s = prop.split(":");
		if (m_relationshipTypeProperty == null)
			m_relationshipTypeProperty = new ArrayList<Tuple<String, String>>();
		final String property = s[s.length - 1]; // the last one is the property we are changing, while the rest is the string we are changing it to
		m_relationshipTypeProperty.add(new Tuple<String, String>(property, getValueFromStringArrayForAllExceptLastSubstring(s)));
	}
	
	public List<Tuple<String, String>> getRelationshipTypeProperty()
	{
		return m_relationshipTypeProperty;
	}
	
	public void clearRelationshipTypeProperty()
	{
		m_relationshipTypeProperty.clear();
	}
	
	/**
	 * Adds to, not sets. Anything that adds to instead of setting needs a clear function as well.
	 * 
	 * @param names
	 * @throws GameParseException
	 */
	public void setTerritoryEffects(final String names) throws GameParseException
	{
		final String[] s = names.split(":");
		for (int i = 0; i < s.length; i++)
		{
			final TerritoryEffect effect = getData().getTerritoryEffectList().get(s[i]);
			if (effect == null)
				throw new GameParseException("Triggers: Could not find territoryEffect. name:" + s[i]);
			m_territoryEffects.add(effect);
		}
	}
	
	public List<TerritoryEffect> getTerritoryEffects()
	{
		return m_territoryEffects;
	}
	
	public void clearTerritoryEffects()
	{
		m_territoryEffects.clear();
	}
	
	public void setTerritoryEffectAttachmentName(final String name) throws GameParseException
	{
		final String[] s = name.split(":");
		if (s.length != 2)
			throw new GameParseException("Triggers: territoryEffectAttachmentName must have 2 entries, the type of attachment and the name of the attachment.");
		// covers TerritoryEffectAttachment
		if (!(s[1].equals("TerritoryEffectAttachment")))
			throw new GameParseException("Triggers: territoryEffectAttachmentName value must be TerritoryEffectAttachment");
		// TODO validate attachment exists?
		if (s[0].length() < 1)
			throw new GameParseException("Triggers: territoryEffectAttachmentName count must be a valid attachment name");
		if (s[1].equals("TerritoryEffectAttachment") && !s[0].startsWith(Constants.TERRITORYEFFECT_ATTACHMENT_NAME))
			throw new GameParseException("Triggers: attachment incorrectly named:" + s[0]);
		m_territoryEffectAttachmentName = new Tuple<String, String>(s[1], s[0]);
	}
	
	public Tuple<String, String> getTerritoryEffectAttachmentName()
	{
		if (m_territoryEffectAttachmentName == null)
			return new Tuple<String, String>("TerritoryEffectAttachment", Constants.TERRITORYEFFECT_ATTACHMENT_NAME);
		return m_territoryEffectAttachmentName;
	}
	
	/**
	 * Adds to, not sets. Anything that adds to instead of setting needs a clear function as well.
	 * 
	 * @param prop
	 * @throws GameParseException
	 */
	public void setTerritoryEffectProperty(final String prop) throws GameParseException
	{
		final String[] s = prop.split(":");
		if (m_territoryEffectProperty == null)
			m_territoryEffectProperty = new ArrayList<Tuple<String, String>>();
		final String property = s[s.length - 1]; // the last one is the property we are changing, while the rest is the string we are changing it to
		m_territoryEffectProperty.add(new Tuple<String, String>(property, getValueFromStringArrayForAllExceptLastSubstring(s)));
	}
	
	public List<Tuple<String, String>> getTerritoryEffectProperty()
	{
		return m_territoryEffectProperty;
	}
	
	public void clearTerritoryEffectProperty()
	{
		m_territoryEffectProperty.clear();
	}
	
	/**
	 * Fudging this, it really represents adding placements.
	 * Adds to, not sets. Anything that adds to instead of setting needs a clear function as well.
	 * 
	 * @param place
	 * @throws GameParseException
	 */
	public void setPlacement(final String place) throws GameParseException
	{
		final String[] s = place.split(":");
		int count = -1, i = 0;
		if (s.length < 1)
			throw new GameParseException("Triggers: Empty placement list");
		try
		{
			count = getInt(s[0]);
			i++;
		} catch (final Exception e)
		{
			count = 1;
		}
		if (s.length < 1 || s.length == 1 && count != -1)
			throw new GameParseException("Triggers: Empty placement list");
		final Territory territory = getData().getMap().getTerritory(s[i]);
		if (territory == null)
			throw new GameParseException("Triggers: Territory does not exist " + s[i]);
		else
		{
			i++;
			final IntegerMap<UnitType> map = new IntegerMap<UnitType>();
			for (; i < s.length; i++)
			{
				final UnitType type = getData().getUnitTypeList().getUnitType(s[i]);
				if (type == null)
					throw new GameParseException("Triggers: UnitType does not exist " + s[i]);
				else
					map.add(type, count);
			}
			if (m_placement == null)
				m_placement = new HashMap<Territory, IntegerMap<UnitType>>();
			if (m_placement.containsKey(territory))
				map.add(m_placement.get(territory));
			m_placement.put(territory, map);
		}
	}
	
	public Map<Territory, IntegerMap<UnitType>> getPlacement()
	{
		return m_placement;
	}
	
	public void clearPlacement()
	{
		m_placement.clear();
	}
	
	/**
	 * Adds to, not sets. Anything that adds to instead of setting needs a clear function as well.
	 * 
	 * @param place
	 * @throws GameParseException
	 */
	public void setPurchase(final String place) throws GameParseException
	{
		final String[] s = place.split(":");
		int count = -1, i = 0;
		if (s.length < 1)
			throw new GameParseException("Triggers: Empty purchase list");
		try
		{
			count = getInt(s[0]);
			i++;
		} catch (final Exception e)
		{
			count = 1;
		}
		if (s.length < 1 || s.length == 1 && count != -1)
			throw new GameParseException("Triggers: Empty purchase list");
		else
		{
			if (m_purchase == null)
				m_purchase = new IntegerMap<UnitType>();
			for (; i < s.length; i++)
			{
				final UnitType type = getData().getUnitTypeList().getUnitType(s[i]);
				if (type == null)
					throw new GameParseException("Triggers: UnitType does not exist " + s[i]);
				else
					m_purchase.add(type, count);
			}
		}
	}
	
	public IntegerMap<UnitType> getPurchase()
	{
		return m_purchase;
	}
	
	public void clearPurchase()
	{
		m_purchase.clear();
	}
	
	private static void placeUnits(final TriggerAttachment t, final Territory terr, final IntegerMap<UnitType> uMap, final PlayerID player, final GameData data, final IDelegateBridge aBridge)
	{
		// createUnits
		final List<Unit> units = new ArrayList<Unit>();
		;
		for (final UnitType u : uMap.keySet())
		{
			units.addAll(u.create(uMap.getInt(u), player));
		}
		final CompositeChange change = new CompositeChange();
		// mark no movement
		for (final Unit unit : units)
		{
			change.add(ChangeFactory.markNoMovementChange(unit));
		}
		// place units
		final Collection<Unit> factoryAndAA = Match.getMatches(units, Matches.UnitIsAAOrIsFactoryOrIsInfrastructure);
		change.add(DelegateFinder.battleDelegate(data).getOriginalOwnerTracker().addOriginalOwnerChange(factoryAndAA, player));
		final String transcriptText = MyFormatter.attachmentNameToText(t.getName()) + ": " + player.getName() + " has " + MyFormatter.unitsToTextNoOwner(units) + " placed in " + terr.getName();
		aBridge.getHistoryWriter().startEvent(transcriptText);
		aBridge.getHistoryWriter().setRenderingData(units);
		final Change place = ChangeFactory.addUnits(terr, units);
		change.add(place);
		/* No longer needed, as territory unitProduction is now set by default to equal the territory value. Therefore any time it is different from the default, the map maker set it, so we shouldn't screw with it.
		if(Match.someMatch(units, Matches.UnitIsFactoryOrCanProduceUnits) && !Match.someMatch(terr.getUnits().getUnits(), Matches.UnitIsFactoryOrCanProduceUnits) && games.strategy.triplea.Properties.getSBRAffectsUnitProduction(data))
		{
			// if no factories are there, make sure the territory has no damage (that unitProduction = production)
			TerritoryAttachment ta = TerritoryAttachment.get(terr);
			int prod = 0;
			if(ta != null)
				prod = ta.getProduction();
			
		    Change unitProd = ChangeFactory.changeUnitProduction(terr, prod);
		    change.add(unitProd);
		}*/
		aBridge.addChange(change);
		// handle adding to enemy territories
		/* creation of new battles is handled at the beginning of the battle delegate, in "setupUnitsInSameTerritoryBattles", not here.
		if (Matches.isTerritoryEnemyAndNotUnownedWaterOrImpassibleOrRestricted(player, data).match(terr))
			getBattleTracker(data).addBattle(new RouteScripted(terr), units, false, player, aBridge, null);*/
	}
	
	/* creation of new battles is handled at the beginning of the battle delegate, in "setupUnitsInSameTerritoryBattles", not here.
	public static void triggerMustFightBattle(PlayerID player1, PlayerID player2, IDelegateBridge aBridge)
	{
		GameData data = aBridge.getData();
		for (Territory terr : Match.getMatches(data.getMap().getTerritories(), Matches.territoryHasUnitsOwnedBy(player1)))
		{
			if (Matches.territoryHasEnemyUnits(player1, data).match(terr))
				DelegateFinder.battleDelegate(data).getBattleTracker().addBattle(new RouteScripted(terr), terr.getUnits().getMatches(Matches.unitIsOwnedBy(player1)), false, player1, aBridge, null);
		}
	}
	
	private static BattleTracker getBattleTracker(GameData data)
	{
		return DelegateFinder.battleDelegate(data).getBattleTracker();
	}*/

	//
	// And now for the actual triggers, as called throughout the engine.
	// Each trigger should be called exactly twice, once in BaseDelegate (for use with 'when'), and a second time as the default location for when 'when' is not used.
	// Should be void.
	//
	
	private static void triggerNotifications(final Set<TriggerAttachment> satisfiedTriggers, final IDelegateBridge aBridge, final String beforeOrAfter, final String stepName)
	{
		final Collection<TriggerAttachment> trigs = Match.getMatches(satisfiedTriggers, notificationMatch(beforeOrAfter, stepName));
		final Set<String> notifications = new HashSet<String>();
		for (final TriggerAttachment t : trigs)
		{
			t.use(aBridge);
			notifications.add(t.getNotification());
		}
		final Iterator<String> notificationMessages = notifications.iterator();
		while (notificationMessages.hasNext())
		{
			final String notificationMessageKey = notificationMessages.next();
			final String message = NotificationMessages.getInstance().getMessage(notificationMessageKey);
			aBridge.getHistoryWriter().startEvent("Notification to player " + aBridge.getPlayerID().getName() + ": " + message);
			((ITripleaPlayer) aBridge.getRemote(aBridge.getPlayerID())).reportMessage(("<html>" + message + "</html>"), "Notification");
		}
	}
	
	private static void triggerPlayerPropertyChange(final Set<TriggerAttachment> satisfiedTriggers, final IDelegateBridge aBridge, final String beforeOrAfter, final String stepName)
	{
		final Collection<TriggerAttachment> trigs = Match.getMatches(satisfiedTriggers, playerPropertyMatch(beforeOrAfter, stepName));
		final CompositeChange change = new CompositeChange();
		for (final TriggerAttachment t : trigs)
		{
			t.use(aBridge);
			for (final Tuple<String, String> property : t.getPlayerProperty())
			{
				for (final PlayerID aPlayer : t.getPlayers())
				{
					String newValue = property.getSecond();
					boolean clearFirst = false;
					// test if we are clearing the variable first, and if so, remove the leading "-clear-"
					if (newValue.length() > 0 && newValue.startsWith("-clear-"))
					{
						newValue = newValue.replaceFirst("-clear-", "");
						clearFirst = true;
					}
					// covers PlayerAttachment, TriggerAttachment, RulesAttachment, TechAttachment
					if (t.getPlayerAttachmentName().getFirst().equals("PlayerAttachment"))
					{
						final PlayerAttachment attachment = PlayerAttachment.get(aPlayer, t.getPlayerAttachmentName().getSecond());
						if (attachment.getRawProperty(property.getFirst()).equals(newValue))
							continue;
						if (clearFirst && newValue.length() < 1)
							change.add(ChangeFactory.attachmentPropertyClear(attachment, property.getFirst(), true));
						else
							change.add(ChangeFactory.attachmentPropertyChange(attachment, newValue, property.getFirst(), true, clearFirst));
						aBridge.getHistoryWriter().startEvent(
									MyFormatter.attachmentNameToText(t.getName()) + ": Setting " + property.getFirst() + (newValue.length() > 0 ? " to " + newValue : " cleared ") + " for "
												+ t.getPlayerAttachmentName().getSecond() + " attached to " + aPlayer.getName());
					}
					else if (t.getPlayerAttachmentName().getFirst().equals("RulesAttachment"))
					{
						final RulesAttachment attachment = RulesAttachment.get(aPlayer, t.getPlayerAttachmentName().getSecond());
						if (attachment.getRawProperty(property.getFirst()).equals(newValue))
							continue;
						if (clearFirst && newValue.length() < 1)
							change.add(ChangeFactory.attachmentPropertyClear(attachment, property.getFirst(), true));
						else
							change.add(ChangeFactory.attachmentPropertyChange(attachment, newValue, property.getFirst(), true, clearFirst));
						aBridge.getHistoryWriter().startEvent(
									MyFormatter.attachmentNameToText(t.getName()) + ": Setting " + property.getFirst() + (newValue.length() > 0 ? " to " + newValue : " cleared ") + " for "
												+ t.getPlayerAttachmentName().getSecond() + " attached to " + aPlayer.getName());
					}
					else if (t.getPlayerAttachmentName().getFirst().equals("TriggerAttachment"))
					{
						final AbstractTriggerAttachment attachment = AbstractTriggerAttachment.get(aPlayer, t.getPlayerAttachmentName().getSecond());
						if (attachment.getRawProperty(property.getFirst()).equals(newValue))
							continue;
						if (clearFirst && newValue.length() < 1)
							change.add(ChangeFactory.attachmentPropertyClear(attachment, property.getFirst(), true));
						else
							change.add(ChangeFactory.attachmentPropertyChange(attachment, newValue, property.getFirst(), true, clearFirst));
						aBridge.getHistoryWriter().startEvent(
									MyFormatter.attachmentNameToText(t.getName()) + ": Setting " + property.getFirst() + (newValue.length() > 0 ? " to " + newValue : " cleared ") + " for "
												+ t.getPlayerAttachmentName().getSecond() + " attached to " + aPlayer.getName());
					}
					else if (t.getPlayerAttachmentName().getFirst().equals("TechAttachment"))
					{
						final TechAttachment attachment = TechAttachment.get(aPlayer, t.getPlayerAttachmentName().getSecond());
						if (attachment.getRawProperty(property.getFirst()).equals(newValue))
							continue;
						if (clearFirst && newValue.length() < 1)
							change.add(ChangeFactory.attachmentPropertyClear(attachment, property.getFirst(), true));
						else
							change.add(ChangeFactory.attachmentPropertyChange(attachment, newValue, property.getFirst(), true, clearFirst));
						aBridge.getHistoryWriter().startEvent(
									MyFormatter.attachmentNameToText(t.getName()) + ": Setting " + property.getFirst() + (newValue.length() > 0 ? " to " + newValue : " cleared ") + " for "
												+ t.getPlayerAttachmentName().getSecond() + " attached to " + aPlayer.getName());
					}
					else if (t.getPlayerAttachmentName().getFirst().equals("PoliticalActionAttachment"))
					{
						final PoliticalActionAttachment attachment = PoliticalActionAttachment.get(aPlayer, t.getPlayerAttachmentName().getSecond());
						if (attachment.getRawProperty(property.getFirst()).equals(newValue))
							continue;
						if (clearFirst && newValue.length() < 1)
							change.add(ChangeFactory.attachmentPropertyClear(attachment, property.getFirst(), true));
						else
							change.add(ChangeFactory.attachmentPropertyChange(attachment, newValue, property.getFirst(), true, clearFirst));
						aBridge.getHistoryWriter().startEvent(
									MyFormatter.attachmentNameToText(t.getName()) + ": Setting " + property.getFirst() + (newValue.length() > 0 ? " to " + newValue : " cleared ") + " for "
												+ t.getPlayerAttachmentName().getSecond() + " attached to " + aPlayer.getName());
					}
					// TODO add other attachment changes here if they attach to a player
				}
			}
		}
		if (!change.isEmpty())
			aBridge.addChange(change);
	}
	
	private static void triggerRelationshipTypePropertyChange(final Set<TriggerAttachment> satisfiedTriggers, final IDelegateBridge aBridge, final String beforeOrAfter, final String stepName)
	{
		final Collection<TriggerAttachment> trigs = Match.getMatches(satisfiedTriggers, relationshipTypePropertyMatch(beforeOrAfter, stepName));
		final CompositeChange change = new CompositeChange();
		for (final TriggerAttachment t : trigs)
		{
			t.use(aBridge);
			for (final Tuple<String, String> property : t.getRelationshipTypeProperty())
			{
				for (final RelationshipType aRelationshipType : t.getRelationshipTypes())
				{
					String newValue = property.getSecond();
					boolean clearFirst = false;
					// test if we are clearing the variable first, and if so, remove the leading "-clear-"
					if (newValue.length() > 0 && newValue.startsWith("-clear-"))
					{
						newValue = newValue.replaceFirst("-clear-", "");
						clearFirst = true;
					}
					// covers RelationshipTypeAttachment
					if (t.getTerritoryAttachmentName().getFirst().equals("RelationshipTypeAttachment"))
					{
						final RelationshipTypeAttachment attachment = RelationshipTypeAttachment.get(aRelationshipType, t.getRelationshipTypeAttachmentName().getSecond());
						if (attachment.getRawProperty(property.getFirst()).equals(newValue))
							continue;
						if (clearFirst && newValue.length() < 1)
							change.add(ChangeFactory.attachmentPropertyClear(attachment, property.getFirst(), true));
						else
							change.add(ChangeFactory.attachmentPropertyChange(attachment, newValue, property.getFirst(), true, clearFirst));
						aBridge.getHistoryWriter().startEvent(
									MyFormatter.attachmentNameToText(t.getName()) + ": Setting " + property.getFirst() + (newValue.length() > 0 ? " to " + newValue : " cleared ") + " for "
												+ t.getRelationshipTypeAttachmentName().getSecond() + " attached to " + aRelationshipType.getName());
					}
					// TODO add other attachment changes here if they attach to a territory
				}
			}
		}
		if (!change.isEmpty())
			aBridge.addChange(change);
	}
	
	private static void triggerTerritoryPropertyChange(final Set<TriggerAttachment> satisfiedTriggers, final IDelegateBridge aBridge, final String beforeOrAfter, final String stepName)
	{
		final Collection<TriggerAttachment> trigs = Match.getMatches(satisfiedTriggers, territoryPropertyMatch(beforeOrAfter, stepName));
		final CompositeChange change = new CompositeChange();
		final HashSet<Territory> territoriesNeedingReDraw = new HashSet<Territory>();
		for (final TriggerAttachment t : trigs)
		{
			t.use(aBridge);
			for (final Tuple<String, String> property : t.getTerritoryProperty())
			{
				for (final Territory aTerritory : t.getTerritories())
				{
					territoriesNeedingReDraw.add(aTerritory);
					String newValue = property.getSecond();
					boolean clearFirst = false;
					// test if we are clearing the variable first, and if so, remove the leading "-clear-"
					if (newValue.length() > 0 && newValue.startsWith("-clear-"))
					{
						newValue = newValue.replaceFirst("-clear-", "");
						clearFirst = true;
					}
					// covers TerritoryAttachment, CanalAttachment
					if (t.getTerritoryAttachmentName().getFirst().equals("TerritoryAttachment"))
					{
						final TerritoryAttachment attachment = TerritoryAttachment.get(aTerritory, t.getTerritoryAttachmentName().getSecond());
						if (attachment == null)
							throw new IllegalStateException("Triggers: No territory attachment for:" + aTerritory.getName()); // water territories may not have an attachment, so this could be null
						if (attachment.getRawProperty(property.getFirst()).equals(newValue))
							continue;
						if (clearFirst && newValue.length() < 1)
							change.add(ChangeFactory.attachmentPropertyClear(attachment, property.getFirst(), true));
						else
							change.add(ChangeFactory.attachmentPropertyChange(attachment, newValue, property.getFirst(), true, clearFirst));
						aBridge.getHistoryWriter().startEvent(
									MyFormatter.attachmentNameToText(t.getName()) + ": Setting " + property.getFirst() + (newValue.length() > 0 ? " to " + newValue : " cleared ") + " for "
												+ t.getTerritoryAttachmentName().getSecond() + " attached to " + aTerritory.getName());
					}
					else if (t.getTerritoryAttachmentName().getFirst().equals("CanalAttachment"))
					{
						final CanalAttachment attachment = CanalAttachment.get(aTerritory, t.getTerritoryAttachmentName().getSecond());
						if (attachment.getRawProperty(property.getFirst()).equals(newValue))
							continue;
						if (clearFirst && newValue.length() < 1)
							change.add(ChangeFactory.attachmentPropertyClear(attachment, property.getFirst(), true));
						else
							change.add(ChangeFactory.attachmentPropertyChange(attachment, newValue, property.getFirst(), true, clearFirst));
						aBridge.getHistoryWriter().startEvent(
									MyFormatter.attachmentNameToText(t.getName()) + ": Setting " + property.getFirst() + (newValue.length() > 0 ? " to " + newValue : " cleared ") + " for "
												+ t.getTerritoryAttachmentName().getSecond() + " attached to " + aTerritory.getName());
					}
					// TODO add other attachment changes here if they attach to a territory
				}
			}
		}
		if (!change.isEmpty())
		{
			aBridge.addChange(change);
			for (final Territory aTerritory : territoriesNeedingReDraw)
			{
				aTerritory.notifyAttachmentChanged();
			}
		}
	}
	
	private static void triggerTerritoryEffectPropertyChange(final Set<TriggerAttachment> satisfiedTriggers, final IDelegateBridge aBridge, final String beforeOrAfter, final String stepName)
	{
		final Collection<TriggerAttachment> trigs = Match.getMatches(satisfiedTriggers, territoryEffectPropertyMatch(beforeOrAfter, stepName));
		final CompositeChange change = new CompositeChange();
		for (final TriggerAttachment t : trigs)
		{
			t.use(aBridge);
			for (final Tuple<String, String> property : t.getTerritoryEffectProperty())
			{
				for (final TerritoryEffect aTerritoryEffect : t.getTerritoryEffects())
				{
					String newValue = property.getSecond();
					boolean clearFirst = false;
					// test if we are clearing the variable first, and if so, remove the leading "-clear-"
					if (newValue.length() > 0 && newValue.startsWith("-clear-"))
					{
						newValue = newValue.replaceFirst("-clear-", "");
						clearFirst = true;
					}
					// covers TerritoryEffectAttachment
					if (t.getTerritoryEffectAttachmentName().getFirst().equals("TerritoryEffectAttachment"))
					{
						final TerritoryEffectAttachment attachment = TerritoryEffectAttachment.get(aTerritoryEffect, t.getTerritoryEffectAttachmentName().getSecond());
						if (attachment.getRawProperty(property.getFirst()).equals(newValue))
							continue;
						if (clearFirst && newValue.length() < 1)
							change.add(ChangeFactory.attachmentPropertyClear(attachment, property.getFirst(), true));
						else
							change.add(ChangeFactory.attachmentPropertyChange(attachment, newValue, property.getFirst(), true, clearFirst));
						aBridge.getHistoryWriter().startEvent(
									MyFormatter.attachmentNameToText(t.getName()) + ": Setting " + property.getFirst() + (newValue.length() > 0 ? " to " + newValue : " cleared ") + " for "
												+ t.getTerritoryEffectAttachmentName().getSecond() + " attached to " + aTerritoryEffect.getName());
					}
					// TODO add other attachment changes here if they attach to a territory
				}
			}
		}
		if (!change.isEmpty())
			aBridge.addChange(change);
	}
	
	private static void triggerUnitPropertyChange(final Set<TriggerAttachment> satisfiedTriggers, final IDelegateBridge aBridge, final String beforeOrAfter, final String stepName)
	{
		final Collection<TriggerAttachment> trigs = Match.getMatches(satisfiedTriggers, unitPropertyMatch(beforeOrAfter, stepName));
		final CompositeChange change = new CompositeChange();
		for (final TriggerAttachment t : trigs)
		{
			t.use(aBridge);
			for (final Tuple<String, String> property : t.getUnitProperty())
			{
				for (final UnitType aUnitType : t.getUnitType())
				{
					String newValue = property.getSecond();
					boolean clearFirst = false;
					// test if we are clearing the variable first, and if so, remove the leading "-clear-"
					if (newValue.length() > 0 && newValue.startsWith("-clear-"))
					{
						newValue = newValue.replaceFirst("-clear-", "");
						clearFirst = true;
					}
					// covers UnitAttachment, UnitSupportAttachment
					if (t.getUnitAttachmentName().getFirst().equals("UnitAttachment"))
					{
						final UnitAttachment attachment = UnitAttachment.get(aUnitType, t.getUnitAttachmentName().getSecond());
						if (attachment.getRawProperty(property.getFirst()).equals(newValue))
							continue;
						if (clearFirst && newValue.length() < 1)
							change.add(ChangeFactory.attachmentPropertyClear(attachment, property.getFirst(), true));
						else
							change.add(ChangeFactory.attachmentPropertyChange(attachment, newValue, property.getFirst(), true, clearFirst));
						aBridge.getHistoryWriter().startEvent(
									MyFormatter.attachmentNameToText(t.getName()) + ": Setting " + property.getFirst() + (newValue.length() > 0 ? " to " + newValue : " cleared ") + " for "
												+ t.getUnitAttachmentName().getSecond() + " attached to " + aUnitType.getName());
					}
					else if (t.getUnitAttachmentName().getFirst().equals("UnitSupportAttachment"))
					{
						final UnitSupportAttachment attachment = UnitSupportAttachment.get(aUnitType, t.getUnitAttachmentName().getSecond());
						if (attachment.getRawProperty(property.getFirst()).equals(newValue))
							continue;
						if (clearFirst && newValue.length() < 1)
							change.add(ChangeFactory.attachmentPropertyClear(attachment, property.getFirst(), true));
						else
							change.add(ChangeFactory.attachmentPropertyChange(attachment, newValue, property.getFirst(), true, clearFirst));
						aBridge.getHistoryWriter().startEvent(
									MyFormatter.attachmentNameToText(t.getName()) + ": Setting " + property.getFirst() + (newValue.length() > 0 ? " to " + newValue : " cleared ") + " for "
												+ t.getUnitAttachmentName().getSecond() + " attached to " + aUnitType.getName());
					}
					// TODO add other attachment changes here if they attach to a unitType
				}
			}
		}
		if (!change.isEmpty())
			aBridge.addChange(change);
	}
	
	private static void triggerRelationshipChange(final Set<TriggerAttachment> satisfiedTriggers, final IDelegateBridge aBridge, final String beforeOrAfter, final String stepName)
	{
		final GameData data = aBridge.getData();
		final Collection<TriggerAttachment> trigs = Match.getMatches(satisfiedTriggers, relationshipChangeMatch(beforeOrAfter, stepName));
		final CompositeChange change = new CompositeChange();
		for (final TriggerAttachment t : trigs)
		{
			t.use(aBridge);
			for (final String relationshipChange : t.getRelationshipChange())
			{
				final String[] s = relationshipChange.split(":");
				final PlayerID player1 = data.getPlayerList().getPlayerID(s[0]);
				final PlayerID player2 = data.getPlayerList().getPlayerID(s[1]);
				final RelationshipType currentRelation = data.getRelationshipTracker().getRelationshipType(player1, player2);
				if (s[2].equals(Constants.RELATIONSHIP_CONDITION_ANY) || (s[2].equals(Constants.RELATIONSHIP_CONDITION_ANY_NEUTRAL) && Matches.RelationshipTypeIsNeutral.match(currentRelation))
							|| (s[2].equals(Constants.RELATIONSHIP_CONDITION_ANY_ALLIED) && Matches.RelationshipTypeIsAllied.match(currentRelation))
							|| (s[2].equals(Constants.RELATIONSHIP_CONDITION_ANY_WAR) && Matches.RelationshipTypeIsAtWar.match(currentRelation))
							|| currentRelation.equals(data.getRelationshipTypeList().getRelationshipType(s[2])))
				{
					final RelationshipType triggerNewRelation = data.getRelationshipTypeList().getRelationshipType(s[3]);
					change.add(ChangeFactory.relationshipChange(player1, player2, currentRelation, triggerNewRelation));
					aBridge.getHistoryWriter().startEvent(
								MyFormatter.attachmentNameToText(t.getName()) + ": Changing Relationship for " + player1.getName() + " and " + player2.getName() + " from "
											+ currentRelation.getName() + " to " + triggerNewRelation.getName());
					/* creation of new battles is handled at the beginning of the battle delegate, in "setupUnitsInSameTerritoryBattles", not here.
					if (Matches.RelationshipTypeIsAtWar.match(triggerNewRelation))
						triggerMustFightBattle(player1, player2, aBridge);*/
				}
			}
		}
		if (!change.isEmpty())
			aBridge.addChange(change);
	}
	
	private static void triggerAvailableTechChange(final Set<TriggerAttachment> satisfiedTriggers, final IDelegateBridge aBridge, final String beforeOrAfter, final String stepName)
	{
		final Collection<TriggerAttachment> trigs = Match.getMatches(satisfiedTriggers, techAvailableMatch(beforeOrAfter, stepName));
		for (final TriggerAttachment t : trigs)
		{
			t.use(aBridge);
			for (final PlayerID aPlayer : t.getPlayers())
			{
				for (final String cat : t.getAvailableTech().keySet())
				{
					final TechnologyFrontier tf = aPlayer.getTechnologyFrontierList().getTechnologyFrontier(cat);
					if (tf == null)
						throw new IllegalStateException("Triggers: tech category doesn't exist:" + cat + " for player:" + aPlayer);
					for (final TechAdvance ta : t.getAvailableTech().get(cat).keySet())
					{
						if (t.getAvailableTech().get(cat).get(ta))
						{
							aBridge.getHistoryWriter().startEvent(MyFormatter.attachmentNameToText(t.getName()) + ": " + aPlayer.getName() + " gains access to " + ta);
							final Change change = ChangeFactory.addAvailableTech(tf, ta, aPlayer);
							aBridge.addChange(change);
						}
						else
						{
							aBridge.getHistoryWriter().startEvent(MyFormatter.attachmentNameToText(t.getName()) + ": " + aPlayer.getName() + " loses access to " + ta);
							final Change change = ChangeFactory.removeAvailableTech(tf, ta, aPlayer);
							aBridge.addChange(change);
						}
					}
				}
			}
		}
	}
	
	private static void triggerTechChange(final Set<TriggerAttachment> satisfiedTriggers, final IDelegateBridge aBridge, final String beforeOrAfter, final String stepName)
	{
		final GameData data = aBridge.getData();
		final Collection<TriggerAttachment> trigs = Match.getMatches(satisfiedTriggers, techMatch(beforeOrAfter, stepName));
		for (final TriggerAttachment t : trigs)
		{
			t.use(aBridge);
			for (final PlayerID aPlayer : t.getPlayers())
			{
				for (final TechAdvance ta : t.getTech())
				{
					if (ta.hasTech(TechAttachment.get(aPlayer)) || !TechAdvance.getTechAdvances(data, aPlayer).contains(ta))
						continue;
					aBridge.getHistoryWriter().startEvent(MyFormatter.attachmentNameToText(t.getName()) + ": " + aPlayer.getName() + " activates " + ta);
					TechTracker.addAdvance(aPlayer, aBridge, ta);
				}
			}
		}
	}
	
	private static void triggerProductionChange(final Set<TriggerAttachment> satisfiedTriggers, final IDelegateBridge aBridge, final String beforeOrAfter, final String stepName)
	{
		final Collection<TriggerAttachment> trigs = Match.getMatches(satisfiedTriggers, prodMatch(beforeOrAfter, stepName));
		final CompositeChange change = new CompositeChange();
		for (final TriggerAttachment t : trigs)
		{
			t.use(aBridge);
			for (final PlayerID aPlayer : t.getPlayers())
			{
				change.add(ChangeFactory.changeProductionFrontier(aPlayer, t.getFrontier()));
				aBridge.getHistoryWriter().startEvent(
							MyFormatter.attachmentNameToText(t.getName()) + ": " + aPlayer.getName() + " has their production frontier changed to: " + t.getFrontier().toString());
			}
		}
		if (!change.isEmpty())
			aBridge.addChange(change);
	}
	
	private static void triggerProductionFrontierEditChange(final Set<TriggerAttachment> satisfiedTriggers, final IDelegateBridge aBridge, final String beforeOrAfter, final String stepName)
	{
		final GameData data = aBridge.getData();
		final Collection<TriggerAttachment> trigs = Match.getMatches(satisfiedTriggers, prodFrontierEditMatch(beforeOrAfter, stepName));
		final CompositeChange change = new CompositeChange();
		for (final TriggerAttachment t : trigs)
		{
			t.use(aBridge);
			final Iterator<String> iter = t.getProductionRule().iterator();
			while (iter.hasNext())
			{
				boolean add = true;
				final String[] s = iter.next().split(":");
				final ProductionFrontier front = data.getProductionFrontierList().getProductionFrontier(s[0]);
				String rule = s[1];
				if (rule.startsWith("-"))
				{
					rule = rule.replaceFirst("-", "");
					add = false;
				}
				final ProductionRule pRule = data.getProductionRuleList().getProductionRule(rule);
				if (add)
				{
					if (!front.getRules().contains(pRule))
					{
						change.add(ChangeFactory.addProductionRule(pRule, front));
						aBridge.getHistoryWriter().startEvent(MyFormatter.attachmentNameToText(t.getName()) + ": " + pRule.getName() + " added to " + front.getName());
					}
				}
				else
				{
					if (front.getRules().contains(pRule))
					{
						change.add(ChangeFactory.removeProductionRule(pRule, front));
						aBridge.getHistoryWriter().startEvent(MyFormatter.attachmentNameToText(t.getName()) + ": " + pRule.getName() + " removed from " + front.getName());
					}
				}
			}
		}
		if (!change.isEmpty())
			aBridge.addChange(change); // TODO: we should sort the frontier list if we make changes to it...
	}
	
	private static void triggerPurchase(final Set<TriggerAttachment> satisfiedTriggers, final IDelegateBridge aBridge, final String beforeOrAfter, final String stepName)
	{
		final Collection<TriggerAttachment> trigs = Match.getMatches(satisfiedTriggers, purchaseMatch(beforeOrAfter, stepName));
		for (final TriggerAttachment t : trigs)
		{
			t.use(aBridge);
			for (final PlayerID aPlayer : t.getPlayers())
			{
				final List<Unit> units = new ArrayList<Unit>();
				for (final UnitType u : t.getPurchase().keySet())
				{
					units.addAll(u.create(t.getPurchase().getInt(u), aPlayer));
				}
				if (!units.isEmpty())
				{
					final String transcriptText = MyFormatter.attachmentNameToText(t.getName()) + ": " + MyFormatter.unitsToTextNoOwner(units) + " gained by " + aPlayer;
					aBridge.getHistoryWriter().startEvent(transcriptText);
					aBridge.getHistoryWriter().setRenderingData(units);
					final Change place = ChangeFactory.addUnits(aPlayer, units);
					aBridge.addChange(place);
				}
			}
		}
	}
	
	private static void triggerSupportChange(final Set<TriggerAttachment> satisfiedTriggers, final IDelegateBridge aBridge, final String beforeOrAfter, final String stepName)
	{
		final Collection<TriggerAttachment> trigs = Match.getMatches(satisfiedTriggers, supportMatch(beforeOrAfter, stepName));
		final CompositeChange change = new CompositeChange();
		for (final TriggerAttachment t : trigs)
		{
			t.use(aBridge);
			for (final PlayerID aPlayer : t.getPlayers())
			{
				for (final UnitSupportAttachment usa : t.getSupport().keySet())
				{
					final List<PlayerID> p = new ArrayList<PlayerID>(usa.getPlayers());
					if (p.contains(aPlayer))
					{
						if (!t.getSupport().get(usa))
						{
							p.remove(aPlayer);
							change.add(ChangeFactory.attachmentPropertyChange(usa, p, "players"));
							aBridge.getHistoryWriter().startEvent(MyFormatter.attachmentNameToText(t.getName()) + ": " + aPlayer.getName() + " is removed from " + usa.toString());
						}
					}
					else
					{
						if (t.getSupport().get(usa))
						{
							p.add(aPlayer);
							change.add(ChangeFactory.attachmentPropertyChange(usa, p, "players"));
							aBridge.getHistoryWriter().startEvent(MyFormatter.attachmentNameToText(t.getName()) + ": " + aPlayer.getName() + " is added to " + usa.toString());
						}
					}
				}
			}
		}
		if (!change.isEmpty())
			aBridge.addChange(change);
	}
	
	private static void triggerUnitPlacement(final Set<TriggerAttachment> satisfiedTriggers, final IDelegateBridge aBridge, final String beforeOrAfter, final String stepName)
	{
		final GameData data = aBridge.getData();
		final Collection<TriggerAttachment> trigs = Match.getMatches(satisfiedTriggers, placeMatch(beforeOrAfter, stepName));
		for (final TriggerAttachment t : trigs)
		{
			t.use(aBridge);
			for (final PlayerID aPlayer : t.getPlayers())
			{
				for (final Territory ter : t.getPlacement().keySet())
				{
					// aBridge.getHistoryWriter().startEvent(MyFormatter.attachmentNameToText(t.getName()) + ": " + aPlayer.getName() + " places " + t.getPlacement().get(ter).toString() + " in territory " + ter.getName());
					placeUnits(t, ter, t.getPlacement().get(ter), aPlayer, data, aBridge);
				}
			}
		}
	}
	
	private static void triggerResourceChange(final Set<TriggerAttachment> satisfiedTriggers, final IDelegateBridge aBridge, final String beforeOrAfter, final String stepName)
	{
		final GameData data = aBridge.getData();
		final Collection<TriggerAttachment> trigs = Match.getMatches(satisfiedTriggers, resourceMatch(beforeOrAfter, stepName));
		for (final TriggerAttachment t : trigs)
		{
			t.use(aBridge);
			for (final PlayerID aPlayer : t.getPlayers())
			{
				int toAdd = t.getResourceCount();
				if (t.getResource().equals(Constants.PUS))
					toAdd *= Properties.getPU_Multiplier(data);
				int total = aPlayer.getResources().getQuantity(t.getResource()) + toAdd;
				if (total < 0)
				{
					toAdd -= total;
					total = 0;
				}
				aBridge.addChange(ChangeFactory.changeResourcesChange(aPlayer, data.getResourceList().getResource(t.getResource()), toAdd));
				final String PUMessage = MyFormatter.attachmentNameToText(t.getName()) + ": " + aPlayer.getName() + " met a national objective for an additional " + t.getResourceCount() + " "
							+ t.getResource() + "; end with " + total + " " + t.getResource();
				aBridge.getHistoryWriter().startEvent(PUMessage);
			}
		}
	}
	
	private static void triggerVictory(final Set<TriggerAttachment> satisfiedTriggers, final IDelegateBridge aBridge, final String beforeOrAfter, final String stepName)
	{
		final GameData data = aBridge.getData();
		final Collection<TriggerAttachment> trigs = Match.getMatches(satisfiedTriggers, victoryMatch(beforeOrAfter, stepName));
		for (final TriggerAttachment t : trigs)
		{
			t.use(aBridge);
			if (t.getVictory() == null || t.getPlayers() == null)
				continue;
			final String victoryMessage = NotificationMessages.getInstance().getMessage(t.getVictory());
			try
			{
				aBridge.getHistoryWriter().startEvent("Players: " + MyFormatter.defaultNamedToString(t.getPlayers()) + " have just won the game, with this victory: " + victoryMessage);
				final IDelegate delegateEndRound = data.getDelegateList().getDelegate("endRound");
				((EndRoundDelegate) delegateEndRound).signalGameOver(("<html>" + victoryMessage + "</html>"), t.getPlayers(), aBridge);
			} catch (final Exception e)
			{
				e.printStackTrace();
			}
		}
	}
	
	//
	// All matches need to check for: t.getUses()!=0
	//
	// In addition, all triggers can be activated in only 1 of 2 places: default or when
	//
	// default = t.getWhen == null (this means when was not set, and so the trigger should activate in its default place, like before purchase phase for production frontier trigger changes
	//
	// when = t.getWhen != null (this means when was set, and so the trigger should not activate in its default place, and instead should activate before or after a specific stepName
	//
	// therefore all matches also need to check for: whenStepOrDefaultTriggerMatch(beforeOrAfter, stepName).match(t)
	//
	
	public static Match<TriggerAttachment> prodMatch(final String beforeOrAfter, final String stepName)
	{
		return new Match<TriggerAttachment>()
		{
			@Override
			public boolean match(final TriggerAttachment t)
			{
				return availableUses.match(t) && whenOrDefaultMatch(beforeOrAfter, stepName).match(t) && t.getFrontier() != null;
			}
		};
	}
	
	public static Match<TriggerAttachment> prodFrontierEditMatch(final String beforeOrAfter, final String stepName)
	{
		return new Match<TriggerAttachment>()
		{
			@Override
			public boolean match(final TriggerAttachment t)
			{
				return availableUses.match(t) && whenOrDefaultMatch(beforeOrAfter, stepName).match(t) && t.getProductionRule() != null && t.getProductionRule().size() > 0;
			}
		};
	}
	
	public static Match<TriggerAttachment> techMatch(final String beforeOrAfter, final String stepName)
	{
		return new Match<TriggerAttachment>()
		{
			@Override
			public boolean match(final TriggerAttachment t)
			{
				return availableUses.match(t) && whenOrDefaultMatch(beforeOrAfter, stepName).match(t) && !t.getTech().isEmpty();
			}
		};
	}
	
	public static Match<TriggerAttachment> techAvailableMatch(final String beforeOrAfter, final String stepName)
	{
		return new Match<TriggerAttachment>()
		{
			@Override
			public boolean match(final TriggerAttachment t)
			{
				return availableUses.match(t) && whenOrDefaultMatch(beforeOrAfter, stepName).match(t) && t.getAvailableTech() != null;
			}
		};
	}
	
	public static Match<TriggerAttachment> placeMatch(final String beforeOrAfter, final String stepName)
	{
		return new Match<TriggerAttachment>()
		{
			@Override
			public boolean match(final TriggerAttachment t)
			{
				return availableUses.match(t) && whenOrDefaultMatch(beforeOrAfter, stepName).match(t) && t.getPlacement() != null;
			}
		};
	}
	
	public static Match<TriggerAttachment> purchaseMatch(final String beforeOrAfter, final String stepName)
	{
		return new Match<TriggerAttachment>()
		{
			@Override
			public boolean match(final TriggerAttachment t)
			{
				return availableUses.match(t) && whenOrDefaultMatch(beforeOrAfter, stepName).match(t) && t.getPurchase() != null;
			}
		};
	}
	
	public static Match<TriggerAttachment> resourceMatch(final String beforeOrAfter, final String stepName)
	{
		return new Match<TriggerAttachment>()
		{
			@Override
			public boolean match(final TriggerAttachment t)
			{
				return availableUses.match(t) && whenOrDefaultMatch(beforeOrAfter, stepName).match(t) && t.getResource() != null && t.getResourceCount() != 0;
			}
		};
	}
	
	public static Match<TriggerAttachment> supportMatch(final String beforeOrAfter, final String stepName)
	{
		return new Match<TriggerAttachment>()
		{
			@Override
			public boolean match(final TriggerAttachment t)
			{
				return availableUses.match(t) && whenOrDefaultMatch(beforeOrAfter, stepName).match(t) && t.getSupport() != null;
			}
		};
	}
	
	public static Match<TriggerAttachment> unitPropertyMatch(final String beforeOrAfter, final String stepName)
	{
		return new Match<TriggerAttachment>()
		{
			@Override
			public boolean match(final TriggerAttachment t)
			{
				return availableUses.match(t) && whenOrDefaultMatch(beforeOrAfter, stepName).match(t) && !t.getUnitType().isEmpty() && t.getUnitProperty() != null;
			}
		};
	}
	
	public static Match<TriggerAttachment> territoryPropertyMatch(final String beforeOrAfter, final String stepName)
	{
		return new Match<TriggerAttachment>()
		{
			@Override
			public boolean match(final TriggerAttachment t)
			{
				return availableUses.match(t) && whenOrDefaultMatch(beforeOrAfter, stepName).match(t) && !t.getTerritories().isEmpty() && t.getTerritoryProperty() != null;
			}
		};
	}
	
	public static Match<TriggerAttachment> playerPropertyMatch(final String beforeOrAfter, final String stepName)
	{
		return new Match<TriggerAttachment>()
		{
			@Override
			public boolean match(final TriggerAttachment t)
			{
				return availableUses.match(t) && whenOrDefaultMatch(beforeOrAfter, stepName).match(t) && t.getPlayerProperty() != null;
			}
		};
	}
	
	public static Match<TriggerAttachment> relationshipTypePropertyMatch(final String beforeOrAfter, final String stepName)
	{
		return new Match<TriggerAttachment>()
		{
			@Override
			public boolean match(final TriggerAttachment t)
			{
				return availableUses.match(t) && whenOrDefaultMatch(beforeOrAfter, stepName).match(t) && !t.getRelationshipTypes().isEmpty() && t.getRelationshipTypeProperty() != null;
			}
		};
	}
	
	public static Match<TriggerAttachment> territoryEffectPropertyMatch(final String beforeOrAfter, final String stepName)
	{
		return new Match<TriggerAttachment>()
		{
			@Override
			public boolean match(final TriggerAttachment t)
			{
				return availableUses.match(t) && whenOrDefaultMatch(beforeOrAfter, stepName).match(t) && !t.getTerritoryEffects().isEmpty() && t.getTerritoryEffectProperty() != null;
			}
		};
	}
	
	public static Match<TriggerAttachment> relationshipChangeMatch(final String beforeOrAfter, final String stepName)
	{
		return new Match<TriggerAttachment>()
		{
			@Override
			public boolean match(final TriggerAttachment t)
			{
				return availableUses.match(t) && whenOrDefaultMatch(beforeOrAfter, stepName).match(t) && !t.getRelationshipChange().isEmpty();
			}
		};
	}
	
	public static Match<TriggerAttachment> victoryMatch(final String beforeOrAfter, final String stepName)
	{
		return new Match<TriggerAttachment>()
		{
			@Override
			public boolean match(final TriggerAttachment t)
			{
				return availableUses.match(t) && whenOrDefaultMatch(beforeOrAfter, stepName).match(t) && t.getVictory() != null && t.getVictory().length() > 0;
			}
		};
	}
	
	// Super methods. These need to be here because otherwise the engine's property util can not access the methods using getClass().getDeclaredMethods()
	// Do not remove
	// Do not edit
	
	@Deprecated
	@Override
	public void setTrigger(final String conditions) throws GameParseException
	{
		super.setTrigger(conditions);
	}
	
	@Deprecated
	@Override
	public List<RulesAttachment> getTrigger()
	{
		return super.getTrigger();
	}
	
	@Deprecated
	@Override
	public void clearTrigger()
	{
		super.clearTrigger();
	}
	
	@Override
	public void setConditions(final String conditions) throws GameParseException
	{
		super.setConditions(conditions);
	}
	
	@Override
	public List<RulesAttachment> getConditions()
	{
		return super.getConditions();
	}
	
	@Override
	public void clearConditions()
	{
		super.clearConditions();
	}
	
	@Override
	public void setUses(final String s)
	{
		super.setUses(s);
	}
	
	@Override
	public void setUses(final Integer u)
	{
		super.setUses(u);
	}
	
	@Override
	public void setUses(final int u)
	{
		super.setUses(u);
	}
	
	@Override
	public void setUsedThisRound(final String s)
	{
		super.setUsedThisRound(s);
	}
	
	@Override
	public void setUsedThisRound(final boolean usedThisRound)
	{
		super.setUsedThisRound(usedThisRound);
	}
	
	@Override
	public void setUsedThisRound(final Boolean usedThisRound)
	{
		super.setUsedThisRound(usedThisRound);
	}
	
	@Override
	public boolean getUsedThisRound()
	{
		return super.getUsedThisRound();
	}
	
	@Override
	public int getUses()
	{
		return super.getUses();
	}
	
	@Override
	public boolean getInvert()
	{
		return super.getInvert();
	}
	
	@Override
	public void setInvert(final String s)
	{
		super.setInvert(s);
	}
	
	@Override
	public void setWhen(final String when) throws GameParseException
	{
		super.setWhen(when);
	}
	
	@Override
	public Tuple<String, String> getWhen()
	{
		return super.getWhen();
	}
	
	@Override
	public void setNotification(final String sNotification)
	{
		super.setNotification(sNotification);
	}
	
	@Override
	public String getNotification()
	{
		return super.getNotification();
	}
	
	@Override
	public String getConditionType()
	{
		return super.getConditionType();
	}
	
	@Override
	public void setConditionType(final String s) throws GameParseException
	{
		super.setConditionType(s);
	}
}
