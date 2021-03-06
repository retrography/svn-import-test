package games.strategy.engine.chat;

import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import junit.framework.TestCase;

public class ChatIgnoreListTest extends TestCase
{
	@Override
	public void setUp() throws BackingStoreException
	{
		// clear this
		clearStore();
	}
	
	@Override
	public void tearDown() throws BackingStoreException
	{
		clearStore();
	}
	
	private void clearStore() throws BackingStoreException
	{
		final Preferences prefs = ChatIgnoreList.getPrefNode();
		prefs.clear();
		prefs.flush();
	}
	
	public void testLoadStore()
	{
		ChatIgnoreList list = new ChatIgnoreList();
		assertFalse(list.shouldIgnore("test"));
		list.add("test");
		assertTrue(list.shouldIgnore("test"));
		list = new ChatIgnoreList();
		assertTrue(list.shouldIgnore("test"));
	}
}
