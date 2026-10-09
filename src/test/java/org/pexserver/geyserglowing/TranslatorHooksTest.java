package org.pexserver.geyserglowing;

import org.geysermc.geyser.registry.PacketTranslatorRegistry;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.translator.protocol.PacketTranslator;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveEntitiesPacket;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TranslatorHooksTest {
    @Test void delegatesBeforeObservingAndRestoresOriginalOnClose() {
        var registry = PacketTranslatorRegistry.<Packet>create();
        List<String> order = new ArrayList<>();
        PacketTranslator<ClientboundRemoveEntitiesPacket> original = new PacketTranslator<>() {
            public void translate(GeyserSession session, ClientboundRemoveEntitiesPacket packet) { order.add("geyser"); }
            public boolean shouldExecuteInEventLoop() { return false; }
        };
        registry.register(ClientboundRemoveEntitiesPacket.class, original);
        var hooks = new TranslatorHooks(registry, (session, packet) -> order.add("glowing"));
        hooks.install(ClientboundRemoveEntitiesPacket.class);
        assertFalse(registry.get(ClientboundRemoveEntitiesPacket.class).shouldExecuteInEventLoop());
        invoke(registry, mock(GeyserSession.class));
        assertEquals(List.of("geyser", "glowing"), order);
        hooks.close();
        assertSame(original, registry.get(ClientboundRemoveEntitiesPacket.class));
        hooks.close();
    }

    @Test void closePreservesTranslatorInstalledByAnotherExtension() {
        var registry = PacketTranslatorRegistry.<Packet>create();
        registry.register(ClientboundRemoveEntitiesPacket.class, mock(PacketTranslator.class));
        var hooks = new TranslatorHooks(registry, (session, packet) -> {});
        hooks.install(ClientboundRemoveEntitiesPacket.class);
        PacketTranslator<ClientboundRemoveEntitiesPacket> replacement = mock(PacketTranslator.class);
        registry.register(ClientboundRemoveEntitiesPacket.class, replacement);
        hooks.close();
        assertSame(replacement, registry.get(ClientboundRemoveEntitiesPacket.class));
    }

    @Test void failsClearlyWhenRequiredTranslatorIsMissing() {
        var hooks = new TranslatorHooks(PacketTranslatorRegistry.create(), (session, packet) -> {});
        assertThrows(IllegalStateException.class, () -> hooks.install(ClientboundRemoveEntitiesPacket.class));
    }

    @SuppressWarnings("unchecked")
    private static void invoke(PacketTranslatorRegistry<Packet> registry, GeyserSession session) {
        ((PacketTranslator<ClientboundRemoveEntitiesPacket>) registry.get(ClientboundRemoveEntitiesPacket.class))
            .translate(session, new ClientboundRemoveEntitiesPacket(new int[]{2}));
    }
}
