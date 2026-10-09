package org.pexserver.geyserglowing;

import org.geysermc.geyser.registry.PacketTranslatorRegistry;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.translator.protocol.PacketTranslator;
import org.geysermc.mcprotocollib.network.packet.Packet;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

/** Decorates existing translators, preserving their behavior and packet ordering. */
final class TranslatorHooks implements AutoCloseable {
    private final PacketTranslatorRegistry<Packet> registry;
    private final BiConsumer<GeyserSession, Packet> observer;
    private final List<Runnable> restorers = new ArrayList<>();

    TranslatorHooks(PacketTranslatorRegistry<Packet> registry, BiConsumer<GeyserSession, Packet> observer) {
        this.registry = Objects.requireNonNull(registry);
        this.observer = Objects.requireNonNull(observer);
    }

    @SuppressWarnings("unchecked")
    <P extends Packet> void install(Class<P> packetClass) {
        PacketTranslator<P> original = (PacketTranslator<P>) registry.get(packetClass);
        if (original == null) throw new IllegalStateException("No translator for " + packetClass.getSimpleName());
        PacketTranslator<P> wrapper = new PacketTranslator<>() {
            @Override
            public void translate(GeyserSession session, P packet) {
                original.translate(session, packet);
                observer.accept(session, packet);
            }

            @Override
            public boolean shouldExecuteInEventLoop() {
                return original.shouldExecuteInEventLoop();
            }
        };
        registry.register(packetClass, wrapper);
        restorers.add(() -> {
            // Respect a replacement made by another extension after our installation.
            if (registry.get(packetClass) == wrapper) registry.register(packetClass, original);
        });
    }

    @Override
    public void close() {
        for (int i = restorers.size() - 1; i >= 0; i--) restorers.get(i).run();
        restorers.clear();
    }
}
