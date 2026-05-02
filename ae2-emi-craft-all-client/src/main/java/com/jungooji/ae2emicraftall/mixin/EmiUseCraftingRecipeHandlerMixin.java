package com.jungooji.ae2emicraftall.mixin;

import appeng.menu.me.items.CraftingTermMenu;
import com.jungooji.ae2emicraftall.Ae2EmiCraftAllClient;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.handler.EmiCraftContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "appeng.integration.modules.emi.EmiUseCraftingRecipeHandler", remap = false)
public abstract class EmiUseCraftingRecipeHandlerMixin {
    @Inject(method = "craft", at = @At("HEAD"), cancellable = true, remap = false)
    private void ae2emicraftall$craftAllToAe2(EmiRecipe recipe, EmiCraftContext<?> context, CallbackInfoReturnable<Boolean> cir) {
        Ae2EmiCraftAllClient.logCraftAttempt(recipe, context);
        if (!(context.getScreenHandler() instanceof CraftingTermMenu menu)) {
            Ae2EmiCraftAllClient.logDefaultHandler(recipe, context, "screen is not CraftingTermMenu");
            return;
        }
        if (context.getType() != EmiCraftContext.Type.CRAFTABLE) {
            Ae2EmiCraftAllClient.logDefaultHandler(recipe, context, "context type is not CRAFTABLE");
            return;
        }
        if (context.getDestination() != EmiCraftContext.Destination.INVENTORY) {
            Ae2EmiCraftAllClient.logDefaultHandler(recipe, context, "destination is not INVENTORY");
            return;
        }
        if (Ae2EmiCraftAllClient.start(menu, recipe, context.getAmount())) {
            cir.setReturnValue(true);
        } else if (Ae2EmiCraftAllClient.tryStartAe2Autocraft(menu, recipe, context.getAmount())) {
            cir.setReturnValue(true);
        } else {
            boolean postFillPending = Ae2EmiCraftAllClient.schedulePostDefaultFillClick(menu, recipe, context.getAmount());
            Ae2EmiCraftAllClient.logDefaultHandler(
                    recipe,
                    context,
                    postFillPending
                            ? "custom handler and AE2 autocraft fallback rejected request; post-default fill click pending"
                            : "custom handler, AE2 autocraft fallback, and post-default fill click rejected request"
            );
        }
    }
}
