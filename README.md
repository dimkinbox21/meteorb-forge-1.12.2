# MeteorB (Forge 1.12.2)

Port in progress. This file records what was measured while getting the build
chain running, before any module is ported.

## What already works

The whole derive/reobf chain from the 1.8 project runs unchanged on 1.12.2:

    C:\tools\jdk8u504-b01\bin\javac.exe ... tools\DeriveMcpJar.java ...  # class 52, JDK 8 fine
    C:\Users\dimki\projects\forge-1.12.2-meteor\derive.cmd

Three mapping artifacts, all recovered the way 1.8's were:

| Stage | 1.8 | 1.12.2 |
|---|---|---|
| obf -> Searge | `deobfuscation_data-1.8.lzma` | `deobfuscation_data-1.12.2.lzma`, decoded to `tools/joined.srg` |
| Searge -> MCP | `mcp_snapshot_20141130` | `mcp_snapshot-20171003-1.12` (`fields.csv`, `methods.csv`, `params.csv`) |

Both come from `https://maven.minecraftforge.net/`, inside the Forge jar and at
`de/oceanlabs/mcp/mcp_snapshot/20171003-1.12/`. The MDK at
`.../forge-1.12.2-14.23.5.2859-mdk.zip` names the mapping snapshot in its
`build.gradle`, which is how the coordinates were found.

Derived jar: `libs/mc-1.12.2-mcp.jar` (2050 classes renamed, 3344 class /
11915 field / 20817 method entries). Reobf map: 28706 pairs, all owner-aware,
zero csv-only. `src/main/java/com/dimki/meteorb/Probe112.java` compiles against
the derived jar and reobfuscates correctly; run `derive.cmd` then `probe.cmd`.

## 1.12.2 differences found by the compiler

* **Shift-click is an enum.** `Container.slotClick` is now
  `(IILnet/minecraft/inventory/ClickType;EntityPlayer)ItemStack`. Two problems
  with it: `ClickType`'s constants are NOT renamed by the mapping - 0 of 7
  survived MCP - so `ClickType.PICKUP` does not compile; use
  `ClickType.values()[0]`. Every `windowClick` call in ChestDump and AutoArmor
  has to change.
* **Armour is typed by slot, not int.** `ItemArmor.armorType` is an
  `EntityEquipmentSlot`; `damageReduceAmount` survived, and `toughness` is new.
* **The inventory is a list.** `InventoryPlayer.mainInventory` is a
  `NonNullList` whose generic type is a non-public obfuscated class, so
  `mainInventory.get(i)` does not compile; `getStackInSlot(i)` is the public
  door. `armorItemInSlot(i)` likewise.
* **`getCooldownTracker()` has no MCP name** - it stayed `func_184811_cZ`, so
  it is reached by reflection, as 1.8 reached `func_152377_a`.

## What vanilla does for us now

* **AutoJump** - `GameSettings$Options.AUTO_JUMP`; player flags
  `field_184829_a`/`field_184830_b`. Nothing to port.
* **Combat cooldown** - `getCooledAttackStrength(partial)` is the damage
  multiplier, and `CooldownTracker.getCooldown(Item, partial)` is the charge.
  KillAura's "best weapon" has to become "best damage over one cooldown cycle".

## Not yet known

`SlotArmor` does not appear in the TSRG under that name, so AutoArmor's slot
mapping must be re-derived from bytecode the way 1.8's was.
