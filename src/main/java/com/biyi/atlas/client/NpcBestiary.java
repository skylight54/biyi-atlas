package com.biyi.atlas.client;

import com.biyi.atlas.map.BestiaryEntry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 从实体（优先 CNPC 的 NPC）读出图鉴信息：名字、称号、生命上限、掉落物。
 *
 * <p>用**反射**访问 CNPC（`noppes.npcs.entity.EntityNPCInterface` 的
 * `display.getTitle()` / `inventory.drops` / `stats.maxHealth`），这样不引入编译期依赖，
 * 没装 CNPC 也不会崩。</p>
 */
public final class NpcBestiary {

    private NpcBestiary() {
    }

    public static BestiaryEntry read(Entity entity) {
        String name = entity.getDisplayName().getString();
        if (name == null || name.isBlank()) {
            name = entity.getName().getString();
        }
        String entityType = String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
        String title = "";
        int health = entity instanceof LivingEntity living ? (int) living.getMaxHealth() : -1;
        List<String> drops = new ArrayList<>();

        try {
            Class<?> npcClass = Class.forName("noppes.npcs.entity.EntityNPCInterface");
            if (npcClass.isInstance(entity)) {
                Object display = field(npcClass, entity, "display");
                if (display != null) {
                    Object npcName = invoke(display, "getName");
                    if (npcName instanceof String value && !value.isBlank()) {
                        name = value;
                    }
                    Object npcTitle = invoke(display, "getTitle");
                    if (npcTitle instanceof String value && !value.isBlank()) {
                        title = value;
                    }
                }
                Object stats = field(npcClass, entity, "stats");
                if (stats != null) {
                    Object maxHealth = field(stats.getClass(), stats, "maxHealth");
                    if (maxHealth instanceof Integer value) {
                        health = value;
                    }
                }
                Object inventory = field(npcClass, entity, "inventory");
                if (inventory != null) {
                    Object dropMap = field(inventory.getClass(), inventory, "drops");
                    if (dropMap instanceof Map<?, ?> map) {
                        for (Object item : map.values()) {
                            String itemName = itemName(item);
                            if (!itemName.isEmpty() && !drops.contains(itemName)) {
                                drops.add(itemName);
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // CNPC 不存在或版本不同：保留基础信息
        }
        return new BestiaryEntry(name, title, health, drops, entityType);
    }

    private static Object field(Class<?> owner, Object target, String name) {
        try {
            Field field = owner.getField(name);
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object invoke(Object target, String method) {
        try {
            Method resolved = target.getClass().getMethod(method);
            return resolved.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** CNPC 的 IItemStack：优先 getDisplayName()，退回 getName()。 */
    private static String itemName(Object item) {
        for (String method : new String[]{"getDisplayName", "getName"}) {
            Object value = invoke(item, method);
            if (value instanceof String text && !text.isBlank()) {
                return text;
            }
            if (value != null && !(value instanceof String)) {
                String text = String.valueOf(value);
                if (!text.isBlank() && !text.equals("null")) {
                    return text;
                }
            }
        }
        return "";
    }
}
