package com.civfactions.sabre;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.net.Inet4Address;
import java.net.UnknownHostException;
import java.util.ArrayList;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.material.Bed;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import com.civfactions.sabre.test.MockBlock;
import com.civfactions.sabre.test.MockPlayer;
import com.civfactions.sabre.test.MockWorld;
import com.civfactions.sabre.test.TestFixture;
import com.comphenix.protocol.ProtocolLibrary;

@RunWith(PowerMockRunner.class)
@PrepareForTest({ SabrePlugin.class, PluginDescriptionFile.class, ProtocolLibrary.class })
public class SabrePluginIT {
	
	private static String BAN_MESSAGE = "Test ban message";
	
    private static TestFixture testFixture;
    private static SabrePlugin plugin;
    private static PlayerManager pm;
    private static PlayerListener playerListener;
    
	/*
	@BeforeClass
	public static void setUp() throws Exception {
        testFixture = new TestFixture();
        assertTrue(testFixture.setUp());
        plugin = testFixture.getPlugin();
        
        pm = plugin.getPlayerManager();
        playerListener = plugin.getPlayerListener();
	}
	
	@AfterClass
	public static void tearDown() throws Exception {
		testFixture.tearDown();
	} */
	
	@Ignore
	@Test
	public void testPlayerJoin() throws Exception {
		
		MockWorld overWorld = testFixture.getWorld(plugin.config().getFreeWorldName());
        MockPlayer newPlayer = MockPlayer.create(overWorld, "NewPlayer");
        
        AsyncPlayerPreLoginEvent playerPreLoginEvent = new AsyncPlayerPreLoginEvent(newPlayer.name, Inet4Address.getLocalHost(), newPlayer.ID);
        PlayerJoinEvent playerJoinEvent = new PlayerJoinEvent(newPlayer, null);
        PlayerQuitEvent playerQuitEvent = new PlayerQuitEvent(newPlayer, null);
        
        // Player doesn't exist yet
		assertNull(pm.getPlayerById(newPlayer.getUniqueId()));
		
		// Pre login
		playerListener.onPlayerPreLogin(playerPreLoginEvent);
		assertEquals(playerPreLoginEvent.getLoginResult(), Result.ALLOWED);
		assertEquals(playerPreLoginEvent.getKickMessage(), "");
		
		// Player join
		newPlayer.isOnline = true;
		playerListener.onPlayerJoin(playerJoinEvent);
		IPlayer sp = pm.getPlayerById(newPlayer.getUniqueId());
		assertNotNull("Player added to player manager", sp);
		assertTrue("Player is online", pm.getOnlinePlayers().contains(sp));
		assertTrue("Player is online", sp.isOnline());
		assertFalse("Player not admin", sp.isAdmin());
		assertFalse("Player not admin bypass", sp.getAdminBypass());
		assertFalse("Player not set to auto join", sp.getAutoJoin());
		assertEquals("No offline messages", sp.getOfflineMessages().size(), 0);
		assertEquals("Spawn in free world", sp.getPlayer().getWorld(), overWorld);
		verify(newPlayer, times(1)).teleport(any(Location.class));
		assertEquals(newPlayer.messages.poll(), Lang.playerYouWakeUp);
		
		// Player quit
		playerListener.onPlayerQuit(playerQuitEvent);
		 sp = pm.getPlayerById(newPlayer.getUniqueId());
		assertFalse("Player is offline", pm.getOnlinePlayers().contains(sp));
		assertFalse("Player is online", sp.isOnline());
		
		// Banned player join
		//sp.setBanned(true);
		//sp.setBanMessage(BAN_MESSAGE);
		playerListener.onPlayerPreLogin(playerPreLoginEvent);
		assertEquals(playerPreLoginEvent.getLoginResult(), Result.KICK_BANNED);
		assertEquals(playerPreLoginEvent.getKickMessage(), String.format("%s\n%s", Lang.youAreBanned, BAN_MESSAGE));
		
		// Player re-join
		//sp.setBanned(false);
		//sp.setBanMessage("");
		playerListener.onPlayerJoin(playerJoinEvent);
		 sp = pm.getPlayerById(newPlayer.getUniqueId());
		assertTrue("Player is online", pm.getOnlinePlayers().contains(sp));
		assertTrue("Player is online", sp.isOnline());
		assertEquals(newPlayer.messages.poll(), null);
		
		// Player dies
		PlayerDeathEvent playerDeathEvent = new PlayerDeathEvent(newPlayer, new ArrayList<ItemStack>(), 0, "Died");
		playerListener.onPlayerDeath(playerDeathEvent);
		assertNull("No death message", playerDeathEvent.getDeathMessage());
		
		// Player respawn
		PlayerRespawnEvent playerRespawnEvent = new PlayerRespawnEvent(newPlayer, new Location(overWorld, 0, 64, 0), false);
		playerListener.onPlayerRespawn(playerRespawnEvent);
		assertEquals(newPlayer.messages.poll(), Lang.playerYouWakeUp);
		
		// Player respawn bed
		Location bedLocation = new Location(overWorld, 100, 50, 100);
		MockBlock bedBlock = overWorld.addBlock(MockBlock.create(bedLocation, Material.BED_BLOCK));
		Bed bed = new Bed();
		bed.setHeadOfBed(false);
		bedBlock.getState().setData(bed);
		//sp.setBedLocation(bedLocation);
		PlayerRespawnEvent playerRespawnBedEvent = new PlayerRespawnEvent(newPlayer, new Location(overWorld, 100, 64, 100), true);
		playerListener.onPlayerRespawn(playerRespawnBedEvent);
		assertEquals(playerRespawnBedEvent.getRespawnLocation(), bedLocation);
		assertEquals(newPlayer.getBedSpawnLocation(), bedLocation);
		assertEquals(newPlayer.messages.poll(), null);
		
		// Random spawn if bed missing
		bedBlock.setType(Material.AIR);
		playerListener.onPlayerRespawn(playerRespawnBedEvent);
		assertEquals(newPlayer.messages.poll(), plugin.txt().parse(Lang.playerBedMissing));
		assertEquals(newPlayer.messages.poll(), Lang.playerYouWakeUp);
		
		// Random spawn again without bed message even if bed is back
		bedBlock.setType(Material.BED_BLOCK);
		playerListener.onPlayerRespawn(playerRespawnBedEvent);
		assertEquals(newPlayer.messages.poll(), Lang.playerYouWakeUp);
		
		// Player quit
		playerListener.onPlayerQuit(playerQuitEvent);
		 sp = pm.getPlayerById(newPlayer.getUniqueId());
		assertFalse("Player is offline", pm.getOnlinePlayers().contains(sp));
		assertFalse("Player is online", sp.isOnline());
	}
	
	public static void newPlayerJoinServer(World world, PlayerListener pl, String name) {
		MockPlayer newPlayer = MockPlayer.create(world, name);
		newPlayer.isOnline = true;
		AsyncPlayerPreLoginEvent playerPreLoginEvent = null;
		try {
			playerPreLoginEvent = new AsyncPlayerPreLoginEvent(newPlayer.name, Inet4Address.getLocalHost(), newPlayer.ID);
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}
		assertNotNull(playerPreLoginEvent);
		PlayerJoinEvent playerJoinEvent = new PlayerJoinEvent(newPlayer, null);
		pl.onPlayerPreLogin(playerPreLoginEvent);
		pl.onPlayerJoin(playerJoinEvent);
	}
}
