package com.elfmcys.yesstevemodel.forge.mixin;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraftforge.network.Channel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Channel.class)
public interface ChannelAccessor {

    @Invoker("toVanillaPacket")
    Packet<?> ysm$toVanillaPacket(Connection connection, Object message);
}
