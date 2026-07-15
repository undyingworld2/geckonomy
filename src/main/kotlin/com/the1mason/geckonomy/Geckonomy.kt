package com.the1mason.geckonomy

import org.bukkit.plugin.java.JavaPlugin

class Geckonomy : JavaPlugin() {

    override fun onEnable() {
        // Plugin startup logic
        logger.info("Geckonomy Plugin Enabled")
    }

    override fun onDisable() {
        // Plugin shutdown logic
    }
}
