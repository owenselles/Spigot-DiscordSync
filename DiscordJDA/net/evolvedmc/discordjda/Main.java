package net.evolvedmc.discordjda;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class Main extends JavaPlugin {
	
	public void onEnable() {
		Bukkit.getServer().getConsoleSender().sendMessage(getDescription().getFullName() + " has been enabled and hooked onto DiscordSync");
	}
	
	public void onDisable() {
		Bukkit.getServer().getConsoleSender().sendMessage(getDescription().getFullName() + " has been disabled");
	}
}
