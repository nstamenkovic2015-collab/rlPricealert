# Price Alert (Fabric, Minecraft 26.3)

Watches chat for auction / shop listings of items you choose and pops up an alert
(top-right of the game window, with sound) when one is listed at or under your max price.
Separate mod - does not need Pay Popup.

## Commands
| Command | What it does |
|---|---|
| `/pricealert add <maxPrice> <item>` | Watch an item, e.g. `/pricealert add 1.5k Diamond` (k, m, b, t work) |
| `/pricealert remove <item>` | Stop watching an item |
| `/pricealert list` | Show everything you're watching |
| `/pricealert clear` | Remove all watches |
| `/pricealert toggle` | Turn alerts on / off |
| `/pricealert sound` | Turn sound on / off |
| `/pricealert peritem` | Compare price of ONE item (price / amount) instead of the total |
| `/pricealert strict` | On: only listing-looking messages. Off: any chat line with your item + a price |
| `/pricealert test` | Show a fake alert |

Settings are saved in `.minecraft/config/pricealert.json`. You can add your server's own
listing words to `"keywords"` there.

Gold "STEAL ALERT!" = 50% or more under your max.

## Build setup
Copy these from your Pay Popup repo into this folder (they already work for 26.3):
`build.gradle`, `settings.gradle`, `gradle.properties`, `gradlew`, `gradlew.bat`, the `gradle/` folder,
and `.github/workflows/` if you build on GitHub.

Then change only the names:
- `gradle.properties`: `maven_group=com.pricealert` and `archives_base_name=pricealert`
- `settings.gradle`: `rootProject.name = "pricealert"` (if it has that line)
