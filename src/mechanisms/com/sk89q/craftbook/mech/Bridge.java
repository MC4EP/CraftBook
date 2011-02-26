package com.sk89q.craftbook.mech;

import java.util.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.event.block.*;
import com.sk89q.craftbook.*;
import com.sk89q.craftbook.bukkit.*;
import com.sk89q.craftbook.util.*;
import com.sk89q.worldedit.blocks.*;
import com.sk89q.worldedit.regions.*;

/**
 * The default bridge mechanism -- signposts on either side of a 3xN plane of
 * blocks.
 * 
 * @author hash
 * 
 */
public class Bridge extends Mechanic {
    public static class BridgeFactory implements MechanicFactory<Bridge> {
        public BridgeFactory(MechanismsPlugin plugin) {
            this.plugin = plugin;
        }
        
        protected MechanismsPlugin plugin;

        /**
         * Explore around the trigger to find a Bridge; throw if things look funny.
         * 
         * @param pt the trigger (should be a signpost)
         * @return a Bridge if we could make a valid one, or null if this looked
         *         nothing like a bridge.
         * @throws InvalidMechanismException
         *             if the area looked like it was intended to be a bridge, but
         *             it failed.
         */
        public Bridge detect(BlockWorldVector pt) throws InvalidMechanismException {
            Block block = pt.getWorld().getBlockAt(BukkitUtil.toLocation(pt));
            // check if this looks at all like something we're interested in first
            if (block.getTypeId() == BlockID.WALL_SIGN) {
                BlockState state = block.getState();
                if (state instanceof Sign && ((Sign) state).getLine(1).equalsIgnoreCase("[Bridge]")) {
                    // okay, now we can start doing exploration of surrounding blocks
                    // and if something goes wrong in here then we throw fits.
                    return new Bridge(block, plugin);
                }
            }
            return null;
        }
    }

    /**
     * @param trigger
     *            if you didn't already check if this is a sign with appropriate
     *            text, you're going on Santa's naughty list.
     * @param plugin
     * @throws InvalidMechanismException
     */
    private Bridge(Block trigger, MechanismsPlugin plugin) throws InvalidMechanismException {
        super();
        
        if (!SignUtil.isCardinal(trigger)) throw new InvalidDirectionException();
        BlockFace dir = SignUtil.getFacing(trigger);
        
        this.plugin = plugin;
        this.trigger = trigger;
        
        // Attempt to detect whether the bridge is above or below the sign,
        // first assuming that the bridge is above
        Material mat;
        findBase: {
            proximalBaseCenter = trigger.getFace(BlockFace.UP);
            mat = proximalBaseCenter.getType();
            if (settings.canUseBlock(mat)) {
                if ((proximalBaseCenter.getFace(SignUtil.getLeft(trigger)).getType() == mat)
                 && (proximalBaseCenter.getFace(SignUtil.getRight(trigger)).getType()) == mat)
                    break findBase;     // yup, it's above
                // cant throw the invalid construction exception here
                // because there still might be a valid one below
            }
            proximalBaseCenter = trigger.getFace(BlockFace.DOWN);
            mat = proximalBaseCenter.getType();
            if (settings.canUseBlock(mat)) {
                if ((proximalBaseCenter.getFace(SignUtil.getLeft(trigger)).getType() == mat)
                 && (proximalBaseCenter.getFace(SignUtil.getRight(trigger)).getType()) == mat)
                    break findBase;     // it's below
                throw new InvalidConstructionException("Blocks adjacent to the bridge block must be of the same type.");
            } else {
                throw new UnacceptableTypeException();
            }
        }
        
        // Find the other side
        farside = trigger.getFace(dir);
        for (int i = 0; i <= settings.maxLength; i++) {
            // about the loop index:
            // i = 0 is the first block after the proximal base
            // since we're allowed to have settings.maxLength toggle blocks,
            // i = settings.maxLength is actually the farthest place we're 
            // allowed to find the distal signpost
            
            if (farside.getType() == Material.SIGN_POST) {
                String otherSignText = ((Sign)farside.getState()).getLines()[1];
                if ("[Bridge]".equalsIgnoreCase(otherSignText)) break;
                if ("[Bridge End]".equalsIgnoreCase(otherSignText)) break;
            }
            
            farside = trigger.getFace(dir);
        }
        if (farside.getType() == Material.SIGN_POST)
            throw new InvalidConstructionException("[Bridge] sign required on other side (or it was too far away).");
        
        // Check the other side's base blocks for matching type
        Block distalBaseCenter = farside.getFace(trigger.getFace(proximalBaseCenter));
        if ((distalBaseCenter.getType() != mat)
         || (distalBaseCenter.getFace(SignUtil.getLeft(trigger)).getType() != mat)
         || (distalBaseCenter.getFace(SignUtil.getRight(trigger)).getType() != mat))
            throw new InvalidConstructionException("The other side must be made with the same blocks.");
        
        // Select the togglable region
        toggle = new CuboidRegion(BukkitUtil.toVector(proximalBaseCenter),BukkitUtil.toVector(distalBaseCenter));
        toggle.contract(BukkitUtil.toVector(SignUtil.getBack(trigger)));
        toggle.contract(BukkitUtil.toVector(SignUtil.getFront(trigger)));
        toggle.expand(BukkitUtil.toVector(SignUtil.getLeft(trigger)));
        toggle.expand(BukkitUtil.toVector(SignUtil.getRight(trigger)));
        
        // Win!
    }
    
    protected MechanismsPlugin plugin;
    protected BridgeSettings settings;
    
    /** The signpost we came from. */
    private Block trigger;
    /** The block that determines bridge type. */
    private Block proximalBaseCenter;
    /** The signpost on the other end. */
    private Block farside;
    /** The rectangle that we toggle. */
    private CuboidRegion toggle;
    // we don't store anything about the blocks on the ends because 
    // we never poke them; just check that they're sane when we're building
    // the bridge.  if this were a PersistentMechanic, those six blocks
    // would be considered defining blocks, though.
    

    public void onRightClick(BlockRightClickEvent event) {
        if (!BukkitUtil.toWorldVector(event.getBlock()).equals(trigger)) return; //wth? our manager is insane
        flipState();
        //notify event.getPlayer();
    }
    
    public void onBlockRedstoneChange(BlockRedstoneEvent event) {
        if (!BukkitUtil.toWorldVector(event.getBlock()).equals(trigger)) return; //wth? our manager is insane
        flipState();
    }
    
    private void flipState() {
        // this is kinda funky, but we only check one position 
        // to see if the bridge is open and/or closable.
        // efficiency choice :/
        Block hinge = proximalBaseCenter.getFace(SignUtil.getFacing(trigger));
        
        // aaand we also only check if it's something we can 
        // smosh or not when deciding if we're open or closed.
        // there are no errors upon weird blocks like obsidian 
        // in the middle of a wooden bridge.
        if (canPassThrough(hinge.getType())) {
            setToggleRegion(proximalBaseCenter.getType());
        } else {
            setToggleRegion(Material.AIR);
        }
    }
    
    private void setToggleRegion(Material mat) {
        for (com.sk89q.worldedit.BlockVector bv : toggle) {     // this package specification is something that needs to be fixed in the overall scheme
            trigger.getWorld().getBlockAt(bv.getBlockX(), bv.getBlockY(), bv.getBlockZ()).setType(mat);
        }
    }
    
    
    

    /**
     * @return whether the door can pass through this BlockType (and displace it
     *         if needed).
     */
    private static boolean canPassThrough(Material t) {
        switch (t) {
            case AIR:
            case WATER:
            case STATIONARY_WATER:
            case LAVA:
            case STATIONARY_LAVA:
            case SNOW:
                return true;
            default:
                return false;
        }
    }
    
    
    
    public void unload() {
        /* we're not persistent */
    }
    
    public boolean isActive() {
        /* we're not persistent */
        return false;
    }
    
    
    
    /**
     * Thrown when the sign is an invalid direction.
     */
    private static class InvalidDirectionException extends InvalidMechanismException {}
    
    /**
     * Thrown when the bridge type is unacceptable.
     */
    private static class UnacceptableTypeException extends InvalidMechanismException {}
    
    /**
     * Thrown when the bridge type is not constructed correctly.
     */
    private static class InvalidConstructionException extends InvalidMechanismException {
        public InvalidConstructionException(String msg) {
            super(msg);
        }
    }
    
    public static class BridgeSettings {
        /**
         * If you put air in this... you go straight to hell do not pass go do
         * not collect 200 dollars.
         */
        public Set<Material> allowedBlocks;
        public int maxLength;
        
        /**
         * @param b
         * @return true if the given block type can be used for a bridge; false
         *         otherwise.
         */
        public boolean canUseBlock(Material b) {
            return allowedBlocks.contains(b);
        }
        
        /**
         * @param b
         * @return true if the given block type can be used for a bridge; false
         *         otherwise.
         */
        private boolean canUseBlock(Block b) {
            return allowedBlocks.contains(b.getType());
        }
    }
}
