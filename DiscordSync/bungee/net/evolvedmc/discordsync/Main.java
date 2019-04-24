package bungee.net.evolvedmc.discordsync;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;

import bungee.spigot.net.evolvedmc.discordsync.discord.Discord;
import bungee.spigot.net.evolvedmc.discordsync.discord.Join;
import bungee.spigot.net.evolvedmc.discordsync.discord.Leave;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.entities.Guild;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;

public class Main extends Plugin {
	
	public Guild guild;
	public JDA jda;
	public HashMap<UUID,String>uuidCodeMap;
	public HashMap<UUID,String>uuidIdMap;
	public List<UUID>verifiedmembers;
	public Configuration configuration;
	
	public void onEnable() {
		new Discord(this);
		getProxy().getPluginManager().registerListener(this, new Join());
		getProxy().getPluginManager().registerListener(this, new Leave());
		getProxy().getPluginManager().registerCommand(this, new Verify());
		createConfig();
		getLogger().info(getDescription().getName() + " has been enabled!");
	}
	
	public void saveConfig() {
		try {
	        File file = new File(ProxyServer.getInstance().getPluginsFolder()+ "/config.yml");  
			ConfigurationProvider.getProvider(YamlConfiguration.class).save(configuration, file);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	private void createConfig() {
		try {
	        File file = new File(ProxyServer.getInstance().getPluginsFolder()+ "/config.yml");  
			if (!file.exists()) {
				file.createNewFile();
			}
			configuration = ConfigurationProvider.getProvider(YamlConfiguration.class).load(file);   
			configuration.set("Print_Out.1", "This configuration file works!");
			saveConfig();
		} catch (IOException e1) {
			e1.printStackTrace();
		}

	}
	
	public void onDisable() {
		Bukkit.getServer().getConsoleSender().sendMessage(getDescription().getName() + " has been disabled!");
	}
}
