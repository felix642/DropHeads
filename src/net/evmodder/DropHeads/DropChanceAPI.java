package net.evmodder.DropHeads;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Skull;
import org.bukkit.block.data.Rotatable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.permissions.Permissible;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.BlockProjectileSource;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;
import net.evmodder.DropHeads.events.EntityBeheadEvent;
import net.evmodder.EvLib.EvUtils;
import net.evmodder.EvLib.FileIO;
import net.evmodder.EvLib.extras.HeadUtils;
import net.evmodder.EvLib.extras.TellrawUtils;
import net.evmodder.EvLib.extras.TextUtils;
import net.evmodder.EvLib.extras.TellrawUtils.Component;
import net.evmodder.EvLib.extras.TellrawUtils.ListComponent;
import net.evmodder.EvLib.extras.TellrawUtils.SelectorComponent;

public class DropChanceAPI{
	public enum AnnounceMode {GLOBAL, LOCAL, DIRECT, OFF}; // TODO: remove public
	private final AnnounceMode DEFAULT_ANNOUNCE;
	private final EventPriority PRIORITY;
	private enum DropMode {EVENT, SPAWN, PLACE, PLACE_BY_KILLER, PLACE_BY_VICTIM, GIVE};
	private final ArrayList<DropMode> DROP_MODES; // TODO: final HashMap<EntityType, DropMode> mobDropModes

	private final boolean PLAYER_HEADS_ONLY, REPLACE_DEATH_MESSAGE, VANILLA_WSKELE_HANDLING;
	public final double DEFAULT_CHANCE; // TODO: remove public
	public final double LOOTING_ADD, LOOTING_MULT; // TODO: remove public
	private final boolean DEBUG_MODE, LOG_PLAYER_BEHEAD, LOG_MOB_BEHEAD;
	private final String LOG_MOB_FORMAT, LOG_PLAYER_FORMAT;
	private final String[] MSG_BEHEAD, MSH_BEHEAD_BY, MSH_BEHEAD_BY_WITH, MSH_BEHEAD_BY_WITH_NAMED;
	private final boolean USE_PLAYER_DISPLAYNAMES = false;//TODO: move to config, when possible
	private final boolean CROSS_DIMENSIONAL_BROADCAST = true;//TODO: move to config
	private final int LOCAL_RANGE = 200;//TODO: move to config
	private final int JSON_LIMIT;
	private final BlockFace[] possibleHeadRotations = new BlockFace[]{
			BlockFace.NORTH, BlockFace.NORTH_EAST, BlockFace.NORTH_WEST,
			BlockFace.SOUTH, BlockFace.SOUTH_EAST, BlockFace.SOUTH_WEST,
			BlockFace.NORTH_NORTH_EAST, BlockFace.NORTH_NORTH_WEST,
			BlockFace.SOUTH_SOUTH_EAST, BlockFace.SOUTH_SOUTH_WEST
	};

	private final DropHeads pl;
	private final Random rand;
	private final HashSet<Material> headOverwriteBlocks;
	public final Set<Material> mustUseTools; // TODO: remove public
	private final HashSet<EntityType> noLootingEffectMobs;
	public final HashMap<EntityType, Double> mobChances; // TODO: remove public
	private final HashMap<EntityType, HashMap<String, Double>> subtypeMobChances;
	private final HashMap<EntityType, AnnounceMode> mobAnnounceModes;
	public final HashMap<Material, Double> weaponBonuses; // TODO: remove public
	private final HashMap<String, Double> droprateMultiplierPerms;
	private final TreeMap<Long, Double> timeAliveBonuses;
	private final HashSet<UUID> playersToHideDeathMessageFor;

	public DropChanceAPI(){
		pl = DropHeads.getPlugin();
		rand = new Random();
		PLAYER_HEADS_ONLY = pl.getConfig().getBoolean("player-heads-only", false);
		VANILLA_WSKELE_HANDLING = pl.getConfig().getBoolean("vanilla-wither-skeleton-skulls", false);
		LOOTING_ADD = pl.getConfig().getDouble("looting-addition", 0D);
		LOOTING_MULT = pl.getConfig().getDouble("looting-multiplier", pl.getConfig().getDouble("looting-mutliplier", 1.01D));
		if(LOOTING_ADD >= 1) pl.getLogger().warning("looting-addition is set to 1.0 or greater, this means heads will ALWAYS drop when looting is used!");
		if(LOOTING_MULT < 1) pl.getLogger().warning("looting-multiplier is set below 1.0, this means looting will DECREASE the chance of head drops!");
		PRIORITY = JunkUtils.parseEnumOrDefault(pl.getConfig().getString("death-listener-priority", "LOW"), EventPriority.LOW);
		REPLACE_DEATH_MESSAGE = pl.getConfig().getBoolean("behead-announcement-replaces-player-death-message",
				pl.getConfig().getBoolean("behead-announcement-replaces-death-message", true)) && PRIORITY != EventPriority.MONITOR;
		DEBUG_MODE = pl.getConfig().getBoolean("debug-messages", true);
		final boolean ENABLE_LOG = pl.getConfig().getBoolean("log.enable", false);
		LOG_MOB_BEHEAD = ENABLE_LOG && pl.getConfig().getBoolean("log.log-mob-behead", false);
		LOG_PLAYER_BEHEAD = ENABLE_LOG && pl.getConfig().getBoolean("log.log-player-behead", false);
		LOG_MOB_FORMAT = LOG_MOB_BEHEAD ? pl.getConfig().getString("log.log-mob-behead-format",
				"${TIMESTAMP},mob decapitated,${VICTIM},${KILLER},${ITEM}") : null;
		LOG_PLAYER_FORMAT = LOG_PLAYER_BEHEAD ? pl.getConfig().getString("log.log-player-behead-format",
				"${TIMESTAMP},player decapitated,${VICTIM},${KILLER},${ITEM}") : null;

		JSON_LIMIT = pl.getConfig().getInt("message-json-limit", 15000);
		MSG_BEHEAD = JunkUtils.parseStringOrStringList(pl.getConfig(), "message-beheaded", "${VICTIM} was beheaded");
		MSH_BEHEAD_BY = JunkUtils.parseStringOrStringList(pl.getConfig(), "message-beheaded-by-entity", "${VICTIM}&r was beheaded by ${KILLER}&r");
		MSH_BEHEAD_BY_WITH = JunkUtils.parseStringOrStringList(pl.getConfig(), "message-beheaded-by-entity-with-item",
				"${VICTIM}&r was beheaded by ${KILLER}&r using ${ITEM}&r");
		MSH_BEHEAD_BY_WITH_NAMED = JunkUtils.parseStringOrStringList(pl.getConfig(), "message-beheaded-by-entity-with-item-named",
				"${VICTIM}&r was beheaded by ${KILLER}&r using ${ITEM}&r");

//		USE_PLAYER_DISPLAYNAMES = pl.getConfig().getBoolean("message-beheaded-use-player-displaynames", true);//TODO

		DROP_MODES = new ArrayList<>();
		if(pl.getConfig().contains("head-item-drop-mode"))
		for(String dropModeName : pl.getConfig().getStringList("head-item-drop-mode")){
			try{DROP_MODES.add(DropMode.valueOf(dropModeName.toUpperCase()));}
			catch(IllegalArgumentException ex){pl.getLogger().severe("Unknown head DropMode: "+dropModeName);}
		}
		if(DROP_MODES.isEmpty()) DROP_MODES.add(DropMode.EVENT);

		headOverwriteBlocks = new HashSet<>();
		if(pl.getConfig().contains("head-place-overwrite-blocks"))
		for(String matName : pl.getConfig().getStringList("head-place-overwrite-blocks")){
			try{headOverwriteBlocks.add(Material.valueOf(matName.toUpperCase()));}
			catch(IllegalArgumentException ex){pl.getLogger().severe("Unknown material in 'head-place-overwrite-blocks': "+matName);}
		}
		else headOverwriteBlocks.add(Material.AIR);

		mobAnnounceModes = new HashMap<>();
		mobAnnounceModes.put(EntityType.UNKNOWN, JunkUtils.parseAnnounceMode(
				pl.getConfig().getString("behead-announcement-mobs", "LOCAL"), AnnounceMode.LOCAL));
		mobAnnounceModes.put(EntityType.PLAYER, JunkUtils.parseAnnounceMode(
				pl.getConfig().getString("behead-announcement-players", "GLOBAL"), AnnounceMode.GLOBAL));
		ConfigurationSection announceModes = pl.getConfig().getConfigurationSection("behead-announcement");
		AnnounceMode tempDefaultAnnounce = mobAnnounceModes.get(EntityType.UNKNOWN);
		if(announceModes != null) for(String mobName : announceModes.getKeys(false)){
			try{
				EntityType eType = EntityType.valueOf(mobName.toUpperCase().replace("DEFAULT", "UNKNOWN"));
				mobAnnounceModes.put(eType, JunkUtils.parseAnnounceMode(announceModes.getString(mobName), tempDefaultAnnounce));
			}
			catch(IllegalArgumentException ex){pl.getLogger().severe("Unknown entity type in 'behead-announce': "+mobName);}
		}
		DEFAULT_ANNOUNCE = mobAnnounceModes.get(EntityType.UNKNOWN);

		if(pl.getConfig().getBoolean("must-use-axe")){
			mustUseTools = Arrays.stream(Material.values()).filter(mat -> mat.name().endsWith("_AXE")).collect(Collectors.toSet());
		}
		else{
			(mustUseTools = pl.getConfig().getStringList("must-use").stream().map(toolName -> {
				if(toolName.isEmpty()) return null;
				Material mat = Material.getMaterial(toolName.toUpperCase());
				if(mat == null) pl.getLogger().warning("Unknown Tool \""+toolName+"\"!");
				return mat;
			}).collect(Collectors.toSet())).remove(null);
		}

		weaponBonuses = new HashMap<Material, Double>();
		ConfigurationSection specificToolModifiers = pl.getConfig().getConfigurationSection("specific-tool-modifiers");
		if(specificToolModifiers != null) for(String toolName : specificToolModifiers.getKeys(false)){
			Material mat = Material.getMaterial(toolName.toUpperCase());
			if(mat != null) weaponBonuses.put(mat, specificToolModifiers.getDouble(toolName));
		}

		timeAliveBonuses = new TreeMap<>();
		timeAliveBonuses.put(-1L, 0D); // Ensure there is always a lower entry, and it defaults to 0
		ConfigurationSection specificTimeAliveModifiers = pl.getConfig().getConfigurationSection("time-alive-modifiers");
		if(specificTimeAliveModifiers != null) for(String formattedTime : specificTimeAliveModifiers.getKeys(false)){
			try{
				long time = TextUtils.parseTime(formattedTime);
				timeAliveBonuses.put(time, specificTimeAliveModifiers.getDouble(formattedTime));
			}
			catch(NumberFormatException ex){pl.getLogger().severe("Error parsing time string: \""+formattedTime+'"');}
		}

		droprateMultiplierPerms = new HashMap<String, Double>();
		ConfigurationSection customDropratePerms = pl.getConfig().getConfigurationSection("custom-droprate-multiplier-permissions");
		if(customDropratePerms != null) for(String perm : customDropratePerms.getKeys(/*recursive=*/true)){
			//TODO: This will generate ["dropheads", "dropheads.group", "dropheads.group.2x", ...] because of how Bukkit/YML works
			try{droprateMultiplierPerms.put(perm, customDropratePerms.getDouble(perm, 1D));}
			catch(NumberFormatException ex){pl.getLogger().severe("Error parsing droprate multiplier for perm: \""+perm+'"');}
		}

		//Load individual mobs' drop chances
		mobChances = new HashMap<EntityType, Double>();
		subtypeMobChances = new HashMap<EntityType, HashMap<String, Double>>();
		noLootingEffectMobs = new HashSet<EntityType>();
		//double chanceForUnknown = 0D;
		if(PLAYER_HEADS_ONLY){
			DEFAULT_CHANCE = 0D;
			String defaultChances = FileIO.loadResource(pl, "head-drop-rates.txt");
			String chances = FileIO.loadFile("head-drop-rates.txt", defaultChances);
			for(final String line : chances.split("\n")){
				final String[] parts = line.split(":");
				if(parts.length < 2 || !parts[0].trim().toUpperCase().equals("PLAYER")) continue;
				final String value = parts[1].trim();
				try{
					double dropChance = Double.parseDouble(value);
					if(dropChance < 0D || dropChance > 1D){
						pl.getLogger().warning("Invalid value: "+value);
						pl.getLogger().warning("Drop chance should be a decimal between 0 and 1");
						if(dropChance > 1D && dropChance <= 100D) dropChance /= 100D;
						else continue;
					}
					mobChances.put(EntityType.PLAYER, dropChance);
				}
				catch(NumberFormatException ex){pl.getLogger().severe("Invalid value: "+value);}
			}
		}
		else{
			String defaultChances = FileIO.loadResource(pl, "head-drop-rates.txt");
			HashSet<String> defaultConfigMobs = new HashSet<>();
			for(String line2 : defaultChances.split("\n")){
				String[] parts2 = line2.replace(" ", "").replace("\t", "").toUpperCase().split(":");
				if(parts2.length < 2) continue;
				defaultConfigMobs.add(parts2[0]);
			}
			String chances = FileIO.loadFile("head-drop-rates.txt", defaultChances);
			for(String line : chances.split("\n")){
				String[] parts = line.replace(" ", "").replace("\t", "").toUpperCase().split(":");
				if(parts.length < 2) continue;
				int dataTagSep = parts[0].indexOf('|');
				String eName = dataTagSep == -1 ? parts[0] : parts[0].substring(0, dataTagSep);
				try{
					double dropChance = Double.parseDouble(parts[1]);
					if(dropChance < 0D || dropChance > 1D){
						pl.getLogger().warning("Invalid value: "+parts[1]);
						pl.getLogger().warning("Drop chance should be a decimal between 0 and 1");
						if(dropChance > 1D && dropChance <= 100D) dropChance /= 100D;
						else continue;
					}
					EntityType eType = EntityType.valueOf(eName.replace("DEFAULT", "UNKNOWN"));
					if(parts.length > 2 && parts[2].equals("NOLOOTING")) noLootingEffectMobs.add(eType);
					if(dataTagSep == -1) mobChances.put(eType, dropChance);
					else if(pl.getAPI().textureExists(parts[0])){
						HashMap<String, Double> eTypeChances = subtypeMobChances.getOrDefault(eType, new HashMap<String, Double>());
						eTypeChances.put(parts[0], dropChance);
						subtypeMobChances.put(eType, eTypeChances);
					}
					else{
						pl.getLogger().severe("Unknown entity sub-type: "+parts[0]);
					}
				}
				catch(NumberFormatException ex){pl.getLogger().severe("Invalid value: "+parts[1]);}
				catch(IllegalArgumentException ex){
					// Only throw an error for mobs that aren't defined in the default config (which may be from future/past versions)
					if(!defaultConfigMobs.contains(eName)) pl.getLogger().severe("Unknown entity type: "+eName);
				}
			}
			if(VANILLA_WSKELE_HANDLING && mobChances.getOrDefault(EntityType.WITHER_SKELETON, 0.025D) != 0.025D){
				pl.getLogger().warning("Wither Skeleton Skull drop chance has been modified in 'head-drop-rates.txt', "
						+ "but this value will be ignored because 'vanilla-wither-skeleton-skulls' is set to true.");
			}
			// No need storing 0-chance mobs if the default drop chance is 0
			DEFAULT_CHANCE = mobChances.getOrDefault(EntityType.UNKNOWN, 0D);
			if(DEFAULT_CHANCE == 0D) mobChances.entrySet().removeIf(entry -> entry.getValue() == 0D);
		}  // if(!PLAYER_HEADS_ONLY)

		playersToHideDeathMessageFor = new HashSet<UUID>();
		if(REPLACE_DEATH_MESSAGE){
			EventPriority replacePriority = (PRIORITY == EventPriority.HIGHEST ? EventPriority.MONITOR : EventPriority.HIGHEST);
			pl.getServer().getPluginManager().registerEvent(PlayerDeathEvent.class, new Listener(){}, replacePriority, new EventExecutor(){
				@Override public void execute(Listener listener, Event originalEvent){
					if(originalEvent instanceof PlayerDeathEvent == false) return;
					PlayerDeathEvent evt = (PlayerDeathEvent) originalEvent;
					if(playersToHideDeathMessageFor.remove(evt.getEntity().getUniqueId())) evt.setDeathMessage("");
				}
			}, pl);
		}

		// Dynamically add all the children perms of "dropheads.alywaysbehead.<entity>"
		Permission alwaysBeheadPerm = pl.getServer().getPluginManager().getPermission("dropheads.alwaysbehead");
		if(alwaysBeheadPerm != null) try{
			for(EntityType entity : EntityType.values()){
				Permission alwaysBeheadPermForEntity = new Permission(
						alwaysBeheadPerm.getName()+"."+entity.name().toLowerCase(),
						"This entity will get a head 100% of the time when killing a "+entity.name().toLowerCase(),
						PermissionDefault.FALSE);
				alwaysBeheadPermForEntity.addParent(alwaysBeheadPerm, true);
				pl.getServer().getPluginManager().addPermission(alwaysBeheadPermForEntity);
			}
			alwaysBeheadPerm.recalculatePermissibles();
		}
		catch(IllegalArgumentException ex){/*The permissions are already defined; perhaps this is just a plugin or server reload*/}
	}

	public double getRawDropChance(String textureKey){
		int keyDataTagIdx = textureKey.indexOf('|');
		final String entityName = keyDataTagIdx == -1 ? textureKey : textureKey.substring(0, keyDataTagIdx);
		EntityType eType;
		try{eType = EntityType.valueOf(entityName.toUpperCase());}
		catch(IllegalArgumentException ex){return DEFAULT_CHANCE;}
		final HashMap<String, Double> eSubtypeChances = subtypeMobChances.get(eType);
		if(eSubtypeChances != null){
			keyDataTagIdx = textureKey.lastIndexOf('|');
			Double subtypeChance = null;
			while(keyDataTagIdx != -1 && (subtypeChance=eSubtypeChances.get(textureKey)) == null){
				textureKey = textureKey.substring(0, keyDataTagIdx);
				keyDataTagIdx = textureKey.lastIndexOf('|');
			}
			if(subtypeChance != null) return subtypeChance;
		}
		return mobChances.getOrDefault(eType, DEFAULT_CHANCE);
	}
	public double getRawDropChance(Entity e){
		HashMap<String, Double> eSubtypeChances = subtypeMobChances.get(e.getType());
		if(eSubtypeChances != null){
			String textureKey = TextureKeyLookup.getTextureKey(e);
			int keyDataTagIdx = textureKey.lastIndexOf('|');
			Double subtypeChance = null;
			while(keyDataTagIdx != -1 && (subtypeChance=eSubtypeChances.get(textureKey)) == null){
				textureKey = textureKey.substring(0, keyDataTagIdx);
				keyDataTagIdx = textureKey.lastIndexOf('|');
			}
			if(subtypeChance != null) return subtypeChance;
		}
		return mobChances.getOrDefault(e.getType(), DEFAULT_CHANCE);
	}
	public double getTimeAliveBonus(Entity e){
		long millisecondsLived = e.getTicksLived()*50L;
		return timeAliveBonuses.floorEntry(millisecondsLived).getValue();
	}
	public double getPermsBasedDropRateModifier(Permissible killer){
		if(killer == null) return 1D;
		return droprateMultiplierPerms.entrySet().stream().parallel()
				.filter(e -> killer.hasPermission(e.getKey()))
				.map(e -> e.getValue())
				.reduce(1D, (a, b) -> a * b);
	}
	
	public void dropHeadItem(ItemStack headItem, Entity entity, Entity killer, Event evt){
		for(DropMode mode : DROP_MODES){
			if(headItem == null) break;
			switch(mode){
				case EVENT:
					if(evt instanceof EntityDeathEvent) ((EntityDeathEvent)evt).getDrops().add(headItem);
					else entity.getWorld().dropItemNaturally(entity.getLocation(), headItem);
					headItem = null;
					break;
				case PLACE_BY_KILLER:
				case PLACE_BY_VICTIM:
				case PLACE:
					Block headBlock = EvUtils.getClosestBlock(entity.getLocation(), 5, b -> headOverwriteBlocks.contains(b.getType())).getBlock();
					BlockState state = headBlock.getState();
					state.setType(headItem.getType());
					Vector facingVector = entity.getLocation().getDirection(); facingVector.setY(0);  // loc.setPitch(0F)
					Rotatable data = (Rotatable)headBlock.getBlockData();
					data.setRotation(JunkUtils.getClosestBlockFace(facingVector, possibleHeadRotations).getOppositeFace());
					state.setBlockData(data);
					if(headItem.getType() == Material.PLAYER_HEAD){
						HeadUtils.setGameProfile((Skull)state, HeadUtils.getGameProfile((SkullMeta)headItem.getItemMeta()));
					}
					if(mode != DropMode.PLACE){
						Entity entityToCheck = (killer == null ||
								(mode == DropMode.PLACE_BY_VICTIM && (entity instanceof Player || killer instanceof Player == false)))
								? entity : killer;
						Event testPermsEvent;
						if(entityToCheck instanceof Player){
							testPermsEvent = new BlockPlaceEvent(headBlock, state,
								headBlock.getRelative(BlockFace.DOWN), headItem, (Player)entityToCheck, /*canBuild=*/true, EquipmentSlot.HAND);
						}
						else{
							testPermsEvent = new EntityBlockFormEvent(entityToCheck, headBlock, state);
						}
						pl.getServer().getPluginManager().callEvent(testPermsEvent);
						if(((Cancellable)testPermsEvent).isCancelled()){
							pl.getLogger().fine("Head placement failed, permission-lacking player: "+entityToCheck.getName());
							break;
						}
					}
					state.update(/*force=*/true);
					headItem = null;
					break;
				case GIVE:
					headItem = JunkUtils.giveItemToEntity(killer, headItem);
					break;
				case SPAWN:
					entity.getWorld().dropItemNaturally(entity.getLocation(), headItem);
					headItem = null;
					break;
			}//switch(mode)
		}//for(DROP_MODES)
	}

	private Component getVictimComponent(Entity entity){
		return new SelectorComponent(entity.getUniqueId(), USE_PLAYER_DISPLAYNAMES);
	}
	private Component getKillerComponent(Entity killer){
		if(killer == null) return null;
		if(killer instanceof Projectile){
			ProjectileSource shooter = ((Projectile)killer).getShooter();
			if(shooter instanceof Entity) return new SelectorComponent(((Entity)shooter).getUniqueId(), USE_PLAYER_DISPLAYNAMES);
			else if(shooter instanceof BlockProjectileSource){
				return TellrawUtils.getLocalizedDisplayName(((BlockProjectileSource)shooter).getBlock().getState());
			}
			// In theory should never happen:
			else pl.getLogger().warning("Unrecognized projectile source: "+shooter.getClass().getName());
		}
		return new SelectorComponent(killer.getUniqueId(), USE_PLAYER_DISPLAYNAMES);
	}
	private Component getWeaponComponent(Entity killer, ItemStack weapon){
		if(weapon != null) return JunkUtils.getMurderItemComponent(weapon, JSON_LIMIT);
		if(killer != null && killer instanceof Projectile) return new SelectorComponent(killer.getUniqueId(), USE_PLAYER_DISPLAYNAMES);
		return null;
	}
	public ListComponent getBeheadMessage(Entity entity, Entity killer, ItemStack weapon){
		ListComponent message = new ListComponent();
		Component killerComp = getKillerComponent(killer);
		Component itemComp = getWeaponComponent(killer, weapon);
		if(killerComp != null){
			if(itemComp != null){
				boolean hasCustomName = weapon.hasItemMeta() && weapon.getItemMeta().hasDisplayName();
				message.addComponent(hasCustomName
						? MSH_BEHEAD_BY_WITH_NAMED[rand.nextInt(MSH_BEHEAD_BY_WITH_NAMED.length)]
						: MSH_BEHEAD_BY_WITH[rand.nextInt(MSH_BEHEAD_BY_WITH.length)]);
				message.replaceRawDisplayTextWithComponent("${ITEM}", itemComp);
			}
			else message.addComponent(MSH_BEHEAD_BY[rand.nextInt(MSH_BEHEAD_BY.length)]);
			//if(USE_PLAYER_DISPLAYNAMES) message.replaceRawTextWithComponent("${KILLER}", ...);
			message.replaceRawDisplayTextWithComponent("${KILLER}", killerComp);
		}
		else message.addComponent(MSG_BEHEAD[rand.nextInt(MSG_BEHEAD.length)]);
		message.replaceRawDisplayTextWithComponent("${VICTIM}", getVictimComponent(entity));
		return message;
	}

	private void sendTellraw(String target, String message){
		pl.getServer().dispatchCommand(pl.getServer().getConsoleSender(), "minecraft:tellraw "+target+" "+message);
	}
	public void announceHeadDrop(Component message, Entity entity, Entity killer, Event evt){
		if(!message.toPlainText().replaceAll(" ", "").isEmpty()){
			if(DEBUG_MODE) pl.getLogger().info(/*"Tellraw message: "+*/message.toPlainText());

			AnnounceMode mode = mobAnnounceModes.getOrDefault(entity.getType(), DEFAULT_ANNOUNCE);
			if(mode != AnnounceMode.OFF && mode != AnnounceMode.DIRECT && (
				killer.hasPermission("dropheads.silentbehead") ||
				(killer.hasPermission("dropheads.silentbehead.invisible")
					&& killer instanceof LivingEntity && ((LivingEntity)killer).hasPotionEffect(PotionEffectType.INVISIBILITY)
				) ||
				(killer.hasPermission("dropheads.silentbehead.vanished")
					&& killer instanceof Player && JunkUtils.isVanished((Player)killer)
				)
			)) mode = AnnounceMode.DIRECT;
			switch(mode){
				case GLOBAL:
					if(entity instanceof Player && REPLACE_DEATH_MESSAGE && evt != null && PRIORITY != EventPriority.MONITOR){
						((PlayerDeathEvent)evt).setDeathMessage(message.toPlainText());  // is cleared later
					}
					sendTellraw("@a", message.toString());
					break;
				case LOCAL:
					for(Player p : EvUtils.getNearbyPlayers(entity.getLocation(), LOCAL_RANGE, CROSS_DIMENSIONAL_BROADCAST)){
						sendTellraw(p.getName(), message.toString());
					}
					break;
				case DIRECT:
					if(killer instanceof Player) sendTellraw(killer.getName(), message.toString());
					break;
				case OFF:
					break;
			}
		}
	}

	public void logHeadDrop(Entity entity, Entity killer, ItemStack weapon){
		pl.writeToLogFile(
				(entity instanceof Player ? LOG_PLAYER_FORMAT : LOG_MOB_FORMAT)
				.replaceAll("(?i)\\$\\{(VICTIM|BEHEADED)\\}", getVictimComponent(entity).toPlainText())
				.replaceAll("(?i)\\$\\{(KILLER|BEHEADER)\\}", killer == null ? "" : getKillerComponent(killer).toPlainText())
				.replaceAll("(?i)\\$\\{(ITEM|WEAPON)\\}", weapon == null ? "" : getWeaponComponent(killer, weapon).toPlainText())
				.replaceAll("(?i)\\$\\{TIMESTAMP\\}", ""+System.currentTimeMillis())
		);
	}

	public void triggerHeadDropEvent(Entity entity, Entity killer, Event evt, ItemStack weapon, Component beheadMessage){
		ItemStack headItem = pl.getAPI().getHead(entity);
		EntityBeheadEvent beheadEvent = new EntityBeheadEvent(entity, killer, evt, headItem);
		pl.getServer().getPluginManager().callEvent(beheadEvent);
		if(beheadEvent.isCancelled()) return;

		if(REPLACE_DEATH_MESSAGE && !playersToHideDeathMessageFor.add(entity.getUniqueId())){
			pl.getLogger().warning("Player behead triggered twice: "+evt.getEventName());
			return;
		}

		dropHeadItem(headItem, entity, killer, evt);
		if(weapon.getType() == Material.AIR) weapon = null;
		announceHeadDrop(beheadMessage, entity, killer, evt);
		if(entity instanceof Player ? LOG_PLAYER_BEHEAD : LOG_MOB_BEHEAD) logHeadDrop(entity, killer, weapon);
	}
	public void triggerHeadDropEvent(Entity entity, Entity killer, Event evt, ItemStack weapon){
		triggerHeadDropEvent(entity, killer, evt, weapon, /*TODO: lazy eval of getBeheadMessage?*/getBeheadMessage(entity, killer, weapon));
	}
}