package games.strategy.sound;

import games.strategy.engine.data.PlayerID;
import games.strategy.engine.framework.IGameLoader;
import games.strategy.engine.framework.LocalPlayers;
import games.strategy.engine.gamePlayer.IGamePlayer;
import games.strategy.triplea.TripleAPlayer;

import java.util.Collection;
import java.util.Collections;

/**
 * A sound channel allowing sounds normally played on the server (for example: in a delegate, such as a the move delegate) to also be played on clients.
 * 
 * @author veqryn (Mark Christopher Duncan)
 * 
 */
public class DefaultSoundChannel implements ISound
{
	private LocalPlayers m_localPlayers;
	
	public DefaultSoundChannel(final LocalPlayers localPlayers)
	{
		m_localPlayers = localPlayers;
	}
	
	/**
	 * Plays a sound clip on this local machine.
	 * You will want to call this from UI elements (because all users have these), and not call it from delegates (because only the host has these).
	 * 
	 * @param clipName
	 *            the name of the sound clip to play, found in SoundPath.java
	 * @param subFolder
	 *            the name of the player nation who's sound we want to play (ie: russians infantry might make different sounds from german infantry, etc). Can be null.
	 */
	public static void playSoundOnLocalMachine(final String clipName, final String subFolder)
	{
		ClipPlayer.play(clipName, subFolder);
	}
	
	public void initialize()
	{
		// nothing for now
	}
	
	public void shutDown()
	{
		// nothing for now
		m_localPlayers = null;
	}
	
	public void playSoundForAll(final String clipName, final String subFolder)
	{
		playSoundForAll(clipName, subFolder, false, false, false);
	}
	
	public void playSoundForAll(final String clipName, final String subFolder, final boolean doNotIncludeHost, final boolean doNotIncludeClients, final boolean doNotIncludeObservers)
	{
		if (doNotIncludeHost && doNotIncludeClients && doNotIncludeObservers)
			return;
		if (doNotIncludeHost || doNotIncludeClients || doNotIncludeObservers)
		{
			boolean isHost = false;
			boolean isClient = false;
			boolean isObserver = true;
			if (doNotIncludeHost || doNotIncludeClients || doNotIncludeObservers)
			{
				for (final IGamePlayer player : m_localPlayers.getLocalPlayers())
				{
					isObserver = false; // if we have any local players, we are not an observer
					if (player instanceof TripleAPlayer)
					{
						if (IGameLoader.CLIENT_PLAYER_TYPE.equals(((TripleAPlayer) player).getType()))
							isClient = true;
						else
							isHost = true;
					}
					else
					{
						// AIs are run by the host machine
						isHost = true;
					}
				}
			}
			if ((doNotIncludeHost && isHost) || (doNotIncludeClients && isClient) || (doNotIncludeObservers && isObserver))
				return;
		}
		playSoundOnLocalMachine(clipName, subFolder);
	}
	
	public void playSoundToPlayers(final String clipName, final String subFolder, final Collection<PlayerID> playersToSendTo, final Collection<PlayerID> butNotThesePlayers,
				final boolean includeObservers)
	{
		if (playersToSendTo == null || playersToSendTo.isEmpty())
			return;
		if (butNotThesePlayers != null)
		{
			for (final PlayerID p : butNotThesePlayers)
			{
				if (m_localPlayers.playing(p))
				{
					return;
				}
			}
		}
		boolean isPlaying = false;
		for (final PlayerID p : playersToSendTo)
		{
			if (m_localPlayers.playing(p))
			{
				isPlaying = true;
				break;
			}
		}
		if (includeObservers && m_localPlayers.getLocalPlayers().isEmpty())
			isPlaying = true;
		if (isPlaying)
			playSoundOnLocalMachine(clipName, subFolder);
	}
	
	public void playSoundToPlayer(final String clipName, final String subFolder, final PlayerID playerToSendTo, final boolean includeObservers)
	{
		playSoundToPlayers(clipName, subFolder, Collections.singleton(playerToSendTo), null, includeObservers);
	}
}
