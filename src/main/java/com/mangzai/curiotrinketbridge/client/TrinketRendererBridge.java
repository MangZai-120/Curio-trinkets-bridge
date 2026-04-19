package com.mangzai.curiotrinketbridge.client;

import com.mangzai.curiotrinketbridge.CurioTrinketBridge;
import com.mangzai.curiotrinketbridge.bridge.FakeTrinketInventory;
import com.mangzai.curiotrinketbridge.bridge.TrinketDetector;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.client.ICurioRenderer;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 将 Trinkets 的 {@code TrinketRenderer} 适配为 Curios 的 {@link ICurioRenderer}。
 *
 * <p>所有反射对象从 {@link TrinketDetector} 的共享缓存中获取，因此每个适配器实例都很轻量。
 * 仅在客户端使用。
 */
@OnlyIn(Dist.CLIENT)
public class TrinketRendererBridge implements ICurioRenderer {

    private final Item item;
    private final Object trinketRenderer; // dev.emi.trinkets.api.client.TrinketRenderer 实例

    public TrinketRendererBridge(Item item, Object trinketRenderer) {
        this.item = item;
        this.trinketRenderer = trinketRenderer;
    }

    @Override
    public <T extends LivingEntity, M extends EntityModel<T>> void render(
            ItemStack stack,
            SlotContext slotContext,
            PoseStack matrixStack,
            RenderLayerParent<T, M> renderLayerParent,
            MultiBufferSource renderTypeBuffer,
            int light,
            float limbSwing,
            float limbSwingAmount,
            float partialTicks,
            float ageInTicks,
            float netHeadYaw,
            float headPitch) {

        Method renderMethod = TrinketDetector.getRendererRenderMethod();
        if (renderMethod == null || trinketRenderer == null) return;

        // 构造 SlotReference
        Constructor<?> ctor = TrinketDetector.getSlotReferenceConstructor();
        if (ctor == null) return;

        try {
            Object slotRef = ctor.newInstance(FakeTrinketInventory.get(), slotContext.index());
            EntityModel<T> contextModel = renderLayerParent.getModel();
            // Trinket render 参数顺序：stack, slotRef, contextModel, matrices, vertexConsumers,
            // light, entity, limbAngle, limbDistance, tickDelta, animationProgress, headYaw, headPitch
            renderMethod.invoke(trinketRenderer,
                    stack,
                    slotRef,
                    contextModel,
                    matrixStack,
                    renderTypeBuffer,
                    light,
                    slotContext.entity(),
                    limbSwing,
                    limbSwingAmount,
                    partialTicks,
                    ageInTicks,
                    netHeadYaw,
                    headPitch);
        } catch (Exception e) {
            CurioTrinketBridge.LOGGER.debug("TrinketRenderer.render 调用失败 (item={}): {}", item, e.getMessage());
        }
    }
}
