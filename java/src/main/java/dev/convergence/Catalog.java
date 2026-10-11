package dev.convergence;

import net.minecraft.world.item.Items;

final class Catalog {
   static void register() {
      Convergence.register("convergence:helmet", Items.NETHERITE_HELMET, 1, 0.0F, 32767, 6);
      Convergence.register("convergence:chestplate", Items.NETHERITE_CHESTPLATE, 1, 0.0F, 32767, 10);
      Convergence.register("convergence:leggings", Items.NETHERITE_LEGGINGS, 1, 0.0F, 32767, 8);
      Convergence.register("convergence:boots", Items.NETHERITE_BOOTS, 1, 0.0F, 32767, 6);
      Convergence.register("convergence:sword", Items.NETHERITE_SWORD, 1, 1000.0F, 32767, 0);
      Convergence.register("convergence:mace", Items.MACE, 1, 80.0F, 32767, 0);
      Convergence.register("convergence:spear", Items.NETHERITE_SPEAR, 1, 60.0F, 32767, 0);
      Convergence.register("convergence:pickaxe", Items.NETHERITE_PICKAXE, 1, 16.0F, 32767, 0);
      Convergence.register("convergence:axe", Items.NETHERITE_AXE, 1, 20.0F, 32767, 0);
      Convergence.register("convergence:shovel", Items.NETHERITE_SHOVEL, 1, 12.0F, 32767, 0);
      Convergence.register("convergence:hoe", Items.NETHERITE_HOE, 1, 8.0F, 32767, 0);
      Convergence.register("convergence:ingot", Items.NETHERITE_INGOT, 64, 0.0F, 0, 0);
   }
}
