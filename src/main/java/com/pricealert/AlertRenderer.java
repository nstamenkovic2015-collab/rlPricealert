package com.pricealert;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;

import java.util.ArrayDeque;
import java.util.Deque;

/** Slide-in alert card in the top-right corner of the game window. */
public final class AlertRenderer {

    private static final int SLIDE_TICKS = 8;
    private static final int MAX_QUEUE = 10;
    private static final int W = 200, H = 58;

    private static final Deque<ListingParser.Listing> QUEUE = new ArrayDeque<>();
    private static ListingParser.Listing current;
    private static int age, life;

    private AlertRenderer() {}

    public static void show(ListingParser.Listing listing) {
        if (QUEUE.size() >= MAX_QUEUE) QUEUE.pollFirst();
        QUEUE.addLast(listing);
    }

    public static void tick() {
        if (current == null) {
            current = QUEUE.pollFirst();
            if (current == null) return;
            age = 0;
            life = Math.max(40, (int) (PriceAlertConfig.get().displaySeconds * 20));
            playAlertSound(current);
        }
        if (++age >= life) current = null;
    }

    public static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (current == null) return;
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int sw = mc.getWindow().getGuiScaledWidth();
        float t = age + delta.getGameTimeDeltaPartialTick(false);

        float in = Mth.clamp(t / SLIDE_TICKS, 0f, 1f);
        float out = Mth.clamp((life - t) / SLIDE_TICKS, 0f, 1f);
        float slide = easeOutBack(in) * easeOutCubic(out);
        int x = (int) Mth.lerp(slide, sw + 10, sw - W - 6);
        int y = 6;

        ListingParser.Listing l = current;
        double compared = PriceAlertConfig.get().comparePerItem ? l.unitPrice() : l.price();
        int under = l.maxPrice() > 0 ? (int) Math.round((1 - compared / l.maxPrice()) * 100) : 0;
        boolean steal = under >= 50;
        int accent = steal ? 0xFFFFD54F : 0xFF39FF88;

        // pulsing glow
        float pulse = 0.5f + 0.5f * Mth.sin(t * 0.3f);
        int glow = ((int) (40 + 50 * pulse) << 24) | (accent & 0xFFFFFF);
        g.fill(x - 3, y - 3, x + W + 3, y + H + 3, glow);

        // card
        g.fill(x, y, x + W, y + H, 0xE6101418);
        g.fill(x, y, x + 3, y + H, accent);                       // side stripe
        outline(g, x, y, W, H, accent);

        String title = steal ? "STEAL ALERT!" : "PRICE ALERT";
        g.text(font, title, x + 8, y + 5, accent);
        if (!QUEUE.isEmpty()) {
            String more = "+" + QUEUE.size() + " more";
            g.text(font, more, x + W - 6 - font.width(more), y + 5, 0xFFAAAAAA);
        }

        String item = (l.quantity() > 1 ? l.quantity() + "x " : "") + l.item();
        g.text(font, fit(font, item, W - 16), x + 8, y + 17, 0xFFFFFFFF);

        String price = "$" + ListingParser.format(l.price());
        if (l.quantity() > 1) price += " ($" + ListingParser.format(l.unitPrice()) + " each)";
        g.text(font, fit(font, price, W - 16), x + 8, y + 28, 0xFFFFE08A);

        String info = "max $" + ListingParser.format(l.maxPrice()) + "  -  " + Math.max(0, under) + "% under";
        if (l.seller() != null) info += "  -  " + l.seller();
        g.text(font, fit(font, info, W - 16), x + 8, y + 39, 0xFFB0B8C0);

        // time-left bar
        float left = Mth.clamp(1f - t / life, 0f, 1f);
        g.fill(x + 3, y + H - 3, x + W, y + H, 0x40FFFFFF);
        g.fill(x + 3, y + H - 3, x + 3 + (int) ((W - 3) * left), y + H, accent);
    }

    // ------------------------------------------------------------------ helpers

    private static void playAlertSound(ListingParser.Listing l) {
        if (!PriceAlertConfig.get().playSound) return;
        double compared = PriceAlertConfig.get().comparePerItem ? l.unitPrice() : l.price();
        boolean steal = l.maxPrice() > 0 && compared <= l.maxPrice() * 0.5;
        sound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.6f);
        sound(steal ? SoundEvents.PLAYER_LEVELUP : SoundEvents.AMETHYST_BLOCK_CHIME, steal ? 1.3f : 1.0f);
    }

    private static void sound(SoundEvent s, float pitch) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(s, pitch));
    }

    private static String fit(Font font, String s, int maxW) {
        if (font.width(s) <= maxW) return s;
        while (s.length() > 1 && font.width(s + "...") > maxW) s = s.substring(0, s.length() - 1);
        return s + "...";
    }

    private static void outline(GuiGraphicsExtractor g, int x, int y, int w, int h, int c) {
        g.fill(x, y, x + w, y + 1, c);
        g.fill(x, y + h - 1, x + w, y + h, c);
        g.fill(x, y, x + 1, y + h, c);
        g.fill(x + w - 1, y, x + w, y + h, c);
    }

    private static float easeOutBack(float x) {
        float c1 = 1.70158f, c3 = c1 + 1f;
        return 1f + c3 * (float) Math.pow(x - 1f, 3) + c1 * (float) Math.pow(x - 1f, 2);
    }

    private static float easeOutCubic(float x) {
        return 1f - (float) Math.pow(1f - x, 3);
    }
}
