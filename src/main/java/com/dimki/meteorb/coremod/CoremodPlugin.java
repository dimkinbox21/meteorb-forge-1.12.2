package com.dimki.meteorb.coremod;

import java.util.Map;

import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Forge 1.12.2 coremod - the GUI key hook, for ChestDump.
 *
 * <p>AutoJump is vanilla in 1.12.2 (GameSettings.AUTO_JUMP), so the entity
 * transformer of the 1.8 build is gone; this carries only the keyboard half.
 *
 * <p>A KeyBinding is polled only while no screen is open, so ChestDump's bind
 * could never fire with a chest on screen - which is where it is used. Vanilla
 * routes a key pressed with a screen open through
 * {@code GuiScreen.handleKeyboardInput()}, which drains the LWJGL queue and
 * calls the open screen's {@code keyTyped(char, int} - so that method is where
 * the hook goes, exactly as in 1.8.
 *
 * <p>Forge's {@code GuiScreenEvent.KeyboardInputEvent} would have been the
 * cleaner door, and it was checked: in 1.12.2 nothing posts it. No class in the
 * Forge jar refers to it, and {@code GuiScreen.handleKeyboardInput} in the
 * released (patched) classes carries no Forge call - so an event subscription
 * there would simply never fire. That is why this is still a transformer.
 */
/**
 * ...
 */
@net.minecraftforge.fml.relauncher.IFMLLoadingPlugin.MCVersion("1.12.2")
public class CoremodPlugin implements IFMLLoadingPlugin {

    /** GuiScreen, the readable name in slash form. */
    private static final String GUI_CLASS =
            "net/minecraft/client/gui/GuiScreen";

    /**
     * The same name in dotted form. Forge hands the transformer a name in one
     * stage and the other later - measured on 1.8 as "obfuscated / dotted", and
     * the same trap applies here - so both spellings are checked.
     */
    private static final String GUI_CLASS_DOTTED =
            "net.minecraft.client.gui.GuiScreen";

    private static final String KEY_HOOK_OWNER = "com/dimki/meteorb/GuiKeyHook";
    private static final String KEY_SUPPRESS_NAME = "meteorbSuppress";
    private static final String KEY_SUPPRESS_DESC = "(I)I";

    @Override
    public String[] getASMTransformerClass() {
        return new String[] {CoremodPlugin.class.getName() + "$GuiKeyTransformer"};
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }

    /** The only transformer: fold the key verdict into the key code. */
    public static class GuiKeyTransformer implements IClassTransformer {

        @Override
        public byte[] transform(String name, String transformedName,
                                byte[] basicClass) {
            if (basicClass == null || basicClass.length == 0
                    || transformedName == null
                    || !(GUI_CLASS.equals(transformedName)
                         || GUI_CLASS_DOTTED.equals(transformedName))) {
                return basicClass;
            }
            try {
                return inject(basicClass);
            } catch (Throwable t) {
                System.err.println("[MeteorB] GUI key hook injection failed: " + t);
                t.printStackTrace();
                return basicClass;
            }
        }

        /**
         * Inserts the hook at the head of handleKeyboardInput, before the
         * screen reads the key.
         *
         * <p>The verdict is folded into the key code the screen is about to see,
         * so the injection stays branch-free: substituting one stack slot needs
         * no stack-map frames, and ASM never has to resolve a class hierarchy
         * it cannot see at this stage. A consumed key becomes 0, which
         * vanilla's own keyTyped treats as "no key".
         */
        private byte[] inject(byte[] basicClass) {
            ClassNode cn = new ClassNode();
            new ClassReader(basicClass).accept(cn, 0);

            MethodNode target = null;
            for (Object o : cn.methods) {
                MethodNode mn = (MethodNode) o;
                // The keyboard handler is the method that calls keyTyped with a
                // char and an int. Its name is obfuscated at this stage, so it
                // is found by the descriptor it calls, not by a name.
                if (!"()V".equals(mn.desc)) {
                    continue;
                }
                if (callsMethodWithDesc(mn, "(CI)V")) {
                    target = mn;
                    break;
                }
            }
            if (target == null) {
                System.err.println("[MeteorB] GUI keyboard handler not found in "
                        + GUI_CLASS);
                return basicClass;
            }

            // The keyTyped call takes (char, int) on `this`, so the stack there
            // is [this, char, key]; replacing the top slot keeps all three.
            InsnList hook = new InsnList();
            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, KEY_HOOK_OWNER,
                    KEY_SUPPRESS_NAME, KEY_SUPPRESS_DESC, false));

            target.instructions.insertBefore(keyTypedCall(target), hook);

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            System.err.println("[MeteorB] GUI key hook injected into "
                    + GUI_CLASS + "." + target.name + target.desc);
            return cw.toByteArray();
        }

        /** The keyTyped call inside the keyboard handler. */
        private AbstractInsnNode keyTypedCall(MethodNode mn) {
            for (AbstractInsnNode i = mn.instructions.getFirst(); i != null;
                 i = i.getNext()) {
                if (i.getType() == AbstractInsnNode.METHOD_INSN) {
                    MethodInsnNode call = (MethodInsnNode) i;
                    if ("(CI)V".equals(call.desc)) {
                        return i;
                    }
                }
            }
            return null;
        }

        private boolean callsMethodWithDesc(MethodNode mn, String desc) {
            for (AbstractInsnNode i = mn.instructions.getFirst(); i != null;
                 i = i.getNext()) {
                if (i.getType() == AbstractInsnNode.METHOD_INSN) {
                    MethodInsnNode call = (MethodInsnNode) i;
                    if (desc.equals(call.desc)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
