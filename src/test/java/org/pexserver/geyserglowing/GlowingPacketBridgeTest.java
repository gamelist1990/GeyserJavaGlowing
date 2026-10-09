package org.pexserver.geyserglowing;

import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket;
import org.geysermc.geyser.impl.IdentifierImpl;
import org.geysermc.geyser.entity.BedrockEntityDefinition;
import org.geysermc.geyser.entity.EntityTypeDefinition;
import org.geysermc.geyser.entity.properties.GeyserEntityProperties;
import org.geysermc.geyser.entity.properties.type.FloatProperty;
import org.geysermc.geyser.entity.properties.type.IntProperty;
import org.geysermc.geyser.entity.properties.type.PropertyType;
import org.geysermc.geyser.entity.spawn.EntitySpawnContext;
import org.geysermc.geyser.entity.type.Entity;
import org.geysermc.geyser.entity.type.player.PlayerEntity;
import org.geysermc.geyser.entity.type.player.SessionPlayerEntity;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.session.cache.EntityCache;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.EntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.ByteEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.scoreboard.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundSetPlayerTeamPacket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import net.kyori.adventure.text.Component;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Uses real Geyser PlayerEntity/property managers and captures their real Bedrock packets. */
class GlowingPacketBridgeTest {
    private GeyserSession session;
    private EntityCache cache;
    private BedrockEntityDefinition definition;
    private EntityTypeDefinition<?> javaDefinition;
    private GlowingPacketBridge bridge;
    private final Map<Integer, Entity> entities = new HashMap<>();
    private final Set<Entity> dirty = new LinkedHashSet<>();
    private final List<SetEntityDataPacket> sent = new ArrayList<>();
    private final IntProperty mode = new IntProperty(IdentifierImpl.of("glow", "color"), 2, 0, 0);
    private final FloatProperty red = color("r"), green = color("g"), blue = color("b"), alpha = color("a");

    private static FloatProperty color(String name) {
        return new FloatProperty(IdentifierImpl.of("glow", name), 1, 0, 1.0f);
    }

    @BeforeEach
    void setup() {
        session = mock(GeyserSession.class);
        cache = mock(EntityCache.class);
        when(session.getEntityCache()).thenReturn(cache);
        when(cache.getEntityByJavaId(anyInt())).thenAnswer(call -> entities.get(call.getArgument(0)));
        SessionPlayerEntity self = mock(SessionPlayerEntity.class);
        when(self.getEntityId()).thenReturn(1);
        when(session.getPlayerEntity()).thenReturn(self);
        doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; })
            .when(session).ensureInEventLoop(any(Runnable.class));
        doAnswer(call -> { dirty.add(call.getArgument(0)); return null; })
            .when(cache).markDirty(any(Entity.class));
        doAnswer(call -> {
            if (call.getArgument(0) instanceof SetEntityDataPacket packet) sent.add(packet);
            return null;
        }).when(session).sendUpstreamPacket(any());

        // Property definitions and the transport are fixtures; entity updates are real Geyser code.
        List<PropertyType<?, ?>> list = List.of(mode, red, green, blue, alpha);
        GeyserEntityProperties properties = mock(GeyserEntityProperties.class);
        when(properties.getProperties()).thenReturn(list);
        when(properties.getPropertyIndex(anyString())).thenAnswer(call -> {
            String name = call.getArgument(0);
            for (int i = 0; i < list.size(); i++) if (list.get(i).identifier().toString().equals(name)) return i;
            return -1;
        });
        definition = mock(BedrockEntityDefinition.class);
        when(definition.registeredProperties()).thenReturn(properties);
        javaDefinition = mock(EntityTypeDefinition.class);
        when(javaDefinition.height()).thenReturn(1.8f);
        when(javaDefinition.width()).thenReturn(0.6f);
        bridge = new GlowingPacketBridge(session, mode, red, green, blue, alpha);
        player(2);
    }

    private PlayerEntity player(int id) {
        PlayerEntity entity = new TestPlayerEntity(new EntitySpawnContext(session, javaDefinition, id,
            UUID.randomUUID(), definition, Vector3f.ZERO, Vector3f.ZERO, 0, 0, 0, 1000L + id));
        entity.setValid(true);
        entities.put(id, entity);
        return entity;
    }

    // Vanilla metadata initialization requires Geyser's global bootstrap. The
    // property manager and its Bedrock packet emission remain the real methods.
    private static final class TestPlayerEntity extends PlayerEntity {
        TestPlayerEntity(EntitySpawnContext context) { super(context, "PEXkoukunn", null); }
        @Override protected void initializeMetadata() { }
        @Override protected void setAirSupply(int amount) { }
    }

    private static ClientboundSetEntityDataPacket flags(int id, int flags) {
        return new ClientboundSetEntityDataPacket(id,
            new EntityMetadata<?, ?>[]{new ByteEntityMetadata(0, MetadataTypes.BYTE, (byte) flags)});
    }

    private static ClientboundAddEntityPacket spawn(int id) {
        return new ClientboundAddEntityPacket(id, UUID.randomUUID(), EntityType.PLAYER, 0, 64, 0, 0, 0, 0);
    }

    private static ClientboundSetPlayerTeamPacket team(String name, TeamColor color, String... members) {
        return new ClientboundSetPlayerTeamPacket(name, Component.empty(), Component.empty(), Component.empty(),
            false, false, NameTagVisibility.ALWAYS, CollisionRule.ALWAYS, color, members);
    }

    private SetEntityDataPacket flush() {
        for (Entity entity : dirty) entity.updateBedrockMetadata();
        dirty.clear();
        return sent.isEmpty() ? null : sent.getLast();
    }

    private static int mode(SetEntityDataPacket packet) {
        return packet.getProperties().getIntProperties().stream().filter(p -> p.getIndex() == 0)
            .findFirst().orElseThrow().getValue();
    }

    private static float color(SetEntityDataPacket packet, int index) {
        return packet.getProperties().getFloatProperties().stream().filter(p -> p.getIndex() == index)
            .findFirst().orElseThrow().getValue();
    }

    @Test void sendsWhiteGlowAndClearsWithoutChangingOtherJavaFlags() {
        bridge.accept(flags(2, 0x41));
        SetEntityDataPacket on = flush();
        assertEquals(1002L, on.getRuntimeEntityId());
        assertEquals(2, mode(on));
        assertEquals(1.0f, color(on, 1));
        assertEquals(1.0f, color(on, 4));
        bridge.accept(flags(2, 0x01));
        assertEquals(0, mode(flush()));
    }

    @Test void unchangedFlagsDoNotSendAnotherPropertiesPacket() {
        bridge.accept(flags(2, 0x40)); flush();
        int before = sent.size();
        bridge.accept(flags(2, 0x42)); flush();
        assertEquals(before, sent.size());
    }

    @Test void teamBeforeInitialGlowAndColorUpdatesAreApplied() {
        bridge.accept(team("red", TeamColor.RED, "PEXkoukunn"));
        bridge.accept(flags(2, 0x40));
        SetEntityDataPacket packet = flush();
        assertEquals(1.0f, color(packet, 1));
        assertEquals(85 / 255.0f, color(packet, 2));
        bridge.accept(team("red", TeamColor.BLUE).withAction(TeamAction.UPDATE));
        packet = flush();
        assertEquals(85 / 255.0f, color(packet, 1));
        assertEquals(1.0f, color(packet, 3));
    }

    @Test void removingMemberOrTeamRestoresWhite() {
        bridge.accept(team("red", TeamColor.RED, "PEXkoukunn"));
        bridge.accept(flags(2, 0x40)); flush();
        bridge.accept(new ClientboundSetPlayerTeamPacket("red", TeamAction.REMOVE_PLAYER, new String[]{"PEXkoukunn"}));
        assertEquals(1.0f, color(flush(), 2));
        bridge.accept(new ClientboundSetPlayerTeamPacket("red", TeamAction.ADD_PLAYER, new String[]{"PEXkoukunn"}));
        assertEquals(85 / 255.0f, color(flush(), 2));
        bridge.accept(new ClientboundSetPlayerTeamPacket("red"));
        assertEquals(1.0f, color(flush(), 2));
    }

    @Test void changingTeamsDoesNotRetainOldMembership() {
        bridge.accept(team("red", TeamColor.RED, "PEXkoukunn"));
        bridge.accept(flags(2, 0x40)); flush();
        bridge.accept(team("blue", TeamColor.BLUE, "PEXkoukunn")); flush();
        bridge.accept(new ClientboundSetPlayerTeamPacket("red")); flush();
        assertEquals(1.0f, color(sent.getLast(), 3));
        assertEquals(85 / 255.0f, color(sent.getLast(), 1));
    }

    @Test void metadataBeforeSpawnIsAppliedWhenSpawnArrives() {
        entities.remove(2);
        bridge.accept(flags(2, 0x40));
        assertNull(flush());
        player(2);
        bridge.accept(spawn(2));
        assertEquals(2, mode(flush()));
    }

    @Test void reusedPlayerObjectIsResetOnNewSpawn() {
        bridge.accept(flags(2, 0x40)); flush();
        PlayerEntity reused = (PlayerEntity) entities.remove(2);
        bridge.accept(new ClientboundRemoveEntitiesPacket(new int[]{2}));
        reused.setEntityId(3);
        entities.put(3, reused);
        bridge.accept(spawn(3));
        assertEquals(0, mode(flush()));
    }

    @Test void reusedJavaIdSendsPropertiesToReplacementEntity() {
        bridge.accept(flags(2, 0x40)); flush();
        entities.remove(2);
        bridge.accept(new ClientboundRemoveEntitiesPacket(new int[]{2}));
        player(2);
        bridge.accept(flags(2, 0x40));
        assertEquals(2, mode(flush()));
        assertEquals(2, sent.size());
    }

    @Test void respawnWithoutKeepMetadataClearsOwnOutline() {
        player(1);
        bridge.accept(flags(1, 0x40)); flush();
        bridge.accept(new ClientboundRespawnPacket(null, false, false));
        assertEquals(0, mode(flush()));
    }

    @Test void dimensionChangePreservesSelfGlowAndTeamColor() {
        player(1);
        bridge.accept(team("red", TeamColor.RED, "PEXkoukunn"));
        bridge.accept(flags(1, 0x40)); flush();
        bridge.accept(new ClientboundRespawnPacket(null, true, true));
        SetEntityDataPacket packet = flush();
        assertEquals(2, mode(packet));
        assertEquals(85 / 255.0f, color(packet, 2));
    }

    @Test void serverSwitchResetsTeamsAndLocalProperties() {
        player(1);
        bridge.accept(team("red", TeamColor.RED, "PEXkoukunn"));
        bridge.accept(flags(1, 0x40)); flush();
        bridge.accept(mock(ClientboundLoginPacket.class));
        assertEquals(0, mode(flush()));
        bridge.accept(flags(1, 0x40));
        assertEquals(1.0f, color(flush(), 2));
    }

    @Test void observersKeepIndependentTeamColors() {
        GlowingPacketBridge other = new GlowingPacketBridge(session, mode, red, green, blue, alpha);
        bridge.accept(team("red", TeamColor.RED, "PEXkoukunn"));
        bridge.accept(flags(2, 0x40)); flush();
        other.accept(flags(2, 0x40));
        assertEquals(1.0f, color(flush(), 2));
    }

    @Test void ignoresMobMetadataAndUnrelatedMetadata() {
        entities.put(3, mock(Entity.class));
        bridge.accept(flags(3, 0x40));
        bridge.accept(new ClientboundSetEntityDataPacket(2,
            new EntityMetadata<?, ?>[]{new ByteEntityMetadata(1, MetadataTypes.BYTE, (byte) 0x40)}));
        assertNull(flush());
    }

    @Test void closedConnectionIsNotUpdated() {
        when(session.isClosed()).thenReturn(true);
        bridge.accept(flags(2, 0x40));
        assertNull(flush());
    }
}
