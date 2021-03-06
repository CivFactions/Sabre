package com.gordonfreemanq.sabre.util;

import java.util.UUID;

import org.bukkit.entity.Player;

public interface CombatInterface {

	public boolean tagPlayer(Player player);
	public boolean tagPlayer(String playerName);
	public Integer remainingSeconds(Player player);
	public boolean isTagged(UUID player);
}
