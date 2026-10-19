# MeteorB (Forge 1.12.2)

Port in progress. Menu and ChestDump run in game; more modules follow.

    Minecraft 1.12.2 + Forge 14.23.5.2859
    Instance: C:\Users\dimki\AppData\Roaming\PrismLauncher\instances\1.12.2

## Where it stands

The module menu and ChestDump both work in game. The menu's panel draws, left
click toggles a row, right click starts a bind capture, Escape cancels, Delete
clears, the conflict warning fires, and states and binds persist in
`config/meteorb.json` on world or game exit. ChestDump dumps the inventory into
an open chest on its key, hotbar preserved, and Escape still closes the menu
and every other screen normally.

ChestDump is registered and enabled by default. Adding a module is one line in
`Modules.java`; it is no longer necessary to leave the registry empty to keep
the build working.

## Build

    C:\Users\dimki\projects\forge-1.12.2-meteor\build.cmd      # compile
    C:\Users\dimki\projects\forge-1.12.2-meteor\package.cmd    # reobfuscate + jar

Both need the JDK 8 at `C:\tools\jdk8u504-b01` (1.12.2 is class file 52, and a
newer JDK is refused). `build.cmd` compiles against `libs/mc-1.12.2-mcp.jar`,
which `derive.cmd` produces from the obfuscated client jar; `package.cmd`
reobfuscates the mod's own classes back to Searge, because that is what Forge
loads them against.

`derive.cmd` and the three tools in `tools/` are unchanged from the 1.8 project.
The mapping artifacts are recovered the same way: Forge's jar carries
`deobfuscation_data-1.12.2.lzma` (obfuscated -> Searge), and the mapping snapshot
named by the MDK's `build.gradle` - `mcp_snapshot/20171003-1.12` - supplies the
second hop to MCP. Both are on `https://maven.minecraftforge.net/`.

## Differences from 1.8

Everything below was found by compiling or running against 1.12.2, not assumed.

* `GuiScreen`'s font field is `fontRenderer`, and `drawStringWithShadow` takes
  float coordinates where 1.8 took ints.
* `Minecraft.thePlayer` and `theWorld` are now `player` and `world`.
* `InventoryPlayer.mainInventory` is a NonNullList whose generic type is not public,
  so `get(i)` does not compile - `getStackInSlot(i)` is the door.
* `Container.slotClick` takes a `ClickType` enum, and its constants did not
  survive MCP - `ClickType.PICKUP` does not compile, `values()[ordinal]` does.
  This is the one that changes ChestDump and AutoArmor when they land.
* `ItemArmor.armorType` is an `EntityEquipmentSlot` and `toughness` is new.
* `getCooldownTracker` has no MCP name and is reached by reflection.
* `ItemStack.stackSize` is private now - `getCount()` is the accessor.
* `GuiScreenEvent.KeyboardInputEvent` exists but **is not posted** by 1.12.2
  Forge at all: nothing in the Forge jar refers to it, and the released
  `GuiScreen.handleKeyboardInput` carries no Forge call. An event subscription
  there would never fire, so ChestDump's key still goes through a coremod
  transformer, and both of that transformer's 1.8 fixes (swallowed Escape, and
  every key being handed back as KEY_ESCAPE) were carried across too.

## What vanilla does for us now

AutoJump: `GameSettings$Options.AUTO_JUMP`, no port needed. Combat cooldown:
`getCooledAttackStrength(partial)` is the damage multiplier, so KillAura's "best
weapon" becomes "best damage over one cooldown cycle".
