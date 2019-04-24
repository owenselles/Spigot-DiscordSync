package spigot.net.evolvedmc.discordsync;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import spigot.net.evolvedmc.discordsync.discord.Discord;

public class Main extends JavaPlugin {
	
	public void onEnable() {
		new Discord(this);
		getConfig().options().copyDefaults(true);
		saveDefaultConfig();
		
		Bukkit.getServer().getConsoleSender().sendMessage(getDescription().getFullName() + " has been enabled!");
	}
	
	public void onDisable() {
		Bukkit.getServer().getConsoleSender().sendMessage(getDescription().getFullName() + " has been disabled!");
	}
}
