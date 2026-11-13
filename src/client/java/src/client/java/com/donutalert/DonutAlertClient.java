package com.donutalert;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DonutAlert for Minecraft 26.1.x (Fabric, Mojang names).
 * Stream-style pay alerts. Client-side and visual only.
 *
 * Same text size for every tier - only the animation, color, effects and
 * the funny line change.
 *
 * IMPORTANT: edit PAID_PATTERN to match the REAL pay message on the server.
 *   group 1 = player name, group 2 = number, group 3 = suffix (K/M/B/T)
 *
 * Test in-game:  /donutalert 100k   /donutalert 1b
 */
public class DonutAlertClient implements ClientModInitializer {

    static final Pattern PAID_PATTERN = Pattern.compile(
            "(\\w{3,16}) (?:paid|sent) you \\$?([\\d,.]+)\\s*([KMBTkmbt]?)");
    static final Pattern TEST_CMD = Pattern.compile("^donutalert\\s+([\\d.,]+)\\s*([KMBTkmbt]?)$");

    // Tiers: 0 <10K | 1 10K+ | 2 100K+ | 3 1M+ | 4 100M+ (nuke)
    static final double[] THRESHOLDS = {0, 10_000, 100_000, 1_000_000, 100_000_000};
    static final long[] DURATIONS = {3500, 4000, 4500, 5500, 7000};
    static final int[] COLORS = {0xFFFFFF, 0x55FF55, 0xFFD700, 0xC040FF, 0xFF3030};
    static final String[] VERBS = {"tipped you", "sent you", "blessed you with", "bombed you with", "NUKED you with"};
    static final float BASE_SCALE = 3.0f; // same size for every tier

    static final String[][] FUNNY = {
            {"Lunch money. Respect.", "Every donut counts.", "A tip is a tip!"},
            {"Not bad, keep farming!", "Smells like a good trade.", "Nice. Now go touch grass."},
            {"Someone has a rich uncle.", "Ka-ching! Wallet getting heavy.", "Your bank account says thanks."},
            {"Your wallet just called, it's crying.", "That's a lot of donuts...", "Is this legal?? Asking for DonutSMP."},
            {"Call the bank, the economy is dead.", "RIP economy. Gone. Reduced to atoms.", "You're the CEO of donuts now."}
    };

    static class Alert {
        String name;
        double amount;
        int tier;
        long start;
        String funny;
    }

    static class Particle {
        float x, y, vx, vy;
        int color, size;
    }

    static final ArrayDeque<Alert> queue = new ArrayDeque<>();
    static final List<Particle> particles = new ArrayList<>();
    static final Random rng = new Random();
    static Alert current;
    static long lastFrame;

    @Override
    public void onInitializeClient() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            handleChat(message.getString());
        });

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("donutalert", "alert"),
                (graphics, deltaTracker) -> render(graphics));

        // Local test command: typing /donutalert 1b plays the alert and is NOT sent to the server
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
            Matcher m = TEST_CMD.matcher(command.trim());
            if (!m.matches()) return true;
            Double amt = parse(m.group(1), m.group(2));
            if (amt != null) enqueue("TestPlayer", amt);
            return false;
        });
    }

    static Double parse(String num, String suffix) {
        try {
            double base = Double.parseDouble(num.replace(",", ""));
            double mult = switch (suffix.toUpperCase()) {
                case "K" -> 1e3;
                case "M" -> 1e6;
                case "B" -> 1e9;
                case "T" -> 1e12;
                default -> 1;
            };
            return base * mult;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static void handleChat(String text) {
        Matcher m = PAID_PATTERN.matcher(text);
        if (!m.find()) return;
        Double amt = parse(m.group(2), m.group(3));
        if (amt != null) enqueue(m.group(1), amt);
    }

    static void enqueue(String name, double amount) {
        Alert a = new Alert();
        a.name = name;
        a.amount = amount;
        for (int i = 0; i < THRESHOLDS.length; i++) if (amount >= THRESHOLDS[i]) a.tier = i;
        String[] lines = FUNNY[a.tier];
        a.funny = lines[rng.nextInt(lines.length)];
        queue.add(a);
    }

    static String format(double v) {
        if (v >= 1e12) return trim(v / 1e12) + "T";
        if (v >= 1e9) return trim(v / 1e9) + "B";
        if (v >= 1e6) return trim(v / 1e6) + "M";
        if (v >= 1e3) return trim(v / 1e3) + "K";
        return trim(v);
    }

    static String trim(double v) {
        return (v == Math.floor(v)) ? String.valueOf((long) v) : String.format("%.1f", v);
    }

    static float easeOutBack(float x) {
        float c1 = 1.70158f, c3 = c1 + 1;
        return 1 + c3 * (float) Math.pow(x - 1, 3) + c1 * (float) Math.pow(x - 1, 2);
    }

    static float easeOutBounce(float x) {
        float n1 = 7.5625f, d1 = 2.75f;
        if (x < 1 / d1) return n1 * x * x;
        if (x < 2 / d1) return n1 * (x -= 1.5f / d1) * x + 0.75f;
        if (x < 2.5 / d1) return n1 * (x -= 2.25f / d1) * x + 0.9375f;
        return n1 * (x -= 2.625f / d1) * x + 0.984375f;
    }

    static int hue(float h) {
        return Color.HSBtoRGB(h, 0.85f, 1f) & 0xFFFFFF;
    }

    static void spawnParticles(Alert a, int w, int h) {
        int count = switch (a.tier) {
            case 0 -> 0;
            case 1 -> 25;
            case 2 -> 60;
            case 3 -> 90;
            default -> 160;
        };
        for (int i = 0; i < count; i++) {
            Particle p = new Particle();
            p.x = rng.nextFloat() * w;
            p.size = 2 + rng.nextInt(3);
            switch (a.tier) {
                case 1 -> { // green sparkles float up from the bottom
                    p.y = h + rng.nextFloat() * 40;
                    p.vx = (rng.nextFloat() - 0.5f) * 30;
                    p.vy = -(60 + rng.nextFloat() * 100);
                    p.color = 0x55FF55;
                }
                case 2 -> { // gold coins rain down
                    p.y = -rng.nextFloat() * 120;
                    p.vx = (rng.nextFloat() - 0.5f) * 20;
                    p.vy = 90 + rng.nextFloat() * 120;
                    p.color = rng.nextBoolean() ? 0xFFD700 : 0xFFF2A0;
                }
                case 3 -> { // purple sparks shoot outward from center
                    p.x = w / 2f;
                    p.y = h * 0.22f;
                    double ang = rng.nextDouble() * Math.PI * 2;
                    float sp = 80 + rng.nextFloat() * 220;
                    p.vx = (float) Math.cos(ang) * sp;
                    p.vy = (float) Math.sin(ang) * sp;
                    p.color = rng.nextBoolean() ? 0xC040FF : 0xFFFFFF;
                }
                default -> { // nuke: rainbow confetti + red embers
                    p.y = -rng.nextFloat() * 200;
                    p.vx = (rng.nextFloat() - 0.5f) * 90;
                    p.vy = 120 + rng.nextFloat() * 220;
                    p.color = rng.nextInt(4) == 0 ? 0xFF4020 : hue(rng.nextFloat());
                }
            }
            particles.add(p);
        }
    }

    static void playSound(Alert a) {
        SoundEvent s = switch (a.tier) {
            case 0 -> SoundEvents.EXPERIENCE_ORB_PICKUP;
            case 1 -> SoundEvents.PLAYER_LEVELUP;
            case 2 -> SoundEvents.UI_TOAST_CHALLENGE_COMPLETE;
            case 3 -> SoundEvents.LIGHTNING_BOLT_THUNDER;
            default -> SoundEvents.WITHER_SPAWN;
        };
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(s, 1.0f));
    }

    static int tierRgb(int tier, long now, float offset) {
        return switch (tier) {
            case 2 -> blend(0xFFD700, 0xFFFFFF, 0.5f + 0.5f * (float) Math.sin(now / 120.0 + offset));
            case 3 -> blend(0xC040FF, 0xFFFFFF, 0.5f + 0.5f * (float) Math.sin(now / 90.0 + offset));
            case 4 -> hue(((now % 1200) / 1200f + offset * 0.08f) % 1f);
            default -> COLORS[tier];
        };
    }

    static int blend(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }

    /** Draws the big amount one character at a time (for wave/shimmer). */
    static void drawAmount(GuiGraphicsExtractor g, Font font, String text, float cx, float cy,
                           float scale, long now, int tier, int alpha) {
        float total = font.width(text) * scale;
        float x = cx - total / 2f;
        float amp = tier == 4 ? 5f : (tier == 1 ? 1.5f : 0f);
        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            float wave = amp * (float) Math.sin(now / 130.0 + i * 0.9);
            int argb = (alpha << 24) | tierRgb(tier, now, i);
            g.pose().pushMatrix();
            g.pose().translate(x, cy + wave);
            g.pose().scale(scale, scale);
            g.text(font, Component.literal(ch), 0, 0, argb, true);
            g.pose().popMatrix();
            x += font.width(ch) * scale;
        }
    }

    static void drawLine(GuiGraphicsExtractor g, Font font, String text, float cx, float y,
                         float scale, int argb) {
        g.pose().pushMatrix();
        g.pose().translate(cx, y);
        g.pose().scale(scale, scale);
        g.centeredText(font, Component.literal(text), 0, 0, argb);
        g.pose().popMatrix();
    }

    static void render(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        long now = System.currentTimeMillis();
        float dt = Math.min((now - lastFrame) / 1000f, 0.1f);
        lastFrame = now;
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();

        if (current != null && now - current.start > DURATIONS[current.tier]) current = null;
        if (current == null && !queue.isEmpty()) {
            current = queue.poll();
            current.start = now;
            spawnParticles(current, w, h);
            playSound(current);
        }

        // Particles
        particles.removeIf(p -> p.y > h + 20 || p.y < -260 || p.x < -20 || p.x > w + 20);
        for (Particle p : particles) {
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            g.fill((int) p.x, (int) p.y, (int) p.x + p.size, (int) p.y + p.size, 0xFF000000 | p.color);
        }

        if (current == null) return;

        Alert a = current;
        int tier = a.tier;
        long el = now - a.start;
        long dur = DURATIONS[tier];
        float t = el / (float) dur;
        float p = Math.min(1f, el / 600f); // intro progress
        float fade = t > 0.85f ? Math.max(0f, (1f - t) / 0.15f) : 1f;
        int alpha = (int) (255 * fade);
        if (alpha < 8) return;

        float cx = w / 2f;
        float cy = h * 0.20f;
        float sc = 1f;
        float shakeX = 0, shakeY = 0;

        // ---------- Per-tier animation ----------
        switch (tier) {
            case 0 -> cy -= (1f - easeOutBack(Math.min(1f, el / 450f))) * 50f; // gentle drop-in
            case 1 -> cx -= (1f - easeOutBack(p)) * w * 0.6f;                   // slide in from left w/ overshoot
            case 2 -> sc = Math.max(0.01f, easeOutBack(p));                     // zoom pop
            case 3 -> {                                                         // slam from big + shake
                sc = 1f + (1f - p) * 1.8f;
                if (el < 1200) {
                    shakeX = (rng.nextFloat() - 0.5f) * 8;
                    shakeY = (rng.nextFloat() - 0.5f) * 8;
                }
            }
            default -> {                                                        // nuke: bounce-drop from sky + heavy shake
                cy -= (1f - easeOutBounce(Math.min(1f, el / 900f))) * h * 0.6f;
                if (el < 1800) {
                    shakeX = (rng.nextFloat() - 0.5f) * 14;
                    shakeY = (rng.nextFloat() - 0.5f) * 14;
                }
            }
        }
        cx += shakeX;
        cy += shakeY;

        // ---------- Screen effects ----------
        int rgbMain = tierRgb(tier, now, 0);
        if (tier >= 2) { // glow bars top + bottom
            float pulse = 0.5f + 0.5f * (float) Math.sin(now / 150.0);
            int ga = (int) (alpha * (0.12f + 0.2f * pulse));
            g.fill(0, 0, w, 8, (ga << 24) | rgbMain);
            g.fill(0, h - 8, w, h, (ga << 24) | rgbMain);
        }
        if (tier == 3 && el < 350) { // lightning flash
            int fa = (int) (110 * (1f - el / 350f));
            g.fill(0, 0, w, h, (fa << 24) | 0xFFFFFF);
        }
        if (tier == 4 && el < 900) { // nuke flash: white -> red
            float k = el / 900f;
            int fa = (int) (170 * (1f - k));
            g.fill(0, 0, w, h, (fa << 24) | blend(0xFFFFFF, 0xFF3010, k));
        }
        if (tier >= 3 && el < 1200) { // shockwave rings
            for (int r = 0; r < (tier == 4 ? 3 : 1); r++) {
                float k = (el - r * 200f) / 1000f;
                if (k < 0 || k > 1) continue;
                int half = (int) (20 + k * w * 0.45f);
                int ra = (int) (alpha * (1f - k) * 0.8f);
                int col = (ra << 24) | rgbMain;
                int x1 = (int) cx - half, x2 = (int) cx + half;
                int y1 = (int) cy - half / 3, y2 = (int) cy + half / 3;
                g.fill(x1, y1, x2, y1 + 2, col);
                g.fill(x1, y2, x2, y2 + 2, col);
                g.fill(x1, y1, x1 + 2, y2, col);
                g.fill(x2, y1, x2 + 2, y2 + 2, col);
            }
        }

        // ---------- Text (same base size for all tiers) ----------
        drawAmount(g, font, "$" + format(a.amount), cx, cy, BASE_SCALE * sc, now, tier, alpha);

        float lineAlpha = Math.min(1f, Math.max(0f, (el - 250) / 400f)) * fade;
        int la = (int) (255 * lineAlpha);
        if (la > 8) {
            drawLine(g, font, "<" + a.name + "> " + VERBS[tier] + " $" + format(a.amount),
                    cx, cy + 30, 1.5f, (la << 24) | 0xFFFFFF);
            float b = lineAlpha * Math.min(1f, Math.max(0f, (el - 650) / 400f));
            int fa = (int) (255 * b);
            if (fa > 8) drawLine(g, font, a.funny, cx, cy + 46, 1.2f, (fa << 24) | 0xAAAAAA);
        }
    }
}
