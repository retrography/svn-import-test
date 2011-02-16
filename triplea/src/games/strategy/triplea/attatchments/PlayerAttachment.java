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
 * PlayerAttatchment.java
 *
 * Created on August 29, 2005, 3:14 PM
 */

package games.strategy.triplea.attatchments;

import games.strategy.engine.data.DefaultAttachment;
import games.strategy.engine.data.PlayerID;
import games.strategy.triplea.Constants;

/**
 *
 * @author  Adam Jette
 * @version 1.0
 */
public class PlayerAttachment extends DefaultAttachment
{
    /**
     * Convenience method.
     */
    public static PlayerAttachment get(PlayerID p)
    {
        return (PlayerAttachment) p.getAttachment(Constants.PLAYER_ATTATCHMENT_NAME);
    }

    private int m_vps = 0;
    private int m_captureVps = 0; // need to store some data during a turn
    private int m_retainCapitalNumber = 1; // number of capitals needed before we lose all our money
    private int m_retainCapitalProduceNumber = 1; // number of capitals needed before we lose ability to gain money and produce units
    private boolean m_takeUnitControl = false;
    private boolean m_giveUnitControl = false;
    private boolean m_destroysPUs = false; // do we lose our money and have it disappear or is that money captured?
    
    /** Creates new PlayerAttachment */
    public PlayerAttachment()
    {
    }

    public void setVps(String value)
    {
        m_vps = getInt(value);
    }

    public String getVps()
    {
        return "" + m_vps;
    }

    public void setCaptureVps(String value)
    {
        m_captureVps = getInt(value);
    }

    public String getCaptureVps()
    {
        return "" + m_captureVps;
    }

    public void setRetainCapitalNumber(String value)
    {
    	m_retainCapitalNumber = getInt(value);
    }

    public int getRetainCapitalNumber()
    {
        return m_retainCapitalNumber;
    }

    public void setRetainCapitalProduceNumber(String value)
    {
    	m_retainCapitalProduceNumber = getInt(value);
    }

    public int getRetainCapitalProduceNumber()
    {
        return m_retainCapitalProduceNumber;
    }
    
    public void setTakeUnitControl(String value)
    {
        m_takeUnitControl = getBool(value);
    }

    public boolean getTakeUnitControl()
    {
        return m_takeUnitControl;
    } 
    
    public void setGiveUnitControl(String value)
    {
    	m_giveUnitControl = getBool(value);
    }

    public boolean getGiveUnitControl()
    {
        return m_giveUnitControl;
    }
    
    public void setDestroysPUs(String value)
    {
    	m_destroysPUs = getBool(value);
    }

    public boolean getDestroysPUs()
    {
        return m_destroysPUs;
    }
}