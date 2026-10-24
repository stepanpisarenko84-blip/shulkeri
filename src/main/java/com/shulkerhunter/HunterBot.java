package com.shulkerhunter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Бот на элитрах: облетает Энд кольцами -> находит End City (по purpur-блокам в загруженных
 * чанках) -> летит к шалкерам и бьёт их (в полёте и пешком) -> подбирает яйцо.
 * Останавливается, когда в инвентаре стало больше яиц шалкера, чем было при включении.
 *
 * Нужно: элитры надеты, фейерверки в левой руке (лучше) или в хотбаре, меч/топор в хотбаре.
 */
public class HunterBot {
    private static final double CRUISE_Y = 120;
    private static final double REACH = 2.9;
    private static final double VISITED_RADIUS = 90;
    private static final double MIN_GLIDE_SPEED = 0.8;   // ниже — нужен фейерверк

    private boolean enabled;
    private int startEggs;

    private int scanCooldown, boostCd, glideCd, groundTicks;
    private boolean recovering;

    private Vec3 cityTarget;
    private final List<Vec3> visitedCities = new ArrayList<>();

    private boolean ringInit;
    private double ringRadius = 1200, ringAngle, ringTravel;

    public void toggle(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) return;
        if (enabled) { stop(mc, p, "ВЫКЛ"); return; }

        if (!p.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) { say(p, "Надень элитры!"); return; }
        if (fireworkCount(p) == 0) { say(p, "Нужны фейерверки (лучше в левой руке)!"); return; }

        enabled = true;
        startEggs = eggCount(p);
        cityTarget = null;
        ringInit = false;
        recovering = false;
        scanCooldown = boostCd = glideCd = groundTicks = 0;
        say(p, "ShulkerHunter: ВКЛ");
    }

    public void tick(Minecraft mc) {
        if (!enabled) return;

        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (p == null || level == null || mc.gameMode == null) { enabled = false; return; }

        release(mc);

        if (!p.isAlive()) { enabled = false; return; }
        if (level.dimension() != Level.END) { stop(mc, p, "ты не в Энде"); return; }
        if (eggCount(p) > startEggs) { stop(mc, p, "ЯЙЦО ШАЛКЕРА ПОЛУЧЕНО!"); return; }

        ItemStack elytra = p.getItemBySlot(EquipmentSlot.CHEST);
        if (!elytra.is(Items.ELYTRA) || elytra.getMaxDamage() - elytra.getDamageValue() <= 3) {
            stop(mc, p, "элитры сломаны или сняты");
            return;
        }
        if (fireworkCount(p) == 0 && !p.isFallFlying() && p.onGround()) {
            stop(mc, p, "кончились фейерверки");
            return;
        }

        if (boostCd > 0) boostCd--;
        if (glideCd > 0) glideCd--;

        if (p.getHealth() < 8f) recovering = true;
        else if (p.getHealth() >= 16f) recovering = false;

        // ---- выбор цели ----
        AABB box = p.getBoundingBox().inflate(128);
        Vec3 goal;
        Shulker fight = null;

        ItemEntity egg = nearest(level.getEntitiesOfClass(ItemEntity.class, box,
                i -> i.getItem().is(Items.SHULKER_SPAWN_EGG)), p);
        Shulker shulker = nearest(level.getEntitiesOfClass(Shulker.class, box, Shulker::isAlive), p);

        if (egg != null) {
            goal = egg.position();
        } else if (recovering) {
            // мало HP: летим прочь от опасности, пока не восстановимся
            Vec3 dir = Vec3.directionFromRotation(0f, p.getYRot()).scale(200);
            goal = new Vec3(p.getX() + dir.x, CRUISE_Y + 20, p.getZ() + dir.z);
        } else if (shulker != null) {
            fight = shulker;
            goal = shulker.getBoundingBox().getCenter();
        } else {
            goal = searchGoal(mc, level, p);
        }

        // ---- движение ----
        boolean boosted = false;
        if (p.isFallFlying()) {
            steerGlide(p, goal);
            if (p.getDeltaMovement().length() < MIN_GLIDE_SPEED) boosted = boost(mc, p);
            groundTicks = 0;
        } else if (p.onGround()) {
            groundTicks++;
            boolean walk = fight != null && groundTicks < 300 && horizDist(p, goal) < 24;
            if (walk) {
                lookAt(p, goal);
                mc.options.keyUp.setDown(true);
                if (p.horizontalCollision) mc.options.keyJump.setDown(true);
            } else {
                mc.options.keyJump.setDown(true);       // взлёт: прыжок -> планирование
                if (groundTicks > 400) groundTicks = 0;
            }
        } else {
            groundTicks = 0;
            if (glideCd <= 0) {                           // в воздухе, но не планируем — раскрыть элитры
                if (p.tryToStartFallFlying() && mc.getConnection() != null) {
                    mc.getConnection().send(new ServerboundPlayerCommandPacket(p,
                            ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
                }
                glideCd = 5;
            }
        }

        // ---- удар ----
        if (fight != null && !boosted) {
            double distSq = fight.getBoundingBox().distanceToSqr(p.getEyePosition());
            if (distSq <= REACH * REACH && p.getAttackStrengthScale(0.5f) >= 1.0f) {
                selectWeapon(p);
                if (!p.isFallFlying()) lookAt(p, goal);
                mc.gameMode.attack(p, fight);
                p.swing(InteractionHand.MAIN_HAND);
            }
        }
    }

    // ---------- поиск ----------

    private Vec3 searchGoal(Minecraft mc, ClientLevel level, LocalPlayer p) {
        if (--scanCooldown <= 0) {
            scanCooldown = 20;
            Vec3 found = findCity(mc, level, p);
            if (found != null) cityTarget = found;
        }
        if (cityTarget != null) {
            if (horizDist(p, cityTarget) < 40) {
                visitedCities.add(cityTarget);   // долетели, шалкеров нет — дальше
                cityTarget = null;
            } else {
                return new Vec3(cityTarget.x, Math.max(cityTarget.y + 25, CRUISE_Y), cityTarget.z);
            }
        }

        if (!ringInit) {
            ringAngle = Math.atan2(p.getZ(), p.getX());
            ringRadius = Math.max(1200, Math.hypot(p.getX(), p.getZ()));
            ringTravel = 0;
            ringInit = true;
        }
        Vec3 wp = new Vec3(Math.cos(ringAngle) * ringRadius, CRUISE_Y, Math.sin(ringAngle) * ringRadius);
        if (horizDist(p, wp) < 48) {
            ringAngle += 250.0 / ringRadius;
            ringTravel += 250;
            if (ringTravel > Math.PI * 2 * ringRadius) {
                ringTravel = 0;
                ringRadius += 300;
            }
        }
        return wp;
    }

    private Vec3 findCity(Minecraft mc, ClientLevel level, LocalPlayer p) {
        int pcx = p.blockPosition().getX() >> 4;
        int pcz = p.blockPosition().getZ() >> 4;
        int r = mc.options.renderDistance().get();

        Vec3 best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                ChunkAccess chunk = level.getChunk(pcx + dx, pcz + dz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                LevelChunkSection[] sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    LevelChunkSection sec = sections[i];
                    if (sec.hasOnlyAir()) continue;
                    if (!sec.maybeHas(st -> st.is(Blocks.PURPUR_BLOCK) || st.is(Blocks.PURPUR_PILLAR))) continue;
                    int y = chunk.getSectionYFromSectionIndex(i) << 4;
                    Vec3 c = new Vec3((pcx + dx) * 16 + 8, y + 8, (pcz + dz) * 16 + 8);
                    if (isVisited(c)) continue;
                    double d = c.distanceToSqr(p.position());
                    if (d < bestD) { bestD = d; best = c; }
                }
            }
        }
        return best;
    }

    private boolean isVisited(Vec3 c) {
        for (Vec3 v : visitedCities) if (v.distanceTo(c) < VISITED_RADIUS) return true;
        return false;
    }

    // ---------- полёт ----------

    private void steerGlide(LocalPlayer p, Vec3 goal) {
        Vec3 d = goal.subtract(p.position());
        double h = Math.hypot(d.x, d.z);
        float wantYaw = (float) (Math.atan2(d.z, d.x) * 180.0 / Math.PI) - 90f;
        float wantPitch;
        if (h > 60) {
            wantPitch = Mth.clamp((float) (-d.y * 0.8), -40f, 30f);       // контроль высоты
        } else {
            wantPitch = Mth.clamp((float) -(Math.atan2(d.y, h) * 180.0 / Math.PI), -50f, 60f);
        }
        p.setYRot(approach(p.getYRot(), wantYaw, 14f));
        p.setXRot(approach(p.getXRot(), wantPitch, 8f));
    }

    private static float approach(float cur, float want, float maxStep) {
        return cur + Mth.clamp(Mth.wrapDegrees(want - cur), -maxStep, maxStep);
    }

    private boolean boost(Minecraft mc, LocalPlayer p) {
        if (boostCd > 0) return false;
        InteractionHand hand;
        if (p.getOffhandItem().is(Items.FIREWORK_ROCKET)) {
            hand = InteractionHand.OFF_HAND;
        } else {
            int slot = hotbarSlot(p, st -> st.is(Items.FIREWORK_ROCKET));
            if (slot < 0) return false;
            p.getInventory().setSelectedSlot(slot);
            hand = InteractionHand.MAIN_HAND;
        }
        mc.gameMode.useItem(p, hand);
        boostCd = 12;
        return true;
    }

    private static void selectWeapon(LocalPlayer p) {
        int slot = hotbarSlot(p, st -> st.is(ItemTags.SWORDS) || st.is(ItemTags.AXES));
        if (slot >= 0 && p.getInventory().getSelectedSlot() != slot) {
            p.getInventory().setSelectedSlot(slot);
        }
    }

    // ---------- утилиты ----------

    private static int hotbarSlot(LocalPlayer p, Predicate<ItemStack> pred) {
        for (int i = 0; i < 9; i++) if (pred.test(p.getInventory().getItem(i))) return i;
        return -1;
    }

    private static int countItems(LocalPlayer p, net.minecraft.world.item.Item item) {
        int n = 0;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st.is(item)) n += st.getCount();
        }
        return n;
    }

    private static int eggCount(LocalPlayer p) { return countItems(p, Items.SHULKER_SPAWN_EGG); }

    private static int fireworkCount(LocalPlayer p) {
        int n = countItems(p, Items.FIREWORK_ROCKET);
        if (p.getOffhandItem().is(Items.FIREWORK_ROCKET)) n += p.getOffhandItem().getCount();
        return n;
    }

    private static double horizDist(LocalPlayer p, Vec3 v) {
        return Math.hypot(v.x - p.getX(), v.z - p.getZ());
    }

    private static void lookAt(LocalPlayer p, Vec3 pos) {
        Vec3 d = pos.subtract(p.getEyePosition());
        double h = Math.sqrt(d.x * d.x + d.z * d.z);
        p.setYRot((float) (Math.atan2(d.z, d.x) * 180.0 / Math.PI) - 90f);
        p.setXRot((float) -(Math.atan2(d.y, h) * 180.0 / Math.PI));
    }

    private static <T extends Entity> T nearest(List<T> list, Entity from) {
        return list.stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(from))).orElse(null);
    }

    private static void release(Minecraft mc) {
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
    }

    private void stop(Minecraft mc, LocalPlayer p, String why) {
        enabled = false;
        release(mc);
        say(p, "ShulkerHunter: " + why);
    }

    private static void say(LocalPlayer p, String msg) {
        Minecraft.getInstance().gui.setOverlayMessage(Component.literal(msg), false);
    }
}
