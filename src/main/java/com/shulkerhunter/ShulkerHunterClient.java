package com.shulkerhunter;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class ShulkerHunterClient implements ClientModInitializer {
    private static KeyMapping toggleKey;
    private final HunterBot bot = new HunterBot();

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("shulkerhunter", "main"));

        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.shulkerhunter.toggle",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                category));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (toggleKey.consumeClick()) {
                bot.toggle(mc);
            }
            bot.tick(mc);
        });
    }
}
