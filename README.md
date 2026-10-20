# MeteorB (Forge 1.12.2)

All eight modules are ported and confirmed in game.

    Minecraft 1.12.2 + Forge 14.23.5.2859
    Instance: C:\Users\dimki\AppData\Roaming\PrismLauncher\instances\1.12.2
    Repo: https://github.com/dimkinbox21/meteorb-forge-1.12.2

Lineage: written for Beta 1.7.3 on Babric
([`babric-meteor-mod`](https://github.com/dimkinbox21/meteorb-babric-1.7.3)),
then ported to Forge 1.8
([`meteorb-forge-1.8`](https://github.com/dimkinbox21/meteorb-forge-1.8)).
This is the 1.12.2 port.

## Modules

Each is toggled from the in-game menu, optionally bound to a key, and persists
in `config/meteorb.json`. **Right Shift** opens the menu; left click a row
toggles it, right click starts a bind capture, Escape cancels, Delete clears.

| Module | Behaviour |
|---|---|
| **ChestDump** | One key dumps the inventory into an open chest, hotbar preserved. |
| **AutoTool** | While mining, the best tool is held and the previous slot comes back. |
| **Scaffold** | Places a block under the feet over a gap, pillars up while jump is held. |
| **AutoArmor** | Equips better armour from the inventory, and puts the old piece back. |
| **KillAura** | Attacks the nearest hostile mob, switching to the best weapon while a fight lasts. |
| **AutoEat** | Eats when the bar is down to 3.5 icons, or when hurt and hunger has started. |

AutoJump is absent on purpose: vanilla grew `GameSettings$Options.AUTO_JUMP` in
1.9, and reimplementing it would be a downgrade.

ChestDump was the only module registered-and-enabled at first; the registry is
a plain list now and adding a module is one line in `Modules.java`.

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
  ChestDump needs `values()[1]` (QUICK_MOVE), AutoArmor `values()[0]` (PICKUP).
* `ItemStack.stackSize` is private now - `getCount()` is the accessor.
* `GuiScreenEvent.KeyboardInputEvent` exists but **is not posted** by 1.12.2
  Forge at all: nothing in the Forge jar refers to it, and the released
  `GuiScreen.handleKeyboardInput` carries no Forge call. An event subscription
  there would never fire, so ChestDump's key still goes through a coremod
  transformer, and both of that transformer's 1.8 fixes (swallowed Escape, and
  every key being handed back as KEY_ESCAPE) were carried across too.
* Placement is `PlayerControllerMP.processRightClickBlock`, which takes an extra
  `EnumHand` and shifts every later parameter by one. Scaffold finds the method
  by which types appear rather than by index, so it compiles against either jar.
* `MathHelper.floor_double` is `MathHelper.floor`, and `Vec3` is `Vec3d`.

## The parts that cost the most to get right

Recorded because each of these looked right, read correctly off a jar, and was
still wrong - the compile-time jar's names are not always the names the game
uses.

**Scaffold opened chests.** The placement call activates the neighbour block
*before* it attempts a placement, and a chest's handler opens its container and
answers true, so the click looks successful and places nothing. Three fixes
failed before the cause was found: `block instanceof IInventory` is false for
every chest (the block is not its own inventory - `BlockContainer` implements
only `ITileEntityProvider` and the contents live in the tile entity), and
`getDeclaredMethod("onBlockActivated", ...)` never matches, because
reobfuscation maps member names back to Searge and a string literal is invisible
to the reobfuscator. The working filter asks what the block *does*: it must
declare an activation handler - matched by parameter shape, never by name - and
carry a tile entity or be a workbench.

**AutoArmor's two slot numberings are offset, not mirrored.** `ContainerPlayer`
builds its armour slots from `VALID_EQUIPMENT_SLOTS = {HEAD, CHEST, LEGS, FEET}`,
so container 0 is the head; `EntityEquipmentSlot.getSlotIndex()` reports FEET 1,
LEGS 2, CHEST 3, HEAD 4, because MAINHAND holds 0 and OFFHAND holds 5. Reading
the enum's constructor arguments off the compile-time jar yields a plausible
table that is off by one, which is why the numbers here come from the game and
not from the jar.

**KillAura's charge is not the CooldownTracker.** `CooldownTracker.setCooldown`
has exactly two callers in this jar - `EntityMob` and `NetHandlerPlayClient` -
so for a player `hasCooldown(item)` is always false, and a gate built on it
never waits. The player's charge is a counter `getCooledAttackStrength` reports
directly. The weapon score became damage per *second*, because 1.9 gives combat
a cooldown: vanilla's formula is `base * strength * (0.2 + 0.8 * strength)` over
a cycle of `20.0 / attackSpeed` ticks.

**Spiders.** The client cannot see a spider's target - `entityToAttack` is never
networked - so the rule is the two halves vanilla's own AI applies: provocation
first (`hurtTime > 0` or health below maximum), then the light gate that decides
who may *start* a fight. A wounded spider in full daylight is correctly a
target; an untouched one in full daylight is correctly not.

## AutoJump

Gone, deliberately. Vanilla has it now (`GameSettings$Options.AUTO_JUMP`), and
the module that used to be here would only be a worse copy of the built-in.
