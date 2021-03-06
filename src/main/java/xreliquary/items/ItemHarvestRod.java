package xreliquary.items;

import com.google.common.collect.ImmutableMap;
import net.minecraft.block.Block;
import net.minecraft.block.BlockDirt;
import net.minecraft.block.IGrowable;
import net.minecraft.block.state.IBlockState;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Enchantments;
import net.minecraft.init.Items;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.EnumAction;
import net.minecraft.item.ItemDye;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.stats.StatBase;
import net.minecraft.stats.StatList;
import net.minecraft.util.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.IPlantable;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import xreliquary.Reliquary;
import xreliquary.blocks.BlockFertileLilypad;
import xreliquary.entities.EntityXRFakePlayer;
import xreliquary.init.ModCapabilities;
import xreliquary.items.util.FilteredItemStackHandler;
import xreliquary.items.util.HarvestRodItemStackHandler;
import xreliquary.items.util.IHarvestRodCache;
import xreliquary.network.PacketCountSync;
import xreliquary.network.PacketHandler;
import xreliquary.reference.Names;
import xreliquary.reference.Reference;
import xreliquary.reference.Settings;
import xreliquary.util.InventoryHelper;
import xreliquary.util.LanguageHelper;
import xreliquary.util.NBTHelper;
import xreliquary.util.XRFakePlayerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Random;

public class ItemHarvestRod extends ItemToggleable {

	public static final String BONE_MEAL_MODE = "bone_meal";
	private static final String PLANTABLE_MODE = "plantable";
	public static final String HOE_MODE = "hoe";
	private static final String MODE_NBT_TAG = "mode";
	private static final String PLANTABLE_INDEX_NBT_TAG = "plantable_index";
	private static final int AOE_START_COOLDOWN = 10;

	public ItemHarvestRod() {
		super(Names.Items.HARVEST_ROD);
		this.setCreativeTab(Reliquary.CREATIVE_TAB);
		this.setMaxStackSize(1);
		canRepair = false;
	}

	@Override
	protected void addMoreInformation(ItemStack rod, @Nullable World world, List<String> tooltip) {
		LanguageHelper.formatTooltip(getUnlocalizedNameInefficiently(rod) + ".tooltip2", ImmutableMap.of("charge", Integer.toString(getBoneMealCount(rod, true))), tooltip);
		for (int slot=1; slot<getCountPlantable(rod, true); slot++) {
			ItemStack plantable = getPlantableInSlot(rod, slot, true);
			LanguageHelper.formatTooltip(getUnlocalizedNameInefficiently(rod) + ".tooltip3",
					ImmutableMap.of("plantable", plantable.getItem().getItemStackDisplayName(plantable), "charge", Integer.toString(getPlantableQuantity(rod, slot, true))), tooltip);
		}

		if(this.isEnabled(rod))
			LanguageHelper.formatTooltip("tooltip.absorb_active", ImmutableMap.of("item", TextFormatting.WHITE + Items.DYE.getItemStackDisplayName(new ItemStack(Items.DYE, 1, Reference.WHITE_DYE_META))), tooltip);
		LanguageHelper.formatTooltip("tooltip.absorb", null, tooltip);
	}

	@Nonnull
	@Override
	public EnumAction getItemUseAction(ItemStack ist) {
		return EnumAction.BLOCK;
	}

	private int getBonemealLimit() {
		return Settings.Items.HarvestRod.boneMealLimit;
	}

	private int getBonemealWorth() {
		return Settings.Items.HarvestRod.boneMealWorth;
	}

	public int getBonemealCost() {
		return Settings.Items.HarvestRod.boneMealCost;
	}

	public int getLuckRolls() {
		return Settings.Items.HarvestRod.boneMealLuckRolls;
	}

	public int getLuckPercent() {
		return Settings.Items.HarvestRod.boneMealLuckPercentChance;
	}

	private int getBreakRadius() {
		return Settings.Items.HarvestRod.AOERadius;
	}

	@Override
	public ICapabilityProvider initCapabilities(ItemStack stack, NBTTagCompound nbt) {
		return new ICapabilitySerializable<NBTTagCompound>() {
			HarvestRodItemStackHandler itemHandler = new HarvestRodItemStackHandler();

			@Override
			public NBTTagCompound serializeNBT() {
				return itemHandler.serializeNBT();
			}

			@Override
			public void deserializeNBT(NBTTagCompound tagCompound) {
				itemHandler.deserializeNBT(tagCompound);
			}

			@Override
			public boolean hasCapability(@Nonnull Capability<?> capability, EnumFacing facing) {
				return capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY;
			}

			@Override
			public <T> T getCapability(@Nonnull Capability<T> capability, EnumFacing facing) {
				if(capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY)
					//noinspection unchecked
					return (T) itemHandler;

				return null;
			}
		};
	}

	@Override
	public void onUpdate(ItemStack ist, World world, Entity e, int slotNumber, boolean isSelected) {
		if(world.isRemote)
			return;
		EntityPlayer player = null;
		if(e instanceof EntityPlayer) {
			player = (EntityPlayer) e;
		}
		if(player == null)
			return;

		if(this.isEnabled(ist)) {
			if(getBoneMealCount(ist) + getBonemealWorth() <= getBonemealLimit()) {
				if(InventoryHelper.consumeItem(new ItemStack(Items.DYE, 1, Reference.WHITE_DYE_META), player)) {
					setBoneMealCount(ist, getBoneMealCount(ist) + getBonemealWorth(), player);
				}
			}

			consumePlantables(ist, player);
		}
	}

	private void consumePlantables(@Nonnull ItemStack harvestRod, EntityPlayer player) {
		for(int slot = 0; slot < player.inventory.mainInventory.size(); slot++) {
			ItemStack currentStack = player.inventory.mainInventory.get(slot);
			if(currentStack.getItem() instanceof IPlantable && incrementPlantable(harvestRod, currentStack, player)) {
				InventoryHelper.consumeItem(currentStack, player, 0, 1);
				break;
			}
		}
	}

	@Override
	public boolean onBlockStartBreak(ItemStack ist, BlockPos pos, EntityPlayer player) {
		if(player.world.isRemote)
			return false;

		boolean brokenBlock = false;

		Block block = player.world.getBlockState(pos).getBlock();
		if(block instanceof IPlantable || block == Blocks.MELON_BLOCK || block == Blocks.PUMPKIN) {
			for(int xOff = -getBreakRadius(); xOff <= getBreakRadius(); xOff++) {
				for(int yOff = -getBreakRadius(); yOff <= getBreakRadius(); yOff++) {
					for(int zOff = -getBreakRadius(); zOff <= getBreakRadius(); zOff++) {
						brokenBlock |= doHarvestBlockBreak(block, ist, pos, player, xOff, yOff, zOff);
					}
				}
			}
		}

		return brokenBlock;
	}

	private boolean doHarvestBlockBreak(Block initialBlock, @Nonnull ItemStack ist, BlockPos pos, EntityPlayer player, int xOff, int yOff, int zOff) {
		pos = pos.add(xOff, yOff, zOff);

		IBlockState blockState = player.world.getBlockState(pos);
		Block block = blockState.getBlock();

		if((initialBlock == Blocks.MELON_BLOCK || initialBlock == Blocks.PUMPKIN) && !(block == Blocks.MELON_BLOCK || block == Blocks.PUMPKIN))
			return false;

		if(!(block instanceof IPlantable || block == Blocks.MELON_BLOCK || block == Blocks.PUMPKIN))
			return false;
		if(block instanceof BlockFertileLilypad)
			return false;

		if(player.world.isRemote) {
			for(int particles = 0; particles <= 8; particles++)
				player.world.playEvent(player, 2001, pos, Block.getStateId(blockState));
		} else {
			NonNullList<ItemStack> drops = NonNullList.create();
			blockState.getBlock().getDrops(drops, player.world, pos, blockState, EnchantmentHelper.getEnchantmentLevel(Enchantments.FORTUNE, ist));
			Random rand = new Random();

			for(ItemStack stack : drops) {
				float f = 0.7F;
				double d = (double) (rand.nextFloat() * f) + (double) (1.0F - f) * 0.5D;
				double d1 = (double) (rand.nextFloat() * f) + (double) (1.0F - f) * 0.5D;
				double d2 = (double) (rand.nextFloat() * f) + (double) (1.0F - f) * 0.5D;
				EntityItem entityitem = new EntityItem(player.world, (double) pos.getX() + d, (double) pos.getY() + d1, (double) pos.getZ() + d2, stack);
				entityitem.setPickupDelay(10);
				player.world.spawnEntity(entityitem);
			}

			player.world.setBlockState(pos, Blocks.AIR.getDefaultState());
			StatBase stats = StatList.getBlockStats(blockState.getBlock());
			if(stats != null) {
				player.addStat(stats);
			}
			player.addExhaustion(0.01F);
		}

		return true;
	}

	private void boneMealBlock(ItemStack ist, EntityPlayer player, World world, BlockPos pos, boolean updateNBT) {
		ItemStack fakeItemStack = new ItemStack(Items.DYE, 1, Reference.WHITE_DYE_META);

		boolean usedRod = false;
		for(int repeatedUses = 0; repeatedUses <= getLuckRolls(); repeatedUses++) {
			if(repeatedUses == 0 || world.rand.nextInt(100) <= getLuckPercent()) {
				if(ItemDye.applyBonemeal(fakeItemStack, world, pos)) {
					if(!usedRod)
						usedRod = true;
					player.world.playSound(null, player.getPosition(), SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.NEUTRAL, 0.1F, 0.5F * ((player.world.rand.nextFloat() - player.world.rand.nextFloat()) * 0.7F + 1.2F));
				}
			}
		}

		if(usedRod && !player.capabilities.isCreativeMode)
			setBoneMealCount(ist, getBoneMealCount(ist) - getBonemealCost(), player, updateNBT);
	}

	public int getBoneMealCount(ItemStack ist) {
		return getBoneMealCount(ist, false);
	}

	public int getBoneMealCount(ItemStack ist, boolean isClient) {
		if (isClient) {
			return NBTHelper.getContainedStackCount(ist, 0);
		}

		IItemHandler itemHandler = ist.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);

		if(!(itemHandler instanceof FilteredItemStackHandler))
			return 0;

		FilteredItemStackHandler filteredHandler = (FilteredItemStackHandler) itemHandler;

		return filteredHandler.getTotalAmount(HarvestRodItemStackHandler.BONEMEAL_SLOT);
	}

	public void setBoneMealCount(ItemStack harvestRod, int boneMealCount) {
		setBoneMealCount(harvestRod, boneMealCount, null, true);
	}

	private void setBoneMealCount(ItemStack harvestRod, int boneMealCount, EntityPlayer player) {
		IItemHandler itemHandler = harvestRod.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);

		if(!(itemHandler instanceof FilteredItemStackHandler))
			return;

		FilteredItemStackHandler filteredHandler = (FilteredItemStackHandler) itemHandler;

		filteredHandler.setTotalAmount(0, boneMealCount);
		updateContainedItemNBT(harvestRod, player, (short) 0, ItemStack.EMPTY, boneMealCount);
	}

	private void setBoneMealCount(ItemStack harvestRod, int boneMealCount, EntityPlayer player, boolean updateNBT) {
		IItemHandler itemHandler = harvestRod.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);

		if(!(itemHandler instanceof FilteredItemStackHandler))
			return;

		FilteredItemStackHandler filteredHandler = (FilteredItemStackHandler) itemHandler;

		filteredHandler.setTotalAmount(0, boneMealCount);
		updateContainedItemNBT(harvestRod, player, (short) 0, ItemStack.EMPTY, boneMealCount, updateNBT);
	}

	private boolean incrementPlantable(ItemStack harvestRod, ItemStack plantable, EntityPlayer player) {
		IItemHandler itemHandler = harvestRod.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);

		if(!(itemHandler instanceof FilteredItemStackHandler))
			return false;

		FilteredItemStackHandler filteredHandler = (FilteredItemStackHandler) itemHandler;

		ItemStack plantableCopy = plantable.copy();
		plantableCopy.setCount(1);

		for(int slot = 2; slot < filteredHandler.getSlots(); slot++) {
			ItemStack remainingStack = filteredHandler.insertItem(slot, plantableCopy, false);
			if(remainingStack.isEmpty()) {
				int parentSlot = filteredHandler.getParentSlot(slot);
				updateContainedItemNBT(harvestRod, player, (short) parentSlot, plantableCopy, getPlantableQuantity(harvestRod, parentSlot));
				return true;
			}
		}
		return false;
	}

	private void updateContainedItemNBT(ItemStack harvestRod, EntityPlayer player, short slot, ItemStack stack, int count, boolean serverSide) {
		if (!serverSide) {
			EnumHand hand = player.getHeldItemMainhand() == harvestRod ? EnumHand.MAIN_HAND : EnumHand.OFF_HAND;
			PacketHandler.networkWrapper.sendTo(new PacketCountSync(hand, slot, stack, count), (EntityPlayerMP) player);
		} else {
			NBTHelper.updateContainedStack(harvestRod, slot, stack, count);
		}
	}

	private void updateContainedItemNBT(ItemStack harvestRod, EntityPlayer player, short parentSlot, ItemStack stack, int count) {
		if (player != null && player.isHandActive() && (player.getHeldItemMainhand() == harvestRod || player.getHeldItemOffhand() == harvestRod)) {
			EnumHand hand = player.getHeldItemMainhand() == harvestRod ? EnumHand.MAIN_HAND : EnumHand.OFF_HAND;
			PacketHandler.networkWrapper.sendTo(new PacketCountSync(hand, parentSlot, stack, count), (EntityPlayerMP) player);
		} else {
			NBTHelper.updateContainedStack(harvestRod, parentSlot, stack, count);
		}
	}

	private void decrementPlantable(ItemStack harvestRod, int parentSlot, EntityPlayer player, boolean updateNBT) {
		IItemHandler itemHandler = harvestRod.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);

		if(!(itemHandler instanceof FilteredItemStackHandler))
			return;

		FilteredItemStackHandler filteredHandler = (FilteredItemStackHandler) itemHandler;

		filteredHandler.setTotalAmount(parentSlot, filteredHandler.getTotalAmount(parentSlot) - 1);

		updateContainedItemNBT(harvestRod, player, (short) parentSlot, filteredHandler.getStackInParentSlot(parentSlot), filteredHandler.getTotalAmount(parentSlot), updateNBT);
	}

	@Nonnull
	@Override
	public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, @Nonnull EnumHand hand) {
		ItemStack stack = player.getHeldItem(hand);
		if(player.isSneaking())
			return super.onItemRightClick(world, player, hand);

		player.setActiveHand(hand);

		return new ActionResult<>(EnumActionResult.SUCCESS, stack);
	}

	@Override
	public int getMaxItemUseDuration(ItemStack stack) {
		return 300;
	}

	@Override
	public void onPlayerStoppedUsing(@Nonnull ItemStack harvestRod, World world, EntityLivingBase entity, int timeLeft) {
		if(entity.world.isRemote || !(entity instanceof EntityPlayer))
			return;

		EntityPlayer player = (EntityPlayer) entity;

		RayTraceResult result = this.rayTrace(player.world, player, true);

		//noinspection ConstantConditions
		if(result != null) {
			//noinspection ConstantConditions
			IHarvestRodCache cache = harvestRod.getCapability(ModCapabilities.HARVEST_ROD_CACHE, null);
			if(cache != null) {
				cache.reset();
			}
			BlockPos pos = result.getBlockPos();

			if(BONE_MEAL_MODE.equals(getMode(harvestRod))) {
				if(getBoneMealCount(harvestRod) >= getBonemealCost() || player.capabilities.isCreativeMode) {
					boneMealBlock(harvestRod, player, world, pos, true);
				}
			} else if(PLANTABLE_MODE.equals(getMode(harvestRod))) {
				if(getPlantableQuantity(harvestRod, getCurrentPlantableSlot(harvestRod)) > 0 || player.capabilities.isCreativeMode) {
					plantItem(harvestRod, player, pos, player.getActiveHand(), true);
				}
			} else if(HOE_MODE.equals(getMode(harvestRod))) {
				hoeLand(world, pos);
			}

		} else {
			removeStackFromCurrent(harvestRod, player);
		}
	}

	private void hoeLand(World world, BlockPos pos) {
		ItemStack fakeHoe = new ItemStack(Items.WOODEN_HOE);
		EntityXRFakePlayer fakePlayer = XRFakePlayerFactory.get((WorldServer) world);
		fakePlayer.setHeldItem(EnumHand.MAIN_HAND, fakeHoe);

		if(Items.WOODEN_HOE.onItemUse(fakePlayer, world, pos, EnumHand.MAIN_HAND, EnumFacing.UP, 0, 0, 0) == EnumActionResult.SUCCESS)
			world.playSound(null, pos, SoundEvents.ITEM_HOE_TILL, SoundCategory.BLOCKS, 1.0F, 1.0F);
	}

	private void removeStackFromCurrent(ItemStack stack, EntityPlayer player) {
		if(getMode(stack).equals(BONE_MEAL_MODE)) {
			ItemStack boneMealStack = new ItemStack(Items.DYE, 1, Reference.WHITE_DYE_META);
			int numberToAdd = Math.min(boneMealStack.getMaxStackSize(), getBoneMealCount(stack));
			int numberAdded = InventoryHelper.tryToAddToInventory(boneMealStack, player.inventory, numberToAdd);
			setBoneMealCount(stack, getBoneMealCount(stack) - numberAdded, player, true);
		} else if(getMode(stack).equals(PLANTABLE_MODE)) {
			byte plantableSlot = getCurrentPlantableSlot(stack);
			ItemStack plantableStack = getCurrentPlantable(stack);
			int plantableQuantity = getPlantableQuantity(stack, plantableSlot);

			int numberToAdd = Math.min(plantableStack.getMaxStackSize(), plantableQuantity);
			int numberAdded = InventoryHelper.tryToAddToInventory(plantableStack, player.inventory, numberToAdd);

			int updatedPlantableQuantity = getPlantableQuantity(stack, plantableSlot) - numberAdded;

			setPlantableQuantity(stack, plantableSlot, updatedPlantableQuantity, player);
			if(updatedPlantableQuantity == 0)
				shiftModeOnEmptyPlantable(stack, plantableSlot);
		}
	}

	public void shiftModeOnEmptyPlantable(ItemStack harvestRod, byte plantableSlot) {
		if(plantableSlot > 0)
			setCurrentPlantableSlot(harvestRod, (byte) (plantableSlot - 1));
		cycleMode(harvestRod);
	}

	private void plantItem(ItemStack harvestRod, EntityPlayer player, BlockPos pos, EnumHand hand, boolean updateNBT) {
		byte plantableSlot = getCurrentPlantableSlot(harvestRod);
		ItemStack fakePlantableStack = getCurrentPlantable(harvestRod).copy();
		fakePlantableStack.setCount(1);

		EntityXRFakePlayer fakePlayer = XRFakePlayerFactory.get((WorldServer) player.world);
		fakePlayer.setHeldItem(hand, fakePlantableStack);

		if(fakePlantableStack.onItemUse(fakePlayer, player.world, pos, EnumHand.MAIN_HAND, EnumFacing.UP, 0, 0, 0) == EnumActionResult.SUCCESS) {
			player.world.playSound(null, player.getPosition(), SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.1F, 0.5F * ((player.world.rand.nextFloat() - player.world.rand.nextFloat()) * 0.7F + 1.2F));

			if(!player.capabilities.isCreativeMode) {
				int plantableQuantity = getPlantableQuantity(harvestRod, plantableSlot);
				decrementPlantable(harvestRod, plantableSlot, player, updateNBT);
				if(plantableQuantity <= 1)
					shiftModeOnEmptyPlantable(harvestRod, plantableSlot);
			}
		}
	}

	@Nonnull
	private ItemStack getCurrentPlantable(@Nonnull ItemStack harvestRod) {
		return getCurrentPlantable(harvestRod, false);
	}

	public ItemStack getCurrentPlantable(@Nonnull ItemStack harvestRod, boolean isClient) {
		int currentSlot = getCurrentPlantableSlot(harvestRod);
		if (isClient) {
			return NBTHelper.getContainedStack(harvestRod, currentSlot);
		}

		return getPlantableInSlot(harvestRod, currentSlot);
	}

	@Nonnull
	public ItemStack getPlantableInSlot(@Nonnull ItemStack harvestRod, int slot) {
		return getPlantableInSlot(harvestRod, slot, false);
	}

	public ItemStack getPlantableInSlot(@Nonnull ItemStack harvestRod, int slot, boolean isClient) {
		if (isClient) {
			return NBTHelper.getContainedStack(harvestRod, slot);
		}

		IItemHandler itemHandler = harvestRod.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);

		if(!(itemHandler instanceof FilteredItemStackHandler))
			return ItemStack.EMPTY;

		FilteredItemStackHandler filteredHandler = (FilteredItemStackHandler) itemHandler;

		return filteredHandler.getStackInParentSlot(slot);
	}

	@Override
	public void onUsingTick(ItemStack harvestRod, EntityLivingBase entity, int count) {
		if(entity.world.isRemote || !(entity instanceof EntityPlayer))
			return;

		EntityPlayer player = (EntityPlayer) entity;

		if(isCoolDownOver(harvestRod, count)) {
			RayTraceResult result = this.rayTrace(player.world, player, true);
			//noinspection ConstantConditions
			if(result != null) {
				World world = player.world;
				//noinspection ConstantConditions
				IHarvestRodCache cache = harvestRod.getCapability(ModCapabilities.HARVEST_ROD_CACHE, null);

				if(cache != null) {
					switch(getMode(harvestRod)) {
						case BONE_MEAL_MODE:
							if(getBoneMealCount(harvestRod) >= getBonemealCost() || player.capabilities.isCreativeMode) {
								BlockPos blockToBoneMeal = getNextBlockToBoneMeal(world, cache, result.getBlockPos(), Settings.Items.HarvestRod.AOERadius);

								if(blockToBoneMeal != null) {
									boneMealBlock(harvestRod, player, world, blockToBoneMeal, false);
									return;
								}
							}
							break;
						case PLANTABLE_MODE:
							if(getPlantableQuantity(harvestRod, getCurrentPlantableSlot(harvestRod)) >= 1 || player.capabilities.isCreativeMode) {
								BlockPos blockToPlantOn = getNextBlockToPlantOn(world, cache, result.getBlockPos(), Settings.Items.HarvestRod.AOERadius, (IPlantable) getCurrentPlantable(harvestRod).getItem());

								if(blockToPlantOn != null) {
									plantItem(harvestRod, player, blockToPlantOn, player.getActiveHand(), false);
									return;
								}
							}
							break;
						case HOE_MODE:
							BlockPos blockToHoe = getNextBlockToHoe(world, cache, result.getBlockPos(), Settings.Items.HarvestRod.AOERadius);
							if(blockToHoe != null) {
								hoeLand(world, blockToHoe);
								return;
							}
						default:
							break;
					}
				}
			}
		}
	}

	private BlockPos getNextBlockToHoe(World world, IHarvestRodCache cache, BlockPos pos, int range) {
		if(cache.isQueueEmpty() || !pos.equals(cache.getStartBlockPos()))
			fillQueueToHoe(world, cache, pos, range);

		return cache.getNextBlockInQueue();
	}

	private void fillQueueToHoe(World world, IHarvestRodCache cache, BlockPos pos, int range) {
		cache.setStartBlockPos(pos);
		cache.clearBlockQueue();
		for(int x = pos.getX() - range; x <= pos.getX() + range; x++) {
			for(int y = pos.getY() - range; y <= pos.getY() + range; y++) {
				for(int z = pos.getZ() - range; z <= pos.getZ() + range; z++) {
					BlockPos currentPos = new BlockPos(x, y, z);
					IBlockState blockState = world.getBlockState(currentPos);
					Block block = blockState.getBlock();

					if(world.isAirBlock(currentPos.up())) {
						if(block == Blocks.GRASS || (block == Blocks.DIRT && (blockState.getValue(BlockDirt.VARIANT) == BlockDirt.DirtType.DIRT || blockState.getValue(BlockDirt.VARIANT) == BlockDirt.DirtType.COARSE_DIRT))) {
							cache.addBlockToQueue(currentPos);
						}
					}
				}
			}
		}

	}

	private BlockPos getNextBlockToPlantOn(World world, IHarvestRodCache cache, BlockPos pos, int range, IPlantable plantable) {
		if(cache.isQueueEmpty() || !pos.equals(cache.getStartBlockPos()))
			fillQueueToPlant(world, cache, pos, range, plantable);

		return cache.getNextBlockInQueue();
	}

	private void fillQueueToPlant(World world, IHarvestRodCache cache, BlockPos pos, int range, IPlantable plantable) {
		cache.setStartBlockPos(pos);
		cache.clearBlockQueue();

		boolean checkerboard = false;
		boolean bothOddOrEven = false;

		if(plantable == Items.PUMPKIN_SEEDS || plantable == Items.MELON_SEEDS) {
			checkerboard = true;
			boolean xEven = pos.getX() % 2 == 0;
			boolean zEven = pos.getZ() % 2 == 0;
			bothOddOrEven = xEven == zEven;
		}

		for(int x = pos.getX() - range; x <= pos.getX() + range; x++) {
			for(int y = pos.getY() - range; y <= pos.getY() + range; y++) {
				for(int z = pos.getZ() - range; z <= pos.getZ() + range; z++) {
					BlockPos currentPos = new BlockPos(x, y, z);
					IBlockState blockState = world.getBlockState(currentPos);
					if((!checkerboard || (bothOddOrEven == ((currentPos.getX() % 2 == 0) == (currentPos.getZ() % 2 == 0)))) && blockState.getBlock().canSustainPlant(blockState, world, pos, EnumFacing.UP, plantable) && world.isAirBlock(currentPos.up())) {
						cache.addBlockToQueue(currentPos);
					}
				}
			}
		}
	}

	@Override
	public boolean onEntitySwing(EntityLivingBase entityLiving, ItemStack stack) {
		if(entityLiving.world.isRemote)
			return false;
		if(entityLiving.isSneaking()) {
			cycleMode(stack);
			return true;
		}
		return false;
	}

	private boolean isCoolDownOver(ItemStack stack, int count) {
		return getMaxItemUseDuration(stack) - count >= AOE_START_COOLDOWN && (getMaxItemUseDuration(stack) - count) % Settings.Items.HarvestRod.AOECooldown == 0;
	}

	private BlockPos getNextBlockToBoneMeal(World world, IHarvestRodCache cache, BlockPos pos, int range) {
		if(cache.isQueueEmpty() || !pos.equals(cache.getStartBlockPos()))
			fillQueueToBoneMeal(world, cache, pos, range);

		return cache.getNextBlockInQueue();
	}

	private void fillQueueToBoneMeal(World world, IHarvestRodCache cache, BlockPos pos, int range) {
		cache.setStartBlockPos(pos);
		cache.clearBlockQueue();
		for(int x = pos.getX() - range; x <= pos.getX() + range; x++) {
			for(int y = pos.getY() - range; y <= pos.getY() + range; y++) {
				for(int z = pos.getZ() - range; z <= pos.getZ() + range; z++) {
					BlockPos currentPos = new BlockPos(x, y, z);
					IBlockState blockState = world.getBlockState(currentPos);
					if(blockState.getBlock() instanceof IGrowable && ((IGrowable) blockState.getBlock()).canGrow(world, currentPos, blockState, world.isRemote)) {
						cache.addBlockToQueue(currentPos);
					}
				}
			}
		}
	}

	private void cycleMode(ItemStack harvestRod) {
		String currentMode = getMode(harvestRod);
		int plantableCount = getCountPlantable(harvestRod);
		switch(currentMode) {
			case BONE_MEAL_MODE:
				if(plantableCount > 0) {
					setMode(harvestRod, PLANTABLE_MODE);
					setCurrentPlantableSlot(harvestRod, (byte) 1);
				} else {
					setMode(harvestRod, HOE_MODE);
				}
				break;
			case PLANTABLE_MODE:
				if(plantableCount > getCurrentPlantableSlot(harvestRod)) {
					setCurrentPlantableSlot(harvestRod, (byte) (getCurrentPlantableSlot(harvestRod) + 1));
				} else {
					setMode(harvestRod, HOE_MODE);
				}
				break;
			case HOE_MODE:
				setMode(harvestRod, BONE_MEAL_MODE);
				break;
			default:
				break;
		}
	}

	public int getCountPlantable(@Nonnull ItemStack harvestRod) {
		return getCountPlantable(harvestRod, false);
	}

	public int getCountPlantable(@Nonnull ItemStack harvestRod, boolean isClient) {
		if (isClient) {
			return NBTHelper.getCountContainedStacks(harvestRod); // because bonemeal needs to be excluded
		}

		IItemHandler itemHandler = harvestRod.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);

		if(!(itemHandler instanceof FilteredItemStackHandler))
			return 0;

		FilteredItemStackHandler filteredHandler = (FilteredItemStackHandler) itemHandler;

		return ((filteredHandler.getSlots() / FilteredItemStackHandler.SLOTS_PER_TYPE) - 2); //one stack is bonemeal and the other null stack for inserts
	}

	public byte getCurrentPlantableSlot(ItemStack stack) {
		//noinspection ConstantConditions
		return stack.hasTagCompound() ? stack.getTagCompound().getByte(PLANTABLE_INDEX_NBT_TAG) : -1;
	}

	private void setCurrentPlantableSlot(ItemStack stack, byte index) {
		if(stack.hasTagCompound()) {
			//noinspection ConstantConditions
			stack.getTagCompound().setByte(PLANTABLE_INDEX_NBT_TAG, index);
		}
	}

	private void setMode(ItemStack stack, String mode) {
		NBTHelper.setString(MODE_NBT_TAG, stack, mode);
	}

	public String getMode(ItemStack stack) {
		String mode = NBTHelper.getString(MODE_NBT_TAG, stack);

		return mode.equals("") ? BONE_MEAL_MODE : mode;
	}

	public int getPlantableQuantity(ItemStack harvestRod, int parentSlot) {
		return getPlantableQuantity(harvestRod, parentSlot, false);
	}

	public int getPlantableQuantity(ItemStack harvestRod, int parentSlot, boolean isClient) {
		if (isClient) {
			return NBTHelper.getContainedStackCount(harvestRod, parentSlot);
		}

		IItemHandler itemHandler = harvestRod.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);

		if(!(itemHandler instanceof FilteredItemStackHandler))
			return 0;

		FilteredItemStackHandler filteredHandler = (FilteredItemStackHandler) itemHandler;

		return filteredHandler.getTotalAmount(parentSlot);
	}

	public void setPlantableQuantity(ItemStack harvestRod, byte plantableSlot, int quantity) {
		setPlantableQuantity(harvestRod, plantableSlot, quantity, null);
	}

	private void setPlantableQuantity(ItemStack harvestRod, byte plantableSlot, int quantity, EntityPlayer player) {
		IItemHandler itemHandler = harvestRod.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);

		if(!(itemHandler instanceof FilteredItemStackHandler))
			return;

		FilteredItemStackHandler filteredHandler = (FilteredItemStackHandler) itemHandler;

		filteredHandler.setTotalAmount(plantableSlot, quantity);

		updateContainedItemNBT(harvestRod, player, (short) plantableSlot, filteredHandler.getStackInParentSlot(plantableSlot), filteredHandler.getTotalAmount(plantableSlot), true);
	}
}
