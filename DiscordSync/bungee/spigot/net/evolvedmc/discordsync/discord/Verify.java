package bungee.spigot.net.evolvedmc.discordsync.discord;

import bungee.net.evolvedmc.discordsync.Main;
import net.dv8tion.jda.core.entities.Member;
import net.dv8tion.jda.core.entities.Role;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;

public class Verify extends Command {
	
	public Verify() {
		super("Verify");
	}

	public Main plugin;
	public Discord dc;

	@SuppressWarnings("deprecation")
	@Override
	public void execute(CommandSender sender, String[] args) {
		if(sender instanceof ProxiedPlayer) {
			ProxiedPlayer p = (ProxiedPlayer) sender;
				if(plugin.configuration.contains("Data."+p.getUniqueId().toString())) {
					p.sendMessage("Error! You are already verified!");
					return;
				}
				if(!dc.uuidCodeMap.containsKey(p.getUniqueId())) {
					p.sendMessage("Not pending verification process!");
					return;
				}
				if(args.length!=1) {
					p.sendMessage("Usage: /verify [code]");
					return;
				}
				String verify2code = dc.uuidCodeMap.get(p.getUniqueId());
				if(!verify2code.equals(args[0])) {
					p.sendMessage("Code is invalid! Please try again!");
					return;
				}
				String discordid = dc.uuidIdMap.get(p.getUniqueId());
				Member target = dc.guild.getMemberById(discordid);
				if(target==null) {
					dc.uuidCodeMap.remove(p.getUniqueId());
					dc.uuidIdMap.remove(p.getUniqueId());
					p.sendMessage(":x: Error! It seems that you are not connected to the discord server!");
					return;
				} 
				plugin.configuration.set("Data."+p.getUniqueId(), discordid);
				plugin.saveConfig();
				dc.uuidCodeMap.remove(p.getUniqueId());
				dc.uuidIdMap.remove(p.getUniqueId());
				Role verified = dc.guild.getRolesByName(plugin.configuration.getString("Role-name"), true).get(0);
				dc.guild.getController().addSingleRoleToMember(target, verified).queue();
				target.getUser().openPrivateChannel().complete().sendMessage(":white_check_mark: Verified! you have been succesfully linked with your minecraft account ("+p.getName()+")!");
				p.sendMessage("Verified! you have been succesfully linked with your discord account ("+target.getUser().getName()+"#"+target.getUser().getDiscriminator()+")!");
				p.sendMessage("You've been verified succesfully :)");
	        }
	}
}
