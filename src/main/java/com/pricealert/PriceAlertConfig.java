package com.pricealert;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Saved to .minecraft/config/pricealert.json (only this one file is read/written).
 */
public class PriceAlertConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("pricealert.json");
    private static PriceAlertConfig instance = new PriceAlertConfig();

    /** One item you are watching and the most you are willing to pay. */
    public static class Watch {
        public String item;
        public double maxPrice;

        public Watch() {}

        public Watch(String item, double maxPrice) {
            this.item = item;
            this.maxPrice = maxPrice;
        }
    }

    public boolean enabled = true;
    public boolean playSound = true;
    /** Compare price per single item (price / quantity) instead of the total listing price. */
    public boolean comparePerItem = false;
    /** Only react to messages that look like a listing (contain one of the keywords below). */
    public boolean requireKeyword = true;
    /** Seconds each on-screen alert stays up. */
    public float displaySeconds = 6f;

    public List<Watch> watches = new ArrayList<>();

    public List<String> keywords = new ArrayList<>(List.of(
            "auction", "auctioned", "listed", "listing", "selling", "for sale", "sell",
            "[ah]", "ah", "bin", "shop", "market", "price", "buy it now"));

    public static PriceAlertConfig get() {
        return instance;
    }

    public static void load() {
        if (Files.exists(FILE)) {
            try (Reader r = Files.newBufferedReader(FILE)) {
                PriceAlertConfig c = GSON.fromJson(r, PriceAlertConfig.class);
                if (c != null) {
                    if (c.watches == null) c.watches = new ArrayList<>();
                    if (c.keywords == null) c.keywords = new ArrayList<>();
                    instance = c;
                }
            } catch (Exception e) {
                PriceAlertClient.LOGGER.warn("Couldn't read pricealert.json, using defaults", e);
            }
        }
        save();
    }

    public static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer w = Files.newBufferedWriter(FILE)) {
                GSON.toJson(instance, w);
            }
        } catch (Exception e) {
            PriceAlertClient.LOGGER.warn("Couldn't save pricealert.json", e);
        }
    }
}
