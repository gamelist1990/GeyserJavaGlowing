package org.pexserver.geyserglowing;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.entity.property.type.GeyserFloatEntityProperty;
import org.geysermc.geyser.api.entity.property.type.GeyserIntEntityProperty;
import org.geysermc.geyser.api.event.bedrock.SessionDisconnectEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntityPropertiesEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineResourcePacksEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostReloadEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserShutdownEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.api.pack.PackCodec;
import org.geysermc.geyser.api.pack.ResourcePack;
import org.geysermc.geyser.api.util.Identifier;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.registry.Registries;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveEntitiesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundSetPlayerTeamPacket;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Mirrors the Java edition GLOWING flag to Bedrock's native entity-property channel.
 * No animation packets, particles, Paper plugin, or Geyser source patches are used.
 * The translator hooks intentionally rely on Geyser internals and are version-sensitive.
 */
public final class GeyserJavaGlowingExtension implements Extension {
    private final Map<GeyserSession, GlowingPacketBridge> bridges = new ConcurrentHashMap<>();
    private TranslatorHooks hooks;

    private GeyserIntEntityProperty mode;
    private GeyserFloatEntityProperty r;
    private GeyserFloatEntityProperty g;
    private GeyserFloatEntityProperty b;
    private GeyserFloatEntityProperty a;

    @Subscribe
    public void onProperties(GeyserDefineEntityPropertiesEvent event) {
        Identifier player = Identifier.of("minecraft:player");
        mode = event.registerIntegerProperty(player, Identifier.of("glow:color"), 0, 2, 0);
        r = event.registerFloatProperty(player, Identifier.of("glow:r"), 0.0f, 1.0f, 1.0f);
        g = event.registerFloatProperty(player, Identifier.of("glow:g"), 0.0f, 1.0f, 1.0f);
        b = event.registerFloatProperty(player, Identifier.of("glow:b"), 0.0f, 1.0f, 1.0f);
        a = event.registerFloatProperty(player, Identifier.of("glow:a"), 0.0f, 1.0f, 1.0f);
    }

    @Subscribe
    public void onResourcePacks(GeyserDefineResourcePacksEvent event) {
        Path target = dataFolder().resolve("GeyserJavaGlowing.mcpack");
        try {
            Files.createDirectories(dataFolder());
            try (InputStream in = getClass().getResourceAsStream("/GeyserJavaGlowing.mcpack")) {
                if (in == null) throw new IOException("Bundled resource pack was not found");
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            event.register(ResourcePack.create(PackCodec.path(target)));
            logger().info("Registered the player outline resource pack.");
        } catch (Exception exception) {
            logger().error("Failed to register glowing resource pack: " + exception.getMessage());
        }
    }

    @Subscribe
    public void onInitialize(GeyserPostInitializeEvent event) {
        installHooks();
    }

    @Subscribe
    public void onReload(GeyserPostReloadEvent event) {
        bridges.clear();
        installHooks();
    }

    private void installHooks() {
        if (mode == null || r == null || g == null || b == null || a == null) {
            logger().error("Glowing properties were not registered; packet hooks were not installed.");
            return;
        }
        if (hooks != null) hooks.close();
        hooks = new TranslatorHooks(Registries.JAVA_PACKET_TRANSLATORS,
            (session, packet) -> {
                if (!session.isClosed()) {
                    bridges.computeIfAbsent(session,
                        key -> new GlowingPacketBridge(key, mode, r, g, b, a)).accept(packet);
                }
            });
        try {
            hooks.install(ClientboundSetEntityDataPacket.class);
            hooks.install(ClientboundAddEntityPacket.class);
            hooks.install(ClientboundSetPlayerTeamPacket.class);
            hooks.install(ClientboundRemoveEntitiesPacket.class);
            hooks.install(ClientboundLoginPacket.class);
            hooks.install(ClientboundRespawnPacket.class);
            logger().info("Installed glowing hooks for 6 Java packet translators.");
        } catch (RuntimeException exception) {
            hooks.close();
            logger().error("Incompatible Geyser packet translators: " + exception.getMessage());
        }
    }

    @Subscribe
    public void onDisconnect(SessionDisconnectEvent event) {
        if (!(event.connection() instanceof GeyserSession session)) return;
        // Dropping the reference is sufficient; no per-session listener or scheduled
        // retry remains to keep a disconnected connection alive.
        bridges.remove(session);
    }

    @Subscribe
    public void onShutdown(GeyserShutdownEvent event) {
        if (hooks != null) hooks.close();
        bridges.clear();
    }
}
