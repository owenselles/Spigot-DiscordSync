package spigot.net.evolvedmc.discordsync.discord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import javax.security.auth.login.LoginException;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import net.dv8tion.jda.core.AccountType;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.JDABuilder;
import net.dv8tion.jda.core.entities.Guild;
import net.dv8tion.jda.core.entities.Member;
import net.dv8tion.jda.core.entities.Role;
import net.dv8tion.jda.core.events.message.MessageReceivedEvent;
import net.dv8tion.jda.core.hooks.ListenerAdapter;
import spigot.net.evolvedmc.discordsync.Main;

public class Discord extends ListenerAdapter implements CommandExecutor, Listener {
	
	public Main plugin;
	public Guild guild;
	public JDA jda;
	public HashMap<UUID,String>uuidCodeMap;
	public HashMap<UUID,String>uuidIdMap;
	public List<UUID>verifiedmembers;
	
	public Discord(Main main) {
		this.plugin = main;
		startBot();
		plugin.getCommand("Verify").setExecutor(this);
		plugin.getCommand("DChat").setExecutor(this);
		plugin.getServer().getPluginManager().registerEvents(this, plugin);
		jda.addEventListener(this);
		guild = jda.getGuilds().get(0);
		uuidCodeMap = new HashMap<>();
		uuidIdMap = new HashMap<>();
		verifiedmembers = new ArrayList<>();
	}
	
	@SuppressWarnings("deprecation")
	private void startBot() {
		JDABuilder builder = new JDABuilder(AccountType.BOT);
		String token = plugin.getConfig().getString("bot-token");
		builder.setToken(token);
		try {
			builder.buildAsync();
		} catch (LoginException e) {
			e.printStackTrace();
		}
}
	
	@Override
	public void onMessageReceived(MessageReceivedEvent e) {
		if(e.getAuthor().isBot()||e.getAuthor().isFake()||e.isWebhookMessage()) {
			return;
		}
		String[] args = e.getMessage().getContentRaw().split(" ");
		if(args[0].equalsIgnoreCase("!link")) {
			if(e.getMember().getRoles().stream().filter(role -> role.getName().equals("123")).findAny().orElse(null) != null) {
				e.getChannel().sendMessage(":x: Error!"+e.getAuthor().getAsMention()+", you are already verified!").queue();
				return;
			}
			if(args.length!=2) {
				e.getChannel().sendMessage(":x: Error! You need to specify a player!").queue();
				return;
			}
			Player target = Bukkit.getPlayer(args[1]);
			if(target==null) {
				e.getChannel().sendMessage(":x: Error! The player needs to be online!").queue();
				return;
			}
			String verifycode = new Random().nextInt(800000)+200000+"EV";
			uuidCodeMap.put(target.getUniqueId(), verifycode);
			uuidIdMap.put(target.getUniqueId(), e.getAuthor().getId());
			e.getAuthor().openPrivateChannel().complete().sendMessage("Your verification code has been generated!\n Execute this command in game to verify your account: /verify "+verifycode).queue();;
		}
	}
	
	@EventHandler
	public void onJoin(PlayerJoinEvent e) {
		Player p = e.getPlayer();
		String discordid = uuidIdMap.get(p.getUniqueId());
		Member target = guild.getMemberById(discordid);
		if(!target.getNickname().equals(p.getName())) {
			// TODO
		}
		if(plugin.getConfig().contains("Data."+e.getPlayer().getUniqueId().toString())) {
			verifiedmembers.add(e.getPlayer().getUniqueId());
		}
	}
	
	@EventHandler
	public void onQuit(PlayerQuitEvent e) {
		if(plugin.getConfig().contains("Data."+e.getPlayer().getUniqueId().toString())) {
			verifiedmembers.remove(e.getPlayer().getUniqueId());
		}
	}
	
	@Override
	public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
		if(!(sender instanceof Player)) {
			sender.sendMessage("Only players can execute this command!");
			return true;
		}
		Player p = (Player) sender;
        if (cmd.getName().equalsIgnoreCase("Verify")) {
			if(plugin.getConfig().contains("Data."+p.getUniqueId().toString())) {
				p.sendMessage("Error! You are already verified!");
				return true;
			}
			if(!uuidCodeMap.containsKey(p.getUniqueId())) {
				p.sendMessage("Not pending verification process!");
				return true;
			}
			if(args.length!=1) {
				p.sendMessage("Usage: /verify [code]");
				return true;
			}
			String verify2code = uuidCodeMap.get(p.getUniqueId());
			if(!verify2code.equals(args[0])) {
				p.sendMessage("Code is invalid! Please try again!");
				return true;
			}
			String discordid = uuidIdMap.get(p.getUniqueId());
			Member target = guild.getMemberById(discordid);
			if(target==null) {
				uuidCodeMap.remove(p.getUniqueId());
				uuidIdMap.remove(p.getUniqueId());
				p.sendMessage(":x: Error! It seems that you are not connected to the discord server!");
				return true;
			} 
			plugin.getConfig().set("Data."+p.getUniqueId(), discordid);
			plugin.saveConfig();
			uuidCodeMap.remove(p.getUniqueId());
			uuidIdMap.remove(p.getUniqueId());
			Role verified = guild.getRolesByName(plugin.getConfig().getString("Role-name"), true).get(0);
			guild.getController().addSingleRoleToMember(target, verified).queue();
			target.getUser().openPrivateChannel().complete().sendMessage(":white_check_mark: Verified! you have been succesfully linked with your minecraft account ("+p.getName()+")!");
			p.sendMessage("Verified! you have been succesfully linked with your discord account ("+target.getUser().getName()+"#"+target.getUser().getDiscriminator()+")!");
			p.sendMessage("You've been verified succesfully :)");
        }
        if (cmd.getName().equalsIgnoreCase("DChat")) {
			if(!plugin.getConfig().contains("Data."+p.getUniqueId().toString())) {
				p.sendMessage("Error! You're not verified!");
				return true;
			}
			if(args.length!=1) {
				p.sendMessage("Usage: /DChat [message]");
				return true;
			}
			StringBuilder builder = new StringBuilder();
			for (int i = 1; i < args.length; i++) {
			    builder.append(args[i]).append(" ");
			}
			String msg = builder.toString();
        }
		return true;
	}
}
