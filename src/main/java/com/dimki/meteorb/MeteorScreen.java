package com.dimki.meteorb;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

import java.util.List;

/**
 * Meteor-style module menu, the same design as the b1.7.3 and Forge 1.8
 * builds: dark translucent panel, one row per module, toggle on left click,
 * bind on right click, capture the next key press, Escape cancels, Delete
 * clears, and an occupied key warns before it is taken.
 *
 * <p>Three changes from the 1.8 version, each read out of the 1.12.2 jar:
 *
 * <ul>
 *   <li>the font field is {@code fontRenderer}, not {@code fontRendererObj} -
 *       MCP renamed it;</li>
 *   <li>{@code FontRenderer.drawStringWithShadow} takes float coordinates in
 *       1.12.2 (it took ints in 1.8), so the x and y arguments are cast;</li>
 *   <li>{@code drawGradientRect} is inherited from {@code Gui} rather than
 *       declared on {@code GuiScreen}, which changes nothing at the call site
 *       but is why it is not found by searching GuiScreen.</li>
 * </ul>
 *
 * <p>Key handling is unchanged in shape: vanilla drains the keyboard queue in
 * {@code GuiScreen.handleKeyboardInput()} and hands each press to the open
 * screen, so the bind capture still lives here.
 */
public class MeteorScreen extends GuiScreen {
    private static final int PANEL_COLOR = 0xC0101014;
    private static final int HEADER_COLOR = 0xFF30B5FF;   // Meteor blue
    private static final int TEXT_COLOR = 0xFFE8E8E8;
    private static final int TEXT_DIM = 0xFF9A9A9A;
    private static final int ENABLED_COLOR = 0xFF55FF7F;
    private static final int DISABLED_COLOR = 0xFFFF5555;
    private static final int WARNING_COLOR = 0xFFFFAA00;

    private static final int ROW_HEIGHT = 14;
    private static final int PANEL_WIDTH = 190;

    private static boolean bindCapturing = false;
    private static Module pendingModule = null;
    private static String pendingWarning = null;

    @Override
    public void initGui() {
        // No vanilla buttons; custom rendering only.
    }

    /** Vanilla would pause the game; this menu must not. */
    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    /** Called by the key handler while a bind capture is pending. */
    public static boolean onKeyInMenu(int key) {
        if (!bindCapturing) {
            return false;
        }
        if (key == Keyboard.KEY_ESCAPE) {
            cancelCapture();
            return true;
        }
        if (key == Keyboard.KEY_DELETE) {
            pendingModule.setBindKey(BindManager.KEY_NONE);
            cancelCapture();
            return true;
        }
        // Conflict check: warn first, apply on second identical attempt.
        for (Module other : Modules.get().getAll()) {
            if (other != pendingModule && other.getBindKey() == key) {
                if (pendingWarning != null && pendingWarning.contains(other.getName())) {
                    other.setBindKey(BindManager.KEY_NONE);
                } else {
                    pendingWarning = "Used by " + other.getName() + " - press again";
                    return true;
                }
                break;
            }
        }
        pendingModule.setBindKey(key);
        cancelCapture();
        return true;
    }

    private static void cancelCapture() {
        bindCapturing = false;
        pendingModule = null;
        pendingWarning = null;
    }

    /** Whether the menu is waiting for a bind key press. */
    public static boolean isCapturing() {
        return bindCapturing;
    }

    private int panelX;
    private int panelY;
    private int panelH;

    private int rowIndexAt(int mx, int my) {
        int x = panelX;
        int y = panelY;
        if (mx < x || mx > x + PANEL_WIDTH || my < y || my > y + panelH) {
            return -1;
        }
        int rel = my - (y + 18);
        if (rel < 0) {
            return -1;
        }
        int idx = rel / ROW_HEIGHT;
        if (idx >= Modules.get().getAll().size()) {
            return -1;
        }
        return idx;
    }

    @Override
    public void drawScreen(int mx, int my, float partialTicks) {
        // Dim background like vanilla screens.
        this.drawWorldBackground(0);

        FontRenderer tr = this.fontRenderer;
        List<Module> modules = Modules.get().getAll();

        panelH = 18 + modules.size() * ROW_HEIGHT + 6;
        panelX = (this.width - PANEL_WIDTH) / 2;
        panelY = (this.height - panelH) / 2;

        this.drawGradientRect(panelX, panelY, panelX + PANEL_WIDTH, panelY + panelH,
                PANEL_COLOR, PANEL_COLOR);
        // Header underline.
        this.drawGradientRect(panelX, panelY + 14, panelX + PANEL_WIDTH, panelY + 15,
                HEADER_COLOR, HEADER_COLOR);

        // 1.12.2 takes float coordinates here, 1.8 took ints.
        tr.drawStringWithShadow("MeteorB", (float) (panelX + 6), (float) (panelY + 4),
                HEADER_COLOR);

        int y = panelY + 18;
        for (int i = 0; i < modules.size(); i++) {
            Module module = modules.get(i);
            boolean hovered = rowIndexAt(mx, my) == i;
            if (hovered) {
                this.drawGradientRect(panelX + 2, y - 2, panelX + PANEL_WIDTH - 2,
                        y + ROW_HEIGHT - 3, 0x30FFFFFF, 0x30FFFFFF);
            }

            int stateColor = module.isEnabled() ? ENABLED_COLOR : DISABLED_COLOR;
            tr.drawStringWithShadow(module.isEnabled() ? "ON " : "OFF",
                    (float) (panelX + 6), (float) y, stateColor);
            tr.drawStringWithShadow(module.getName(), (float) (panelX + 34),
                    (float) y, TEXT_COLOR);

            String bindText = bindCapturing && pendingModule == module
                    ? "..." : keyName(module.getBindKey());
            int bindW = tr.getStringWidth(bindText);
            int bindColor = (bindCapturing && pendingModule == module)
                    ? WARNING_COLOR : TEXT_DIM;
            tr.drawStringWithShadow(bindText,
                    (float) (panelX + PANEL_WIDTH - bindW - 6), (float) y, bindColor);

            y += ROW_HEIGHT;
        }

        if (pendingWarning != null) {
            int w = tr.getStringWidth(pendingWarning);
            tr.drawStringWithShadow(pendingWarning,
                    (float) (panelX + (PANEL_WIDTH - w) / 2),
                    (float) (panelY + panelH + 4), WARNING_COLOR);
        }

        // Footer hint lines under the panel, dim.
        String hint1 = bindCapturing ? "Press a key for " + pendingModule.getName()
                : "L-click: toggle  R-click: bind";
        String hint2 = bindCapturing ? "ESC cancel, DEL clear"
                : "menu key: " + keyName(BindManager.getMenuKey())
                        + " (R-click a module to bind)";
        int h1w = tr.getStringWidth(hint1);
        int h2w = tr.getStringWidth(hint2);
        int base = panelY + panelH + (pendingWarning != null ? 16 : 4);
        tr.drawStringWithShadow(hint1, (float) (panelX + (PANEL_WIDTH - h1w) / 2),
                (float) base, TEXT_DIM);
        tr.drawStringWithShadow(hint2, (float) (panelX + (PANEL_WIDTH - h2w) / 2),
                (float) (base + 10), TEXT_DIM);
    }

    private static String keyName(int code) {
        if (code == BindManager.KEY_NONE) {
            return "none";
        }
        return Keyboard.getKeyName(code);
    }

    @Override
    protected void mouseClicked(int mx, int my, int button) {
        int idx = rowIndexAt(mx, my);
        if (idx < 0) {
            return;
        }
        Module module = Modules.get().getAll().get(idx);
        if (bindCapturing) {
            return;
        }
        if (button == 0) { // left: toggle
            module.toggle();
        } else if (button == 1) { // right: start bind capture
            bindCapturing = true;
            pendingModule = module;
            pendingWarning = null;
        }
    }

    @Override
    protected void keyTyped(char character, int key) {
        // While capturing, every key belongs to the capture. Outside a capture,
        // Escape closes the menu. The menu key itself is handled here too, so
        // the screen closes on the same press that opened it.
        if (bindCapturing) {
            MeteorScreen.onKeyInMenu(key);
            return;
        }
        if (key == Keyboard.KEY_ESCAPE) {
            BindManager.closeMenu();
            return;
        }
        if (key == BindManager.getMenuKey()) {
            BindManager.closeMenu();
        }
    }

    @Override
    public void onGuiClosed() {
        BindManager.menuOpen = false;
        cancelCapture();
        // No write here on purpose: the config is saved when the world or the
        // game exits, so opening and closing the menu does not touch the file.
    }
}
