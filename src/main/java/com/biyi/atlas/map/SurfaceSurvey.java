package com.biyi.atlas.map;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

/** 服务端准备区域和作者客户端调查共用同一采样规则。 */
public final class SurfaceSurvey {
    private SurfaceSurvey(){}
    public static boolean sample(Level level, TerrainSurvey data, int cx, int cz) {
        if(level.getChunk(cx,cz).isEmpty())return false;
        boolean complete = true;
        for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) {
            int wx = cx * 16 + x * 4, wz = cz * 16 + z * 4;
            // WORLD_SURFACE 随客户端区块同步；NO_LEAVES 在客户端可能未填充，曾把全图写成 minY-1。
            BlockPos surface = findSurface(level, wx, wz);
            if (surface == null) { complete = false; continue; }
            var biome = level.getBiome(surface);
            var state = level.getBlockState(surface);
            String id = biome.unwrapKey().map(k -> k.location().getPath()).orElse("");
            int kind = TerrainSurvey.LAND;
            if (state.getFluidState().is(FluidTags.LAVA)) kind = TerrainSurvey.LAVA;
            else if (state.getFluidState().is(FluidTags.WATER)) kind = TerrainSurvey.WATER;
            else if (state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.POWDER_SNOW)
                    || biome.value().coldEnoughToSnow(surface) || id.contains("snow") || id.contains("frozen")) kind = TerrainSurvey.SNOW;
            else if (biome.is(BiomeTags.IS_FOREST) || biome.is(BiomeTags.IS_TAIGA) || biome.is(BiomeTags.IS_JUNGLE)) kind = TerrainSurvey.FOREST;
            else if (id.contains("desert") || id.contains("beach") || id.contains("badlands")) kind = TerrainSurvey.SAND;
            if(isBuilding(state))kind=TerrainSurvey.BUILDING;
            data.put(cx * 4 + x, cz * 4 + z, surface.getY(), kind);
            int mask=0;
            boolean maskComplete=true;
            for(int dz=0;dz<4;dz++)for(int dx=0;dx<4;dx++){
                BlockPos p=(dx==0&&dz==0)?surface:findSurface(level,wx+dx,wz+dz);
                if(p==null){maskComplete=false;continue;}
                if(isBuilding(level.getBlockState(p)))mask|=1<<(dz*4+dx);
            }
            if(maskComplete)data.putStructure(cx*4+x,cz*4+z,mask);
        }
        return complete;
    }

    private static BlockPos findSurface(Level level, int x, int z) {
        int min = level.getMinBuildHeight(), max = level.getMaxBuildHeight();
        int y = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, new BlockPos(x, min, z)).getY() - 1;
        // 高度图未就绪时有限度地回退到实际方块列，不缓存虚假的虚空高度。
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int found = com.biyi.atlas.map.TerrainSurface.find(y, min, max, candidate -> {
            pos.set(x, candidate, z);
            var state = level.getBlockState(pos);
            return !state.isAir() && !state.is(BlockTags.LEAVES)
                    && (!state.is(BlockTags.LOGS)||isBuilding(state));
        });
        return found == Integer.MIN_VALUE ? null : new BlockPos(x, found, z);
    }
    private static boolean isBuilding(net.minecraft.world.level.block.state.BlockState state){
        if(!state.getFluidState().isEmpty())return false;
        String id=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return state.is(BlockTags.PLANKS)||com.biyi.atlas.map.BuildingMaterials.matches(id);
    }
}
