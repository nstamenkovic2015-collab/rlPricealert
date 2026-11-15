package com.pricealert;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Price Alert - watches chat for auction / shop listings of items you choose
 * and pops up an alert when one is listed at or under your max price.
 *
 * Commands:
 *   /pricealert add <maxPrice> <item name>   e.g. /pricealert add 1.5k Diamond
 *   /pricealert remove <item name>
 *   /pricealert list
 *   /pricealert clear
 *   /pricealert toggle      on / off
 *   /pricealert sound       sound on / off
 *   /pricealert peritem     compare price per single item instead of total
 *   /pricealert strict      only listing-looking messages (on) or any chat line (off)
 *   /pricealert test        shows a fake alert
 */
public class PriceAlertClient implements ClientModInitializer {

    public static final String MOD_ID = "pricealert";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final String PREFIX = "§6[PriceAlert]§r ";
    private static String lastMessage = "";
    private static long lastMessageTime;

    @Override
    public void onInitializeClient() {
        PriceAlertConfig.load();
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) handleMessage(message);
        });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "alert"), AlertRenderer::render);
        ClientTickEvents.END_CLIENT_TICK.register(client -> AlertRenderer.tick());
        registerCommands();
        LOGGER.info("Price Alert loaded - watching {} item(s)", PriceAlertConfig.get().watches.size());
    }

    private static void handleMessage(Component message) {
        if (!PriceAlertConfig.get().enabled) return;
        String text = message.getString();
        if (text.startsWith("[PriceAlert]")) return;

        // ignore the exact same line repeated within 5 s (servers often echo listings)
        long now = System.currentTimeMillis();
        if (text.equals(lastMessage) && now - lastMessageTime < 5000) return;
        lastMessage = text;
        lastMessageTime = now;

        ListingParser.Listing listing = ListingParser.parse(text);
        if (listing != null) {
            LOGGER.debug("Deal found: {} for {}", listing.item(), listing.price());
            AlertRenderer.show(listing);
        }
    }

    // ------------------------------------------------------------------ commands

    private static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(
                ClientCommands.literal("pricealert")
                        .executes(ctx -> help(ctx))
                        .then(ClientCommands.literal("add")
                                .then(ClientCommands.argument("maxPrice", StringArgumentType.word())
                                        .then(ClientCommands.argument("item", StringArgumentType.greedyString())
                                                .executes(PriceAlertClient::add))))
                        .then(ClientCommands.literal("remove")
                                .then(ClientCommands.argument("item", StringArgumentType.greedyString())
                                        .executes(PriceAlertClient::remove)))
                        .then(ClientCommands.literal("list").executes(PriceAlertClient::list))
                        .then(ClientCommands.literal("clear").executes(ctx -> {
                            PriceAlertConfig.get().watches.clear();
                            PriceAlertConfig.save();
                            return say(ctx, "Watch list cleared.");
                        }))
                        .then(ClientCommands.literal("toggle").executes(ctx -> {
                            PriceAlertConfig c = PriceAlertConfig.get();
                            c.enabled = !c.enabled;
                            PriceAlertConfig.save();
                            return say(ctx, "Price Alert is now " + onOff(c.enabled));
                        }))
                        .then(ClientCommands.literal("sound").executes(ctx -> {
                            PriceAlertConfig c = PriceAlertConfig.get();
                            c.playSound = !c.playSound;
                            PriceAlertConfig.save();
                            return say(ctx, "Sound is now " + onOff(c.playSound));
                        }))
                        .then(ClientCommands.literal("peritem").executes(ctx -> {
                            PriceAlertConfig c = PriceAlertConfig.get();
                            c.comparePerItem = !c.comparePerItem;
                            PriceAlertConfig.save();
                            return say(ctx, c.comparePerItem
                                    ? "Now comparing the price of §eone item§r (price / amount)."
                                    : "Now comparing the §etotal§r listing price.");
                        }))
                        .then(ClientCommands.literal("strict").executes(ctx -> {
                            PriceAlertConfig c = PriceAlertConfig.get();
                            c.requireKeyword = !c.requireKeyword;
                            PriceAlertConfig.save();
                            return say(ctx, c.requireKeyword
                                    ? "Strict mode " + onOff(true) + ": only messages that look like listings."
                                    : "Strict mode " + onOff(false) + ": any chat line with your item and a price.");
                        }))
                        .then(ClientCommands.literal("test").executes(PriceAlertClient::test))
        ));
    }

    private static int help(CommandContext<FabricClientCommandSource> ctx) {
        say(ctx, "Commands:");
        ctx.getSource().sendFeedback(Component.literal(
                "§e/pricealert add <maxPrice> <item>§7 - e.g. add 1.5k Diamond\n"
                        + "§e/pricealert remove <item>§7, §elist§7, §eclear\n"
                        + "§e/pricealert toggle§7, §esound§7, §eperitem§7, §estrict§7, §etest"));
        return 1;
    }

    private static int add(CommandContext<FabricClientCommandSource> ctx) {
        String priceArg = StringArgumentType.getString(ctx, "maxPrice");
        String item = StringArgumentType.getString(ctx, "item").trim();
        double max = ListingParser.parseAmount(priceArg);
        if (max <= 0) return say(ctx, "§cThat price isn't a number. Try 500, 1.5k, 2m...");
        if (item.isEmpty()) return say(ctx, "§cType an item name.");

        PriceAlertConfig c = PriceAlertConfig.get();
        c.watches.removeIf(w -> w.item != null && w.item.equalsIgnoreCase(item));
        c.watches.add(new PriceAlertConfig.Watch(item, max));
        PriceAlertConfig.save();
        return say(ctx, "Watching §f" + item + "§r at or under §a$" + ListingParser.format(max));
    }

    private static int remove(CommandContext<FabricClientCommandSource> ctx) {
        String item = StringArgumentType.getString(ctx, "item").trim();
        boolean removed = PriceAlertConfig.get().watches.removeIf(w -> w.item != null && w.item.equalsIgnoreCase(item));
        PriceAlertConfig.save();
        return say(ctx, removed ? "Stopped watching §f" + item : "§cYou weren't watching \"" + item + "\". See /pricealert list");
    }

    private static int list(CommandContext<FabricClientCommandSource> ctx) {
        PriceAlertConfig c = PriceAlertConfig.get();
        if (c.watches.isEmpty()) return say(ctx, "Not watching anything yet. Try §e/pricealert add 1.5k Diamond");
        say(ctx, "Watching " + c.watches.size() + " item(s)" + (c.enabled ? "" : " §c(alerts are OFF)") + ":");
        StringBuilder sb = new StringBuilder();
        for (PriceAlertConfig.Watch w : c.watches) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("§7- §f").append(w.item).append(" §7<= §a$").append(ListingParser.format(w.maxPrice));
        }
        ctx.getSource().sendFeedback(Component.literal(sb.toString()));
        return 1;
    }

    private static int test(CommandContext<FabricClientCommandSource> ctx) {
        PriceAlertConfig c = PriceAlertConfig.get();
        String item = c.watches.isEmpty() ? "Diamond" : c.watches.get(0).item;
        double max = c.watches.isEmpty() ? 2000 : c.watches.get(0).maxPrice;
        double price = Math.max(1, Math.round(max * 0.6));
        AlertRenderer.show(new ListingParser.Listing(item, price, 1, price, "TestSeller", max,
                "TestSeller is selling " + item + " for $" + ListingParser.format(price)));
        return say(ctx, "Showing a test alert...");
    }

    private static int say(CommandContext<FabricClientCommandSource> ctx, String msg) {
        ctx.getSource().sendFeedback(Component.literal(PREFIX + msg));
        return 1;
    }

    private static String onOff(boolean b) {
        return (b ? "§aON" : "§cOFF") + "§r";
    }
}
