package com.scrollboxinfo;

import com.google.inject.Provides;
import javax.inject.Inject;

import com.scrollboxinfo.data.ClueCountStorage;
import com.scrollboxinfo.overlay.ClueWidgetItemOverlay;
import com.scrollboxinfo.overlay.StackLimitInfoBox;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.api.InventoryID;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@PluginDescriptor(
	name = "Scroll Box Info",
	description = "Keep track of how many clues you have, your current clue stack limit, and how many clues until next stack limit increase",
	tags = {"scroll", "watson", "case"}
)
public class ScrollBoxInfoPlugin extends Plugin
{
	@Inject
	private Client client;
	@Inject
	private ScrollBoxInfoConfig config;
	@Inject
	private QuestChecker questChecker;
	@Inject
	private ClueCountStorage clueCountStorage;
	@Inject
	private ClueCounter clueCounter;
	@Inject
	private ClueWidgetItemOverlay clueWidgetItemOverlay;
	@Inject
	private OverlayManager overlayManager;
	@Inject
	private ConfigManager configManager;
	@Inject
	private ClueUtils clueUtils;
	@Inject
	private InfoBoxManager infoBoxManager;
	@Inject
	private ItemManager itemManager;
	private StackLimitInfoBox stackInfoBox;
	@Inject
	private ClientThread clientThread;

	private boolean bankWasOpenLastTick = false;
	private boolean bankIsOpen = false;
	private boolean depositBoxIsOpen = false;
	private boolean depositBoxWasOpenLastTick = false;
	private long lastAccountHash = -1;
	private String lastWorldBucket = "";
	private static final String PLUGIN_VERSION = "1.2.0";
	private static final String CHANGELOG_RESOURCE = "/changelog.md";
	private boolean changelogShownThisSession = false;
	private static final String IGNORE_CONFIG_KEY = "lastSeenChangelogVersion";
	private final Map<ClueTier, Integer> previousInventoryScrollBoxCount = new HashMap<>();
	private final Map<ClueTier, Boolean> previousInventoryClueScrollState = new HashMap<>();
	private final Map<ClueTier, Boolean> previousInventoryChallengeScrollState = new HashMap<>();
	private final Map<ClueTier, Integer> previousBankScrollBoxCount = new HashMap<>();
	private final Map<ClueTier, Boolean> previousBankClueScrollState = new HashMap<>();
	private final Map<ClueTier, Boolean> previousBankChallengeScrollState = new HashMap<>();
	private final Map<ClueTier, Integer> previousTotalClueCounts = new HashMap<>();
	private final Map<ClueTier, StackLimitInfoBox> stackInfoBoxes = new HashMap<>();
	private final Map<ClueTier, Integer> recentlyDropped = new EnumMap<>(ClueTier.class);
	private final Map<ClueTier, Integer> recentlyPickedUp = new EnumMap<>(ClueTier.class);
	private static final Set<WorldType> SPECIAL_WORLD_TYPES = EnumSet.of(
			WorldType.QUEST_SPEEDRUNNING,
			WorldType.TOURNAMENT_WORLD,
			WorldType.SEASONAL,
			WorldType.DEADMAN
	);

	private void checkAndDisplayInfobox(ClueTier tier, int count, int cap) {
		if (!config.showFullStackInfobox() || !isTierInfoboxEnabled(tier)) {
			StackLimitInfoBox box = stackInfoBoxes.remove(tier);
			if (box != null) {
				infoBoxManager.removeInfoBox(box);
			}
			return;
		}

		StackLimitInfoBox box = stackInfoBoxes.get(tier);

		if (count >= cap) {
			if (box == null) {
				int clueItemId = clueUtils.getClueItemId(tier);
				BufferedImage image = itemManager.getImage(clueItemId);
				box = new StackLimitInfoBox(image, this, tier, count);
				infoBoxManager.addInfoBox(box);
				stackInfoBoxes.put(tier, box);
			}
		} else if (box != null) {
			infoBoxManager.removeInfoBox(box);
			stackInfoBoxes.remove(tier);
		}
	}

	private boolean isTierInfoboxEnabled(ClueTier tier) {
		switch (tier) {
			case BEGINNER:
				return config.showBeginnerInfobox();
			case EASY:
				return config.showEasyInfobox();
			case MEDIUM:
				return config.showMediumInfobox();
			case HARD:
				return config.showHardInfobox();
			case ELITE:
				return config.showEliteInfobox();
			case MASTER:
				return config.showMasterInfobox();
			default:
				return true;
		}
	}

	private void sendTotalClueCountsChatMessage()
	{
		for (ClueTier tier : ClueTier.values())
		{
			int current = clueCounter.getClueCounts(tier);
			int cap = StackLimitCalculator.getStackLimit(tier, client);

			String color = (current == cap) ? "ff0000" : "006600"; // red : green
			String message = String.format(
					"<col=%s>%s clue count: %d/%d",
					color,
					ClueUtils.formatTierName(tier),
					current,
					cap
			);

			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null);
		}
	}

	private void removeAllInfoboxes()
	{
		if (stackInfoBoxes != null && !stackInfoBoxes.isEmpty())
		{
			for (StackLimitInfoBox box : stackInfoBoxes.values())
			{
				if (box != null)
				{
					infoBoxManager.removeInfoBox(box);
				}
			}
			stackInfoBoxes.clear();
		}
	}

	private String getWorldBucket()
	{
		EnumSet<WorldType> worldTypes = client.getWorldType();
		for (WorldType wt : SPECIAL_WORLD_TYPES)
		{
			if (worldTypes.contains(wt))
			{
				return wt.name();
			}
		}
		return "SHARED";
	}

	private String totalKey(ClueTier tier)
	{
		return "total_" + getWorldBucket() + "_" + tier.name();
	}

	private String bankKey(ClueTier tier)
	{
		return "bank_" + getWorldBucket() + "_" + tier.name();
	}

	private String bankFlagKey(ClueTier tier)
	{
		return "hasScrollInBank_" + getWorldBucket() + "_" + tier.name();
	}

	private void saveTotalCountToConfig(ClueTier tier, int total)
	{
		configManager.setRSProfileConfiguration("scrollboxinfo", totalKey(tier), total);
		log.debug("Saving total count key = {} -> {}", totalKey(tier), total);
	}

	private void loadTotalCountsFromConfig()
	{
		for (ClueTier tier : ClueTier.values())
		{
			Integer stored = configManager.getRSProfileConfiguration("scrollboxinfo", totalKey(tier), Integer.class);
			if (stored != null)
			{
				clueCountStorage.setCount(tier, stored);
				log.debug("Loading total count key = {} -> {}", totalKey(tier), stored);
			}
			else
			{
				clueCountStorage.setCount(tier, 0);
				log.debug("Couldn't load total count. Config setting not found. Setting total count to 0.");
			}
		}
	}

	private void saveBankCountToConfig(ClueTier tier, int bankCount)
	{
		configManager.setRSProfileConfiguration("scrollboxinfo", bankKey(tier), bankCount);
		log.debug("Saving bank count key = {} -> {}", bankKey(tier), bankCount);
	}

	private void loadBankCountsFromConfig()
	{
		for (ClueTier tier : ClueTier.values())
		{
			Integer stored = configManager.getRSProfileConfiguration("scrollboxinfo", bankKey(tier), Integer.class);
			if (stored != null)
			{
				clueCountStorage.setBankCount(tier, stored);
				log.debug("Loading bank count key = {} -> {}", bankKey(tier), stored);
			}
			else
			{
				clueCountStorage.setBankCount(tier, 0);
				log.debug("Couldn't load bank count. Config setting not found. Setting total count to 0.");
			}
		}
	}

	private void saveBankScrollFlagToConfig(ClueTier tier, boolean value)
	{
		configManager.setRSProfileConfiguration("scrollboxinfo", bankFlagKey(tier), value);
		log.debug("Saving bank scroll flag key = {} -> {}", bankFlagKey(tier), value);
	}

	private boolean loadBankScrollFlagFromConfig(ClueTier tier)
	{
		Boolean stored = configManager.getRSProfileConfiguration("scrollboxinfo", bankFlagKey(tier), Boolean.class);
		log.debug("Loading bank scroll flag key = {} -> {}", bankFlagKey(tier), stored);
		return Boolean.TRUE.equals(stored);
	}

	private void showChangelogIfNeeded()
	{
		if (changelogShownThisSession)
		{
			config.setLastSeenChangelogVersion(PLUGIN_VERSION);
			return;
		}

		String lastSeen = config.lastSeenChangelogVersion();

		if (lastSeen.isEmpty())
		{
			changelogShownThisSession = true;
			config.setLastSeenChangelogVersion(PLUGIN_VERSION);
			return;
		}

		if (PLUGIN_VERSION.equals(lastSeen))
		{
			changelogShownThisSession = true;
			return;
		}

		Map<String, List<String>> changelog = loadChangelogFromResource();
		if (changelog.isEmpty())
		{
			changelogShownThisSession = true;
			config.setLastSeenChangelogVersion(PLUGIN_VERSION);
			return;
		}

		List<String> toShowVersions = new ArrayList<>();
		for (String version : changelog.keySet())
		{
			if (version.equals(lastSeen))
			{
				break;
			}
			toShowVersions.add(version);
		}

		if (toShowVersions.isEmpty())
		{
			changelogShownThisSession = true;
			config.setLastSeenChangelogVersion(PLUGIN_VERSION);
			return;
		}

		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "<col=0051c9>Scroll Box Info Updated", null);
		for (String version : toShowVersions)
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "<col=0051c9>v" + version + ":", null);
			List<String> lines = changelog.get(version);
			for (String line : lines)
			{
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "  <col=0051c9>" + line, null);
			}
		}

		changelogShownThisSession = true;
		config.setLastSeenChangelogVersion(PLUGIN_VERSION);
	}

	private Map<String, List<String>> loadChangelogFromResource()
	{
		Map<String, List<String>> out = new LinkedHashMap<>();
		try (InputStream is = getClass().getResourceAsStream(CHANGELOG_RESOURCE))
		{
			if (is == null)
			{
				log.debug("Changelog resource not found at {}", CHANGELOG_RESOURCE);
				return Collections.emptyMap();
			}

			BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
			String line;
			String currentVersion = null;
			List<String> currentLines = null;

			while ((line = r.readLine()) != null)
			{
				line = line.trim();
				if (line.startsWith("## "))
				{
					if (currentVersion != null && currentLines != null)
					{
						out.put(currentVersion, currentLines);
					}
					currentVersion = line.substring(3).trim();
					currentLines = new ArrayList<>();
				}
				else
				{
					if (currentVersion != null)
					{
						if (!line.isEmpty())
						{
							currentLines.add(line);
						}
					}
				}
			}

			if (currentVersion != null && currentLines != null)
			{
				out.put(currentVersion, currentLines);
			}
		}
		catch (IOException ex)
		{
			log.warn("Failed to read changelog resource", ex);
			return Collections.emptyMap();
		}

		return out;
	}

	private void saveCurrentSettingsForWorld(long accountHash, String worldBucket)
	{
		saveCurrentSettingsForWorld(accountHash, worldBucket, null);
	}

	private void saveCurrentSettingsForWorld(long accountHash, String worldBucket, String changedKey)
	{
		if (accountHash == -1 || worldBucket == null || worldBucket.isEmpty())
		{
			log.debug("saveCurrentSettingsForWorld called but no valid account/world state (accountHash={}, worldBucket='{}') - skipping save",
					accountHash, worldBucket);
			return;
		}

		final String group = "scrollboxinfo";
		final String prefix = group + ".";

		// If a single key changed, only save that one (unless it's the ignored key)
		if (changedKey != null)
		{
			if (IGNORE_CONFIG_KEY.equals(changedKey))
			{
				log.debug("Skipping RS-profile save for ignored key '{}'", changedKey);
				return;
			}

			String value = configManager.getConfiguration(group, changedKey);
			if (value == null)
			{
				log.debug("SKIP saving changed key '{}': no stored value (group='{}')", changedKey, group);
				return;
			}

			final String compositeKey = "settings_" + worldBucket + "_" + changedKey;
			configManager.setRSProfileConfiguration(group, compositeKey, value);
			log.debug("Saved single setting [{}] = '{}' -> stored as RS-profile key '{}'", changedKey, value, compositeKey);
			return;
		}

		// Save all keys (old behavior) except the ignored one
		List<String> allFullKeys = configManager.getConfigurationKeys(group);
		if (allFullKeys == null || allFullKeys.isEmpty())
		{
			log.debug("No config keys found for group '{}' to snapshot for worldBucket={}", group, worldBucket);
			return;
		}

		int saved = 0;
		log.debug("=== BEGIN SAVING SETTINGS for worldBucket='{}' (accountHash={}) ===", worldBucket, accountHash);

		for (String fullKey : allFullKeys)
		{
			String key = fullKey;
			if (fullKey.startsWith(prefix))
			{
				key = fullKey.substring(prefix.length());
			}

			if (IGNORE_CONFIG_KEY.equals(key))
			{
				log.debug("Skipping ignored key '{}' while saving snapshot", key);
				continue;
			}

			String value = configManager.getConfiguration(group, key);
			if (value == null)
			{
				log.debug("SKIP: '{}' has no stored value.", key);
				continue;
			}

			String compositeKey = "settings_" + worldBucket + "_" + key;
			configManager.setRSProfileConfiguration(group, compositeKey, value);
			log.debug("Saved [{}] = '{}' -> stored as '{}'", key, value, compositeKey);
			saved++;
		}

		log.debug("=== END SAVING SETTINGS for worldBucket='{}' - {} keys saved. ===", worldBucket, saved);
	}

	private void loadSettingsForWorld(long accountHash, String worldBucket)
	{
		List<String> allFullKeys = configManager.getConfigurationKeys("scrollboxinfo");
		if (allFullKeys == null || allFullKeys.isEmpty())
		{
			log.debug("No config keys found for group scrollboxinfo to restore for worldBucket={}", worldBucket);
			return;
		}

		final String prefix = "scrollboxinfo.";
		int restored = 0;

		log.debug("=== BEGIN LOADING SETTINGS for worldBucket='{}' (accountHash={}) ===", worldBucket, accountHash);

		for (String fullKey : allFullKeys)
		{
			String key = fullKey;
			if (fullKey.startsWith(prefix))
			{
				key = fullKey.substring(prefix.length());
			}

			if (IGNORE_CONFIG_KEY.equals(key))
			{
				log.debug("Skipping ignored key '{}' while loading snapshot", key);
				continue;
			}

			String compositeKey = "settings_" + worldBucket + "_" + key;
			String storedValue = configManager.getRSProfileConfiguration("scrollboxinfo", compositeKey, String.class);
			if (storedValue == null)
			{
				log.debug("NO SNAPSHOT for '{}'", compositeKey);
				continue;
			}

			configManager.setConfiguration("scrollboxinfo", key, storedValue);
			log.debug("Restored [{}] = '{}' from '{}'", key, storedValue, compositeKey);
			restored++;
		}

		log.debug("=== END LOADING SETTINGS for worldBucket='{}' - {} keys restored. ===", worldBucket, restored);
	}

	// Temporary clean up of old unused keys
	private void removeLegacyKeys()
	{
		final String[] perTierPrefixes = new String[] {
				"total",
				"banked",
				"hasClueOrChallengeScrollInBank",
				"highlightWhenCapped",
				"greeting"
		};

		int removedCount = 0;
		int notFoundCount = 0;

		for (ClueTier tier : ClueTier.values())
		{
			String tierName = tier.name();
			for (String prefix : perTierPrefixes)
			{
				String key;
				if (prefix.equals("hasClueOrChallengeScrollInBank"))
				{
					key = prefix + "_" + tierName;
				}
				else if (prefix.equals("highlightWhenCapped") || prefix.equals("greeting"))
				{
					key = prefix;
				}
				else
				{
					key = prefix + tierName.substring(0, 1) + tierName.substring(1).toLowerCase();
				}

				try
				{
					configManager.unsetConfiguration("scrollboxinfo", key);
				}
				catch (Exception ex)
				{
					log.debug("unsetConfiguration threw for key '{}': {}", key, ex.toString());
				}

				try
				{
					configManager.unsetRSProfileConfiguration("scrollboxinfo", key);
				}
				catch (Exception ex)
				{
					log.debug("unsetRSProfileConfiguration threw for key '{}': {}", key, ex.toString());
				}

				String globalVal = configManager.getConfiguration("scrollboxinfo", key);
				String rsVal = configManager.getRSProfileConfiguration("scrollboxinfo", key);

				if (globalVal == null && rsVal == null)
				{
					log.debug("Removed legacy key '{}': global=null rs=null", key);
					removedCount++;
				}
				else
				{
					log.debug("Legacy key '{}' still present after unset: global='{}' rs='{}' - skipping", key, globalVal, rsVal);
					notFoundCount++;
				}
			}
		}

		for (ClueTier tier : ClueTier.values())
		{
			String explicitKey = "hasClueOrChallengeScrollInBank_" + tier.name();
			try
			{
				configManager.unsetConfiguration("scrollboxinfo", explicitKey);
				configManager.unsetRSProfileConfiguration("scrollboxinfo", explicitKey);
			}
			catch (Exception ex)
			{
				log.debug("unset threw for explicit legacy key '{}': {}", explicitKey, ex.toString());
			}

			String globalVal = configManager.getConfiguration("scrollboxinfo", explicitKey);
			String rsVal = configManager.getRSProfileConfiguration("scrollboxinfo", explicitKey);

			if (globalVal == null && rsVal == null)
			{
				log.debug("Removed explicit legacy key '{}': global=null rs=null", explicitKey);
				removedCount++;
			}
			else
			{
				log.debug("Explicit legacy key '{}' still present after unset: global='{}' rs='{}'", explicitKey, globalVal, rsVal);
				notFoundCount++;
			}
		}

		String bareKey = "hasClueOrChallengeScrollInBank";
		try
		{
			configManager.unsetConfiguration("scrollboxinfo", bareKey);
		}
		catch (Exception ex)
		{
			log.debug("unsetConfiguration threw for bare legacy key '{}': {}", bareKey, ex.toString());
		}

		try
		{
			configManager.unsetRSProfileConfiguration("scrollboxinfo", bareKey);
		}
		catch (Exception ex)
		{
			log.debug("unsetRSProfileConfiguration threw for bare legacy key '{}': {}", bareKey, ex.toString());
		}

		String globalBare = configManager.getConfiguration("scrollboxinfo", bareKey);
		String rsBare = configManager.getRSProfileConfiguration("scrollboxinfo", bareKey);

		if (globalBare == null && rsBare == null)
		{
			log.debug("Removed bare legacy key '{}': global=null rs=null", bareKey);
			removedCount++;
		}
		else
		{
			log.debug("Bare legacy key '{}' still present after unset: global='{}' rs='{}'", bareKey, globalBare, rsBare);
			notFoundCount++;
		}

		log.debug("Legacy cleanup finished. removed={}, notRemoved/remaining={}", removedCount, notFoundCount);
	}

	@Override
	protected void startUp() throws Exception
	{
		loadBankCountsFromConfig();
		loadTotalCountsFromConfig();
		overlayManager.add(clueWidgetItemOverlay);
		removeLegacyKeys();
		//cleanupScrollboxinfoKeys(false);
	}

	@Override
	protected void shutDown() throws Exception
	{
		removeAllInfoboxes();
		overlayManager.remove(clueWidgetItemOverlay);
		clueWidgetItemOverlay.resetMarkedStacks();
	}

	@Subscribe
	public void onClientShutdown(ClientShutdown event)
	{
		GameState state = client.getGameState();

		if (state == GameState.LOGGED_IN)
		{
			saveCurrentSettingsForWorld(lastAccountHash, lastWorldBucket);
		}
	}

	@Subscribe
	public void onProfileChanged(ProfileChanged event)
	{
		refreshInfoboxes();
	}

	private void refreshInfoboxes() {
		removeAllInfoboxes();

		clientThread.invokeLater(() ->
		{
			loadBankCountsFromConfig();
			loadTotalCountsFromConfig();
			for (ClueTier tier : ClueTier.values())
			{
				int count = clueCounter.getClueCounts(tier);
				int cap = StackLimitCalculator.getStackLimit(tier, client);
				checkAndDisplayInfobox(tier, count, cap);
			}
		});
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged varbitChanged)
	{
		if (StackLimitCalculator.SCROLL_VARBITS.contains(varbitChanged.getVarbitId()))
		{
			refreshInfoboxes();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!event.getGroup().equals("scrollboxinfo"))
			return;

		GameState state = client.getGameState();
		if (state == GameState.LOGGED_IN)
		{
			if (lastAccountHash != -1 && lastWorldBucket != null && !lastWorldBucket.isEmpty())
			{
				saveCurrentSettingsForWorld(lastAccountHash, lastWorldBucket, event.getKey());
			}
			else
			{
				log.debug("onConfigChanged: logged in but no lastAccountHash/lastWorldBucket available; skipping save for key '{}'", event.getKey());
			}
		}

		if (event.getKey().equals("markFullStack") && !config.markFullStack())
		{
			clueWidgetItemOverlay.resetMarkedStacks();
		}

		if (event.getKey().equals("showFullStackInfobox")
				|| event.getKey().equals("showBeginnerInfobox")
				|| event.getKey().equals("showEasyInfobox")
				|| event.getKey().equals("showMediumInfobox")
				|| event.getKey().equals("showHardInfobox")
				|| event.getKey().equals("showEliteInfobox")
				|| event.getKey().equals("showMasterInfobox"))
		{
			clientThread.invokeLater(() ->
			{
				for (ClueTier tier : ClueTier.values())
				{
					int count = clueCounter.getClueCounts(tier);
					int cap = StackLimitCalculator.getStackLimit(tier, client);
					checkAndDisplayInfobox(tier, count, cap);
				}
			});
		}
	}

	// TODO: Handle the case of modifying config settings while logged out. Right now, they won't save anywhere
	//  and the changes will be overwritten once the user logs in.
	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();

		log.debug("Gamestate: {}", state);

		if (state == GameState.HOPPING)
		{
			if (lastAccountHash != -1 && !lastWorldBucket.isEmpty())
			{
				log.debug("GameState HOPPING: saving settings for previous account/world before hop (account={}, world={})",
						lastAccountHash, lastWorldBucket);
				saveCurrentSettingsForWorld(lastAccountHash, lastWorldBucket);
			}
			return;
		}

		if (state == GameState.LOGGED_IN)
		{
			long currentAccountHash = client.getAccountHash();
			String currentWorldBucket = getWorldBucket();

			boolean accountChanged = currentAccountHash != lastAccountHash;
			boolean worldChanged = !currentWorldBucket.equals(lastWorldBucket);

			if (!accountChanged && !worldChanged)
			{
				log.debug("LOGGED_IN fired but account/world unchanged, skipping load.");
				return;
			}

			lastAccountHash = currentAccountHash;
			lastWorldBucket = currentWorldBucket;

			log.debug("Detected account or world change. Account: {}, WorldBucket: {}", currentAccountHash, currentWorldBucket);

			clientThread.invokeLater(() ->
			{
				loadSettingsForWorld(currentAccountHash, currentWorldBucket);
				showChangelogIfNeeded();
				loadBankCountsFromConfig();
				loadTotalCountsFromConfig();
				removeLegacyKeys();
				for (ClueTier tier : ClueTier.values())
				{
					int count = clueCounter.getClueCounts(tier);
					int cap = StackLimitCalculator.getStackLimit(tier, client);
					checkAndDisplayInfobox(tier, count, cap);
				}
			});
		}
		else if (state == GameState.LOGIN_SCREEN)
		{
			if (lastAccountHash != -1 && !lastWorldBucket.isEmpty())
			{
				log.debug("LOGIN_SCREEN: saving settings for account={}, world={} before logout", lastAccountHash, lastWorldBucket);
				saveCurrentSettingsForWorld(lastAccountHash, lastWorldBucket);
			}

			lastAccountHash = -1;
			lastWorldBucket = "";
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		bankWasOpenLastTick = bankIsOpen;
		depositBoxWasOpenLastTick = depositBoxIsOpen;

		Widget bankWidget = client.getWidget(ComponentID.BANK_CONTAINER);
		bankIsOpen = bankWidget != null && !bankWidget.isHidden();

		Widget depositBoxWidget = client.getWidget(ComponentID.DEPOSIT_BOX_INVENTORY_ITEM_CONTAINER);
		depositBoxIsOpen = depositBoxWidget != null && !depositBoxWidget.isHidden();
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		ItemContainer inventoryContainer = client.getItemContainer(InventoryID.INVENTORY);
		ItemContainer bankContainer = client.getItemContainer(InventoryID.BANK);

		for (ClueTier tier : ClueTier.values())
		{
			ClueCounts inventory = clueCounter.getClueCounts(tier, inventoryContainer);
			ClueCounts bank = clueCounter.getClueCounts(tier, bankContainer);

			boolean clueEnteredInv = inventory.hasClueScroll() && !previousInventoryClueScrollState.getOrDefault(tier, false);
			boolean clueLeftInv = !inventory.hasClueScroll() && previousInventoryClueScrollState.getOrDefault(tier, false);
			boolean challengeEnteredInv = inventory.hasChallengeScroll() && !previousInventoryChallengeScrollState.getOrDefault(tier, false);
			boolean challengeLeftInv = !inventory.hasChallengeScroll() && previousInventoryChallengeScrollState.getOrDefault(tier, false);
			boolean scrollBoxEnteredInv = inventory.scrollBoxCount() > previousInventoryScrollBoxCount.getOrDefault(tier, 0);
			boolean scrollBoxLeftInv = inventory.scrollBoxCount() < previousInventoryScrollBoxCount.getOrDefault(tier, 0);

			boolean bankedClueScroll = previousBankClueScrollState.getOrDefault(tier, false);
			boolean bankedChallengeScroll = previousBankChallengeScrollState.getOrDefault(tier, false);
			int assumedBankedScrollBoxCount = previousBankScrollBoxCount.getOrDefault(tier, 0);

			int count = inventory.scrollBoxCount();
			if (inventory.hasClueScroll())
				count++;
			if (inventory.hasChallengeScroll())
				count++;

			if (bankContainer != null) {
				bankedClueScroll = bank.hasClueScroll();
				bankedChallengeScroll = bank.hasChallengeScroll();
				assumedBankedScrollBoxCount = bank.scrollBoxCount();

				int bankCount = assumedBankedScrollBoxCount;
				bankCount += bankedClueScroll ? 1 : 0;
				bankCount += bankedChallengeScroll ? 1 : 0;

				if (bankedChallengeScroll && bankedClueScroll)
					bankCount -= 1;

				if (bankedChallengeScroll || bankedClueScroll)
				{
					saveBankScrollFlagToConfig(tier, true);
				}
				if (!bankedChallengeScroll && !bankedClueScroll)
				{
					saveBankScrollFlagToConfig(tier, false);
				}

				clueCountStorage.setBankCount(tier, bankCount);
				saveBankCountToConfig(tier, bankCount);
				// TODO: handle the case of depositing/withdrawing a clue item and picking up/dropping a clue item in the same tick
			} else if (bankWasOpenLastTick || depositBoxIsOpen || depositBoxWasOpenLastTick) {
				int currentCount = inventory.scrollBoxCount();
				int previousCount = previousInventoryScrollBoxCount.getOrDefault(tier, 0);
				int delta = previousCount - currentCount;

				if(!recentlyPickedUp.isEmpty() || !recentlyDropped.isEmpty()) {

					int pickedUp = recentlyPickedUp.getOrDefault(tier, 0);
					int dropped = recentlyDropped.getOrDefault(tier, 0);

					delta = delta - pickedUp + dropped;

					assumedBankedScrollBoxCount += delta;
				} else {
					assumedBankedScrollBoxCount += delta;

					if (clueEnteredInv) {
						bankedClueScroll = false;
					} else if (clueLeftInv) {
						bankedClueScroll = true;
					}
					if (challengeEnteredInv) {
						bankedChallengeScroll = false;
					} else if (challengeLeftInv) {
						bankedChallengeScroll = true;
					}

					if (bankedChallengeScroll || bankedClueScroll)
					{
						saveBankScrollFlagToConfig(tier, true);
					}
					if (!bankedChallengeScroll && !bankedClueScroll)
					{
						saveBankScrollFlagToConfig(tier, false);
					}

					int assumedBankCount = assumedBankedScrollBoxCount;
					if (bankedChallengeScroll || bankedClueScroll)
						assumedBankCount += 1;

					clueCountStorage.setBankCount(tier, assumedBankCount);
					saveBankCountToConfig(tier, assumedBankCount);
				}
			}

			boolean hasScrollInBank = loadBankScrollFlagFromConfig(tier);

			count += clueCountStorage.getBankCount(tier);

			if ((inventory.hasClueScroll() && inventory.hasChallengeScroll())
				|| (inventory.hasClueScroll() && bankedChallengeScroll)
				|| (inventory.hasChallengeScroll() && bankedClueScroll)
				|| ((inventory.hasClueScroll() || inventory.hasChallengeScroll()) && hasScrollInBank)) {
				count -= 1;
				//log.info("inventory.hasClueScroll: {}, inventory.hasChallengeScroll: {}", inventory.hasClueScroll(), inventory.hasChallengeScroll());
				//log.info("bankedClueScroll: {}, bankedChallengeScroll: {}, hasScrollInBank: {}", bankedClueScroll, bankedChallengeScroll, hasScrollInBank);
			}
			clueCountStorage.setCount(tier, count);
			saveTotalCountToConfig(tier, count);

			int cap = StackLimitCalculator.getStackLimit(tier, client);

			if (config.showFullStackInfobox())
				checkAndDisplayInfobox(tier, count, cap);

			int previousTotalClueCount = previousTotalClueCounts.getOrDefault(tier, 0);
			if (config.showChatMessage()) {
				if (scrollBoxEnteredInv
						&& (clueCounter.getClueCounts(tier) != previousTotalClueCount)
						&& previousTotalClueCounts.containsKey(tier)
						&& bankContainer == null
						&& !bankWasOpenLastTick
						&& !depositBoxIsOpen
						&& !depositBoxWasOpenLastTick) {

					String color = (count == cap) ? "ff0000" : "006600"; // red : green
					String message = String.format("<col=%s>Current %s clue count: %d/%d", color, tier.name().toLowerCase(), count, cap);
					client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null);
				}
			}

			previousBankScrollBoxCount.put(tier, assumedBankedScrollBoxCount);
			previousBankClueScrollState.put(tier, bankedClueScroll);
			previousBankChallengeScrollState.put(tier, bankedChallengeScroll);
			previousInventoryScrollBoxCount.put(tier, inventory.scrollBoxCount());
			previousInventoryClueScrollState.put(tier, inventory.hasClueScroll());
			previousInventoryChallengeScrollState.put(tier, inventory.hasChallengeScroll());
			previousTotalClueCounts.put(tier, clueCountStorage.getCount(tier));
		}
		recentlyPickedUp.clear();
		recentlyDropped.clear();
	}

	@Subscribe
	public void onItemSpawned(ItemSpawned event)
	{
		if (bankWasOpenLastTick || depositBoxIsOpen || depositBoxWasOpenLastTick)
		{
			int itemId = event.getItem().getId();
			ClueTier tier = ClueUtils.getClueTier(client, itemId);
			if (tier == null) return;

			recentlyDropped.put(tier, event.getItem().getQuantity());
		}
	}

	@Subscribe
	public void onItemDespawned(ItemDespawned event)
	{
		if (bankWasOpenLastTick || depositBoxIsOpen || depositBoxWasOpenLastTick)
		{
			int itemId = event.getItem().getId();
			ClueTier tier = ClueUtils.getClueTier(client, itemId);
			if (tier == null) return;

			recentlyPickedUp.put(tier, event.getItem().getQuantity());
		}
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (!config.showInventoryRightClickOption()) return;

		if (event.getOption().equals("Inventory"))
		{
			client.getMenu().createMenuEntry(1)
					.setOption("View clue counts")
					.setType(MenuAction.RUNELITE)
					.onClick(e -> sendTotalClueCountsChatMessage())
					.setDeprioritized(true);
		}
	}


	@Provides
	ScrollBoxInfoConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ScrollBoxInfoConfig.class);
	}
}