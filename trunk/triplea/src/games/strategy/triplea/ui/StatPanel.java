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
 * StatPanel.java
 *
 * Created on January 6, 2002, 4:07 PM
 */

package games.strategy.triplea.ui;

import java.awt.*;
import java.awt.event.*;

import java.util.*;
import javax.swing.*;
import javax.swing.table.*;

import games.strategy.engine.data.*;
import games.strategy.engine.data.events.GameDataChangeListener;

import games.strategy.util.IntegerMap;

import games.strategy.triplea.attatchments.TerritoryAttatchment;
import games.strategy.triplea.Constants;
import games.strategy.triplea.delegate.TechAdvance;

/**
 *
 * @author  Sean Bridges
 */
public class StatPanel extends JPanel
{
	/** Creates a new instance of InfoPanel */
	public StatPanel(GameData data)
	{
		setLayout(new GridLayout(2, 1));

		StatTableModel dataModel = new StatTableModel(data);
		TechTableModel techModel = new TechTableModel(data);

      		JTable table = new JTable(dataModel);
      		// Strangely, this is enabled by default
      		table.getTableHeader().setReorderingAllowed(false);

      		JScrollPane scroll = new JScrollPane(table);
		// add(scroll, BorderLayout.NORTH);
		add(scroll);

      		table = new JTable(techModel);
      		// Strangely, this is enabled by default
      		table.getTableHeader().setReorderingAllowed(false);

		// Make the technology column big.  Value chosen by trial and error
		// The right way to do this is probably to get a FontMetrics object
		// and measure the pixel width of the longest technology name in the
		// current font.
		TableColumn column = table.getColumnModel().getColumn(0);
		column.setPreferredWidth(500);

      		scroll = new JScrollPane(table);
		// add(scroll, BorderLayout.SOUTH);
		add(scroll);

		data.addDataChangeListener(dataModel);
		data.addDataChangeListener(techModel);
	}

	/*
	 * Custom table model
	 */
	class StatTableModel extends AbstractTableModel implements GameDataChangeListener {
		/* Flag to indicate whether data needs to be recalculated */
		private boolean isDirty = true;
		private GameData m_data;
		/* Column Header Names */
	 	private String[] colList = new String[] { "Country", "IPCs", "Production", "Units" };
	 	/* Row Header Names */
		private String[] rowList = new String[] { Constants.RUSSIANS, Constants.GERMANS, Constants.BRITISH, Constants.JAPANESE, Constants.AMERICANS, "Allies", "Axis" } ;
		/* Underlying data for the table */
		private Object[][] data = new Object[rowList.length][colList.length];
		/* Convenience mapping of player names -> row */
		private Map rowMap = null;

		public StatTableModel(GameData data) {
			m_data = data;

			/* Load the player -> row mapping */
			rowMap = new HashMap();
			for (int i = 0; i < rowList.length; i++)
				rowMap.put(rowList[i], new Integer(i));
		}

		public void gameDataChanged()
		{
			isDirty = true;
		}

		/*
		 * Ideally, this would only be called to initialize the stats.  The stats would then be updated
		 * incrementally as they changed.  But I don't think this can happen until the GameDataChange
		 * event gets some more context.
		 */

		private void update()
        {
			PlayerList plist = m_data.getPlayerList();

			// Use an int array for the scratch array for performance (avoids object thrashing)
			int[][] tmpData = new int[rowList.length][colList.length - 2];

			// Iterate over the territories, updating all the per-territory stats in one pass
			Iterator territories = m_data.getMap().iterator();
			while(territories.hasNext())
			{
				Territory current = (Territory) territories.next();
				TerritoryAttatchment ta = TerritoryAttatchment.get(current);
				Integer row = (Integer) rowMap.get(current.getOwner().getName());

				if (row != null)
				{
					tmpData[row.intValue()][1] += ta.getProduction();

					// Take advantage of the fact that the indices of allied countries are all even numbers to update team totals.
					tmpData[5 + (row.intValue() % 2)][1] += ta.getProduction();
				}
			}

			// Now do all the per-country stats
			for (int row = 0; row < rowList.length - 2; row++)
			{
				tmpData[row][0] = plist.getPlayerID(rowList[row]).getResources().getQuantity(Constants.IPCS);
				// Take advantage of the fact that the indices of allied countries are all even numbers to update team totals.
				tmpData[5 + (row % 2)][0] += plist.getPlayerID(rowList[row]).getResources().getQuantity(Constants.IPCS);
			}

			// Initialize the total units column for the axis and allies to 0
			Integer ZERO = new Integer(0);
			data[5][3] = ZERO;
			data[6][3] = ZERO;

			// Finally, throw this into an array that closely matches the table structure
			for (int row = 0; row < rowList.length; row++)
			{
				PlayerID id = plist.getPlayerID(rowList[row]);
				data[row][0] = rowList[row];
				data[row][1] = new Integer(tmpData[row][0]);
				data[row][2] = new Integer(tmpData[row][1]);
				// Not sure how much I like hardcoding this 5 in here.
				if ( row < 5 )
				{
                    int placedUnits = getUnitsPlaced(id);
					data[row][3] = new Integer( placedUnits);

					// Take advantage of the fact that the indices of allied countries are all even numbers to update team totals.
					data[5 + (row % 2)][3] = new Integer(((Integer) data[5 + (row % 2)][3]).intValue() + placedUnits);
				}
			}
		}

        private int getUnitsPlaced(PlayerID id)
        {
            int placed = 0;
            Iterator territories = m_data.getMap().iterator();
            while(territories.hasNext())
            {
                Territory current = (Territory) territories.next();
                placed += current.getUnits().getUnitCount(id);
            }
            return placed;
        }

		/*
		 * Recalcs the underlying data in a lazy manner
		 * Limitation: This is not a threadsafe implementation
		 */
  		public Object getValueAt(int row, int col)
  		{
  			if (isDirty)
  			{
  				update();
  				isDirty = false;
  			}

  			return data[row][col];
  		}

		// Trivial implementations of required methods
		public String getColumnName(int col) { return colList[col]; }
  		public int getColumnCount() { return colList.length; }
  		public int getRowCount() { return rowList.length;}
      	}

	class TechTableModel extends AbstractTableModel implements GameDataChangeListener {
		/* Flag to indicate whether data needs to be recalculated */
		private boolean isDirty = true;
		private GameData m_data;
		/* Column Header Names */
	 	private String[] rowList = new String[] { "Jet Power", "Rockets", "Super Subs", "Long Range Aircraft", "Industrial Technology", "Heavy Bombers" };
	 	/* Row Header Names */
		private String[] colList = new String[] { Constants.RUSSIANS, Constants.GERMANS, Constants.BRITISH, Constants.JAPANESE, Constants.AMERICANS } ;
		/* Underlying data for the table */
		private String[][] data = new String[rowList.length][colList.length + 1];
		/* Convenience mapping of country names -> col */
		private Map colMap = null;
		/* Convenience mapping of technology names -> row */
		private Map rowMap = null;

		public TechTableModel(GameData gdata) {
			m_data = gdata;

			/* Load the country -> col mapping */
			colMap = new HashMap();
			for (int i = 0; i < colList.length; i++)
				colMap.put(colList[i], new Integer(i + 1));

			/* Load the technology -> row mapping */
			rowMap = new HashMap();
			Iterator iter = TechAdvance.getTechAdvances().iterator();
			int row = 0;

			while (iter.hasNext())
			{
				rowMap.put(((TechAdvance) iter.next()).getName(), new Integer(row));
				row++;
			}


			/* Initialize the table with the tech names */
			for (int i = 0; i < rowList.length; i++)
			{
				data[i][0] = rowList[i];
				for (int j = 1; j <= colList.length; j++)
					data[i][j] = "";
			}
		}

		public void update() {
			Iterator iter = m_data.getPlayerList().getPlayers().iterator();
			Map advanceProperty =  (Map) m_data.getProperties().get(Constants.TECH_PROPERTY);

			if (advanceProperty == null)
				return;

			while (iter.hasNext())
			{
				PlayerID pid = (PlayerID) iter.next();
				if (colMap.get(pid.getName()) == null)
					throw new IllegalStateException("Unexpected player in GameData.getPlayerList()" + pid.getName());

				int col = ((Integer) colMap.get(pid.getName())).intValue();

				if (advanceProperty.get(pid) == null)
					continue;

				Iterator advances = ((Collection) advanceProperty.get(pid)).iterator();

				while(advances.hasNext())
				{
					int row = ((Integer) rowMap.get((String) advances.next())).intValue();
					// System.err.println("(" + row + ", " + col + ")");
					data[row][col] = "X";
					// data[row][col] = colList[col].substring(0, 1);
				}
			}
		}

		public String getColumnName(int col)
		{
			if (col == 0)
				return "Technology";
			return colList[col - 1].substring(0, 1);
		}

		/*
		 * Recalcs the underlying data in a lazy manner
		 * Limitation: This is not a threadsafe implementation
		 */
  		public Object getValueAt(int row, int col)
  		{
  			if (isDirty)
  			{
  				update();
  				isDirty = false;
  			}

  			return data[row][col];
  		}

		// Trivial implementations of required methods
  		public int getColumnCount() { return colList.length + 1; }
  		public int getRowCount() { return rowList.length;}

		public void gameDataChanged() { isDirty = true; }

      	}

}



