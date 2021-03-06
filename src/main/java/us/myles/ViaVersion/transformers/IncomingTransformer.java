package us.myles.ViaVersion.transformers;

import io.netty.buffer.ByteBuf;
import org.bukkit.inventory.ItemStack;
import us.myles.ViaVersion.CancelException;
import us.myles.ViaVersion.ConnectionInfo;
import us.myles.ViaVersion.ViaVersionPlugin;
import us.myles.ViaVersion.api.slot.ItemSlotRewriter;
import us.myles.ViaVersion.packets.PacketType;
import us.myles.ViaVersion.packets.State;
import us.myles.ViaVersion.util.PacketUtil;
import us.myles.ViaVersion.util.ReflectionUtil;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class IncomingTransformer {
    private final ConnectionInfo info;

    public IncomingTransformer(ConnectionInfo info) {
        this.info = info;
    }

    public void transform(int packetID, ByteBuf input, ByteBuf output) throws CancelException {
        PacketType packet = PacketType.getIncomingPacket(info.getState(), packetID);
        if (packet == null) {
            System.out.println("incoming packet not found " + packetID + " state: " + info.getState());
            throw new RuntimeException("Incoming Packet not found? " + packetID + " State: " + info.getState() + " Version: " + info.getProtocol());
        }
        int original = packetID;

        if (packet.getPacketID() != -1) {
            packetID = packet.getPacketID();
        }
//        if (packet != PacketType.PLAY_PLAYER_POSITION_LOOK_REQUEST && packet != PacketType.PLAY_KEEP_ALIVE_REQUEST && packet != PacketType.PLAY_PLAYER_POSITION_REQUEST && packet != PacketType.PLAY_PLAYER_LOOK_REQUEST) {
//            System.out.println("Packet Type: " + packet + " New ID: " + packetID + " Original: " + original);
//        }
        if (packet == PacketType.PLAY_TP_CONFIRM || packet == PacketType.PLAY_VEHICLE_MOVE_REQUEST) { //TODO handle client-sided horse riding
            throw new CancelException();
        }
        PacketUtil.writeVarInt(packetID, output);
        if (packet == PacketType.HANDSHAKE) {
            int protVer = PacketUtil.readVarInt(input);
            info.setProtocol(protVer);
            PacketUtil.writeVarInt(protVer <= 102 ? protVer : 47, output); // pretend to be older

            if (protVer <= 102) {
                // not 1.9, remove pipes
                info.setActive(false);
            }
            String serverAddress = PacketUtil.readString(input);
            PacketUtil.writeString(serverAddress, output);

            int serverPort = input.readUnsignedShort();
            output.writeShort(serverPort);

            int nextState = PacketUtil.readVarInt(input);
            PacketUtil.writeVarInt(nextState, output);

            if (nextState == 1) {
                info.setState(State.STATUS);
            }
            if (nextState == 2) {
                info.setState(State.LOGIN);
            }
            return;
        }
        if (packet == PacketType.PLAY_UPDATE_SIGN_REQUEST) {
            Long location = input.readLong();
            output.writeLong(location);
            for (int i = 0; i < 4; i++) {
                String line = PacketUtil.readString(input);
                line = "{\"text\":\"" + line + "\"}";
                PacketUtil.writeString(line, output);
            }
            return;
        }
        if (packet == PacketType.PLAY_TAB_COMPLETE_REQUEST) {
            String text = PacketUtil.readString(input);
            PacketUtil.writeString(text, output);
            input.readBoolean(); // assume command
            output.writeBytes(input);
            return;
        }
        if (packet == PacketType.PLAY_PLAYER_DIGGING) {
            int status = input.readByte() & 0xFF; // unsign
            if (status == 6) { // item swap
                throw new CancelException();
            }
            output.writeByte(status);
            // write remaining bytes
            output.writeBytes(input);
            return;
        }
        if (packet == PacketType.PLAY_CLICK_WINDOW) {
            // if placed in new slot, reject :)
            int windowID = input.readUnsignedByte();
            short slot = input.readShort();

            byte button = input.readByte();
            short action = input.readShort();
            byte mode = input.readByte();
            if (slot == 45 && windowID == 0) {
                try {
                    Class<?> setSlot = ReflectionUtil.nms("PacketPlayOutSetSlot");
                    Constructor setSlotConstruct = setSlot.getDeclaredConstructor(int.class, int.class, ReflectionUtil.nms("ItemStack"));
                    // properly construct
                    Object setSlotPacket = setSlotConstruct.newInstance(windowID, slot, null);
                    info.getChannel().pipeline().writeAndFlush(setSlotPacket); // slot is empty
                    slot = -999; // we're evil, they'll throw item on the ground
                } catch (ClassNotFoundException e) {
                    e.printStackTrace();
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                } catch (InstantiationException e) {
                    e.printStackTrace();
                } catch (InvocationTargetException e) {
                    e.printStackTrace();
                } catch (NoSuchMethodException e) {
                    e.printStackTrace();
                }

            }
            output.writeByte(windowID);
            output.writeShort(slot);
            output.writeByte(button);
            output.writeShort(action);
            output.writeByte(mode);
            ItemSlotRewriter.rewrite1_9To1_8(input, output);
            return;
        }
        if (packet == PacketType.PLAY_CLIENT_SETTINGS) {
            String locale = PacketUtil.readString(input);
            PacketUtil.writeString(locale, output);

            byte view = input.readByte();
            output.writeByte(view);

            int chatMode = PacketUtil.readVarInt(input);
            output.writeByte(chatMode);

            boolean chatColours = input.readBoolean();
            output.writeBoolean(chatColours);

            short skinParts = input.readUnsignedByte();
            output.writeByte(skinParts);

            PacketUtil.readVarInt(input);
            return;
        }
        if (packet == PacketType.PLAY_ANIMATION_REQUEST) {
            PacketUtil.readVarInt(input);
            return;
        }
        if (packet == PacketType.PLAY_USE_ENTITY) {
            int target = PacketUtil.readVarInt(input);
            PacketUtil.writeVarInt(target, output);

            int type = PacketUtil.readVarInt(input);
            PacketUtil.writeVarInt(type, output);
            if (type == 2) {
                float targetX = input.readFloat();
                output.writeFloat(targetX);
                float targetY = input.readFloat();
                output.writeFloat(targetY);
                float targetZ = input.readFloat();
                output.writeFloat(targetZ);
            }
            if (type == 0 || type == 2) {
                PacketUtil.readVarInt(input);
            }
            return;
        }
        if (packet == PacketType.PLAY_PLAYER_BLOCK_PLACEMENT) {
            Long position = input.readLong();
            output.writeLong(position);
            int face = PacketUtil.readVarInt(input);
            output.writeByte(face);
            int hand = PacketUtil.readVarInt(input);

            ItemStack inHand = ViaVersionPlugin.getHandItem(info);
            try {
                us.myles.ViaVersion.api.slot.ItemSlotRewriter.ItemStack item = us.myles.ViaVersion.api.slot.ItemSlotRewriter.ItemStack.fromBukkit(inHand);
                ItemSlotRewriter.fixIdsFrom1_9To1_8(item);
                ItemSlotRewriter.writeItemStack(item, output);
            } catch (Exception e) {
                e.printStackTrace();
            }

            short curX = input.readUnsignedByte();
            output.writeByte(curX);
            short curY = input.readUnsignedByte();
            output.writeByte(curY);
            short curZ = input.readUnsignedByte();
            output.writeByte(curZ);
            return;
        }
        if (packet == PacketType.PLAY_USE_ITEM) {
            output.clear();
            PacketUtil.writeVarInt(PacketType.PLAY_PLAYER_BLOCK_PLACEMENT.getPacketID(), output);
            // Simulate using item :)
            output.writeLong(-1L);
            output.writeByte(255);
            // write item in hand
            ItemStack inHand = ViaVersionPlugin.getHandItem(info);
            try {
                us.myles.ViaVersion.api.slot.ItemSlotRewriter.ItemStack item = us.myles.ViaVersion.api.slot.ItemSlotRewriter.ItemStack.fromBukkit(inHand);
                ItemSlotRewriter.fixIdsFrom1_9To1_8(item);
                ItemSlotRewriter.writeItemStack(item, output);
            } catch (Exception e) {
                e.printStackTrace();
            }

            output.writeByte(-1);
            output.writeByte(-1);
            output.writeByte(-1);
            return;
        }
        if (packet == PacketType.PLAY_CREATIVE_INVENTORY_ACTION) {
            short slot = input.readShort();
            output.writeShort(slot);

            ItemSlotRewriter.rewrite1_9To1_8(input, output);
        }
        output.writeBytes(input);
    }
}
