package bungee.spigot.net.evolvedmc.discordsync.discord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import javax.security.auth.login.LoginException;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import bungee.net.evolvedmc.discordsync.Main;
import net.dv8tion.jda.core.AccountType;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.JDABuilder;
import net.dv8tion.jda.core.entities.Guild;
import net.dv8tion.jda.core.events.message.MessageReceivedEvent;
import net.dv8tion.jda.core.hooks.ListenerAdapter;

public class Discord extends ListenerAdapter {
	
	public Main plugin;
	public Guild guild;
	public JDA jda;
	public HashMap<UUID,String>uuidCodeMap;
	public HashMap<UUID,String>uuidIdMap;
	public List<UUID>verifiedmembers;
	
	public Discord(Main main) {
		this.plugin = main;
		startBot();
		jda.addEventListener(this);
		guild = jda.getGuilds().get(0);
		uuidCodeMap = new HashMap<>();
		uuidIdMap = new HashMap<>();
		verifiedmembers = new ArrayList<>();
	}

	@SuppressWarnings("deprecation")
	private void startBot() {
		JDABuilder builder = new JDABuilder(AccountType.BOT);
		String token = plugin.configuration.getString("bot-token");
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
}
