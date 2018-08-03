package io.dico.parcels2.defaultimpl

import io.dico.parcels2.*
import io.dico.parcels2.blockvisitor.RegionTraversal
import io.dico.parcels2.blockvisitor.Worker
import io.dico.parcels2.blockvisitor.WorktimeLimiter
import io.dico.parcels2.options.DefaultGeneratorOptions
import io.dico.parcels2.util.*
import org.bukkit.*
import org.bukkit.block.Biome
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Skull
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.Sign
import org.bukkit.block.data.type.Slab
import java.util.Random

private val airType = Bukkit.createBlockData(Material.AIR)

class DefaultParcelGenerator(val name: String, private val o: DefaultGeneratorOptions) : ParcelGenerator() {
    private var _world: World? = null
    override val world: World
        get() {
            if (_world == null) _world = Bukkit.getWorld(name)!!.also {
                maxHeight = it.maxHeight
                return it
            }
            return _world!!
        }

    private var maxHeight = 0
    val sectionSize = o.parcelSize + o.pathSize
    val pathOffset = (if (o.pathSize % 2 == 0) o.pathSize + 2 else o.pathSize + 1) / 2
    val makePathMain = o.pathSize > 2
    val makePathAlt = o.pathSize > 4

    private inline fun <T> generate(chunkX: Int,
                                    chunkZ: Int,
                                    floor: T, wall:
                                    T, pathMain: T,
                                    pathAlt: T,
                                    fill: T,
                                    setter: (Int, Int, Int, T) -> Unit) {

        val floorHeight = o.floorHeight
        val parcelSize = o.parcelSize
        val sectionSize = sectionSize
        val pathOffset = pathOffset
        val makePathMain = makePathMain
        val makePathAlt = makePathAlt

        // parcel bottom x and z
        // umod is unsigned %: the result is always >= 0
        val pbx = ((chunkX shl 4) - o.offsetX) umod sectionSize
        val pbz = ((chunkZ shl 4) - o.offsetZ) umod sectionSize

        var curHeight: Int
        var x: Int
        var z: Int
        for (cx in 0..15) {
            for (cz in 0..15) {
                x = (pbx + cx) % sectionSize - pathOffset
                z = (pbz + cz) % sectionSize - pathOffset
                curHeight = floorHeight

                val type = when {
                    (x in 0 until parcelSize && z in 0 until parcelSize) -> floor
                    (x in -1..parcelSize && z in -1..parcelSize) -> {
                        curHeight++
                        wall
                    }
                    (makePathAlt && x in -2 until parcelSize + 2 && z in -2 until parcelSize + 2) -> pathAlt
                    (makePathMain) -> pathMain
                    else -> {
                        curHeight++
                        wall
                    }
                }

                for (y in 0 until curHeight) {
                    setter(cx, y, cz, fill)
                }
                setter(cx, curHeight, cz, type)
            }
        }
    }

    override fun generateChunkData(world: World?, random: Random?, chunkX: Int, chunkZ: Int, biome: BiomeGrid?): ChunkData {
        val out = Bukkit.createChunkData(world)
        generate(chunkX, chunkZ, o.floorType, o.wallType, o.pathMainType, o.pathAltType, o.fillType) { x, y, z, type ->
            out.setBlock(x, y, z, type)
        }
        return out
    }

    override fun populate(world: World?, random: Random?, chunk: Chunk?) {
        // do nothing
    }

    override fun getFixedSpawnLocation(world: World?, random: Random?): Location {
        val fix = if (o.parcelSize.even) 0.5 else 0.0
        return Location(world, o.offsetX + fix, o.floorHeight + 1.0, o.offsetZ + fix)
    }

    override fun makeParcelBlockManager(worktimeLimiter: WorktimeLimiter): ParcelBlockManager {
        return ParcelBlockManagerImpl(worktimeLimiter)
    }

    override fun makeParcelLocator(container: ParcelContainer): ParcelLocator {
        return ParcelLocatorImpl(container)
    }

    private inline fun <T> convertBlockLocationToId(x: Int, z: Int, mapper: (Int, Int) -> T): T? {
        val sectionSize = sectionSize
        val parcelSize = o.parcelSize
        val absX = x - o.offsetX - pathOffset
        val absZ = z - o.offsetZ - pathOffset
        val modX = absX umod sectionSize
        val modZ = absZ umod sectionSize
        if (modX in 0 until parcelSize && modZ in 0 until parcelSize) {
            return mapper((absX - modX) / sectionSize, (absZ - modZ) / sectionSize)
        }
        return null
    }

    private inner class ParcelLocatorImpl(val container: ParcelContainer) : ParcelLocator {
        override val world: World = this@DefaultParcelGenerator.world
        override fun getParcelAt(x: Int, z: Int): Parcel? {
            return convertBlockLocationToId(x, z, container::getParcelById)
        }

        override fun getParcelIdAt(x: Int, z: Int): ParcelId? {
            return convertBlockLocationToId(x, z) { idx, idz -> ParcelId(world.name, world.uid, idx, idz) }
        }
    }

    @Suppress("DEPRECATION")
    private inner class ParcelBlockManagerImpl(override val worktimeLimiter: WorktimeLimiter) : ParcelBlockManager {
        override val world: World = this@DefaultParcelGenerator.world

        override fun getBottomBlock(parcel: ParcelId): Vec2i = Vec2i(
            sectionSize * parcel.pos.x + pathOffset + o.offsetX,
            sectionSize * parcel.pos.z + pathOffset + o.offsetZ
        )

        override fun getHomeLocation(parcel: ParcelId): Location {
            val bottom = getBottomBlock(parcel)
            return Location(world, bottom.x.toDouble(), o.floorHeight + 1.0, bottom.z + (o.parcelSize - 1) / 2.0, -90F, 0F)
        }

        override fun setOwnerBlock(parcel: ParcelId, owner: ParcelOwner?) {
            val b = getBottomBlock(parcel)

            val wallBlock = world.getBlockAt(b.x - 1, o.floorHeight + 1, b.z - 1)
            val signBlock = world.getBlockAt(b.x - 2, o.floorHeight + 1, b.z - 1)
            val skullBlock = world.getBlockAt(b.x - 1, o.floorHeight + 2, b.z - 1)

            if (owner == null) {
                wallBlock.blockData = o.wallType
                signBlock.type = Material.AIR
                skullBlock.type = Material.AIR
            } else {

                val wallBlockType: BlockData = if (o.wallType is Slab)
                    (o.wallType.clone() as Slab).apply { type = Slab.Type.DOUBLE }
                else
                    o.wallType

                wallBlock.blockData = wallBlockType

                signBlock.blockData = (Bukkit.createBlockData(Material.WALL_SIGN) as Sign).apply { rotation = BlockFace.NORTH }

                val sign = signBlock.state as org.bukkit.block.Sign
                sign.setLine(0, "${parcel.x},${parcel.z}")
                sign.setLine(2, owner.name)
                sign.update()

                skullBlock.type = Material.PLAYER_HEAD
                val skull = skullBlock.state as Skull
                if (owner.uuid != null) {
                    skull.owningPlayer = owner.offlinePlayer
                } else {
                    skull.owner = owner.name
                }
                skull.rotation = BlockFace.WEST
                skull.update()
            }
        }

        override fun setBiome(parcel: ParcelId, biome: Biome): Worker = worktimeLimiter.submit {
            val world = world
            val b = getBottomBlock(parcel)
            val parcelSize = o.parcelSize
            for (x in b.x until b.x + parcelSize) {
                for (z in b.z until b.z + parcelSize) {
                    markSuspensionPoint()
                    world.setBiome(x, z, biome)
                }
            }
        }

        override fun clearParcel(parcel: ParcelId): Worker = worktimeLimiter.submit {
            val bottom = getBottomBlock(parcel)
            val region = Region(Vec3i(bottom.x, 0, bottom.z), Vec3i(o.parcelSize, maxHeight + 1, o.parcelSize))
            val blocks = RegionTraversal.DOWNWARD.regionTraverser(region)
            val blockCount = region.blockCount.toDouble()

            val world = world
            val floorHeight = o.floorHeight
            val airType = airType
            val floorType = o.floorType
            val fillType = o.fillType

            for ((index, vec) in blocks.withIndex()) {
                markSuspensionPoint()
                val y = vec.y
                val blockType = when {
                    y > floorHeight -> airType
                    y == floorHeight -> floorType
                    else -> fillType
                }
                world[vec].blockData = blockType
                setProgress((index + 1) / blockCount)
            }
        }

        override fun doBlockOperation(parcel: ParcelId, direction: RegionTraversal, operation: (Block) -> Unit): Worker = worktimeLimiter.submit {
            val bottom = getBottomBlock(parcel)
            val region = Region(Vec3i(bottom.x, 0, bottom.z), Vec3i(o.parcelSize, maxHeight + 1, o.parcelSize))
            val blocks = direction.regionTraverser(region)
            val blockCount = region.blockCount.toDouble()
            val world = world

            for ((index, vec) in blocks.withIndex()) {
                markSuspensionPoint()
                operation(world[vec])
                setProgress((index + 1) / blockCount)
            }
        }

    }

}