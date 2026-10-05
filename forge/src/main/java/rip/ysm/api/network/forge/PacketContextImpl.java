package rip.ysm.api.network.forge;

import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.network.CustomPayloadEvent;
import org.jetbrains.annotations.Nullable;
import rip.ysm.api.network.PacketContext;

final class PacketContextImpl implements PacketContext {

    private final CustomPayloadEvent.Context context;

    PacketContextImpl(CustomPayloadEvent.Context context) {
        this.context = context;
    }

    @Override
    public boolean isClientSide() {
        return context.isClientSide();
    }

    @Override
    public boolean isServerSide() {
        return context.isServerSide();
    }

    @Override
    public @Nullable ServerPlayer getSender() {
        return context.getSender();
    }

    @Override
    public Connection getConnection() {
        return context.getConnection();
    }

    @Override
    public void enqueueWork(Runnable runnable) {
        context.enqueueWork(runnable);
    }
}
