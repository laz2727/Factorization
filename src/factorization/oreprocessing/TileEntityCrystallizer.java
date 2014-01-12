package factorization.oreprocessing;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.packet.Packet;
import net.minecraft.util.Icon;
import net.minecraftforge.common.ForgeDirection;
import factorization.common.BlockIcons;
import factorization.common.FactoryType;
import factorization.shared.BlockClass;
import factorization.shared.Core;
import factorization.shared.FzUtil;
import factorization.shared.TileEntityFactorization;
import factorization.shared.NetworkFactorization.MessageType;

public class TileEntityCrystallizer extends TileEntityFactorization {
    ItemStack inputs[] = new ItemStack[6];
    ItemStack output;

    public ItemStack growing_crystal, solution;
    public int heat, progress;
    public final static int topHeat = 300;
    
    @Override
    public Icon getIcon(ForgeDirection dir) {
        switch (dir) {
        case UP: return BlockIcons.cauldron_top;
        default: return BlockIcons.cauldron_side;
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        writeSlotsToNBT(tag);
        tag.setInteger("heat", heat);
        tag.setInteger("progress", progress);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        readSlotsFromNBT(tag);
        heat = tag.getInteger("heat");
        progress = tag.getInteger("progress");
    }

    @Override
    public int getSizeInventory() {
        return inputs.length + 1;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        needLogic();
        if (slot == inputs.length) {
            return output;
        }
        return inputs[slot];
    }

    @Override
    public void setInventorySlotContents(int slot, ItemStack is) {
        if (slot == inputs.length) {
            output = is;
        } else {
            inputs[slot] = is;
        }
        onInventoryChanged();
    }

    @Override
    public String getInvName() {
        return "Crystallizer";
    }

    private static final int[] INPUTS_s = {0, 1, 2, 3, 4, 5}, OUTPUT_s = {6};
    
    @Override
    public int[] getAccessibleSlotsFromSide(int s) {
        ForgeDirection side = ForgeDirection.getOrientation(s);
        if (side == ForgeDirection.DOWN) {
            return OUTPUT_s;
        }
        return INPUTS_s;
    }
    
    @Override
    public boolean isItemValidForSlot(int slotIndex, ItemStack itemstack) {
        return slotIndex < inputs.length;
    }

    @Override
    public FactoryType getFactoryType() {
        return FactoryType.CRYSTALLIZER;
    }

    @Override
    public BlockClass getBlockClass() {
        return BlockClass.Machine;
    }

    int pickInputSlot(ItemStack must_match) {
        int max_size, slot;
        slot = -1;
        max_size = -99;
        for (int i = 0; i < inputs.length; i++) {
            if (must_match != null && inputs[i] != null && !FzUtil.couldMerge(must_match, inputs[i])) {
                continue;
            }
            int here_size = FzUtil.getStackSize(inputs[i]);
            if (here_size > max_size) {
                max_size = here_size;
                slot = i;
            }
        }
        return slot;
    }

    public int getProgressRemaining() {
        //20 ticks per second; 60 seconds per minute; 60 minutes per day
        return ((20 * 60 * 20) / getLogicSpeed()) - progress;
    }

    public float getProgress() {
        return ((float) progress) / (getProgressRemaining() + progress);
    }

    public boolean needHeat() {
        if (heat >= topHeat) {
            return false;
        }
        return getMatchingRecipe() != null;
    }

    void empty() {
        growing_crystal = null;
        solution = null;
        shareState();
    }

    @Override
    protected void doLogic() {
        if (heat <= 0) {
            current_state = 1;
            empty();
            return;
        }
        CrystalRecipe match = getMatchingRecipe();
        if (match == null) {
            heat = Math.max(heat - 3, 0);
            progress = (int) Math.min(progress * 0.005 - 1, 0);
            current_state = 2;
            empty();
            return;
        }
        if (growing_crystal == null) {
            growing_crystal = match.output;
            solution = match.solution;
            share_delay = 0;
            current_state = 3;
        }
        if (heat < topHeat) {
            current_state = 4;
            shareState();
            return;
        }
        //we're hot enough. Do progress
        needLogic();
        if (progress == 0) {
            share_delay = 0;
            current_state = 5;
        }
        progress += 1;
        if (getProgressRemaining() <= 0 || Core.cheat) {
            heat = Core.cheat ? topHeat - 20 : 0;
            progress = 0;
            match.apply(this);
            share_delay = 0;
            current_state = 6;
        }
        shareState();
    }

    int share_delay = 20 * 30;
    int current_state = -1, last_state = -1;

    void shareState() {
        share_delay--;
        if (share_delay <= 0 || current_state != last_state) {
            share_delay = 20 * 15;
            broadcastMessage(null, getAuxillaryInfoPacket());
            last_state = current_state;
        }
    }

    int countMaterial(ItemStack toMatch) {
        int count = 0;
        for (ItemStack is : inputs) {
            if (is == null) {
                continue;
            } else if (FzUtil.wildcardSimilar(toMatch, is)) {
                count += is.stackSize;
            }
        }
        return count;
    }

    public static ArrayList<CrystalRecipe> recipes = new ArrayList();
    
    public static class CrystalRecipe {
        public ItemStack input, output, solution;
        public float output_count;

        public CrystalRecipe(ItemStack input, ItemStack output, float output_count, ItemStack solution) {
            this.input = input;
            this.output = output;
            this.output_count = output_count;
            this.solution = solution;
        }

        boolean matches(TileEntityCrystallizer crys) {
            if (crys.output != null) {
                if (!FzUtil.couldMerge(crys.output, output)) {
                    return false;
                }
                if (crys.output.stackSize + output_count > crys.output.getMaxStackSize()) {
                    return false;
                }
            }
            if (solution != null) {
                if (crys.countMaterial(solution) < solution.stackSize) {
                    return false;
                }
            }
            if (input != null) {
                return crys.countMaterial(input) >= input.stackSize;
            } else {
                return true;
            }
        }
        
        private void applyTo(TileEntityCrystallizer crys, int slot) {
            int delta = (int) output_count;
            if (delta != output_count && rand.nextFloat() < (output_count - delta)) {
                delta++;
            }
            if (crys.output != null && crys.output.stackSize + delta > crys.output.getMaxStackSize()) {
                return;
            }
            ItemStack is = input.copy();
            while (is.stackSize > 0) {
                crys.inputs[slot].stackSize--;
                crys.inputs[slot] = FzUtil.normalize(crys.inputs[slot]);
                is.stackSize--;
            }
            if (crys.output == null) {
                crys.output = output.copy();
                assert output.stackSize == 0: "output stack size is specified in the output_count";
                crys.output.stackSize = 0;
            }
            crys.output.stackSize += delta;
        }

        void apply(TileEntityCrystallizer crys) {
            for (int i = 0; i < crys.inputs.length; i++) {
                ItemStack is = crys.inputs[i];
                if (is != null && FzUtil.wildcardSimilar(input, is)) {
                    applyTo(crys, i);
                }
            }
        }
    }

    public static void addRecipe(ItemStack input, ItemStack output, float output_count, ItemStack solution) {
        if (output.stackSize != 1) {
            throw new RuntimeException("Stacksize should be 1");
        }
        if (output_count == 0) {
            throw new RuntimeException("output_count is 0");
        }
        output = output.copy();
        output.stackSize = 0;
        recipes.add(new CrystalRecipe(input, output, output_count, solution));
    }

    CrystalRecipe getMatchingRecipe() {
        for (CrystalRecipe r : recipes) {
            if (r.matches(this)) {
                return r;
            }
        }
        return null;
    }

    ItemStack null2fake(ItemStack is) {
        if (is == null) {
            return Core.registry.crystallizer_item;
        }
        return is;
    }

    @Override
    public Packet getAuxillaryInfoPacket() {
        return getDescriptionPacketWith(MessageType.CrystallizerInfo, null2fake(growing_crystal), null2fake(solution), progress);
    }

    @Override
    public boolean handleMessageFromServer(int messageType, DataInputStream input) throws IOException {
        if (super.handleMessageFromServer(messageType, input)) {
            return true;
        }
        if (messageType == MessageType.CrystallizerInfo) {
            growing_crystal = FzUtil.readStack(input);
            solution = FzUtil.readStack(input);
            progress = input.readInt();
            return true;
        }
        return false;
    }
    
    @Override
    public double getMaxRenderDistanceSquared() {
        return 576; //24²
    }
}