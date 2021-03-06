package com.ferreusveritas.dynamictrees.blocks;

import java.util.List;
import java.util.Random;

import com.ferreusveritas.dynamictrees.ModBlocks;
import com.ferreusveritas.dynamictrees.ModConfigs;
import com.ferreusveritas.dynamictrees.api.TreeHelper;
import com.ferreusveritas.dynamictrees.api.cells.CellNull;
import com.ferreusveritas.dynamictrees.api.cells.ICell;
import com.ferreusveritas.dynamictrees.api.network.IBurningListener;
import com.ferreusveritas.dynamictrees.api.network.MapSignal;
import com.ferreusveritas.dynamictrees.api.treedata.ITreePart;
import com.ferreusveritas.dynamictrees.systems.GrowSignal;
import com.ferreusveritas.dynamictrees.systems.nodemappers.NodeDestroyer;
import com.ferreusveritas.dynamictrees.systems.nodemappers.NodeNetVolume;
import com.ferreusveritas.dynamictrees.trees.DynamicTree;
import com.ferreusveritas.dynamictrees.trees.Species;
import com.ferreusveritas.dynamictrees.util.MathHelper;

import net.minecraft.block.Block;
import net.minecraft.block.BlockFire;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.EnumPushReaction;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.properties.PropertyInteger;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Enchantments;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Explosion;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.common.property.ExtendedBlockState;
import net.minecraftforge.common.property.IExtendedBlockState;
import net.minecraftforge.common.property.IUnlistedProperty;
import net.minecraftforge.common.property.Properties;

public class BlockBranch extends Block implements ITreePart, IBurningListener {
	
	private DynamicTree tree; //The tree this branch type creates
	public static final PropertyInteger RADIUS = PropertyInteger.create("radius", 1, 8);
	
	// This is a nightmare
	public static final IUnlistedProperty<Integer> RADIUSD = new Properties.PropertyAdapter<Integer>(PropertyInteger.create("radiusd", 0, 8));
	public static final IUnlistedProperty<Integer> RADIUSU = new Properties.PropertyAdapter<Integer>(PropertyInteger.create("radiusu", 0, 8));
	public static final IUnlistedProperty<Integer> RADIUSN = new Properties.PropertyAdapter<Integer>(PropertyInteger.create("radiusn", 0, 8));
	public static final IUnlistedProperty<Integer> RADIUSS = new Properties.PropertyAdapter<Integer>(PropertyInteger.create("radiuss", 0, 8));
	public static final IUnlistedProperty<Integer> RADIUSW = new Properties.PropertyAdapter<Integer>(PropertyInteger.create("radiusw", 0, 8));
	public static final IUnlistedProperty<Integer> RADIUSE = new Properties.PropertyAdapter<Integer>(PropertyInteger.create("radiuse", 0, 8));
	public static final IUnlistedProperty CONNECTIONS[] = { RADIUSD, RADIUSU, RADIUSN, RADIUSS, RADIUSW, RADIUSE };
	
	public BlockBranch(String name) {
		super(Material.WOOD); //Trees are made of wood. Brilliant.
		setSoundType(SoundType.WOOD); //aaaaand they also sound like wood.
		setHarvestLevel("axe", 0);
		setDefaultState(this.blockState.getBaseState().withProperty(RADIUS, 1));
		setUnlocalizedName(name);
		setRegistryName(name);
	}
	
	///////////////////////////////////////////
	// BLOCKSTATES
	///////////////////////////////////////////
	
	@Override
	protected BlockStateContainer createBlockState() {
		IProperty[] listedProperties = { RADIUS };
		return new ExtendedBlockState(this, listedProperties, CONNECTIONS);
	}
	
	/**
	 * Convert the given metadata into a BlockState for this Block
	 */
	@Override
	public IBlockState getStateFromMeta(int meta) {
		return this.getDefaultState().withProperty(RADIUS, (meta & 7) + 1);
	}
	
	/**
	 * Convert the BlockState into the correct metadata value
	 */
	@Override
	public int getMetaFromState(IBlockState state) {
		return state.getValue(RADIUS) - 1;
	}
	
	@Override
	public IBlockState getExtendedState(IBlockState state, IBlockAccess world, BlockPos pos) {
		if (state instanceof IExtendedBlockState) {
			IExtendedBlockState retval = (IExtendedBlockState) state;
			int thisRadius = getRadius(state);
			
			for (EnumFacing dir : EnumFacing.VALUES) {
				retval = retval.withProperty(CONNECTIONS[dir.getIndex()], getSideConnectionRadius(world, pos, thisRadius, dir));
			}
			return retval;
		}
		
		return state;
	}
	
	///////////////////////////////////////////
	// TREE INFORMATION
	///////////////////////////////////////////
	
	public void setTree(DynamicTree tree) {
		this.tree = tree;
	}
	
	public DynamicTree getTree() {
		return tree;
	}
	
	@Override
	public DynamicTree getTree(IBlockAccess blockAccess, BlockPos pos) {
		return getTree();
	}
	
	@Override
	public boolean isWood(IBlockAccess world, BlockPos pos) {
		return true;
	}
	
	public boolean isSameWood(ITreePart treepart) {
		return isSameWood(TreeHelper.getBranch(treepart));
	}
	
	public boolean isSameWood(BlockBranch branch) {
		return branch != null && getTree() == branch.getTree();
	}
	
	@Override
	public int branchSupport(IBlockAccess blockAccess, BlockBranch branch, BlockPos pos, EnumFacing dir, int radius) {
		return isSameWood(branch) ? 0x11 : 0;// Other branches of the same type are always valid support.
	}
	
	///////////////////////////////////////////
	// WORLD UPDATE
	///////////////////////////////////////////
	
	/**
	 * 
	 * @param world
	 * @param pos
	 * @param radius
	 * @param rand 
	 * @param rapid if true then unsupported branch rot will occur regardless of chance value.  will also rot the entire unsupported branch at once
	 * @return true if the branch was destroyed because of rot
	 */
	public boolean checkForRot(World world, BlockPos pos, int radius, Random rand, float chance, boolean rapid) {
		
		if( !rapid && (chance == 0.0f || rand.nextFloat() > chance) ) {
			return false;//Bail out if not in rapid mode and the rot chance fails
		}
		
		// Rooty dirt below the block counts as a branch in this instance
		// Rooty dirt below for saplings counts as 2 neighbors if the soil is not infertile
		int neigh = 0;// High Nybble is count of branches, Low Nybble is any reinforcing treepart(including branches)
		
		for (EnumFacing dir : EnumFacing.VALUES) {
			BlockPos deltaPos = pos.offset(dir);
			neigh += TreeHelper.getSafeTreePart(world, deltaPos).branchSupport(world, this, deltaPos, dir, radius);
			if (neigh >= 0x10 && (neigh & 0x0F) >= 2) {// Need two neighbors.. one of which must be another branch
				return false;// We've proven that this branch is reinforced so there is no need to continue
			}
		}
		
		boolean didRot = getTree().rot(world, pos, neigh & 0x0F, radius, rand);// Unreinforced branches are destroyed
		
		if(rapid && didRot) {// Speedily rot back dead branches if this block rotted
			for (EnumFacing dir : EnumFacing.VALUES) {// The logic here is that if this block rotted then
				BlockPos neighPos = pos.offset(dir);// the neighbors might be rotted too.
				IBlockState state = world.getBlockState(neighPos);
				if(state.getBlock() == this) { // Only check blocks logs that are the same as this one
					checkForRot(world, neighPos, getRadius(state), rand, 1.0f, true);
				}
			}
		}
		
		return didRot;
	}
	
	///////////////////////////////////////////
	// INTERACTION
	///////////////////////////////////////////
	
	@Override
	public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player, EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
		ItemStack heldItem = player.getHeldItem(hand);
		return TreeHelper.getSafeTreePart(world, pos).getTree(world, pos).onTreeActivated(world, pos, state, player, hand, heldItem, facing, hitX, hitY, hitZ);
	}
	
	@Override
	public float getBlockHardness(IBlockState blockState, World world, BlockPos pos) {
		int radius = getRadius(world, pos);
		return getTree().getPrimitiveLog().getBlock().getBlockHardness(blockState, world, pos) * (radius * radius) / 64.0f * 8.0f;
	};
	
	@Override
	public int getFlammability(IBlockAccess world, BlockPos pos, EnumFacing face) {
		// return 300;
		return getTree().getPrimitiveLog().getBlock().getFlammability(world, pos, face);
	}
	
	@Override
	public int getFireSpreadSpeed(IBlockAccess world, BlockPos pos, EnumFacing face) {
		// return 4096;
		int radius = getRadius(world, pos);
		return (getTree().getPrimitiveLog().getBlock().getFireSpreadSpeed(world, pos, face) * radius) / 8 ;
	}
	
	///////////////////////////////////////////
	// RENDERING
	///////////////////////////////////////////
	
	@Override
	public boolean isFullCube(IBlockState state) {
		return false;
	}
	
	@Override
	public boolean isOpaqueCube(IBlockState state) {
		return getRadius(state) == 8;
	}
	
	@Override
	public boolean shouldSideBeRendered(IBlockState blockState, IBlockAccess blockAccess, BlockPos pos,	EnumFacing side) {
		if (getRadius(blockState) == 8) {
			return super.shouldSideBeRendered(blockState, blockAccess, pos, side);
		} else {
			return true;
		}
	}
	
	
	///////////////////////////////////////////
	// GROWTH
	///////////////////////////////////////////
	
	@Override
	public ICell getHydrationCell(IBlockAccess blockAccess, BlockPos pos, IBlockState blockState, EnumFacing dir, DynamicTree leavesTree) {
		DynamicTree thisTree = getTree();
		
		if(leavesTree == thisTree) {// The requesting leaves must match the tree for hydration to occur
			return thisTree.getCellForBranch(blockAccess, pos, blockState, dir, this);
		} else {
			return CellNull.nullCell;
		}
	}
	
	@Override
	public int getRadius(IBlockAccess blockAccess, BlockPos pos) {
		return getRadius(blockAccess.getBlockState(pos));
	}
	
	public int getRadius(IBlockState blockState) {
		if (blockState.getBlock() == this) {
			return blockState.getValue(RADIUS);
		} else {
			return 0;
		}
	}
	
	public void setRadius(World world, BlockPos pos, int radius) {
		world.setBlockState(pos, this.blockState.getBaseState().withProperty(RADIUS, MathHelper.clamp(radius, 1, 8)), 2);
	}
	
	// Directionless probability grabber
	@Override
	public int probabilityForBlock(IBlockAccess blockAccess, BlockPos pos, BlockBranch from) {
		return isSameWood(from) ? getRadius(blockAccess, pos) + 2 : 0;
	}
	
	public GrowSignal growIntoAir(World world, BlockPos pos, GrowSignal signal, int fromRadius) {
		DynamicTree tree = signal.getSpecies().getTree();
		
		BlockDynamicLeaves leaves = tree.getDynamicLeaves();
		if (leaves != null) {
			if (fromRadius == 1) {// If we came from a twig then just make some leaves
				signal.success = leaves.growLeaves(world, tree, pos, 0);
			} else {// Otherwise make a proper branch
				return leaves.branchOut(world, pos, signal);
			}
		}
		return signal;
	}
	
	@Override
	public GrowSignal growSignal(World world, BlockPos pos, GrowSignal signal) {
		
		if (signal.step()) {// This is always placed at the beginning of every growSignal function
			Species species = signal.getSpecies();
			//DynamicTree tree = signal.getTree();
			
			EnumFacing originDir = signal.dir.getOpposite();// Direction this signal originated from
			EnumFacing targetDir = tree.getCommonSpecies().selectNewDirection(world, pos, this, signal);// This must be cached on the stack for proper recursion
			signal.doTurn(targetDir);
			
			{
				BlockPos deltaPos = pos.offset(targetDir);
				
				// Pass grow signal to next block in path
				ITreePart treepart = TreeHelper.getTreePart(world, deltaPos);
				if (treepart != null) {
					signal = treepart.growSignal(world, deltaPos, signal);// Recurse
				} else if (world.isAirBlock(deltaPos)) {
					signal = growIntoAir(world, deltaPos, signal, getRadius(world, pos));
				}
			}
			
			// Calculate Branch Thickness based on neighboring branches
			float areaAccum = signal.radius * signal.radius;// Start by accumulating the branch we just came from
			
			for (EnumFacing dir : EnumFacing.VALUES) {
				if (!dir.equals(originDir) && !dir.equals(targetDir)) {// Don't count where the signal originated from or the branch we just came back from
					BlockPos deltaPos = pos.offset(dir);
					
					// If it is decided to implement a special block(like a squirrel hole, tree
					// swing, rotting, burned or infested branch, etc) then this new block could be
					// derived from BlockBranch and this works perfectly. Should even work with
					// tileEntity blocks derived from BlockBranch.
					ITreePart treepart = TreeHelper.getTreePart(world, deltaPos);
					if (isSameWood(treepart)) {
						int branchRadius = treepart.getRadius(world, deltaPos);
						areaAccum += branchRadius * branchRadius;
					}
				}
			}
			
			// The new branch should be the square root of all of the sums of the areas of the branches coming into it.
			// But it shouldn't be smaller than it's current size(prevents the instant slimming effect when chopping off branches)
			signal.radius = MathHelper.clamp((float) Math.sqrt(areaAccum) + species.getTapering(), getRadius(world, pos), 8);// WOW!
			setRadius(world, pos, (int) Math.floor(signal.radius));
		}
		
		return signal;
	}
	
	///////////////////////////////////////////
	// PHYSICAL BOUNDS
	///////////////////////////////////////////
	
	// This is only so effective because the center of the player must be inside the block that contains the tree trunk.
	// The result is that only thin branches and trunks can be climbed
	@Override
	public boolean isLadder(IBlockState state, IBlockAccess world, BlockPos pos, EntityLivingBase entity) {
		return true;
	}
	
	@Override
	public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess blockAccess, BlockPos pos) {
		
		if (state.getBlock() != this) {
			return NULL_AABB;
		}
		
		int thisRadius = getRadius(state);
		
		boolean connectionMade = false;
		double radius = thisRadius / 16.0;
		double gap = 0.5 - radius;
		AxisAlignedBB aabb = new AxisAlignedBB(0, 0, 0, 0, 0, 0).grow(radius);
		for (EnumFacing dir : EnumFacing.VALUES) {
			if (getSideConnectionRadius(blockAccess, pos, thisRadius, dir) > 0) {
				connectionMade = true;
				aabb = aabb.expand(dir.getFrontOffsetX() * gap, dir.getFrontOffsetY() * gap, dir.getFrontOffsetZ() * gap);
			}
		}
		if (connectionMade) {
			return aabb.offset(0.5, 0.5, 0.5);
		}
		return new AxisAlignedBB(0.5 - radius, 0.5 - radius, 0.5 - radius, 0.5 + radius, 0.5 + radius, 0.5 + radius);
	}
	
	@Override
	public void addCollisionBoxToList(IBlockState state, World worldIn, BlockPos pos, AxisAlignedBB entityBox, List<AxisAlignedBB> collidingBoxes, Entity entityIn, boolean p_185477_7_) {
		int thisRadius = getRadius(state);
		
		for (EnumFacing dir : EnumFacing.VALUES) {
			int connRadius = getSideConnectionRadius(worldIn, pos, thisRadius, dir);
			if (connRadius > 0) {
				double radius = MathHelper.clamp(connRadius, 1, thisRadius) / 16.0;
				double gap = 0.5 - radius;
				AxisAlignedBB aabb = new AxisAlignedBB(0, 0, 0, 0, 0, 0).grow(radius);
				aabb = aabb.offset(dir.getFrontOffsetX() * gap, dir.getFrontOffsetY() * gap, dir.getFrontOffsetZ() * gap).offset(0.5, 0.5, 0.5);
				addCollisionBoxToList(pos, entityBox, collidingBoxes, aabb);
			}
		}
	}
	
	@Override
	public int getRadiusForConnection(IBlockAccess world, BlockPos pos, BlockBranch from, int fromRadius) {
		return getRadius(world, pos);
	}
	
	public int getSideConnectionRadius(IBlockAccess blockAccess, BlockPos pos, int radius, EnumFacing side) {
		BlockPos deltaPos = pos.offset(side);
		return TreeHelper.getSafeTreePart(blockAccess, deltaPos).getRadiusForConnection(blockAccess, deltaPos, this, radius);
	}
	
	///////////////////////////////////////////
	// NODE ANALYSIS
	///////////////////////////////////////////
	
	@Override
	public MapSignal analyse(World world, BlockPos pos, EnumFacing fromDir, MapSignal signal) {
		// Note: fromDir will be null in the origin node
		if (signal.depth++ < 32) {// Prevents going too deep into large networks, or worse, being caught in a network loop
			signal.run(world, this, pos, fromDir);// Run the inspectors of choice
			for (EnumFacing dir : EnumFacing.VALUES) {// Spread signal in various directions
				if (dir != fromDir) {// don't count where the signal originated from
					BlockPos deltaPos = pos.offset(dir);
					
					signal = TreeHelper.getSafeTreePart(world, deltaPos).analyse(world, deltaPos, dir.getOpposite(), signal);
					
					// This should only be true for the originating block when the root node is found
					if (signal.found && signal.localRootDir == null && fromDir == null) {
						signal.localRootDir = dir;
					}
				}
			}
			signal.returnRun(world, this, pos, fromDir);
		} else {
			world.setBlockToAir(pos);// Destroy one of the offending nodes
			signal.overflow = true;
		}
		signal.depth--;
		
		return signal;
	}
	
	public Species getSpeciesFromSignal(World world, MapSignal signal) {
		Species species;
		if(signal.found) {
			BlockRootyDirt rootyDirt = (BlockRootyDirt) world.getBlockState(signal.root).getBlock();
			species = rootyDirt.getSpecies(world, signal.root);
		} else {
			species = getTree().getCommonSpecies();
		}
		
		return species;
	}
	
	// Destroys all branches recursively not facing the branching direction with the root node
	public int destroyTreeFromNode(World world, BlockPos pos) {//, float fortuneFactor) {
		MapSignal signal = analyse(world, pos, null, new MapSignal());// Analyze entire tree network to find root node
		Species species = getSpeciesFromSignal(world, signal);//Get the species from the root node
		NodeNetVolume volumeSum = new NodeNetVolume();
		// Analyze only part of the tree beyond the break point and calculate it's volume
		analyse(world, pos, signal.localRootDir, new MapSignal(volumeSum, new NodeDestroyer(species)));
		return volumeSum.getVolume();// Drop an amount of wood calculated from the body of the tree network
	}
	
	public int destroyEntireTree(World world, BlockPos pos) {
		MapSignal signal = analyse(world, pos, null, new MapSignal());// Analyze entire tree network to find root node
		Species species = getSpeciesFromSignal(world, signal);//Get the species from the root node
		NodeNetVolume volumeSum = new NodeNetVolume();
		// Analyze the entire tree and calculate it's volume
		analyse(world, pos, null, new MapSignal(volumeSum, new NodeDestroyer(species)));
		return volumeSum.getVolume();// Drop an amount of wood calculated from the body of the tree network
	}
	
	///////////////////////////////////////////
	// DROPS AND HARVESTING
	///////////////////////////////////////////
	
	public List<ItemStack> getWoodDrops(World world, BlockPos pos, int volume) {
		List<ItemStack> ret = new java.util.ArrayList<ItemStack>();//A list for storing all the dead tree guts
		volume *= ModConfigs.treeHarvestMultiplier;// For cheaters.. you know who you are.
		return getTree().getCommonSpecies().getLogsDrops(world, pos, ret, volume);
	}
	
	/*
	1.10.2 Simplified Block Harvesting Logic Flow(for no silk touch)
	
	tryHarvestBlock {
		canHarvest = canHarvestBlock() <- (ForgeHooks.canHarvestBlock occurs in here)
		removed = removeBlock(canHarvest) {
			removedByPlayer() {
				onBlockHarvested()
				world.setBlockState() <- block is set to air here
			}
		}
		
		if (removed) harvestBlock() {
			fortune = getEnchantmentLevel(FORTUNE)
			dropBlockAsItem(fortune) {
				dropBlockAsItemWithChance(fortune) {
					items = getDrops(fortune) {
						getItemDropped(fortune) {
							Item.getItemFromBlock(this) <- (Standard block behavior)
						}
					}
					ForgeEventFactory.fireBlockHarvesting(items) <- (BlockEvent.HarvestDropsEvent)
					(for all items) -> spawnAsEntity(item)
				}
			}
		}
	}
	*/
	
	// We override the standard behaviour because we need to preserve the tree network structure to calculate
	// the wood volume for drops.  The standard removedByPlayer() call will set this block to air before we get
	// a chance to make a summation.  Because we have done this we must re-implement the entire drop logic flow.
	@Override
	public boolean removedByPlayer(IBlockState state, World world, BlockPos pos, EntityPlayer player, boolean canHarvest) {
		ItemStack heldItem = player.getHeldItemMainhand();
		int fortune = EnchantmentHelper.getEnchantmentLevel(Enchantments.FORTUNE, heldItem);
		float fortuneFactor = 1.0f + 0.25f * fortune;
		int woodVolume = destroyTreeFromNode(world, pos);
		List<ItemStack> items = getWoodDrops(world, pos, (int)(woodVolume * fortuneFactor));
		
		//For An-Sar's PrimalCore mod :)
		float chance = net.minecraftforge.event.ForgeEventFactory.fireBlockHarvesting(items, world, pos, state, fortune, 1.0f, false, harvesters.get());
		
		for (ItemStack item : items) {
			if (world.rand.nextFloat() <= chance) {
				spawnAsEntity(world, pos, item);
			}
		}
		
		return true;// Function returns true if Block was destroyed
	}
	
	// Super member also does nothing
	@Override
	public void onBlockHarvested(World world, BlockPos pos, IBlockState state, EntityPlayer player) {
	}
	
	// Since we already created drops in removedByPlayer() we must disable this.
	// Also we should definitely not return BlockBranch itemBlocks and here's why:
	//	* Players can use these blocks to make branch network loops that will grow artificially large in a short time.
	//	* Players can create invalid networks with more than one root node.
	//  * Players can exploit fortune enchanted tools by building a tree with parts and cutting it down for more wood.
	//	* Players can attach the wrong kind of branch to a tree leading to undefined behavior.
	// If a player in creative wants to do these things then that's their prerogative. 
	@Override
	public Item getItemDropped(IBlockState state, Random rand, int fortune) {
		return null;
	}
	
	// Similar to above.. We already created drops in removedByPlayer() so no quantity should be expressed
	@Override
	public int quantityDropped(Random random) {
		return 0;
	}
	
	// We do not allow silk harvest for all the reasons listed in getItemDropped
	@Override
	public boolean canSilkHarvest(World world, BlockPos pos, IBlockState state, EntityPlayer player) {
		return false;
	}
	
	// We do not allow the tree branches to be pushed by a piston for reasons that should be obvious if you
	// are paying attention.
	@Override
	public EnumPushReaction getMobilityFlag(IBlockState state) {
		return EnumPushReaction.BLOCK;
	}
	
	// Explosive harvesting methods will likely result in mostly sticks but i'm okay with that since it kinda makes sense.
	@Override
	public void onBlockExploded(World world, BlockPos pos, Explosion explosion) {
		int woodVolume = destroyTreeFromNode(world, pos);
		for (ItemStack item : getWoodDrops(world, pos, woodVolume)) {
			spawnAsEntity(world, pos, item);
		}
	}
	
	@Override
	public void onBurned(World world, IBlockState oldState, BlockPos burnedPos) {		
		//possible supporting branch was destroyed by fire.
		if(oldState.getBlock() == this) {
			for(EnumFacing dir: EnumFacing.VALUES) {
				BlockPos neighPos = burnedPos.offset(dir);
				if(TreeHelper.isBranch(world, neighPos)) {
					BlockPos rootPos = DynamicTree.findRootNode(world, neighPos);
					if(rootPos == null) {
						analyse(world, neighPos, null, new MapSignal(new NodeDestroyer(getTree().getCommonSpecies())));
					}
				}
			}
		}
		
	}
	
	@Override
	public void neighborChanged(IBlockState state, World world, BlockPos pos, Block blockIn, BlockPos neighbor) {		
		IBlockState neighBlockState = world.getBlockState(neighbor);
		
		if(neighBlockState.getMaterial() == Material.FIRE && neighBlockState.getBlock() != ModBlocks.blockVerboseFire) {
			int age = neighBlockState.getBlock() == Blocks.FIRE ? ((Integer)neighBlockState.getValue(BlockFire.AGE)).intValue() : 0;
			world.setBlockState(neighbor, ModBlocks.blockVerboseFire.getDefaultState().withProperty(BlockFire.AGE, age));
		}
		
	}
	
	@Override
	public boolean isBranch() {
		return true;
	}
	
	///////////////////////////////////////////
	// IRRELEVANT
	///////////////////////////////////////////
	
	@Override
	public boolean isRootNode() {
		return false;
	}
	
}
