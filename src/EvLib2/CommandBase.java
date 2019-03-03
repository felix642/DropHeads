package EvLib2;

import org.bukkit.command.CommandExecutor;
import EvLib2.EvPlugin;

public abstract class CommandBase implements CommandExecutor {
	protected EvPlugin plugin;
	
	public CommandBase(EvPlugin p) {
		plugin = p;
		String commandName = getClass().getSimpleName().substring(7).toLowerCase();
		plugin.getCommand(commandName).setExecutor(this);
	}
}