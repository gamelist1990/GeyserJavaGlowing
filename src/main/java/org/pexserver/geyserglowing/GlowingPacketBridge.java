package org.pexserver.geyserglowing;

import org.geysermc.geyser.api.entity.property.type.GeyserFloatEntityProperty;
import org.geysermc.geyser.api.entity.property.type.GeyserIntEntityProperty;
import org.geysermc.geyser.entity.type.Entity;
import org.geysermc.geyser.entity.type.player.PlayerEntity;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.EntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.ByteEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.scoreboard.TeamAction;
import org.geysermc.mcprotocollib.protocol.data.game.scoreboard.TeamColor;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveEntitiesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundSetPlayerTeamPacket;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** One observer per Bedrock client: never leak another viewer's team or glow state. */
final class GlowingPacketBridge {
    private final GeyserSession session;
    private final GeyserIntEntityProperty mode;
    private final GeyserFloatEntityProperty red;
    private final GeyserFloatEntityProperty green;
    private final GeyserFloatEntityProperty blue;
    private final GeyserFloatEntityProperty alpha;
    private final Set<Integer> glowing = new HashSet<>();
    private final Map<String, TeamState> teams = new HashMap<>();
    private final Map<String, String> memberTeam = new HashMap<>();
    private final Map<Integer, AppliedState> lastApplied = new HashMap<>();

    GlowingPacketBridge(GeyserSession session, GeyserIntEntityProperty mode,
                        GeyserFloatEntityProperty red, GeyserFloatEntityProperty green,
                        GeyserFloatEntityProperty blue, GeyserFloatEntityProperty alpha) {
        this.session = session;
        this.mode = Objects.requireNonNull(mode);
        this.red = Objects.requireNonNull(red);
        this.green = Objects.requireNonNull(green);
        this.blue = Objects.requireNonNull(blue);
        this.alpha = Objects.requireNonNull(alpha);
    }

    /** Called immediately after Geyser translates the same packet, on its session event loop. */
    void accept(Packet packet) {
        if (session.isClosed()) return;
        if (packet instanceof ClientboundSetEntityDataPacket data) {
            Entity entity = session.getEntityCache().getEntityByJavaId(data.getEntityId());
            if (entity != null && !(entity instanceof PlayerEntity)) return;
            for (EntityMetadata<?, ?> entry : data.getMetadata()) {
                if (entry.getId() == 0 && entry instanceof ByteEntityMetadata flags) {
                    int id = data.getEntityId();
                    if ((flags.getPrimitiveValue() & 0x40) != 0) glowing.add(id);
                    else glowing.remove(id);
                    apply(id);
                    break;
                }
            }
        } else if (packet instanceof ClientboundAddEntityPacket spawned) {
            // Geyser reuses cached PlayerEntity objects. Their property manager can still
            // contain the outline from their previous appearance, even with a new Java ID.
            lastApplied.remove(spawned.getEntityId());
            apply(spawned.getEntityId());
        } else if (packet instanceof ClientboundSetPlayerTeamPacket teamPacket) {
            applyTeamPacket(teamPacket);
            for (int javaId : glowing) apply(javaId);
        } else if (packet instanceof ClientboundRemoveEntitiesPacket removed) {
            for (int id : removed.getEntityIds()) {
                glowing.remove(id);
                lastApplied.remove(id);
            }
        } else if (packet instanceof ClientboundLoginPacket) {
            clear();
            apply(session.getPlayerEntity().getEntityId());
        } else if (packet instanceof ClientboundRespawnPacket respawn) {
            int selfId = session.getPlayerEntity().getEntityId();
            boolean selfGlow = respawn.isKeepMetadata() && glowing.contains(selfId);
            glowing.clear();
            lastApplied.clear();
            if (selfGlow) glowing.add(selfId);
            // A metadata reset does not reset custom properties in Geyser. Always
            // explicitly synchronize off as well as on, including the local player.
            apply(selfId);
        }
    }

    void clear() {
        glowing.clear();
        lastApplied.clear();
        teams.clear();
        memberTeam.clear();
    }

    private void applyTeamPacket(ClientboundSetPlayerTeamPacket packet) {
        String teamName = packet.getTeamName();
        TeamAction action = packet.getAction();
        if (action == TeamAction.REMOVE) {
            TeamState old = teams.remove(teamName);
            if (old != null) {
                for (String player : old.members) memberTeam.remove(player, teamName);
            }
            return;
        }
        TeamState state = teams.computeIfAbsent(teamName, k -> new TeamState());
        if (action == TeamAction.CREATE || action == TeamAction.UPDATE) {
            if (packet.getColor() != null) state.color = packet.getColor();
        }
        if (action == TeamAction.CREATE || action == TeamAction.ADD_PLAYER) {
            for (String player : packet.getPlayers() == null ? new String[0] : packet.getPlayers()) {
                String former = memberTeam.put(player, teamName);
                if (former != null && !former.equals(teamName)) {
                    TeamState formerTeam = teams.get(former);
                    if (formerTeam != null) formerTeam.members.remove(player);
                }
                state.members.add(player);
            }
        } else if (action == TeamAction.REMOVE_PLAYER) {
            for (String player : packet.getPlayers() == null ? new String[0] : packet.getPlayers()) {
                memberTeam.remove(player, teamName);
                state.members.remove(player);
            }
        }
    }

    private void apply(int id) {
        Entity entity = session.getEntityCache().getEntityByJavaId(id);
        if (entity == null && session.getPlayerEntity().getEntityId() == id) {
            entity = session.getPlayerEntity();
        }
        if (!(entity instanceof PlayerEntity player)) return;
        boolean enabled = glowing.contains(id);
        int rgb = enabled ? teamRgb(player.getUsername()) : 0xFFFFFF;
        int newArgb = (enabled ? 0x01000000 : 0) | rgb;
        AppliedState previous = lastApplied.get(id);
        if (previous != null && previous.player == player && previous.argb == newArgb) return;
        float r = ((rgb >>> 16) & 0xff) / 255.0f;
        float g = ((rgb >>> 8) & 0xff) / 255.0f;
        float b = (rgb & 0xff) / 255.0f;
        // Batched update emits one Bedrock entity-properties synchronization.
        player.updatePropertiesBatched(updater -> {
            updater.update(mode, enabled ? 2 : 0);
            updater.update(red, r);
            updater.update(green, g);
            updater.update(blue, b);
            updater.update(alpha, 1.0f);
        }, false);
        lastApplied.put(id, new AppliedState(player, newArgb));
    }

    private int teamRgb(String username) {
        TeamState state = teams.get(memberTeam.get(username));
        if (state == null || state.color == null) return 0xFFFFFF;
        return switch (state.color) {
            case BLACK -> 0x000000;
            case DARK_BLUE -> 0x0000AA;
            case DARK_GREEN -> 0x00AA00;
            case DARK_AQUA -> 0x00AAAA;
            case DARK_RED -> 0xAA0000;
            case DARK_PURPLE -> 0xAA00AA;
            case GOLD -> 0xFFAA00;
            case GRAY -> 0xAAAAAA;
            case DARK_GRAY -> 0x555555;
            case BLUE -> 0x5555FF;
            case GREEN -> 0x55FF55;
            case AQUA -> 0x55FFFF;
            case RED -> 0xFF5555;
            case LIGHT_PURPLE -> 0xFF55FF;
            case YELLOW -> 0xFFFF55;
            default -> 0xFFFFFF;
        };
    }

    private record AppliedState(PlayerEntity player, int argb) { }

    private static final class TeamState {
        TeamColor color = TeamColor.WHITE;
        Set<String> members = new HashSet<>();
    }
}
