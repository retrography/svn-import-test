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
 * MapPanel.java
 *
 * Created on November 5, 2001, 1:54 PM
 */

package games.strategy.triplea.ui;

import java.awt.*;
import java.awt.geom.*;
import java.awt.event.*;
import java.util.*;
import java.net.URL;
import javax.swing.*;

import games.strategy.util.PointFileReaderWriter;
import games.strategy.engine.data.*;
import games.strategy.ui.*;
import games.strategy.engine.data.events.TerritoryListener;

import games.strategy.triplea.image.*;

import java.io.*;

/**
 *
 * Responsible for drawing the large map and keeping it updated.
 *
 * @author  Sean Bridges
 * @version 1.0
 */
public class MapPanel extends ImageScrollerLargeView
{
  private final static String CENTERS_FILENAME = "centers.txt";
  private final static String POLYGONS_FILENAME = "polygons.txt";
  private final static String PLACE_FILENAME = "place.txt";

  private Map m_centers = new HashMap(); //maps Territory -> point
  private Map m_place = new HashMap(); //maps Territory -> collection of points
  private SortedSet m_polygonTerritories = new TreeSet(); //collection of PollygonTerritories
  private java.util.List m_mapSelectionListeners = new ArrayList();

  private final GameData m_data;
  private Territory m_currentTerritory; //the territory that the mouse is currently over
                                              //could be null
  private JComponent m_smallImage;

  //current image we are displaying, could be null
  private Route m_route;

  /** Creates new MapPanel */
    public MapPanel(Image image, GameData data, JComponent smallImage) throws IOException
  {
    super(image);
    m_smallImage = smallImage;
    m_data = data;
    data.addTerritoryListener(TERRITORY_LISTENER);

    initPolygons();
    initCenters();
    initPlacement();

    checkTerritories();
    initTerritories();

    this.addMouseListener(MOUSE_LISTENER);
    this.addMouseMotionListener(MOUSE_MOTION_LISTENER);
    }

  private void initCenters() throws IOException
  {
    URL centers = TripleAFrame.class.getResource(CENTERS_FILENAME);
    InputStream stream = centers.openStream();
    Map tempCenters = new PointFileReaderWriter().readOneToOne(stream);
    Iterator iter = tempCenters.keySet().iterator();
    while(iter.hasNext())
    {
      String name = (String) iter.next();
      Territory terr = m_data.getMap().getTerritory(name);
      if(terr == null)
        throw new IllegalStateException("Territory in centers file could not be found in game data. Territory name <" + name + ">");
      m_centers.put(terr, tempCenters.get(name));
    }
  }

  public void centerOn(Territory territory)
  {
    if(territory == null)
      return;

    Point p = (Point) m_centers.get(territory);

    //when centering dont want the map to wrap around,
    //eg if centering on hawaii
    super.setTopLeftNoWrap(p.x - (getWidth()/2), p.y- (getHeight()/2));
  }

  /**
   * Set the route, could be null.
   */
  public void setRoute(Route route)
  {
    if(m_route != route || (m_route != null && !m_route.equals(route)))
    {
      m_route = route;
      initTerritories();
    }
  }

  private void initPlacement() throws IOException
  {
    URL centers = TripleAFrame.class.getResource(PLACE_FILENAME);
    InputStream stream = centers.openStream();
    Map tempPlace = new PointFileReaderWriter().readOneToMany(stream);
    Iterator iter = tempPlace.keySet().iterator();
    while(iter.hasNext())
    {
      String name = (String) iter.next();
      Territory terr = m_data.getMap().getTerritory(name);
      if(terr == null)
        throw new IllegalStateException("Territory in centers file could not be found in game data. Territory name <" + name + ">");
      m_place.put(terr, tempPlace.get(name));
    }
  }


  private void initPolygons() throws IOException
  {
    BufferedReader reader = null;
    try
    {
      URL polygons = getClass().getResource(POLYGONS_FILENAME);
      reader = new BufferedReader(new InputStreamReader(polygons.openStream()));

      String current = reader.readLine();
      while(current != null)
      {
        if(current.trim().length() != 0)
        {
          PolygonTerritory newPoly = PolygonTerritory.read(current, m_data);
          if(getPolygon(newPoly.getTerritory()) != null)
            throw new IllegalStateException("duplicate polygons for territory <" + newPoly.getTerritory().getName() + ">");

          m_polygonTerritories.add(newPoly);
        }
        current = reader.readLine();
      }
    } finally
    {
      if(reader != null)
        reader.close();
    }
  }

  /**
   * Check that all territories have a center and a polygon.
   */
  private void checkTerritories()
  {
    StringBuffer errors = new StringBuffer();
    Iterator territories = m_data.getMap().getTerritories().iterator();

    while(territories.hasNext())
    {
      Territory current = (Territory) territories.next();
      if(getCenter(current) == null)
        errors.append("No center for <" + current.getName() + ">\n");
      if(getPolygon(current) == null)
        errors.append("No polygon for <" + current.getName() + ">\n");
    }
    if(errors.length() != 0)
    {
      //com.geocities.sbridges_geo.debug.Inspector.inspectAndWait(m_polygonTerritories);
      System.err.println("Missing data in centers or polygons file");
      System.err.println(errors);
      System.exit(0);
    }
  }

  private Point getCenter(Territory terr)
  {
    return (Point) m_centers.get(terr);
  }

  private Polygon getPolygon(Territory terr)
  {
    Iterator polys = m_polygonTerritories.iterator();
    while(polys.hasNext() )
    {
      PolygonTerritory poly = (PolygonTerritory) polys.next();
      if(poly.getTerritory().equals(terr))
      {
        return poly.getPolygon();
      }
    }
    return null;
  }

  public void addMapSelectionListener(MapSelectionListener listener)
  {
    m_mapSelectionListeners.add(listener);
  }

  public void removeMapSelectionListener(MapSelectionListener listener)
  {
    m_mapSelectionListeners.remove(listener);
  }

  private void notifyTerritorySelected(Territory t,MouseEvent me)
  {
    java.util.List listeners = new ArrayList(m_mapSelectionListeners.size());
    listeners.addAll(m_mapSelectionListeners);

    for(int i = 0; i < listeners.size(); i++)
    {
      MapSelectionListener msl = (MapSelectionListener) listeners.get(i);
      msl.territorySelected(t, me);
    }
  }

  private void notifyMouseEntered(Territory t)
  {
    java.util.List listeners = new ArrayList(m_mapSelectionListeners.size());
    listeners.addAll(m_mapSelectionListeners);

    for(int i = 0; i < listeners.size(); i++)
    {
      MapSelectionListener msl = (MapSelectionListener) listeners.get(i);
      msl.mouseEntered(t);
    }
  }



  private Territory getTerritory(int x, int y)
  {
    int imageWidth = (int) getImageDimensions().getWidth();
    if(x < 0)
      x+= imageWidth;
    else if(x > imageWidth)
      x-= imageWidth;

    Iterator iter = m_polygonTerritories.iterator();
    while(iter.hasNext() )
    {
      PolygonTerritory current = (PolygonTerritory) iter.next();
      if(current.getPolygon().contains(x,y))
        return current.getTerritory();
    }
    return null;
  }

  private void initTerritories()
  {
    clearOffscreen();
    Iterator iter = m_polygonTerritories.iterator();
    while(iter.hasNext())
    {
      Territory terr = ((PolygonTerritory) iter.next()).getTerritory();
      updateTerritory(terr);
    }
    drawRoute();
    update();

  }

  /**
   * Draw m_route to the screen, do nothing if null.
   */
  private void drawRoute()
  {
    Route route = m_route;
    if(route == null)
      return;

    Graphics2D graphics = (Graphics2D) getOffscreenGraphics();
    graphics.setStroke(new BasicStroke(3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    graphics.setPaint(Color.red);

    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    Territory current = route.getStart();

    Point start = (Point) m_centers.get(current);
    Ellipse2D oval = new Ellipse2D.Double(start.x - 3, start.y - 3, 6,6);
    graphics.draw(oval);

    for(int i = 0; i < route.getLength(); i++)
    {
      Territory next = route.at(i);
      start = (Point) m_centers.get(current);
      start = new Point(start.x, start.y);
      Point finish = (Point) m_centers.get(next);
      finish = new Point(finish.x, finish.y);


      Shape line = new Line2D.Double(start.x, start.y, finish.x, finish.y);

      graphics.draw(line );
      oval = new Ellipse2D.Double(finish.x - 3, finish.y - 3, 6,6);
      graphics.draw(oval);

      current = next;
    }

    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_DEFAULT);
  }

  private void updateTerritory(Territory terr)
  {
    getOffscreenGraphics().setColor(Color.white);

    Iterator placementPoints = ((Collection) m_place.get(terr)).iterator();
    if(placementPoints == null || !placementPoints.hasNext())
      throw new IllegalStateException("No where to palce units");

    Point lastPlace = null;

    UnitCollection units = terr.getUnits();
    Iterator players = units.getPlayersWithUnits().iterator();

    while(players.hasNext())
    {
      PlayerID player = (PlayerID) players.next();

      Iterator types = m_data.getUnitTypeList().iterator();
      while(types.hasNext())
      {
        UnitType current = (UnitType) types.next();
        int count = units.getUnitCount(current, player);
        if(count != 0)
        {
          Point place;
          if(placementPoints.hasNext())
          {
            place = (Point) placementPoints.next();
            lastPlace = new Point(place.x, place.y);
          }
          else
          {
            place = lastPlace;
            lastPlace.x += UnitIconImageFactory.UNIT_ICON_WIDTH;
          }

          Image img = UnitIconImageFactory.instance().getImage(current, player, m_data);
          //getOffscreenGraphics().drawImage(FlagIconImageFactory.instance().getSmallFlag(player), place.x + UnitIconImageFactory.UNIT_ICON_WIDTH - FlagIconImageFactory.SMALL_FLAG_ICON_WIDTH, place.y ,this);
          getOffscreenGraphics().drawImage(img, place.x, place.y, this);
          if(count != 1)
          {
            getOffscreenGraphics().drawString(String.valueOf(count), place.x + (UnitIconImageFactory.UNIT_ICON_WIDTH / 4), place.y + UnitIconImageFactory.UNIT_ICON_HEIGHT);
          }
        }
      }//end for each unit type
    }//end for each player
  }

  private MouseListener MOUSE_LISTENER = new MouseAdapter()
  {
    public void mouseReleased(MouseEvent e)
    {
      Territory terr = getTerritory(e.getX() + getXOffset(), e.getY() + getYOffset());
      if(terr != null)
        notifyTerritorySelected(terr, e);
    }

    public void mouseExit(MouseEvent e)
    {
      if(m_currentTerritory != null)
      {
        m_currentTerritory = null;
        notifyMouseEntered(null);
      }
    }


  };

  private final MouseMotionListener MOUSE_MOTION_LISTENER = new MouseMotionAdapter()
  {
    public void mouseMoved(MouseEvent e)
    {
      Territory terr = getTerritory(e.getX() + getXOffset(), e.getY() + getYOffset());
      //we can use == here since they will be the same object.
      //dont use .equals since we have nulls
      if(terr != m_currentTerritory)
      {
        m_currentTerritory = terr;
        notifyMouseEntered(terr);
      }
    }
  };

  private final TerritoryListener TERRITORY_LISTENER = new TerritoryListener()
  {
    public void unitsChanged(Territory territory)
    {
      initTerritories();
    }

    public void ownerChanged(Territory territory)
    {
      MapImage.getInstance().setOwner(territory, territory.getOwner());
      m_smallImage.repaint();
      initTerritories();
    }
  };
}







