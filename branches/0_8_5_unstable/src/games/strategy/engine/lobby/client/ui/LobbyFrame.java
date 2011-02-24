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

package games.strategy.engine.lobby.client.ui;

import games.strategy.engine.chat.Chat;
import games.strategy.engine.chat.ChatMessagePanel;
import games.strategy.engine.chat.ChatPlayerPanel;
import games.strategy.engine.framework.GameRunner;
import games.strategy.engine.lobby.client.LobbyClient;
import games.strategy.engine.lobby.server.LobbyServer;
import games.strategy.net.IMessenger;
import games.strategy.net.IMessengerErrorListener;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JSplitPane;

public class LobbyFrame extends JFrame
{
    
    private final LobbyClient m_client;
    
    
    
    public LobbyFrame(LobbyClient client)
    {
        super("TripleA Lobby");
        setIconImage(GameRunner.getGameIcon(this));
        m_client = client;
        setJMenuBar(new LobbyMenu(this));
        
        Chat chat = new Chat(m_client.getMessenger(), LobbyServer.LOBBY_CHAT, m_client.getChannelMessenger(), m_client.getRemoteMessenger());
        ChatMessagePanel chatMessagePanel = new ChatMessagePanel(chat);
        
        ChatPlayerPanel chatPlayers = new ChatPlayerPanel(null);
        chatPlayers.addIgnoredPlayerName(LobbyServer.ADMIN_USERNAME);
        chatPlayers.setChat(chat);
        chatPlayers.setPreferredSize(new Dimension(200,600 ));
    
        LobbyGamePanel gamePanel = new LobbyGamePanel(m_client.getMessengers());
        
    
        JSplitPane leftSplit = new JSplitPane( );
        leftSplit.setOrientation(JSplitPane.VERTICAL_SPLIT);
        leftSplit.setTopComponent(gamePanel);
        leftSplit.setBottomComponent(chatMessagePanel);
        
        leftSplit.setResizeWeight(0.8);
        gamePanel.setPreferredSize(new Dimension(700,200 ));
        chatMessagePanel.setPreferredSize(new Dimension(700,400 ));
        
        
        JSplitPane mainSplit = new JSplitPane();
        mainSplit.setOrientation(JSplitPane.HORIZONTAL_SPLIT);
        mainSplit.setLeftComponent(leftSplit);
        mainSplit.setRightComponent(chatPlayers);
        
        add(mainSplit, BorderLayout.CENTER);
        
        pack();
        setLocationRelativeTo(null);
        
      
        
        m_client.getMessenger().addErrorListener(new IMessengerErrorListener()
        {
        
            public void messengerInvalid(IMessenger messenger, Exception reason)
            {
                connectionToServerLost();
        
            }
        
        });
        
        addWindowListener(new WindowAdapter()
        {
            public void windowClosing(WindowEvent e)
            {
                shutdown();
            }
        });
        
        
    }
    
    public LobbyClient getLobbyClient()
    {
        return m_client;
    }
    
    
    void shutdown()
    {
//        int rVal = JOptionPane.showConfirmDialog(this, "Are you sure you want to exit?\nUnsaved game data will be lost.", "Exit" , JOptionPane.YES_NO_OPTION);
//        if(rVal != JOptionPane.OK_OPTION)
//            return;

        System.exit(0);
    }

    private void connectionToServerLost()
    {
        JOptionPane.showMessageDialog(LobbyFrame.this, "Connection to Server Lost", "Connection Lost", JOptionPane.ERROR_MESSAGE, null);
    }

}