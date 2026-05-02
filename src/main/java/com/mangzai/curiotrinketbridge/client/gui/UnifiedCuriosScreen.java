package com.mangzai.curiotrinketbridge.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraftforge.network.PacketDistributor;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.client.KeyRegistry;
import top.theillusivec4.curios.common.inventory.CosmeticCurioSlot;
import top.theillusivec4.curios.common.inventory.CurioSlot;
import top.theillusivec4.curios.common.inventory.container.CuriosContainer;
import top.theillusivec4.curios.common.inventory.container.CuriosContainerV2;
import top.theillusivec4.curios.common.network.NetworkHandler;
import top.theillusivec4.curios.common.network.client.CPacketPage;
import top.theillusivec4.curios.common.network.client.CPacketToggleCosmetics;
import top.theillusivec4.curios.common.network.client.CPacketToggleRender;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/**
 * 使用 Accessories 风格绘制 Curios/Trinkets 的统一饰品界面。
 * 服务端菜单仍沿用 Curios，保证多人同步、Shift 快捷移动和槽位验证不被破坏。
 */
public class UnifiedCuriosScreen<T extends AbstractContainerMenu> extends AbstractContainerScreen<T> {

    private static final ResourceLocation INVENTORY_TEXTURE = ResourceLocation.withDefaultNamespace("textures/gui/container/inventory.png");
    private static final int PANEL_PADDING = 7;
    private static final int BUTTON_SIZE = 12;

    private float legacyScroll;
    private boolean legacyDragging;

    protected UnifiedCuriosScreen(T menu, Inventory inventory, Component title) {
        super(menu, inventory, Component.translatable("screen.curio_trinkets_bridge.unified_accessories"));
        this.imageWidth = 176;
        this.imageHeight = 166;
        this.titleLabelX = 97;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = 74;
    }

    @Override
    protected void init() {
        super.init();
        int panelWidth = this.curioBounds().widthWithPadding();
        int centered = (this.width - this.imageWidth + panelWidth) / 2;
        int minLeft = Math.min(panelWidth + 8, Math.max(8, this.width - this.imageWidth - 8));
        int maxLeft = Math.max(minLeft, this.width - this.imageWidth - 8);
        this.leftPos = Mth.clamp(centered, minLeft, maxLeft);
        this.topPos = Math.max(8, (this.height - this.imageHeight) / 2);
    }

    @Override
    public void render(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.renderControls(guiGraphics, mouseX, mouseY);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
        this.renderBridgeTooltip(guiGraphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(@Nonnull GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        guiGraphics.blit(INVENTORY_TEXTURE, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight);

        if (this.minecraft != null && this.minecraft.player != null) {
            InventoryScreen.renderEntityInInventoryFollowsMouse(guiGraphics,
                    this.leftPos + 51, this.topPos + 75, 30,
                    (float) (this.leftPos + 51) - mouseX,
                    (float) (this.topPos + 25) - mouseY,
                    this.minecraft.player);
        }

        this.renderCurioPanel(guiGraphics);
    }

    @Override
    protected void renderLabels(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, 4210752, false);
        guiGraphics.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, 4210752, false);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (KeyRegistry.openCurios.isActiveAndMatches(InputConstants.getKey(keyCode, scanCode))) {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) player.closeContainer();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            CurioSlot renderSlot = this.findRenderToggle(mouseX, mouseY);
            if (renderSlot != null) {
                NetworkHandler.INSTANCE.send(PacketDistributor.SERVER.noArg(),
                        new CPacketToggleRender(renderSlot.getIdentifier(), renderSlot.getSlotIndex()));
                return true;
            }

            PanelButton panelButton = this.findPanelButton(mouseX, mouseY);
            if (panelButton != null) {
                panelButton.run();
                return true;
            }

            if (this.isLegacyScrollBar(mouseX, mouseY)) {
                this.legacyDragging = true;
                this.updateLegacyScroll(mouseY);
                return true;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) this.legacyDragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.legacyDragging) {
            this.updateLegacyScroll(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (this.isInsideCurioPanel(mouseX, mouseY) || this.hoveredSlot instanceof CurioSlot) {
            if (this.menu instanceof CuriosContainerV2 v2 && v2.totalPages > 1) {
                NetworkHandler.INSTANCE.send(PacketDistributor.SERVER.noArg(),
                        new CPacketPage(v2.containerId, delta < 0));
                return true;
            }

            if (this.menu instanceof CuriosContainer legacy && legacy.canScroll()) {
                int visibleSlots = CuriosApi.getCuriosInventory(legacy.player)
                        .map(handler -> handler.getVisibleSlots()).orElse(8);
                int scrollSteps = Math.max(1, (int) Math.floor(visibleSlots / 8.0D));
                this.legacyScroll = Mth.clamp((float) (this.legacyScroll - delta / scrollSteps), 0.0F, 1.0F);
                legacy.scrollTo(this.legacyScroll);
                return true;
            }
        }

        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private void renderCurioPanel(GuiGraphics guiGraphics) {
        CurioBounds bounds = this.curioBounds();
        if (bounds.isEmpty()) return;

        int panelX = this.leftPos + bounds.minX - PANEL_PADDING;
        int panelY = this.topPos + bounds.minY - PANEL_PADDING - this.panelHeaderHeight();
        int panelWidth = bounds.width() + PANEL_PADDING * 2;
        int panelHeight = bounds.height() + PANEL_PADDING * 2 + this.panelHeaderHeight();

        this.fillPanel(guiGraphics, panelX, panelY, panelWidth, panelHeight);

        for (Slot slot : this.menu.slots) {
            if (!(slot instanceof CurioSlot curioSlot) || !slot.isActive()) continue;
            int x = this.leftPos + slot.x - 1;
            int y = this.topPos + slot.y - 1;
            int outer = curioSlot instanceof CosmeticCurioSlot || curioSlot.isCosmetic() ? 0xFF8A6A96 : 0xFF7E8172;
            guiGraphics.fill(x, y, x + 18, y + 18, outer);
            guiGraphics.fill(x + 1, y + 1, x + 17, y + 17, 0xFF2D2F34);
        }

        this.renderScroll(guiGraphics, panelX, panelY, panelWidth, panelHeight, bounds);
    }

    private void renderControls(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        for (PanelButton button : this.panelButtons()) {
            this.drawSmallButton(guiGraphics, button.x(), button.y(), button.label(), button.contains(mouseX, mouseY));
        }

        for (Slot slot : this.menu.slots) {
            if (!(slot instanceof CurioSlot curioSlot) || curioSlot instanceof CosmeticCurioSlot || curioSlot.isCosmetic()) continue;
            if (!slot.isActive() || !curioSlot.canToggleRender()) continue;
            int x = this.leftPos + slot.x + 11;
            int y = this.topPos + slot.y - 3;
            boolean hovered = mouseX >= x && mouseY >= y && mouseX < x + 8 && mouseY < y + 8;
            int color = curioSlot.getRenderStatus() ? 0xFF79B66B : 0xFF6B7078;
            guiGraphics.fill(x, y, x + 8, y + 8, hovered ? 0xFFFFFFFF : 0xFF25272B);
            guiGraphics.fill(x + 1, y + 1, x + 7, y + 7, color);
            if (!curioSlot.getRenderStatus()) {
                guiGraphics.fill(x + 2, y + 3, x + 6, y + 5, 0xFF24262B);
            }
        }
    }

    private void renderBridgeTooltip(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        PanelButton button = this.findPanelButton(mouseX, mouseY);
        if (button != null) {
            guiGraphics.renderTooltip(this.font, button.tooltip(), mouseX, mouseY);
            return;
        }

        CurioSlot renderSlot = this.findRenderToggle(mouseX, mouseY);
        if (renderSlot != null) {
            guiGraphics.renderTooltip(this.font, Component.translatable("tooltip.curio_trinkets_bridge.toggle_render"), mouseX, mouseY);
            return;
        }

        if (this.hoveredSlot instanceof CurioSlot curioSlot && !this.hoveredSlot.hasItem()) {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null && player.inventoryMenu.getCarried().isEmpty()) {
                guiGraphics.renderTooltip(this.font, Component.literal(curioSlot.getSlotName()), mouseX, mouseY);
            }
        }
    }

    private List<PanelButton> panelButtons() {
        List<PanelButton> buttons = new ArrayList<>();
        CurioBounds bounds = this.curioBounds();
        if (bounds.isEmpty()) return buttons;

        int x = this.leftPos + bounds.minX - PANEL_PADDING + 5;
        int y = this.topPos + bounds.minY - PANEL_PADDING - this.panelHeaderHeight() + 5;

        if (this.menu instanceof CuriosContainerV2 v2) {
            if (v2.totalPages > 1) {
                buttons.add(new PanelButton(x, y, "<", Component.translatable("tooltip.curio_trinkets_bridge.previous_page"),
                        () -> NetworkHandler.INSTANCE.send(PacketDistributor.SERVER.noArg(), new CPacketPage(v2.containerId, false))));
                buttons.add(new PanelButton(x + BUTTON_SIZE + 2, y, ">", Component.translatable("tooltip.curio_trinkets_bridge.next_page"),
                        () -> NetworkHandler.INSTANCE.send(PacketDistributor.SERVER.noArg(), new CPacketPage(v2.containerId, true))));
                x += (BUTTON_SIZE + 2) * 2;
            }

            if (v2.hasCosmetics) {
                buttons.add(new PanelButton(x, y, "C", Component.translatable("tooltip.curio_trinkets_bridge.toggle_cosmetics"),
                        () -> NetworkHandler.INSTANCE.send(PacketDistributor.SERVER.noArg(), new CPacketToggleCosmetics(v2.containerId))));
            }
        }

        return buttons;
    }

    private PanelButton findPanelButton(double mouseX, double mouseY) {
        for (PanelButton button : this.panelButtons()) {
            if (button.contains(mouseX, mouseY)) return button;
        }
        return null;
    }

    private CurioSlot findRenderToggle(double mouseX, double mouseY) {
        for (Slot slot : this.menu.slots) {
            if (!(slot instanceof CurioSlot curioSlot) || curioSlot instanceof CosmeticCurioSlot || curioSlot.isCosmetic()) continue;
            if (!slot.isActive() || !curioSlot.canToggleRender()) continue;
            int x = this.leftPos + slot.x + 11;
            int y = this.topPos + slot.y - 3;
            if (mouseX >= x && mouseY >= y && mouseX < x + 8 && mouseY < y + 8) return curioSlot;
        }
        return null;
    }

    private void drawSmallButton(GuiGraphics guiGraphics, int x, int y, String label, boolean hovered) {
        guiGraphics.fill(x, y, x + BUTTON_SIZE, y + BUTTON_SIZE, hovered ? 0xFFFFFFFF : 0xFF202226);
        guiGraphics.fill(x + 1, y + 1, x + BUTTON_SIZE - 1, y + BUTTON_SIZE - 1, hovered ? 0xFF6D7568 : 0xFF4F564E);
        guiGraphics.drawCenteredString(this.font, label, x + BUTTON_SIZE / 2, y + 2, 0xFFE8E8E8);
    }

    private void renderScroll(GuiGraphics guiGraphics, int panelX, int panelY, int panelWidth, int panelHeight, CurioBounds bounds) {
        if (this.menu instanceof CuriosContainerV2 v2 && v2.totalPages > 1) {
            int trackX = panelX + panelWidth - 8;
            int trackY = panelY + PANEL_PADDING + this.panelHeaderHeight();
            int trackHeight = Math.max(18, panelHeight - PANEL_PADDING * 2 - this.panelHeaderHeight());
            guiGraphics.fill(trackX, trackY, trackX + 4, trackY + trackHeight, 0xFF1E2024);
            int handleHeight = Math.max(12, trackHeight / Math.max(1, v2.totalPages));
            int maxOffset = Math.max(0, trackHeight - handleHeight);
            int handleY = trackY + Math.round(maxOffset * (v2.currentPage / (float) Math.max(1, v2.totalPages - 1)));
            guiGraphics.fill(trackX, handleY, trackX + 4, handleY + handleHeight, 0xFF8A917F);
        } else if (this.menu instanceof CuriosContainer legacy && legacy.canScroll()) {
            int trackX = panelX + panelWidth - 8;
            int trackY = panelY + PANEL_PADDING + this.panelHeaderHeight();
            int trackHeight = Math.max(18, bounds.height());
            guiGraphics.fill(trackX, trackY, trackX + 4, trackY + trackHeight, 0xFF1E2024);
            int handleHeight = Math.max(12, trackHeight / 2);
            int handleY = trackY + Math.round((trackHeight - handleHeight) * this.legacyScroll);
            guiGraphics.fill(trackX, handleY, trackX + 4, handleY + handleHeight, 0xFF8A917F);
        }
    }

    private boolean isLegacyScrollBar(double mouseX, double mouseY) {
        if (!(this.menu instanceof CuriosContainer legacy) || !legacy.canScroll()) return false;
        CurioBounds bounds = this.curioBounds();
        if (bounds.isEmpty()) return false;
        int panelX = this.leftPos + bounds.minX - PANEL_PADDING;
        int panelY = this.topPos + bounds.minY - PANEL_PADDING - this.panelHeaderHeight();
        int panelWidth = bounds.width() + PANEL_PADDING * 2;
        int trackX = panelX + panelWidth - 8;
        int trackY = panelY + PANEL_PADDING + this.panelHeaderHeight();
        int trackHeight = Math.max(18, bounds.height());
        return mouseX >= trackX && mouseY >= trackY && mouseX < trackX + 6 && mouseY < trackY + trackHeight;
    }

    private void updateLegacyScroll(double mouseY) {
        if (!(this.menu instanceof CuriosContainer legacy)) return;
        CurioBounds bounds = this.curioBounds();
        if (bounds.isEmpty()) return;
        int panelY = this.topPos + bounds.minY - PANEL_PADDING - this.panelHeaderHeight();
        int trackY = panelY + PANEL_PADDING + this.panelHeaderHeight();
        int trackHeight = Math.max(18, bounds.height());
        this.legacyScroll = Mth.clamp((float) ((mouseY - trackY) / Math.max(1.0D, trackHeight)), 0.0F, 1.0F);
        legacy.scrollTo(this.legacyScroll);
    }

    private boolean isInsideCurioPanel(double mouseX, double mouseY) {
        CurioBounds bounds = this.curioBounds();
        if (bounds.isEmpty()) return false;
        int x = this.leftPos + bounds.minX - PANEL_PADDING;
        int y = this.topPos + bounds.minY - PANEL_PADDING - this.panelHeaderHeight();
        return mouseX >= x && mouseY >= y
                && mouseX < x + bounds.width() + PANEL_PADDING * 2
                && mouseY < y + bounds.height() + PANEL_PADDING * 2 + this.panelHeaderHeight();
    }

    private int panelHeaderHeight() {
        return this.menu instanceof CuriosContainerV2 v2 && (v2.totalPages > 1 || v2.hasCosmetics) ? 18 : 4;
    }

    private CurioBounds curioBounds() {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;

        for (Slot slot : this.menu.slots) {
            if (!(slot instanceof CurioSlot) || !slot.isActive()) continue;
            minX = Math.min(minX, slot.x);
            minY = Math.min(minY, slot.y);
            maxX = Math.max(maxX, slot.x + 18);
            maxY = Math.max(maxY, slot.y + 18);
        }

        if (minX == Integer.MAX_VALUE) return CurioBounds.EMPTY;
        return new CurioBounds(minX, minY, maxX, maxY);
    }

    private void fillPanel(GuiGraphics guiGraphics, int x, int y, int width, int height) {
        guiGraphics.fill(x, y, x + width, y + height, 0xFF111317);
        guiGraphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xFF555B53);
        guiGraphics.fill(x + 3, y + 3, x + width - 3, y + height - 3, 0xFF24272D);
    }

    private record CurioBounds(int minX, int minY, int maxX, int maxY) {
        static final CurioBounds EMPTY = new CurioBounds(0, 0, 0, 0);

        boolean isEmpty() {
            return this.maxX <= this.minX || this.maxY <= this.minY;
        }

        int width() {
            return this.maxX - this.minX;
        }

        int height() {
            return this.maxY - this.minY;
        }

        int widthWithPadding() {
            return this.isEmpty() ? 0 : this.width() + PANEL_PADDING * 2;
        }
    }

    private record PanelButton(int x, int y, String label, Component tooltip, Runnable action) {
        boolean contains(double mouseX, double mouseY) {
            return mouseX >= this.x && mouseY >= this.y && mouseX < this.x + BUTTON_SIZE && mouseY < this.y + BUTTON_SIZE;
        }

        void run() {
            this.action.run();
        }
    }

    public static final class Legacy extends UnifiedCuriosScreen<CuriosContainer> {
        public Legacy(CuriosContainer menu, Inventory inventory, Component title) {
            super(menu, inventory, title);
        }
    }

    public static final class Revamp extends UnifiedCuriosScreen<CuriosContainerV2> {
        public Revamp(CuriosContainerV2 menu, Inventory inventory, Component title) {
            super(menu, inventory, title);
        }
    }
}