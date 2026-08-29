package cc.keran.itemcd.hook;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.wrappers.BlockPosition;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * ProtocolLib 桥接实现 —— 所有 ProtocolLib 代码都隔离在这个类里。
 *
 * <p>{@link PacketHook} 不会引用本类的任何 ProtocolLib 类型，
 * 因此本类只在确认 ProtocolLib 存在、且配置启用了协议层拦截后才会被加载。
 * 这保证了没有 ProtocolLib 的服务器不会因为类解析而崩溃。</p>
 *
 * <h3>线程模型</h3>
 * <p>ProtocolLib 的同步包监听器在主线程执行，可安全调用 Bukkit API。
 * 这里仍旧保留 {@link org.bukkit.Bukkit#isPrimaryThread()} 检查作为兜底：
 * 若回调被派发到 Netty 线程，则由 {@link PacketHook#shouldCancel} 直接放行，
 * 自动降级交给事件层处理。</p>
 */
final class ProtocolLibBridge implements PacketHook.Bridge {

    /** 包名 -> ProtocolLib 常量 */
    private static PacketType toPacketType(String name) {
        switch (name) {
            case "USE_ITEM":      return PacketType.Play.Client.USE_ITEM;
            case "BLOCK_PLACE":   return PacketType.Play.Client.BLOCK_PLACE;
            case "BLOCK_DIG":     return PacketType.Play.Client.BLOCK_DIG;
            case "USE_ENTITY":    return PacketType.Play.Client.USE_ENTITY;
            case "ARM_ANIMATION": return PacketType.Play.Client.ARM_ANIMATION;
            case "ENTITY_ACTION": return PacketType.Play.Client.ENTITY_ACTION;
            case "HELD_ITEM_SLOT":return PacketType.Play.Client.HELD_ITEM_SLOT;
            default:              return null;
        }
    }

    /** 常量 -> 包名（回调时反查，避免回调方法携带 ProtocolLib 类型） */
    private static String toName(PacketType type) {
        if (type == PacketType.Play.Client.USE_ITEM) return "USE_ITEM";
        if (type == PacketType.Play.Client.BLOCK_PLACE) return "BLOCK_PLACE";
        if (type == PacketType.Play.Client.BLOCK_DIG) return "BLOCK_DIG";
        if (type == PacketType.Play.Client.USE_ENTITY) return "USE_ENTITY";
        if (type == PacketType.Play.Client.ARM_ANIMATION) return "ARM_ANIMATION";
        if (type == PacketType.Play.Client.ENTITY_ACTION) return "ENTITY_ACTION";
        if (type == PacketType.Play.Client.HELD_ITEM_SLOT) return "HELD_ITEM_SLOT";
        return null;
    }

    private final PacketHook hook;
    private final PacketAdapter adapter;
    private final ProtocolManager manager;

    ProtocolLibBridge(PacketHook hook, Plugin plugin, Set<String> typeNames) {
        this.hook = hook;

        List<PacketType> types = new ArrayList<>();
        for (String name : typeNames) {
            PacketType type = toPacketType(name);
            if (type != null) types.add(type);
        }
        if (types.isEmpty()) {
            throw new IllegalStateException("没有有效的包类型");
        }

        this.manager = ProtocolLibrary.getProtocolManager();

        // 匿名 PacketAdapter 子类：本类被加载时才会解析其父类，符合软依赖隔离要求
        this.adapter = new PacketAdapter(plugin, ListenerPriority.LOWEST, types) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                handle(event);
            }
        };

        manager.addPacketListener(adapter);
    }

    @Override
    public void unregister() {
        try {
            manager.removePacketListener(adapter);
        } catch (Throwable ignored) {
            // ProtocolLib 可能已先卸载
        }
    }

    private void handle(PacketEvent event) {
        if (event.isCancelled()) return;

        Player player = event.getPlayer();
        if (player == null) return;

        PacketType type = event.getPacketType();
        String name = toName(type);
        if (name == null) return;

        // 是否需要判定「是否针对空气」（仅 air-only 模式下、且是左键包时才解析坐标）
        boolean targetingAir = true;
        if (hook.isAirOnly() && type == PacketType.Play.Client.BLOCK_DIG) {
            targetingAir = isAirDig(event.getPacket());
        }

        if (hook.shouldCancel(player, name, targetingAir)) {
            event.setCancelled(true);
        }
    }

    /**
     * 判断左键包是否对着空气。
     *
     * <p>1.16 客户端左键点击空气时，发送的 {@code PacketPlayInBlockDig}
     * 携带的方块坐标 y 为 -1（服务器用它表示「没有目标方块」）。
     * 据此区分「挖方块」与「对着空气挥击」。</p>
     */
    private boolean isAirDig(PacketContainer packet) {
        try {
            StructureModifier<BlockPosition> modifier =
                    packet.getModifier().withType(BlockPosition.class, BlockPosition.getConverter());
            if (modifier.size() <= 0) return true;
            BlockPosition pos = modifier.read(0);
            return pos == null || pos.getY() < 0;
        } catch (Throwable t) {
            // 读不到坐标时按「空气」处理，宁可拦下也不放过
            return true;
        }
    }
}
