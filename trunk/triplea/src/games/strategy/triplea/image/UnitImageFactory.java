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
* UnitIconImageFactory.java
*
* Created on November 25, 2001, 8:27 PM
 */

package games.strategy.triplea.image;

import games.strategy.engine.data.*;
import games.strategy.triplea.Constants;
import games.strategy.triplea.delegate.TechTracker;
import games.strategy.ui.Util;

import java.awt.*;
import java.awt.image.*;
import java.net.URL;
import java.util.*;

import javax.swing.*;

/**
 *
 * @author  Sean Bridges
 * @version 1.0
 */
public class UnitImageFactory
{
  /**
   * Width of all icons.
   * You probably want getUnitImageWidth(), which takes scale factor into account.
   */
  public static final int UNIT_ICON_WIDTH = 48;

  /**
   * Height of all icons.
   * You probably want getUnitImageHeight(), which takes scale factor into account. 
   **/
  public static final int UNIT_ICON_HEIGHT = 48;

  private static final String FILE_NAME_BASE = "images/units/";

  //maps Point -> image
  private final Map<String, Image> m_images = new HashMap<String, Image>();
  //maps Point -> Icon
  private final Map<String, ImageIcon> m_icons = new HashMap<String, ImageIcon>();
  // Scaling factor for unit images
  private double m_scaleFactor;

  /** Creates new IconImageFactory */
  public UnitImageFactory(double scaleFactor)
  {
      m_scaleFactor = scaleFactor;
  }

  /**
   * Set the unitScaling factor
   */
  public void setScaleFactor(double scaleFactor) {
    if (m_scaleFactor != scaleFactor) {
      m_scaleFactor = scaleFactor;
      clearImageCache();
    }
  }

  /**
   * Return the unit scaling factor
   */
  public double getScaleFactor() {
    return m_scaleFactor;
  }

  /**
   * Return the width of scaled units
   */
  public int getUnitImageWidth() {
    return (int)(m_scaleFactor * UNIT_ICON_WIDTH);
  }

  /**
   * Return the height of scaled units
   */
  public int getUnitImageHeight() {
    return (int)(m_scaleFactor * UNIT_ICON_HEIGHT);
  }

  // Clear the image and icon cache
  private void clearImageCache()
  {
    m_images.clear();
    m_icons.clear();
  }

  /**
   * Return the appropriate unit image.
   */
  public Image getImage(UnitType type, PlayerID player, GameData data, boolean damaged)
  {
    String baseName = getBaseImageName(type, player, data, damaged);
    String fullName = baseName + player.getName();
    if(m_images.containsKey(fullName))
    {
      return m_images.get(fullName);
    }

    Image baseImage = getBaseImage(baseName, player, damaged);

    // We want to scale units according to the given scale factor.
    // We use smooth scaling since the images are cached to allow
    // to take our time in doing the scaling.
    // Image observer is null, since the image should have been
    // guaranteed to be loaded.
    int width = (int) (baseImage.getWidth(null) * m_scaleFactor);
    int height = (int) (baseImage.getHeight(null) * m_scaleFactor);
    Image scaledImage = baseImage.getScaledInstance(width, height, Image.SCALE_SMOOTH);

    // Ensure the scaling is completed.
    try
    {
      Util.ensureImageLoaded(scaledImage, new java.awt.Label());
    }
    catch (InterruptedException ex)
    {
      ex.printStackTrace();
    }

    m_images.put(fullName, scaledImage);
    return scaledImage;
  }

  private Image getBaseImage(String baseImageName, PlayerID id, boolean damaged)
  {
    String fileName = FILE_NAME_BASE + id.getName() + "/"  + baseImageName  + ".png";
    URL url = this.getClass().getResource(fileName);
    if(url == null)
      throw new IllegalStateException("Cant load :"+ baseImageName + " looking in:" + fileName);

    Image image = Toolkit.getDefaultToolkit().getImage(url);
    try
    {
      Util.ensureImageLoaded(image, new java.awt.Label());
    }
    catch (InterruptedException ex)
    {
      ex.printStackTrace();
    }

    return image;
    
//    ImageFilter filter = new RGBImageFilter()
//    {
//        public int filterRGB(int x, int y, int rgb)
//        {
//          
//            int alpha = ((rgb >> 24)) & 0xff;
//            
//            if(alpha > 0)
//            {
//                int red = (rgb >> 16) & 0xff;
//                int green = (rgb >> 8)  & 0xff;
//                int blue = rgb & 0xff;
//                
//                int average = (int) (((red + green + blue) / 3) );
//                
//                if(average < 35)
//                {
//                    red = 255;
//                    green = 255;
//                    blue = 255;
//                    return (alpha << 24) | (red << 16) | (green << 8) | (blue << 0);
//                }
//                else return 0;
//            }
//            else
//                return 0;
//
//            
//            
//            
//        }
//    };
//    ImageProducer prod = new FilteredImageSource(image.getSource(), filter);
//    return Toolkit.getDefaultToolkit().createImage(prod);
  }

  /**
   * Return a icon image for a unit.
   */
  public ImageIcon getIcon(UnitType type, PlayerID player, GameData data, boolean damaged)
  {
    String baseName = getBaseImageName(type, player, data, damaged);
    String fullName = baseName + player.getName();
    if(m_icons.containsKey(fullName))
    {
      return m_icons.get(fullName);
    }

    Image img = getBaseImage(baseName, player, damaged);
    ImageIcon icon = new ImageIcon(img);
    m_icons.put(fullName, icon);

    return icon;
  }

  public String getBaseImageName(UnitType type, PlayerID id, GameData data, boolean damaged)
  {
    StringBuilder name = new StringBuilder(32);
    name.append(type.getName());

    if (type.getName().equals(Constants.FIGHTER_TYPE))
    {
      if (TechTracker.hasLongRangeAir(id))
      {
	name.append("_lr");
      }
      if (TechTracker.hasJetFighter(id))
      {
	name.append("_jp");
      }
    }

    if (type.getName().equals(Constants.BOMBER_TYPE))
    {
      if (TechTracker.hasLongRangeAir(id))
      {
	name.append("_lr");
      }

      if (TechTracker.hasHeavyBomber(id))
      {
	name.append("_hb");
      }
    }

    if (type.getName().equals(Constants.SUBMARINE_TYPE))
    {
      if (TechTracker.hasSuperSubs(id))
      {
	name.append("_ss");
      }
      if (TechTracker.hasRocket(id))
      {}
    }

    if (type.getName().equals(Constants.FACTORY_TYPE))
    {

      if (TechTracker.hasIndustrialTechnology(id))
      {
	name.append("_it");
      }
    }

    if(damaged)
      name.append("_hit");

    return name.toString();
  }


}
