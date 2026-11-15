package com.pricealert;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a chat line and works out whether it is an auction / shop listing for a
 * watched item, and what the price is.
 *
 * Works with most formats, e.g.
 *   "Steve is selling 64x Diamond for $1,200"
 *   "[AH] Alex listed Netherite Ingot for 2.5k"
 *   "New auction: Elytra | Price: $150M | Seller: Notch"
 *   "Diamond Sword x1 - BIN 900"
 */
public final class ListingParser {

    private ListingParser() {}

    public record Listing(String item, double price, int quantity, double unitPrice,
                          String seller, double maxPrice, String raw) {}

    // a money number: 1200 / 1,200 / 1.5 / 1.5k / 2m / $300 ...
    private static final Pattern MONEY = Pattern.compile(
            "(?<![\\w.])(\\$\\s?)?(\\d{1,3}(?:,\\d{3})+|\\d+(?:\\.\\d+)?)\\s?([kmbtq])?(?![\\w])",
            Pattern.CASE_INSENSITIVE);

    // quantity: "64x", "64 x", "x64", "x 64"
    private static final Pattern QTY = Pattern.compile(
            "(?<![\\w.])(\\d{1,6})\\s?x(?![a-wyz])|(?<![a-z])x\\s?(\\d{1,6})(?![\\w.])",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SELLER_EXPLICIT = Pattern.compile(
            "(?:seller:?\\s*|\\bby\\s+|\\bfrom\\s+)([A-Za-z0-9_]{3,16})", Pattern.CASE_INSENSITIVE);
    private static final Pattern SELLER_VERB = Pattern.compile(
            "([A-Za-z0-9_]{3,16})\\s+(?:is selling|has listed|listed|is auctioning|put up|auctioned)",
            Pattern.CASE_INSENSITIVE);

    private static final String[] PRICE_WORDS = {"for", "price", "bin", "cost", "buy", "@", "each", "at"};

    /** Returns a listing if the message is a deal at or under your max price, otherwise null. */
    public static Listing parse(String message) {
        PriceAlertConfig cfg = PriceAlertConfig.get();
        if (message == null || message.isBlank() || cfg.watches.isEmpty()) return null;
        String lower = message.toLowerCase(Locale.ROOT);

        if (cfg.requireKeyword && !hasKeyword(lower, cfg.keywords)) return null;

        // which watched item is in this message? (longest name wins: "diamond sword" over "diamond")
        PriceAlertConfig.Watch match = null;
        int itemPos = -1;
        for (PriceAlertConfig.Watch w : cfg.watches) {
            if (w.item == null || w.item.isBlank()) continue;
            int pos = indexOfWord(lower, w.item.toLowerCase(Locale.ROOT).trim());
            if (pos >= 0 && (match == null || w.item.length() > match.item.length())) {
                match = w;
                itemPos = pos;
            }
        }
        if (match == null) return null;

        // collect money tokens, find quantity, pick the most price-looking one
        List<Token> tokens = new ArrayList<>();
        Matcher m = MONEY.matcher(message);
        while (m.find()) {
            Token t = new Token();
            t.start = m.start();
            t.end = m.end();
            t.dollar = m.group(1) != null;
            t.suffix = m.group(3) != null;
            t.value = parseAmount(m.group(2) + (m.group(3) == null ? "" : m.group(3)));
            tokens.add(t);
        }
        if (tokens.isEmpty()) return null;

        int quantity = 1;
        int qStart = -1, qEnd = -1, bestDist = Integer.MAX_VALUE;
        Matcher qm = QTY.matcher(message);
        while (qm.find()) {
            String n = qm.group(1) != null ? qm.group(1) : qm.group(2);
            int dist = Math.abs(qm.start() - itemPos);
            if (dist < bestDist && dist < match.item.length() + 14) {
                bestDist = dist;
                quantity = Math.max(1, Integer.parseInt(n));
                qStart = qm.start();
                qEnd = qm.end();
            }
        }
        for (Token t : tokens) {
            if (qStart >= 0 && t.start < qEnd && t.end > qStart) t.quantity = true;
        }

        Token best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Token t : tokens) {
            if (t.quantity) continue;
            // skip numbers that are part of the item name itself (e.g. "Sharpness 5")
            if (t.start >= itemPos && t.end <= itemPos + match.item.length()) continue;
            int score = 0;
            String before = lowerBefore(lower, t.start, 10);
            for (String word : PRICE_WORDS) if (before.contains(word)) { score += 3; break; }
            if (t.dollar) score += 2;
            if (t.suffix) score += 1;
            if (t.start > itemPos) score += 1;
            if (score > bestScore || (score == bestScore && best != null && t.start > best.start)) {
                best = t;
                bestScore = score;
            }
        }
        if (best == null || best.value <= 0) return null;

        double price = best.value;
        double unit = quantity > 1 ? price / quantity : price;
        double compared = cfg.comparePerItem ? unit : price;
        if (compared > match.maxPrice) return null;

        String seller = null;
        Matcher sm = SELLER_EXPLICIT.matcher(message);
        if (sm.find()) {
            seller = sm.group(1);
        } else {
            sm = SELLER_VERB.matcher(message);
            if (sm.find() && indexOfWord(match.item.toLowerCase(Locale.ROOT), sm.group(1).toLowerCase(Locale.ROOT)) < 0)
                seller = sm.group(1);
        }

        return new Listing(match.item, price, quantity, unit, seller, match.maxPrice, message);
    }

    // ------------------------------------------------------------------ helpers

    private static final class Token {
        int start, end;
        double value;
        boolean dollar, suffix, quantity;
    }

    private static boolean hasKeyword(String lower, List<String> keywords) {
        for (String k : keywords) {
            if (k == null || k.isBlank()) continue;
            if (indexOfWord(lower, k.toLowerCase(Locale.ROOT)) >= 0) return true;
        }
        return false;
    }

    /** Finds needle as a whole word/phrase (so "ah" doesn't match "yeah"). */
    private static int indexOfWord(String hay, String needle) {
        int from = 0;
        while (true) {
            int i = hay.indexOf(needle, from);
            if (i < 0) return -1;
            boolean startOk = i == 0 || !Character.isLetterOrDigit(hay.charAt(i - 1)) || !Character.isLetterOrDigit(needle.charAt(0));
            int e = i + needle.length();
            boolean endOk = e >= hay.length() || !Character.isLetterOrDigit(hay.charAt(e))
                    || !Character.isLetterOrDigit(needle.charAt(needle.length() - 1));
            // allow plural: "diamond" matches "diamonds"
            if (!endOk && e < hay.length() && hay.charAt(e) == 's'
                    && (e + 1 >= hay.length() || !Character.isLetterOrDigit(hay.charAt(e + 1)))) endOk = true;
            if (startOk && endOk) return i;
            from = i + 1;
        }
    }

    private static String lowerBefore(String lower, int pos, int len) {
        return lower.substring(Math.max(0, pos - len), pos);
    }

    /** "1.5k" -> 1500, "2,000" -> 2000, "3m" -> 3000000. Returns -1 if invalid. */
    public static double parseAmount(String s) {
        if (s == null) return -1;
        s = s.trim().replace("$", "").replace(",", "").toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return -1;
        double mult = 1;
        char last = s.charAt(s.length() - 1);
        switch (last) {
            case 'k' -> mult = 1e3;
            case 'm' -> mult = 1e6;
            case 'b' -> mult = 1e9;
            case 't' -> mult = 1e12;
            case 'q' -> mult = 1e15;
            default -> {}
        }
        if (mult != 1) s = s.substring(0, s.length() - 1);
        try {
            return Double.parseDouble(s) * mult;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 1500 -> "1.5K", 2000000 -> "2M". */
    public static String format(double v) {
        String[] units = {"", "K", "M", "B", "T", "Q"};
        int u = 0;
        while (Math.abs(v) >= 1000 && u < units.length - 1) {
            v /= 1000;
            u++;
        }
        String num = v >= 100 || v == Math.floor(v) ? String.format(Locale.ROOT, "%.0f", v)
                : String.format(Locale.ROOT, "%.2f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
        return num + units[u];
    }
}
