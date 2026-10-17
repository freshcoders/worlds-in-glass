package com.nick.worldsinglass.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nick.worldsinglass.GlobeSpace;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Pose;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.RemotePlayer;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.neoforged.fml.util.ObfuscationReflectionHelper;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The mobs living in one globe, and the players inside it, as stand-in copies on this client. The server sends their positions a few
 * times a second; in between they glide smoothly, with legs and heads animating as they walk.
 */
public final class MiniEntities
{
    private final Map<Integer, Mini> minis = new HashMap<>();

    private static final class Mini
    {
        final Entity entity;
        double px, py, pz, cx, cy, cz;
        float pBody, cBody, pHead, cHead;
        long updatedAt;
        long lastTick = -1;
        CompoundTag looks = new CompoundTag();

        Mini(Entity entity)
        {
            this.entity = entity;
        }

        float progress(long now, float partialTick)
        {
            return Mth.clamp((now - updatedAt + partialTick) / GlobeSpace.ENTITY_INTERVAL, 0, 1);
        }

        void moveTo(CompoundTag tag, long now, boolean jump)
        {
            // Start from wherever it's shown right now, so a late update doesn't make it skip.
            float t = progress(now, 0);
            px = jump ? tag.getFloat("X") : Mth.lerp(t, px, cx);
            py = jump ? tag.getFloat("Y") : Mth.lerp(t, py, cy);
            pz = jump ? tag.getFloat("Z") : Mth.lerp(t, pz, cz);
            pBody = jump ? tag.getFloat("B") : Mth.rotLerp(t, pBody, cBody);
            pHead = jump ? tag.getFloat("H") : Mth.rotLerp(t, pHead, cHead);
            cx = tag.getFloat("X");
            cy = tag.getFloat("Y");
            cz = tag.getFloat("Z");
            cBody = tag.getFloat("B");
            cHead = tag.getFloat("H");
            updatedAt = now;
        }

        /** Once per game tick: age the entity (for idle animations) and swing its legs to match its speed. */
        void tick(long now)
        {
            lastTick = now;
            entity.tickCount++;
            if (entity instanceof LivingEntity living)
            {
                float perTick = (float) Math.sqrt((cx - px) * (cx - px) + (cz - pz) * (cz - pz)) / GlobeSpace.ENTITY_INTERVAL;
                if (progress(now, 0) >= 1)
                    perTick = 0;
                living.walkAnimation.update(Math.min(perTick * 4, 1), 0.4f);
            }
        }
    }

    private static EntityType<?> typeOf(String id)
    {
        ResourceLocation name = ResourceLocation.tryParse(id);
        return name == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(name) ? null : BuiltInRegistries.ENTITY_TYPE.get(name);
    }

    /** A stand-in for a player inside the globe, with their own skin if they're in the player list. */
    private static Entity tinyPlayer(CompoundTag tag, Level level)
    {
        if (!(level instanceof ClientLevel clientLevel) || !tag.hasUUID("P"))
            return null;
        UUID id = tag.getUUID("P");
        PlayerInfo info = Minecraft.getInstance().getConnection() == null ? null : Minecraft.getInstance().getConnection().getPlayerInfo(id);
        return new RemotePlayer(clientLevel, info != null ? info.getProfile() : new GameProfile(id, tag.getString("N")));
    }

    public void update(ListTag list, Level level)
    {
        long now = level.getGameTime();
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < list.size(); i++)
        {
            CompoundTag tag = list.getCompound(i);
            int id = tag.getInt("I");
            EntityType<?> type = typeOf(tag.getString("T"));
            Mini mini = minis.get(id);
            CompoundTag looks = tag.getCompound("L");
            boolean fresh = mini == null || mini.entity.getType() != type;
            if (fresh)
            {
                Entity entity = type == EntityType.PLAYER ? tinyPlayer(tag, level) : type == null ? null : type.create(level);
                if (entity == null)
                    continue;
                mini = new Mini(entity);
                minis.put(id, mini);
            }
            if (mini.entity instanceof RemotePlayer player)
            {
                // A player inside: crouching or standing, looking up or down.
                player.setPose(tag.getBoolean("C") ? Pose.CROUCHING : Pose.STANDING);
                player.setXRot(tag.getFloat("Xr"));
                player.xRotO = player.getXRot();
                mini.moveTo(tag, now, fresh);
                seen.add(id);
                continue;
            }
            // Variants and colours: apply when new, and again if they change (a sheep sheared, a baby grown up).
            if (fresh || !looks.equals(mini.looks))
            {
                mini.looks = looks;
                if (!looks.isEmpty())
                    mini.entity.load(looks);
            }
            mini.moveTo(tag, now, fresh);
            // Fish flop on their side when out of water; tell the copy whether the real one is swimming.
            ObfuscationReflectionHelper.setPrivateValue(Entity.class, mini.entity, tag.getBoolean("W"), "wasTouchingWater");
            seen.add(id);
        }
        minis.keySet().retainAll(seen);
    }

    /** Draws the mobs; the pose must already be in globe-space units (one unit = one block inside the globe). */
    public void render(PoseStack poseStack, MultiBufferSource buffers, int light, float partialTick, Level level)
    {
        if (minis.isEmpty())
            return;
        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        long now = level.getGameTime();
        dispatcher.setRenderShadow(false);
        try
        {
            for (Mini mini : minis.values())
            {
                if (mini.lastTick != now)
                    mini.tick(now);
                float t = mini.progress(now, partialTick);
                double x = Mth.lerp(t, mini.px, mini.cx);
                double y = Mth.lerp(t, mini.py, mini.cy);
                double z = Mth.lerp(t, mini.pz, mini.cz);
                float body = Mth.rotLerp(t, mini.pBody, mini.cBody);
                float head = Mth.rotLerp(t, mini.pHead, mini.cHead);

                Entity entity = mini.entity;
                entity.setPos(x, y, z);
                entity.setYRot(body);
                entity.yRotO = body;
                if (entity instanceof LivingEntity living)
                {
                    living.yBodyRot = living.yBodyRotO = body;
                    living.yHeadRot = living.yHeadRotO = head;
                }
                dispatcher.render(entity, x, y, z, body, partialTick, poseStack, buffers, light);
            }
        }
        finally
        {
            dispatcher.setRenderShadow(Minecraft.getInstance().options.entityShadows().get());
        }
    }
}
