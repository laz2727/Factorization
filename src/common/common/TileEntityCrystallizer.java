package factorization.common;

import java.io.DataInput;
import java.io.IOException;
import java.util.ArrayList;

import com.google.common.base.Preconditions;

import factorization.common.NetworkFactorization.MessageType;

import net.minecraft.src.FactorizationHack;
import net.minecraft.src.ItemStack;
import net.minecraft.src.NBTTagCompound;
import net.minecraft.src.Packet;
import net.minecraftforge.common.ForgeDirection;

public class TileEntityCrystallizer extends TileEntityFactorization {
    ItemStack inputs[] = new ItemStack[6];
    ItemStack output;

    public ItemStack growing_crystal, solution;
    public int heat, progress;

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
        needLogic();
        if (slot == inputs.length) {
            output = is;
            return;
        }
        inputs[slot] = is;
    }

    @Override
    public String getInvName() {
        return "Crystallizer";
    }

    @Override
    public int getStartInventorySide(ForgeDirection side) {
        if (side == ForgeDirection.UP || side == ForgeDirection.DOWN) {
            return inputs.length;
        }
        return 0;
    }

    @Override
    public int getSizeInventorySide(ForgeDirection side) {
        if (side == ForgeDirection.UP || side == ForgeDirection.DOWN) {
            return 1;
        }
        return inputs.length;
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
            if (must_match != null && inputs[i] != null && !must_match.isItemEqual(inputs[i])) {
                continue;
            }
            int here_size = FactorizationUtil.getStackSize(inputs[i]);
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

    boolean needHeat() {
        if (heat >= 100) {
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
    void doLogic() {
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
        if (heat < 100) {
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
            heat = Core.cheat ? 80 : 0;
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
            if (is != null && is.isItemEqual(toMatch)) {
                count += is.stackSize;
            }
        }
        return count;
    }

    static ArrayList<CrystalRecipe> recipes = new ArrayList();

    static class CrystalRecipe {
        ItemStack input, output, solution;
        float output_count;
        int antium_count;

        public CrystalRecipe(ItemStack input, ItemStack output, float output_count,
                ItemStack solution, int antium_count) {
            this.input = input;
            this.output = output;
            this.output_count = output_count;
            this.solution = solution;
            this.antium_count = antium_count;
        }

        boolean matches(TileEntityCrystallizer crys) {
            if (crys.output != null) {
                if (!crys.output.isItemEqual(output)) {
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

        void apply(TileEntityCrystallizer crys) {
            ItemStack is = input.copy();
            while (is.stackSize > 0) {
                int slot = crys.pickInputSlot(is);
                crys.inputs[slot].stackSize--;
                crys.inputs[slot] = FactorizationUtil.normalize(crys.inputs[slot]);
                is.stackSize--;
            }
            int delta = (int) output_count;
            if (rand.nextFloat() > (output_count - delta)) {
                delta++;
            }
            if (crys.output == null) {
                crys.output = output.copy();
                crys.output.stackSize = delta;
            } else {
                crys.output.stackSize += delta;
            }
        }
    }

    public static void addRecipe(ItemStack input, ItemStack output, float output_count, ItemStack solution,
            int antium_count) {
        recipes.add(new CrystalRecipe(input, output, output_count, solution, antium_count));
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

    ItemStack unfake(ItemStack is) {
        if (is.isItemEqual(Core.registry.crystallizer_item)) {
            return null;
        }
        return is;
    }

    @Override
    public Packet getAuxillaryInfoPacket() {
        return getDescriptionPacketWith(MessageType.CrystallizerInfo, null2fake(growing_crystal), null2fake(solution), progress);
    }

    @Override
    public boolean handleMessageFromServer(int messageType, DataInput input) throws IOException {
        if (super.handleMessageFromServer(messageType, input)) {
            return true;
        }
        if (messageType == MessageType.CrystallizerInfo) {
            growing_crystal = unfake(FactorizationHack.loadItemStackFromDataInput(input));
            solution = unfake(FactorizationHack.loadItemStackFromDataInput(input));
            progress = input.readInt();
            return true;
        }
        return false;
    }
}
