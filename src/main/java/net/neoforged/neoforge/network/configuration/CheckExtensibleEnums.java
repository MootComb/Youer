/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.network.configuration;

import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;
import com.mojang.logging.LogUtils;
import io.netty.buffer.ByteBuf;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.ConfigurationTask;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.asm.enumextension.ExtensionInfo;
import net.neoforged.fml.common.asm.enumextension.IExtensibleEnum;
import net.neoforged.fml.common.asm.enumextension.NetworkedEnum;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.payload.ExtensibleEnumAcknowledgePayload;
import net.neoforged.neoforge.network.payload.ExtensibleEnumDataPayload;
import net.neoforged.neoforgespi.language.ModFileScanData;
import org.jetbrains.annotations.ApiStatus;
import org.slf4j.Logger;

@ApiStatus.Internal
public record CheckExtensibleEnums(ServerConfigurationPacketListener listener) implements ConfigurationTask {
    public static final Type TYPE = new Type(ResourceLocation.fromNamespaceAndPath("neoforge", "check_extensible_enum"));
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final org.objectweb.asm.Type NETWORKED_ENUM = org.objectweb.asm.Type.getType(NetworkedEnum.class);
    private static final List<? extends Class<? extends Enum<?>>> NETWORKED_EXTENSIBLE_ENUM_CLASSES = collectNetworkedEnumClasses();
    private static Map<String, EnumEntry> enumEntries = null;

    @Override
    public void start(Consumer<Packet<?>> packetSender) {
        listener.finishCurrentTask(TYPE);
    }

    public static void handleClientboundPayload(ExtensibleEnumDataPayload payload, IPayloadContext context) {
        context.reply(ExtensibleEnumAcknowledgePayload.INSTANCE);
    }

    public static void handleServerboundPayload(@SuppressWarnings("unused") ExtensibleEnumAcknowledgePayload payload, IPayloadContext context) {
        context.finishCurrentTask(TYPE);
    }

    public static boolean handleVanillaServerConnection(ClientConfigurationPacketListener listener) {
        return true;
    }

    private static synchronized Map<String, EnumEntry> getEnumEntries() {
        if (enumEntries == null) {
            Map<String, EnumEntry> entries = new HashMap<>();
            for (Class<? extends Enum<?>> enumClass : NETWORKED_EXTENSIBLE_ENUM_CLASSES) {
                Optional<ExtensionData> extData = Optional.empty();
                ExtensionInfo extInfo = getEnumExtensionInfo(enumClass);
                if (extInfo.extended()) {
                    List<String> values = new ArrayList<>(extInfo.totalCount() - extInfo.vanillaCount());
                    Enum<?>[] constants = enumClass.getEnumConstants();
                    for (int i = extInfo.vanillaCount(); i < extInfo.totalCount(); i++) {
                        values.add(constants[i].name());
                    }
                    extData = Optional.of(new ExtensionData(extInfo.vanillaCount(), extInfo.totalCount(), values));
                }
                String name = enumClass.getName();
                entries.put(name, new EnumEntry(
                        name,
                        Preconditions.checkNotNull(extInfo.netCheck(), "Enum %s does not have a NetworkCheck value", name),
                        extData));
            }
            enumEntries = entries;
        }
        return enumEntries;
    }

    private static ExtensionInfo getEnumExtensionInfo(Class<? extends Enum<?>> enumClass) {
        try {
            Method mth = enumClass.getDeclaredMethod("getExtensionInfo");
            return (ExtensionInfo) mth.invoke(null);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<? extends Class<? extends Enum<?>>> collectNetworkedEnumClasses() {
        return ModList.get()
                .getAllScanData()
                .stream()
                .map(ModFileScanData::getAnnotations)
                .flatMap(Set::stream)
                .filter(a -> NETWORKED_ENUM.equals(a.annotationType()))
                .map(a -> classForName(a.clazz().getClassName()))
                .filter(Class::isEnum)
                .filter(IExtensibleEnum.class::isAssignableFrom)
                .map(c -> (Class<? extends Enum<?>>) c)
                .toList();
    }

    private static Class<?> classForName(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Failed to load class specified by annotation data", e);
        }
    }

    @Override
    public Type type() {
        return TYPE;
    }

    public record EnumEntry(String className, NetworkedEnum.NetworkCheck networkCheck, Optional<ExtensionData> data) {

        public static final StreamCodec<ByteBuf, EnumEntry> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8,
                EnumEntry::className,
                ByteBufCodecs.STRING_UTF8.map(NetworkedEnum.NetworkCheck::valueOf, NetworkedEnum.NetworkCheck::name),
                EnumEntry::networkCheck,
                ByteBufCodecs.optional(ExtensionData.STREAM_CODEC),
                EnumEntry::data,
                EnumEntry::new);
        public boolean isClientbound() {
            return networkCheck == NetworkedEnum.NetworkCheck.CLIENTBOUND || networkCheck == NetworkedEnum.NetworkCheck.BIDIRECTIONAL;
        }

        public boolean isServerbound() {
            return networkCheck == NetworkedEnum.NetworkCheck.SERVERBOUND || networkCheck == NetworkedEnum.NetworkCheck.BIDIRECTIONAL;
        }

        public boolean isExtended() {
            return data.isPresent();
        }
    }

    public record ExtensionData(int vanillaCount, int totalCount, List<String> entries) {
        public static final StreamCodec<ByteBuf, ExtensionData> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT,
                ExtensionData::vanillaCount,
                ByteBufCodecs.VAR_INT,
                ExtensionData::totalCount,
                ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8),
                ExtensionData::entries,
                ExtensionData::new);
    }

    private enum Mismatch {
        EXTENSIBILITY,
        NETWORK_CHECK,
        EXTENSION,
        ENTRY_COUNT,
        ENTRY_MISMATCH,
    }
}
