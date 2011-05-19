package games.strategy.triplea.delegate;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GameParseException;
import games.strategy.engine.data.GameParser;
import games.strategy.engine.data.ITestDelegateBridge;
import games.strategy.engine.data.PlayerID;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.TestDelegateBridge;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.properties.BooleanProperty;
import games.strategy.engine.data.properties.IEditableProperty;
import games.strategy.engine.display.IDisplay;
import games.strategy.engine.framework.GameRunner;
import games.strategy.engine.random.ScriptedRandomSource;
import games.strategy.triplea.Constants;
import games.strategy.triplea.delegate.Die.DieType;
import games.strategy.triplea.ui.display.DummyDisplay;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;

import junit.framework.TestCase;

import org.xml.sax.SAXException;

public class DiceRollTest extends TestCase
{
    
    private GameData m_data;

    @Override
    protected void setUp() throws Exception
    {
        String gameName = "revised.xml";
        loadGame(gameName);
    }

    private ITestDelegateBridge getDelegateBridge(PlayerID player)
    {
        ITestDelegateBridge bridge1 = new TestDelegateBridge(m_data, player, (IDisplay) new DummyDisplay());
        TestTripleADelegateBridge bridge2 = new TestTripleADelegateBridge(bridge1, m_data);
        return bridge2;
    }
    
    private void loadGame(String gameName) throws FileNotFoundException, GameParseException, SAXException, IOException
    {
        File gameRoot  = GameRunner.getRootFolder();
        File gamesFolder = new File(gameRoot, "games");
        File gameFile = new File(gamesFolder, gameName);
        
        if(!gameFile.exists())
            throw new IllegalStateException("game does not exist");
        
        InputStream input = new BufferedInputStream(new FileInputStream(gameFile));
        
        try
        {
            m_data = (new GameParser()).parse(input);
        }
        finally
        {
            input.close();    
        }
    }

    @Override
    protected void tearDown() throws Exception
    {
        m_data = null;
    }

    
    
    public void testSimple()
    {
        Territory westRussia = m_data.getMap().getTerritory("West Russia");
        MockBattle battle = new MockBattle(westRussia);
        PlayerID russians = m_data.getPlayerList().getPlayerID("Russians");
        
        ITestDelegateBridge bridge = getDelegateBridge(russians);
     
        
        UnitType infantryType = m_data.getUnitTypeList().getUnitType("infantry");
        List<Unit> infantry = infantryType.create(1, russians);
        
        //infantry defends and hits at 1 (0 based)
        bridge.setRandomSource(new ScriptedRandomSource(new int[] {1}));
        DiceRoll roll = DiceRoll.rollDice( infantry, true, russians, bridge, m_data, battle, "");
        assertEquals(1, roll.getHits());
        
        //infantry does not hit at 2 (0 based)
        bridge.setRandomSource(new ScriptedRandomSource(new int[] {2}));
        DiceRoll roll2 = DiceRoll.rollDice( infantry, true, russians, bridge, m_data, battle, "");
        assertEquals(0, roll2.getHits());
        
        
        //infantry attacks and hits at 0 (0 based)
        bridge.setRandomSource(new ScriptedRandomSource(new int[] {0}));
        DiceRoll roll3 = DiceRoll.rollDice( infantry, false, russians, bridge, m_data, battle, "");
        assertEquals(1, roll3.getHits());
        
        //infantry attack does not hit at 1 (0 based)
        bridge.setRandomSource(new ScriptedRandomSource(new int[] {1}));
        DiceRoll roll4 = DiceRoll.rollDice( infantry, false, russians, bridge, m_data, battle, "");
        assertEquals(0, roll4.getHits());
        
    }
    
    public void testSimpleLowLuck()
    {
        makeGameLowLuck();
        
        Territory westRussia = m_data.getMap().getTerritory("West Russia");
        MockBattle battle = new MockBattle(westRussia);
        PlayerID russians = m_data.getPlayerList().getPlayerID("Russians");
        
        ITestDelegateBridge bridge = getDelegateBridge(russians);
     
        
        UnitType infantryType = m_data.getUnitTypeList().getUnitType("infantry");
        List<Unit> infantry = infantryType.create(1, russians);
        
        //infantry defends and hits at 1 (0 based)
        bridge.setRandomSource(new ScriptedRandomSource(new int[] {1}));
        DiceRoll roll = DiceRoll.rollDice( infantry, true, russians, bridge, m_data, battle, "");
        assertEquals(1, roll.getHits());
        
        //infantry does not hit at 2 (0 based)
        bridge.setRandomSource(new ScriptedRandomSource(new int[] {2}));
        DiceRoll roll2 = DiceRoll.rollDice( infantry, true, russians, bridge, m_data, battle, "");
        assertEquals(0, roll2.getHits());
        
        
        //infantry attacks and hits at 0 (0 based)
        bridge.setRandomSource(new ScriptedRandomSource(new int[] {0}));
        DiceRoll roll3 = DiceRoll.rollDice( infantry, false, russians, bridge, m_data, battle, "");
        assertEquals(1, roll3.getHits());
        
        //infantry attack does not hit at 1 (0 based)
        bridge.setRandomSource(new ScriptedRandomSource(new int[] {1}));
        DiceRoll roll4 = DiceRoll.rollDice( infantry, false, russians, bridge, m_data, battle, "");
        assertEquals(0, roll4.getHits());
    }
    
    
    
    public void testArtillerySupport()
    {
        Territory westRussia = m_data.getMap().getTerritory("West Russia");
        MockBattle battle = new MockBattle(westRussia);
        PlayerID russians = m_data.getPlayerList().getPlayerID("Russians");
        
        ITestDelegateBridge bridge = getDelegateBridge(russians);
     
        
        UnitType infantryType = m_data.getUnitTypeList().getUnitType("infantry");
        List<Unit> units = infantryType.create(1, russians);
        
        UnitType artillery = m_data.getUnitTypeList().getUnitType("artillery");
        units.addAll(artillery.create(1, russians));
        
        //artileery supported infantry and art attack at 1 (0 based)
        bridge.setRandomSource(new ScriptedRandomSource(new int[] {1,1}));
        DiceRoll roll = DiceRoll.rollDice( units, false, russians, bridge, m_data, battle, "");
        assertEquals(2, roll.getHits());
    }
    
    public void testLowLuck()
    {
        makeGameLowLuck();
        
        Territory westRussia = m_data.getMap().getTerritory("West Russia");
        MockBattle battle = new MockBattle(westRussia);
        PlayerID russians = m_data.getPlayerList().getPlayerID("Russians");
        
        ITestDelegateBridge bridge = getDelegateBridge(russians);
     
        
        UnitType infantryType = m_data.getUnitTypeList().getUnitType("infantry");
        List<Unit> units = infantryType.create(3, russians);
        
       
        //3 infantry on defense should produce exactly one hit, without rolling the dice
        bridge.setRandomSource(new ScriptedRandomSource(new int[] {ScriptedRandomSource.ERROR}));
        
        DiceRoll roll = DiceRoll.rollDice( units, true, russians, bridge, m_data, battle, "");
        assertEquals(1, roll.getHits());
        
    }

    public void testSerialize() throws Exception
    {
        for(int i =0; i < 254; i++)
        {
            for(int j =0; j < 254; j++)
            {
                
                Die hit = new Die(i,j,DieType.MISS);
                assertEquals(hit, Die.getFromWriteValue(hit.getCompressedValue()));
                
                
                Die notHit = new Die(i,j, DieType.HIT);
                assertEquals(notHit, Die.getFromWriteValue(notHit.getCompressedValue()));
                
                Die ignored = new Die(i,j, DieType.IGNORED);
                assertEquals(ignored, Die.getFromWriteValue(ignored.getCompressedValue()));

                

            }
        }
        
    }
    
    private void makeGameLowLuck()
    {
        for(IEditableProperty property : m_data.getProperties().getEditableProperties())
        {
            if(property.getName().equals(Constants.LOW_LUCK))
            {
                 ((BooleanProperty)  property).setValue(true);
            }
        }
    }
    

    public void testMarineAttackPlus1() throws Exception 
    {
        loadGame("iron_blitz.xml");
        
        Territory algeria = m_data.getMap().getTerritory("Algeria");
        PlayerID americans = m_data.getPlayerList().getPlayerID("Americans");
        
        UnitType marine = m_data.getUnitTypeList().getUnitType("marine");
        List<Unit> attackers = marine.create(1, americans);
        
        ITestDelegateBridge bridge = getDelegateBridge(americans);
        bridge.setRandomSource(new ScriptedRandomSource(new int[] {1}));
        
        MockBattle battle = new MockBattle(algeria);
        battle.setAmphibiousLandAttackers(attackers);
        battle.setIsAmphibious(true);
        
        
        DiceRoll roll=  DiceRoll.rollDice(attackers, false, americans,bridge,m_data, battle, "");
        assertEquals(1, roll.getHits());        
    }

    public void testMarineAttacNormalIfNotAmphibious() throws Exception 
    {
        loadGame("iron_blitz.xml");
        
        Territory algeria = m_data.getMap().getTerritory("Algeria");
        PlayerID americans = m_data.getPlayerList().getPlayerID("Americans");
        
        UnitType marine = m_data.getUnitTypeList().getUnitType("marine");
        List<Unit> attackers = marine.create(1, americans);
        
        ITestDelegateBridge bridge = getDelegateBridge(americans);
        bridge.setRandomSource(new ScriptedRandomSource(new int[] {1}));
        
        MockBattle battle = new MockBattle(algeria);
        battle.setAmphibiousLandAttackers(Collections.<Unit>emptyList());
        battle.setIsAmphibious(true);
                
        
        DiceRoll roll=  DiceRoll.rollDice(attackers, false, americans,bridge,m_data, battle, "");
        assertEquals(0, roll.getHits());        
    }

    
    public void testAA()
    {
        Territory westRussia = m_data.getMap().getTerritory("West Russia");
        
        PlayerID russians = m_data.getPlayerList().getPlayerID("Russians");
        
        ITestDelegateBridge bridge = getDelegateBridge(russians);
     
        
       
        //aa hits at 0 (0 based)
        bridge.setRandomSource(new ScriptedRandomSource(new int[] {0}));
        
        DiceRoll hit = DiceRoll.rollAA(1, bridge, westRussia, m_data);
        assertEquals(hit.getHits(), 1);
        

        //aa missses at 1 (0 based)
        bridge.setRandomSource(new ScriptedRandomSource(new int[] {1}));
        
        DiceRoll miss = DiceRoll.rollAA(1, bridge, westRussia, m_data);
        assertEquals(miss.getHits(), 0);
        
    }

    
    
    public void testAALowLuck()
    {
        
        makeGameLowLuck();
        Territory westRussia = m_data.getMap().getTerritory("West Russia");
        
        PlayerID russians = m_data.getPlayerList().getPlayerID("Russians");
        
        ITestDelegateBridge bridge = getDelegateBridge(russians);
     
        
       
        //aa hits at 0 (0 based)
        bridge.setRandomSource(new ScriptedRandomSource(new int[] {0}));
        
        DiceRoll hit = DiceRoll.rollAA(1, bridge, westRussia, m_data);
        assertEquals(hit.getHits(), 1);
        

        //aa missses at 1 (0 based)
        bridge.setRandomSource(new ScriptedRandomSource(new int[] {1}));
        
        DiceRoll miss = DiceRoll.rollAA(1, bridge, westRussia, m_data);
        assertEquals(miss.getHits(), 0);
        
        //6 bombers, 1 should hit, and nothing should be rolled
        bridge.setRandomSource(new ScriptedRandomSource(new int[] {ScriptedRandomSource.ERROR}));
        
        DiceRoll hitNoRoll = DiceRoll.rollAA(6, bridge, westRussia, m_data);
        assertEquals(hitNoRoll.getHits(),1 );
        
        
    }
    
    public void testHeavyBombers()
    {
        PlayerID british = m_data.getPlayerList().getPlayerID("British");
        
        ITestDelegateBridge testDelegateBridge = getDelegateBridge(british);
        TechTracker.addAdvance(british, m_data, testDelegateBridge, TechAdvance.HEAVY_BOMBER);
        
        List<Unit> bombers = m_data.getMap().getTerritory("United Kingdom").getUnits().getMatches(Matches.UnitIsStrategicBomber);
        
        testDelegateBridge.setRandomSource(new ScriptedRandomSource(new int[] {2,3} ));
        
        DiceRoll dice = DiceRoll.rollDice(bombers, false, british, testDelegateBridge, m_data, new MockBattle(m_data.getMap().getTerritory("Germany")), "");
        
        assertEquals(Die.DieType.HIT, dice.getRolls(4).get(0).getType() );
        assertEquals(Die.DieType.HIT, dice.getRolls(4).get(1).getType() );
    }
    
    
    public void testHeavyBombersDefend()
    {
        PlayerID british = m_data.getPlayerList().getPlayerID("British");
        
        ITestDelegateBridge testDelegateBridge = getDelegateBridge(british);
        TechTracker.addAdvance(british, m_data, testDelegateBridge, TechAdvance.HEAVY_BOMBER);
        
        List<Unit> bombers = m_data.getMap().getTerritory("United Kingdom").getUnits().getMatches(Matches.UnitIsStrategicBomber);
        
        testDelegateBridge.setRandomSource(new ScriptedRandomSource(new int[] {0,1} ));
        
        DiceRoll dice = DiceRoll.rollDice(bombers, true, british, testDelegateBridge, m_data, new MockBattle(m_data.getMap().getTerritory("Germany")), "");
        
        assertEquals(1, dice.getRolls(1).size());
        assertEquals(Die.DieType.HIT, dice.getRolls(1).get(0).getType() );
        
    }
    
    public void testLHTRBomberDefend()
    {
        PlayerID british = m_data.getPlayerList().getPlayerID("British");
        
        m_data.getProperties().set(Constants.LHTR_HEAVY_BOMBERS, true );
        ITestDelegateBridge testDelegateBridge = getDelegateBridge(british);
        
        
        List<Unit> bombers = m_data.getMap().getTerritory("United Kingdom").getUnits().getMatches(Matches.UnitIsStrategicBomber);
        
        testDelegateBridge.setRandomSource(new ScriptedRandomSource(new int[] {0,1} ));
        
        DiceRoll dice = DiceRoll.rollDice(bombers, true, british, testDelegateBridge, m_data, new MockBattle(m_data.getMap().getTerritory("Germany")), "");
        
        assertEquals(1, dice.getRolls(1).size());
        assertEquals(Die.DieType.HIT, dice.getRolls(1).get(0).getType() );
        
    }
    
    
    
    public void testHeavyBombersLHTR()
    {
        m_data.getProperties().set(Constants.LHTR_HEAVY_BOMBERS, Boolean.TRUE);
        
        PlayerID british = m_data.getPlayerList().getPlayerID("British");
        
        ITestDelegateBridge testDelegateBridge = getDelegateBridge(british);
        TechTracker.addAdvance(british, m_data, testDelegateBridge, TechAdvance.HEAVY_BOMBER);
        
        List<Unit> bombers = m_data.getMap().getTerritory("United Kingdom").getUnits().getMatches(Matches.UnitIsStrategicBomber);
        
        testDelegateBridge.setRandomSource(new ScriptedRandomSource(new int[] {2,3} ));
        
        DiceRoll dice = DiceRoll.rollDice(bombers, false, british, testDelegateBridge, m_data, new MockBattle(m_data.getMap().getTerritory("Germany")), "");
        
        assertEquals(Die.DieType.HIT, dice.getRolls(4).get(0).getType() );
        assertEquals(Die.DieType.IGNORED, dice.getRolls(4).get(1).getType() );
        assertEquals(1, dice.getHits());
    }

    
    public void testHeavyBombersLHTR2()
    {
        m_data.getProperties().set(Constants.LHTR_HEAVY_BOMBERS, Boolean.TRUE);
        
        PlayerID british = m_data.getPlayerList().getPlayerID("British");
        
        ITestDelegateBridge testDelegateBridge = getDelegateBridge(british);
        TechTracker.addAdvance(british, m_data, testDelegateBridge, TechAdvance.HEAVY_BOMBER);
        
        List<Unit> bombers = m_data.getMap().getTerritory("United Kingdom").getUnits().getMatches(Matches.UnitIsStrategicBomber);
        
        testDelegateBridge.setRandomSource(new ScriptedRandomSource(new int[] {3,2} ));
        
        DiceRoll dice = DiceRoll.rollDice(bombers, false, british, testDelegateBridge, m_data, new MockBattle(m_data.getMap().getTerritory("Germany")), "");
        
        assertEquals(Die.DieType.IGNORED, dice.getRolls(4).get(0).getType() );
        assertEquals(Die.DieType.HIT, dice.getRolls(4).get(1).getType() );
        assertEquals(1, dice.getHits());
    }
    
    public void testHeavyBombersDefendLHTR()
    {
        m_data.getProperties().set(Constants.LHTR_HEAVY_BOMBERS, Boolean.TRUE);
        
        PlayerID british = m_data.getPlayerList().getPlayerID("British");
        
        ITestDelegateBridge testDelegateBridge = getDelegateBridge(british);
        TechTracker.addAdvance(british, m_data, testDelegateBridge, TechAdvance.HEAVY_BOMBER);
        
        List<Unit> bombers = m_data.getMap().getTerritory("United Kingdom").getUnits().getMatches(Matches.UnitIsStrategicBomber);
        
        testDelegateBridge.setRandomSource(new ScriptedRandomSource(new int[] {0,1} ));
        
        DiceRoll dice = DiceRoll.rollDice(bombers, true, british, testDelegateBridge, m_data, new MockBattle(m_data.getMap().getTerritory("Germany")), "");
        
        assertEquals(2, dice.getRolls(1).size());
        assertEquals(1, dice.getHits());
        assertEquals(Die.DieType.HIT, dice.getRolls(1).get(0).getType() );
        assertEquals(Die.DieType.IGNORED, dice.getRolls(1).get(1).getType() );
        
    }

    public void testDiceRollCount()
    {
        
        PlayerID british = m_data.getPlayerList().getPlayerID("British");
        Unit bombers =  m_data.getMap().getTerritory("United Kingdom").getUnits().getMatches(Matches.UnitIsStrategicBomber).get(0);
        
        //default 1 roll
        assertEquals(1, BattleCalculator.getRolls(bombers, british, false) );
        assertEquals(1, BattleCalculator.getRolls(bombers, british, true) );
        
        
        //hb, for revised 2 on attack, 1 on defence

        ITestDelegateBridge testDelegateBridge = getDelegateBridge(british);
        TechTracker.addAdvance(british, m_data, testDelegateBridge, TechAdvance.HEAVY_BOMBER);

        assertEquals(2, BattleCalculator.getRolls(bombers, british, false) );
        assertEquals(1, BattleCalculator.getRolls(bombers, british, true) );


        
        //lhtr hb, 2 for both
        m_data.getProperties().set(Constants.LHTR_HEAVY_BOMBERS, Boolean.TRUE);
        assertEquals(2, BattleCalculator.getRolls(bombers, british, false) );
        assertEquals(2, BattleCalculator.getRolls(bombers, british, true) );

    }
    
}