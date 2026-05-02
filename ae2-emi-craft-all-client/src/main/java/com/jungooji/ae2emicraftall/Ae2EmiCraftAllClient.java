package com.jungooji.ae2emicraftall;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.core.network.serverbound.MEInteractionPacket;
import appeng.core.network.serverbound.InventoryActionPacket;
import appeng.helpers.InventoryAction;
import appeng.integration.modules.itemlists.CraftingHelper;
import appeng.menu.me.common.GridInventoryEntry;
import appeng.menu.me.common.IClientRepo;
import appeng.menu.me.items.CraftingTermMenu;
import appeng.menu.slot.CraftingTermSlot;
import dev.emi.emi.bom.BoM;
import dev.emi.emi.bom.MaterialTree;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.handler.EmiCraftContext;
import dev.emi.emi.api.stack.EmiStack;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(Ae2EmiCraftAllClient.MOD_ID)
public final class Ae2EmiCraftAllClient {
    public static final String MOD_ID = "ae2_emi_craft_all_client";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static Job job;

    public Ae2EmiCraftAllClient() {
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, Ae2EmiCraftAllClient::onClientTick);
        LOGGER.info("Loaded AE2 EMI Craft All Client helper");
    }

    public static boolean start(CraftingTermMenu menu, EmiRecipe emiRecipe, int amount) {
        if (amount < 1 || menu == null || emiRecipe == null) {
            LOGGER.info("Custom craft-all rejected: recipe={}, amount={}, menu={}, reason=invalid request",
                    recipeId(emiRecipe), amount, menuName(menu));
            return false;
        }

        RecipeHolder<?> holder = getRecipeHolder(menu, emiRecipe);
        if (holder == null || !(holder.value() instanceof Recipe<?> recipe)) {
            LOGGER.info("Custom craft-all rejected: recipe={}, amount={}, menu={}, reason=no backing recipe",
                    recipeId(emiRecipe), amount, menuName(menu));
            return false;
        }

        Slot outputSlot = findOutputSlot(menu);
        if (outputSlot == null) {
            LOGGER.info("Custom craft-all rejected: recipe={}, amount={}, menu={}, reason=no AE2 output slot",
                    recipeId(emiRecipe), amount, menuName(menu));
            return false;
        }

        ResourceLocation recipeId = holder.id();
        OutputDestination destination = determineOutputDestination(emiRecipe);
        if (job != null) {
            LOGGER.info("Replacing active custom craft-all job: oldRecipe={}, oldRemaining={}, newRecipe={}, newAmount={}",
                    job.recipeId, job.remaining, recipeId, amount);
        }
        LOGGER.info("Custom craft-all started: recipe={}, amount={}, containerId={}, outputSlot={}, destination={}",
                recipeId, amount, menu.containerId, outputSlot.index, destination);
        job = new Job(menu.containerId, recipeId, recipe, outputSlot.index, amount, destination);
        job.fillGrid();
        return true;
    }

    public static boolean tryStartAe2Autocraft(CraftingTermMenu menu, EmiRecipe emiRecipe, int amount) {
        if (amount < 1 || menu == null || emiRecipe == null) {
            LOGGER.info("AE2 autocraft fallback rejected: recipe={}, amount={}, menu={}, reason=invalid request",
                    recipeId(emiRecipe), amount, menuName(menu));
            return false;
        }

        LOGGER.info("AE2 autocraft fallback started: recipe={}, amount={}, menu={}",
                recipeId(emiRecipe), amount, menuName(menu));

        ItemOutput targetOutput = getFirstItemOutput(emiRecipe);
        ItemStack targetStack = targetOutput.stack();
        if (targetStack.isEmpty()) {
            LOGGER.info("AE2 autocraft fallback rejected: recipe={}, amount={}, reason={}",
                    recipeId(emiRecipe), amount, targetOutput.sawNonItemOutput() ? "non-item output" : "no item output");
            return false;
        }

        AEItemKey targetKey = AEItemKey.of(targetStack);
        if (targetKey == null) {
            LOGGER.info("AE2 autocraft fallback rejected: recipe={}, target={}, amount={}, reason=no AE item key",
                    recipeId(emiRecipe), targetStack.getHoverName().getString(), amount);
            return false;
        }

        IClientRepo repo = menu.getClientRepo();
        if (repo == null) {
            LOGGER.info("AE2 autocraft fallback rejected: recipe={}, target={}, aeKey={}, amount={}, reason=no client repo",
                    recipeId(emiRecipe), targetStack.getHoverName().getString(), targetKey, amount);
            return false;
        }

        int totalEntries = 0;
        int craftableEntries = 0;
        for (GridInventoryEntry entry : repo.getAllEntries()) {
            totalEntries++;
            if (entry == null || !entry.isCraftable()) {
                continue;
            }
            craftableEntries++;
            AEKey entryKey = entry.getWhat();
            if (!targetKey.equals(entryKey)) {
                continue;
            }

            LOGGER.info("AE2 autocraft fallback matched: recipe={}, target={}, aeKey={}, amount={}, serial={}, stored={}, requestable={}",
                    recipeId(emiRecipe),
                    targetStack.getHoverName().getString(),
                    targetKey,
                    amount,
                    entry.getSerial(),
                    entry.getStoredAmount(),
                    entry.getRequestableAmount());
            PacketDistributor.sendToServer(new MEInteractionPacket(menu.containerId, entry.getSerial(), InventoryAction.AUTO_CRAFT));
            LOGGER.info("AE2 autocraft AUTO_CRAFT sent: recipe={}, target={}, serial={}, containerId={}",
                    recipeId(emiRecipe), targetStack.getHoverName().getString(), entry.getSerial(), menu.containerId);
            return true;
        }

        LOGGER.info("AE2 autocraft fallback rejected: recipe={}, target={}, aeKey={}, amount={}, totalEntries={}, craftableEntries={}, reason=no matching craftable entry",
                recipeId(emiRecipe), targetStack.getHoverName().getString(), targetKey, amount, totalEntries, craftableEntries);
        return false;
    }

    public static boolean schedulePostDefaultFillClick(CraftingTermMenu menu, EmiRecipe emiRecipe, int amount) {
        if (amount < 1 || menu == null || emiRecipe == null) {
            LOGGER.info("Post-default fill click rejected: recipe={}, amount={}, menu={}, reason=invalid request",
                    recipeId(emiRecipe), amount, menuName(menu));
            return false;
        }

        RecipeHolder<?> holder = getRecipeHolder(menu, emiRecipe);
        if (holder != null) {
            LOGGER.info("Post-default fill click rejected: recipe={}, amount={}, menu={}, backingRecipe={}, reason=backing recipe exists",
                    recipeId(emiRecipe), amount, menuName(menu), holder.id());
            return false;
        }

        Slot outputSlot = findOutputSlot(menu);
        if (outputSlot == null) {
            LOGGER.info("Post-default fill click rejected: recipe={}, amount={}, menu={}, reason=no AE2 output slot",
                    recipeId(emiRecipe), amount, menuName(menu));
            return false;
        }

        ItemOutput targetOutput = getFirstItemOutput(emiRecipe);
        ItemStack targetStack = targetOutput.stack();
        if (targetStack.isEmpty()) {
            LOGGER.info("Post-default fill click rejected: recipe={}, amount={}, reason={}",
                    recipeId(emiRecipe), amount, targetOutput.sawNonItemOutput() ? "non-item output" : "no item output");
            return false;
        }

        OutputDestination destination = determineOutputDestination(emiRecipe);
        if (amount > 1) {
            LOGGER.info("Post-default fill fallback amount capped to 1: recipe={}, target={}, requestedAmount={}, cappedAmount=1",
                    recipeId(emiRecipe), targetStack.getHoverName().getString(), amount);
        }

        if (job != null) {
            LOGGER.info("Replacing active custom craft-all job: oldRecipe={}, oldMode={}, oldRemaining={}, newRecipe={}, newMode=POST_DEFAULT_FILL_CLICK, newAmount=1",
                    job.recipeId, job.mode, job.remaining, recipeId(emiRecipe));
        }
        LOGGER.info("Post-default fill click scheduled: recipe={}, target={}, targetCount={}, amount={}, cappedAmount=1, containerId={}, outputSlot={}, destination={}, note=AE2 default EMI handler must fill grid before next tick",
                recipeId(emiRecipe),
                targetStack.getHoverName().getString(),
                targetStack.getCount(),
                amount,
                menu.containerId,
                outputSlot.index,
                destination);
        job = Job.postDefaultFillClick(menu.containerId, emiRecipe.getId(), outputSlot.index, targetStack, destination);
        return true;
    }

    public static void logCraftAttempt(EmiRecipe recipe, EmiCraftContext<?> context) {
        Object screenHandler = context.getScreenHandler();
        LOGGER.info("AE2 EMI craft attempt: recipe={}, category={}, type={}, destination={}, amount={}, screenHandler={}",
                recipeId(recipe),
                recipe == null || recipe.getCategory() == null ? "null" : recipe.getCategory(),
                context.getType(),
                context.getDestination(),
                context.getAmount(),
                screenHandler == null ? "null" : screenHandler.getClass().getName());
    }

    public static void logDefaultHandler(EmiRecipe recipe, EmiCraftContext<?> context, String reason) {
        LOGGER.info("Delegating to AE2 default EMI handler: recipe={}, type={}, destination={}, amount={}, reason={}",
                recipeId(recipe), context.getType(), context.getDestination(), context.getAmount(), reason);
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        if (job == null) {
            return;
        }
        if (job.tick()) {
            job = null;
        }
    }

    private static RecipeHolder<?> getRecipeHolder(CraftingTermMenu menu, EmiRecipe emiRecipe) {
        RecipeHolder<?> holder = emiRecipe.getBackingRecipe();
        if (holder != null) {
            return holder;
        }

        ResourceLocation id = emiRecipe.getId();
        if (id == null) {
            return null;
        }

        return menu.getPlayer()
                .level()
                .getRecipeManager()
                .byKey(id)
                .orElse(null);
    }

    private static ItemOutput getFirstItemOutput(EmiRecipe emiRecipe) {
        if (emiRecipe == null) {
            return new ItemOutput(ItemStack.EMPTY, false);
        }

        boolean sawNonItemOutput = false;
        for (EmiStack output : emiRecipe.getOutputs()) {
            if (output == null || output.isEmpty()) {
                continue;
            }
            ItemStack stack = output.getItemStack();
            if (!stack.isEmpty()) {
                return new ItemOutput(stack.copy(), sawNonItemOutput);
            }
            sawNonItemOutput = true;
        }
        return new ItemOutput(ItemStack.EMPTY, sawNonItemOutput);
    }

    private static Slot findOutputSlot(CraftingTermMenu menu) {
        for (Slot slot : menu.slots) {
            if (slot instanceof CraftingTermSlot) {
                return slot;
            }
        }
        return null;
    }

    private static OutputDestination determineOutputDestination(EmiRecipe emiRecipe) {
        MaterialTree tree = BoM.getTree();
        int treeIndex = BoM.treeIndex;
        int treeCount = BoM.getTrees().size();
        if (tree == null || tree.goal == null) {
            LOGGER.info("EMI tree root check: recipe={}, selectedTree=false, treeIndex={}, treeCount={}, destination={}, note=uses EMI internal BoM",
                    recipeId(emiRecipe), treeIndex, treeCount, OutputDestination.AE2_NETWORK);
            return OutputDestination.AE2_NETWORK;
        }

        EmiRecipe rootRecipe = tree.goal.recipe;
        boolean rootMatches = rootRecipe == emiRecipe;
        OutputDestination destination = rootMatches
                ? OutputDestination.TREE_ROOT_INVENTORY
                : OutputDestination.AE2_NETWORK;
        LOGGER.info("EMI tree root check: recipe={}, selectedTree=true, treeIndex={}, treeCount={}, rootRecipe={}, rootMatches={}, destination={}, note=uses EMI internal BoM",
                recipeId(emiRecipe), treeIndex, treeCount, recipeId(rootRecipe), rootMatches, destination);
        return destination;
    }

    private static Slot findPlayerInventoryDepositSlot(CraftingTermMenu menu, Inventory inventory, ItemStack carried) {
        Slot emptySlot = null;
        for (Slot slot : menu.slots) {
            if (!isPlayerMainInventorySlot(slot, inventory) || !slot.isActive() || !slot.mayPlace(carried)) {
                continue;
            }

            ItemStack slotStack = slot.getItem();
            if (slotStack.isEmpty()) {
                if (emptySlot == null) {
                    emptySlot = slot;
                }
                continue;
            }

            if (!ItemStack.isSameItemSameComponents(slotStack, carried)) {
                continue;
            }

            int maxStackSize = Math.min(slot.getMaxStackSize(carried), carried.getMaxStackSize());
            if (slotStack.getCount() < maxStackSize) {
                return slot;
            }
        }
        return emptySlot;
    }

    private static boolean isPlayerMainInventorySlot(Slot slot, Inventory inventory) {
        if (slot == null || inventory == null || slot.container != inventory) {
            return false;
        }
        int containerSlot = slot.getContainerSlot();
        return containerSlot >= 0 && containerSlot < Inventory.INVENTORY_SIZE;
    }

    private static String recipeId(EmiRecipe recipe) {
        if (recipe == null) {
            return "null";
        }
        ResourceLocation id = recipe.getId();
        if (id != null) {
            return id.toString();
        }
        RecipeHolder<?> holder = recipe.getBackingRecipe();
        return holder == null ? "transient/null" : holder.id().toString();
    }

    private static String menuName(CraftingTermMenu menu) {
        return menu == null ? "null" : menu.getClass().getName();
    }

    private enum OutputDestination {
        AE2_NETWORK,
        TREE_ROOT_INVENTORY
    }

    private record ItemOutput(ItemStack stack, boolean sawNonItemOutput) {
    }

    private enum JobMode {
        DIRECT_RECIPE,
        POST_DEFAULT_FILL_CLICK
    }

    private static final class Job {
        private static final int MAX_CARRIED_WAIT_TICKS = 10;
        private static final int MAX_INVENTORY_DEPOSIT_ATTEMPTS = 40;

        private final int containerId;
        private final ResourceLocation recipeId;
        private final Recipe<?> recipe;
        private final ItemStack expectedOutput;
        private final int outputSlot;
        private final OutputDestination destination;
        private final JobMode mode;
        private final boolean refillGrid;
        private final boolean exactOutputRequired;
        private int remaining;
        private int waitTicks;
        private int ageTicks;
        private boolean waitingForInventoryDeposit;
        private int inventoryDepositWaitTicks;
        private int inventoryDepositAttempts;

        private Job(int containerId, ResourceLocation recipeId, Recipe<?> recipe, int outputSlot, int remaining, OutputDestination destination) {
            this(containerId, recipeId, recipe, ItemStack.EMPTY, outputSlot, remaining, destination, JobMode.DIRECT_RECIPE, true, false);
        }

        private Job(int containerId,
                ResourceLocation recipeId,
                Recipe<?> recipe,
                ItemStack expectedOutput,
                int outputSlot,
                int remaining,
                OutputDestination destination,
                JobMode mode,
                boolean refillGrid,
                boolean exactOutputRequired) {
            this.containerId = containerId;
            this.recipeId = recipeId;
            this.recipe = recipe;
            this.expectedOutput = expectedOutput.copy();
            this.outputSlot = outputSlot;
            this.remaining = remaining;
            this.destination = destination;
            this.mode = mode;
            this.refillGrid = refillGrid;
            this.exactOutputRequired = exactOutputRequired;
        }

        private static Job postDefaultFillClick(int containerId, ResourceLocation recipeId, int outputSlot, ItemStack expectedOutput, OutputDestination destination) {
            return new Job(
                    containerId,
                    recipeId,
                    null,
                    expectedOutput,
                    outputSlot,
                    1,
                    destination,
                    JobMode.POST_DEFAULT_FILL_CLICK,
                    false,
                    true
            );
        }

        boolean tick() {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null || !(minecraft.player.containerMenu instanceof CraftingTermMenu menu)) {
                LOGGER.info("Custom craft-all aborted: recipe={}, mode={}, remaining={}, reason=player left AE2 crafting terminal",
                        recipeId, mode, remaining);
                return true;
            }
            if (menu.containerId != containerId) {
                LOGGER.info("Custom craft-all aborted: recipe={}, mode={}, remaining={}, expectedContainerId={}, actualContainerId={}, reason=container changed",
                        recipeId, mode, remaining, containerId, menu.containerId);
                return true;
            }

            if (waitingForInventoryDeposit) {
                return tickInventoryDeposit(minecraft, menu);
            }

            if (waitTicks > 0) {
                waitTicks--;
                return false;
            }
            if (ageTicks++ > 100) {
                LOGGER.info("Custom craft-all aborted: recipe={}, mode={}, remaining={}, expectedOutput={}, reason={}",
                        recipeId,
                        mode,
                        remaining,
                        expectedOutput.isEmpty() ? "none" : expectedOutput.getHoverName().getString(),
                        mode == JobMode.POST_DEFAULT_FILL_CLICK ? "timeout waiting for output after default handler" : "timeout waiting for output");
                return true;
            }

            Optional<Slot> maybeOutput = menu.slots.stream()
                    .filter(slot -> slot.index == outputSlot)
                    .findFirst();
            if (maybeOutput.isEmpty()) {
                LOGGER.info("Custom craft-all aborted: recipe={}, mode={}, remaining={}, outputSlot={}, reason=output slot disappeared",
                        recipeId, mode, remaining, outputSlot);
                return true;
            }

            Slot output = maybeOutput.get();
            if (!output.hasItem()) {
                if (ageTicks % 20 == 0) {
                    if (refillGrid) {
                        LOGGER.info("Custom craft-all waiting for output: recipe={}, mode={}, remaining={}, ageTicks={}, action=refill grid",
                                recipeId, mode, remaining, ageTicks);
                        fillGrid();
                    } else {
                        LOGGER.info("Post-default fill click waiting for output: recipe={}, target={}, remaining={}, ageTicks={}, action=wait for AE2 default handler result",
                                recipeId,
                                expectedOutput.isEmpty() ? "none" : expectedOutput.getHoverName().getString(),
                                remaining,
                                ageTicks);
                    }
                }
                waitTicks = 1;
                return false;
            }

            ItemStack stack = output.getItem();
            if (exactOutputRequired && !ItemStack.isSameItemSameComponents(stack, expectedOutput)) {
                LOGGER.info("Post-default fill click aborted: recipe={}, expectedOutput={}, expectedCount={}, actualOutput={}, actualCount={}, reason=output mismatch",
                        recipeId,
                        expectedOutput.getHoverName().getString(),
                        expectedOutput.getCount(),
                        stack.getHoverName().getString(),
                        stack.getCount());
                return true;
            }

            int craftPackets = Math.max(1, Math.min(remaining, stack.getMaxStackSize() / Math.max(1, stack.getCount())));
            LOGGER.info("Custom craft-all crafting output: recipe={}, mode={}, output={}, outputCount={}, outputMaxStack={}, craftPackets={}, remainingBefore={}",
                    recipeId,
                    mode,
                    stack.getHoverName().getString(),
                    stack.getCount(),
                    stack.getMaxStackSize(),
                    craftPackets,
                    remaining);
            for (int i = 0; i < craftPackets; i++) {
                PacketDistributor.sendToServer(new InventoryActionPacket(InventoryAction.CRAFT_ITEM, outputSlot, 0));
            }
            LOGGER.info("Custom craft-all CRAFT_ITEM sent: recipe={}, mode={}, outputSlot={}, packets={}, remainingBefore={}",
                    recipeId, mode, outputSlot, craftPackets, remaining);

            remaining -= craftPackets;
            if (destination == OutputDestination.TREE_ROOT_INVENTORY) {
                waitingForInventoryDeposit = true;
                inventoryDepositWaitTicks = 0;
                inventoryDepositAttempts = 0;
                LOGGER.info("Custom craft-all inventory deposit pending: recipe={}, mode={}, remainingAfterCraft={}, reason=tree root recipe",
                        recipeId, mode, remaining);
                return false;
            }

            depositToAe2("destination=AE2_NETWORK");
            return finishCraftCycle();
        }

        private boolean tickInventoryDeposit(Minecraft minecraft, CraftingTermMenu menu) {
            ItemStack carried = menu.getCarried();
            if (carried.isEmpty()) {
                if (inventoryDepositAttempts > 0) {
                    LOGGER.info("Custom craft-all inventory deposit completed: recipe={}, mode={}, attempts={}",
                            recipeId, mode, inventoryDepositAttempts);
                    waitingForInventoryDeposit = false;
                    return finishCraftCycle();
                }

                if (inventoryDepositWaitTicks++ < MAX_CARRIED_WAIT_TICKS) {
                    if (inventoryDepositWaitTicks == 1) {
                        LOGGER.info("Custom craft-all waiting for carried output: recipe={}, mode={}, maxWaitTicks={}",
                                recipeId, mode, MAX_CARRIED_WAIT_TICKS);
                    }
                    return false;
                }

                LOGGER.info("Custom craft-all inventory deposit fallback: recipe={}, mode={}, reason=carried stack did not appear, waitedTicks={}",
                        recipeId, mode, inventoryDepositWaitTicks);
                waitingForInventoryDeposit = false;
                depositToAe2("inventory deposit fallback: carried stack did not appear");
                return finishCraftCycle();
            }

            inventoryDepositWaitTicks = 0;
            if (minecraft.gameMode == null) {
                LOGGER.info("Custom craft-all inventory deposit fallback: recipe={}, mode={}, carried={}, carriedCount={}, reason=no game mode",
                        recipeId, mode, carried.getHoverName().getString(), carried.getCount());
                waitingForInventoryDeposit = false;
                depositToAe2("inventory deposit fallback: no game mode");
                return finishCraftCycle();
            }
            if (inventoryDepositAttempts >= MAX_INVENTORY_DEPOSIT_ATTEMPTS) {
                LOGGER.info("Custom craft-all inventory deposit fallback: recipe={}, mode={}, carried={}, carriedCount={}, attempts={}, reason=too many deposit attempts",
                        recipeId, mode, carried.getHoverName().getString(), carried.getCount(), inventoryDepositAttempts);
                waitingForInventoryDeposit = false;
                depositToAe2("inventory deposit fallback: too many deposit attempts");
                return finishCraftCycle();
            }

            Slot targetSlot = findPlayerInventoryDepositSlot(menu, minecraft.player.getInventory(), carried);
            if (targetSlot == null) {
                LOGGER.info("Custom craft-all inventory deposit fallback: recipe={}, mode={}, carried={}, carriedCount={}, reason=no player inventory space",
                        recipeId, mode, carried.getHoverName().getString(), carried.getCount());
                waitingForInventoryDeposit = false;
                depositToAe2("inventory deposit fallback: no player inventory space");
                return finishCraftCycle();
            }

            ItemStack targetBefore = targetSlot.getItem();
            LOGGER.info("Custom craft-all inventory deposit click: recipe={}, mode={}, carried={}, carriedCount={}, slotIndex={}, containerSlot={}, targetBefore={}, targetBeforeCount={}",
                    recipeId,
                    mode,
                    carried.getHoverName().getString(),
                    carried.getCount(),
                    targetSlot.index,
                    targetSlot.getContainerSlot(),
                    targetBefore.isEmpty() ? "empty" : targetBefore.getHoverName().getString(),
                    targetBefore.isEmpty() ? 0 : targetBefore.getCount());
            minecraft.gameMode.handleInventoryMouseClick(menu.containerId, targetSlot.index, 0, ClickType.PICKUP, minecraft.player);
            inventoryDepositAttempts++;

            ItemStack afterClick = menu.getCarried();
            if (afterClick.isEmpty()) {
                LOGGER.info("Custom craft-all inventory deposit completed: recipe={}, mode={}, attempts={}",
                        recipeId, mode, inventoryDepositAttempts);
                waitingForInventoryDeposit = false;
                return finishCraftCycle();
            }

            LOGGER.info("Custom craft-all inventory deposit partial: recipe={}, mode={}, remainingCarried={}, remainingCount={}, attempts={}",
                    recipeId, mode, afterClick.getHoverName().getString(), afterClick.getCount(), inventoryDepositAttempts);
            return false;
        }

        private void depositToAe2(String reason) {
            PacketDistributor.sendToServer(new MEInteractionPacket(
                    containerId,
                    -1,
                    InventoryAction.PICKUP_OR_SET_DOWN
            ));
            LOGGER.info("Custom craft-all deposit requested: recipe={}, mode={}, action=PICKUP_OR_SET_DOWN, containerId={}, reason={}",
                    recipeId, mode, containerId, reason);
        }

        private boolean finishCraftCycle() {
            if (remaining <= 0) {
                LOGGER.info("Custom craft-all completed: recipe={}, mode={}, destination={}", recipeId, mode, destination);
                return true;
            }

            LOGGER.info("Custom craft-all continuing: recipe={}, mode={}, remainingAfter={}", recipeId, mode, remaining);
            ageTicks = 0;
            waitTicks = 4;
            return false;
        }

        void fillGrid() {
            if (recipe == null) {
                LOGGER.info("Custom craft-all fill grid skipped: recipe={}, mode={}, reason=no backing recipe",
                        recipeId, mode);
                return;
            }

            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null && minecraft.player.containerMenu instanceof CraftingTermMenu menu) {
                LOGGER.info("Custom craft-all fill grid requested: recipe={}, mode={}, containerId={}", recipeId, mode, menu.containerId);
                CraftingHelper.performTransfer(menu, recipeId, recipe, false);
                waitTicks = 2;
            } else {
                LOGGER.info("Custom craft-all fill grid skipped: recipe={}, mode={}, reason=AE2 crafting terminal not open", recipeId, mode);
            }
        }
    }
}
