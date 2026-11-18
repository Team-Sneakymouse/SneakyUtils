package com.sneakyutils.triggers

import com.destroystokyo.paper.event.block.BlockDestroyEvent
import com.destroystokyo.paper.event.block.TNTPrimeEvent as OldPrime
import com.sneakyutils.SneakyUtils
import com.sneakyutils.triggers.service.TriggerService
import org.bukkit.ExplosionResult
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.EntityType
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFadeEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockFormEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.block.FluidLevelChangeEvent
import org.bukkit.event.block.LeavesDecayEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.block.data.Directional
import org.bukkit.block.data.Rail
import org.bukkit.block.data.type.Bed
import org.bukkit.entity.Arrow
import org.bukkit.entity.EnderDragon
import org.bukkit.entity.Enderman
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.Wither
import org.bukkit.event.block.TNTPrimeEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.plugin.Plugin
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.world.WorldMock

/**
 * [Triggers.onBlockRemoved]: every cause from its event, once per watched block per event; cancelled events never; the
 * per-event filters (explosions that only trigger blocks, entity and fluid changes that remove nothing); closing,
 * subscribing and failing inside handlers; owner disable and world unload; and the lookup cost of unwatched blocks.
 * The events are built and fired here as Paper would fire them; MockBukkit doesn't produce them itself.
 */
class BlockRemovedTest {
    private lateinit var server: ServerMock
    private lateinit var owner: Plugin
    private lateinit var world: WorldMock
    private lateinit var triggers: Triggers
    private lateinit var player: PlayerMock
    private val log = ArrayList<String>()

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        world = server.addSimpleWorld("world")
        MockBukkit.load(SneakyUtils::class.java)
        owner = MockBukkit.createMockPlugin("Owner")
        triggers = SneakyUtils.getTriggers()
        player = server.addPlayer("p")
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    private fun block(x: Int, type: Material = Material.STONE, y: Int = 64, z: Int = 0): Block =
        world.getBlockAt(x, y, z).also { it.type = type }

    private fun recorder(name: String) = BlockRemovedHandler { b, cause, e ->
        log += "$name ${b.x},${b.y},${b.z} $cause ${e.javaClass.simpleName}"
    }

    private fun watch(b: Block, name: String = "w"): Subscription = triggers.onBlockRemoved(owner, BlockKey.of(b), recorder(name))

    private fun fire(e: Event): Event = e.also { server.pluginManager.callEvent(it) }

    private fun counter(name: String) = triggers.stats().counter(name)

    @Suppress("DEPRECATION", "removal")
    private fun oldPrime(b: Block, reason: OldPrime.PrimeReason = OldPrime.PrimeReason.EXPLOSION): Event = OldPrime(b, reason, null)

    // One event of every cause for block [b] (and for pistons and explosions, with an unwatched block beside it).
    private fun everyCause(b: Block): List<Event> {
        val other = world.getBlockAt(b.x + 1, b.y, b.z)
        val zombie = world.spawnEntity(Location(world, 0.0, 64.0, 0.0), EntityType.ZOMBIE)
        return listOf(
            BlockBreakEvent(b, player),
            BlockExplodeEvent(other, other.state, mutableListOf(other, b), 1f, ExplosionResult.DESTROY),
            EntityExplodeEvent(zombie, b.location, mutableListOf(b, other), 1f, ExplosionResult.DESTROY_WITH_DECAY),
            BlockBurnEvent(b, null),
            BlockFadeEvent(b, world.getBlockAt(0, 0, 0).state),
            LeavesDecayEvent(b),
            BlockPistonExtendEvent(world.getBlockAt(b.x - 1, b.y, b.z), listOf(b, other), BlockFace.EAST),
            BlockPistonRetractEvent(world.getBlockAt(b.x - 2, b.y, b.z), listOf(other, b), BlockFace.WEST),
            BlockDestroyEvent(b, server.createBlockData(Material.AIR), b.blockData, 0, true),
            EntityChangeBlockEvent(mock(Enderman::class.java), b, server.createBlockData(Material.AIR)),
            BlockFromToEvent(world.getBlockAt(b.x, b.y + 1, b.z), BlockFace.DOWN),
        )
    }

    @Test
    fun everyCauseIsDeliveredOnceWithItsEvent() {
        val torch = block(3, Material.TORCH)
        watch(torch)
        // One tick apart: a subscription gets one delivery of a cause per tick (see the per-tick test).
        for (e in everyCause(torch)) { fire(e); server.scheduler.performOneTick() }
        assertEquals(
            listOf(
                "w 3,64,0 BREAK BlockBreakEvent",
                "w 3,64,0 EXPLODE BlockExplodeEvent",
                "w 3,64,0 EXPLODE EntityExplodeEvent",
                "w 3,64,0 BURN BlockBurnEvent",
                "w 3,64,0 FADE BlockFadeEvent",
                "w 3,64,0 DECAY LeavesDecayEvent",
                "w 3,64,0 PISTON BlockPistonExtendEvent",
                "w 3,64,0 PISTON BlockPistonRetractEvent",
                "w 3,64,0 DESTROY BlockDestroyEvent",
                "w 3,64,0 ENTITY EntityChangeBlockEvent",
                "w 3,64,0 FLUID BlockFromToEvent",
            ),
            log,
        )
        assertEquals(11L, counter("events.block-removed"))
        assertEquals(1, triggers.stats().count("block-removed"))
    }

    @Test
    fun cancelledEventsAreNeverDelivered() {
        server.pluginManager.registerEvents(object : Listener {
            // One generic handler per event class would need reflection; cancelling at LOWEST from each is enough.
            @EventHandler(priority = EventPriority.LOWEST) fun a(e: BlockBreakEvent) = cancel(e)
            @EventHandler(priority = EventPriority.LOWEST) fun b(e: BlockExplodeEvent) = cancel(e)
            @EventHandler(priority = EventPriority.LOWEST) fun c(e: EntityExplodeEvent) = cancel(e)
            @EventHandler(priority = EventPriority.LOWEST) fun d(e: BlockBurnEvent) = cancel(e)
            @EventHandler(priority = EventPriority.LOWEST) fun f(e: BlockFadeEvent) = cancel(e)
            @EventHandler(priority = EventPriority.LOWEST) fun g(e: LeavesDecayEvent) = cancel(e)
            @EventHandler(priority = EventPriority.LOWEST) fun h(e: BlockPistonExtendEvent) = cancel(e)
            @EventHandler(priority = EventPriority.LOWEST) fun i(e: BlockPistonRetractEvent) = cancel(e)
            @EventHandler(priority = EventPriority.LOWEST) fun j(e: BlockDestroyEvent) = cancel(e)
            @EventHandler(priority = EventPriority.LOWEST) fun k(e: EntityChangeBlockEvent) = cancel(e)
            @EventHandler(priority = EventPriority.LOWEST) fun l(e: BlockFromToEvent) = cancel(e)
            @EventHandler(priority = EventPriority.LOWEST) fun m(e: TNTPrimeEvent) = cancel(e)
            fun cancel(e: Cancellable) { e.isCancelled = true }
        }, owner)
        val torch = block(3, Material.TORCH)
        watch(torch)
        val tnt = block(10, Material.TNT)
        watch(tnt)
        val egg = block(12, Material.DRAGON_EGG)
        watch(egg)
        val events = everyCause(torch) + listOf(
            TNTPrimeEvent(tnt, TNTPrimeEvent.PrimeCause.REDSTONE, null, null),
            oldPrime(tnt).also { (it as Cancellable).isCancelled = true }, // as a listener before ours would
            BlockFromToEvent(egg, world.getBlockAt(12, 70, 0)),
        )
        for (e in events) fire(e)
        assertTrue(events.all { (it as Cancellable).isCancelled })
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun anExplosionDeliversEveryWatchedBlockOnceAndSkipsTriggerOnlyExplosions() {
        val blocks = (0 until 200).map { block(it, z = 5) }
        val watched = blocks.filter { it.x % 4 == 0 }
        for (b in watched) watch(b)
        val second = watch(blocks[0], "second")
        fire(BlockExplodeEvent(blocks[0], blocks[0].state, blocks.toMutableList(), 1f, ExplosionResult.DESTROY))
        // In the list's order; for one block, in the order subscribed.
        val expected = watched.map { "w ${it.x},64,5 EXPLODE BlockExplodeEvent" }.toMutableList()
        expected.add(1, "second 0,64,5 EXPLODE BlockExplodeEvent")
        assertEquals(expected, log)
        second.close()

        log.clear()
        val zombie = world.spawnEntity(Location(world, 0.0, 64.0, 0.0), EntityType.ZOMBIE)
        // A wind charge triggers blocks (buttons, doors) without removing them; KEEP removes nothing.
        fire(EntityExplodeEvent(zombie, blocks[0].location, blocks.toMutableList(), 1f, ExplosionResult.TRIGGER_BLOCK))
        fire(BlockExplodeEvent(blocks[0], blocks[0].state, blocks.toMutableList(), 1f, ExplosionResult.KEEP))
        assertEquals(emptyList<String>(), log)
        server.scheduler.performOneTick()
        fire(EntityExplodeEvent(zombie, blocks[0].location, blocks.toMutableList(), 1f, ExplosionResult.DESTROY))
        assertEquals(watched.size, log.size)
    }

    @Test
    fun pistonsReportTheOriginalPositionsOfMovedAndBrokenBlocks() {
        val pushed = block(1)
        val broken = block(2, Material.SHORT_GRASS)
        val destination = block(3, Material.AIR)
        watch(pushed, "pushed")
        watch(broken, "broken")
        watch(destination, "destination")
        // Paper's getBlocks() lists the moved blocks, then the ones the piston breaks.
        fire(BlockPistonExtendEvent(world.getBlockAt(0, 64, 0), listOf(pushed, broken), BlockFace.EAST))
        assertEquals(listOf("pushed 1,64,0 PISTON BlockPistonExtendEvent", "broken 2,64,0 PISTON BlockPistonExtendEvent"), log)
    }

    @Test
    fun entityChangesCountOnlyWhenTheBlockTurnsIntoAnotherType() {
        val zombie = mock(FallingBlock::class.java)
        val sand = block(1, Material.SAND)
        val air = block(2, Material.AIR)
        val grass = block(3, Material.GRASS_BLOCK)
        for (b in listOf(sand, air, grass)) watch(b)
        fire(EntityChangeBlockEvent(zombie, sand, server.createBlockData(Material.SAND)))   // same type: not removed
        fire(EntityChangeBlockEvent(zombie, air, server.createBlockData(Material.SAND)))    // a falling block landing
        assertEquals(emptyList<String>(), log)
        fire(EntityChangeBlockEvent(zombie, sand, server.createBlockData(Material.AIR)))    // starts to fall
        fire(EntityChangeBlockEvent(zombie, grass, server.createBlockData(Material.DIRT)))  // a sheep eating
        assertEquals(listOf("w 1,64,0 ENTITY EntityChangeBlockEvent", "w 3,64,0 ENTITY EntityChangeBlockEvent"), log)
    }

    @Test
    fun fluidOnlyWashesAwayBlocksThatAreNotAirFluidOrWaterloggable() {
        val water = block(0, Material.WATER, y = 70)
        val targets = listOf(Material.AIR, Material.WATER, Material.LAVA, Material.OAK_STAIRS, Material.REDSTONE_WIRE)
            .mapIndexed { i, m -> block(i + 1, m) }
        for (t in targets) watch(t)
        for (t in targets) fire(BlockFromToEvent(world.getBlockAt(t.x, 65, 0), BlockFace.DOWN))
        assertEquals(listOf("w 5,64,0 FLUID BlockFromToEvent"), log, "only the redstone wire is washed away")
    }

    @Test
    fun unwatchedBlocksCostOneLookupEachAndNothingBeforeTheFirstSubscription() {
        val service = triggers as TriggerService
        val sneakyUtils = server.pluginManager.getPlugin("SneakyUtils")!!
        fun listening() = BlockDestroyEvent.getHandlerList().registeredListeners.any { it.plugin === sneakyUtils }
        assertFalse(listening(), "no removal listener until something is watched")
        val blocks = (0 until 100).map { block(it, z = 9) }
        fire(BlockExplodeEvent(blocks[0], blocks[0].state, blocks.toMutableList(), 1f, ExplosionResult.DESTROY))
        assertEquals(0L, counter("block-removed.lookups"))

        val sub = watch(block(500))
        assertTrue(listening())
        fire(BlockExplodeEvent(blocks[0], blocks[0].state, blocks.toMutableList(), 1f, ExplosionResult.DESTROY))
        fire(BlockBreakEvent(blocks[1], player))
        assertEquals(606L, counter("block-removed.lookups"),
            "per block the events took away: one for it, five for the neighbours that may rest on it (rails, wire)")
        assertEquals(0L, counter("handler.calls"))
        assertEquals(emptyList<String>(), log)

        // Watched blocks in another world: this world's events stop at the world lookup.
        sub.close()
        triggers.onBlockRemoved(owner, BlockKey.of(server.addSimpleWorld("other").getBlockAt(0, 64, 0)), recorder("x"))
        fire(BlockExplodeEvent(blocks[0], blocks[0].state, blocks.toMutableList(), 1f, ExplosionResult.DESTROY))
        assertEquals(606L, counter("block-removed.lookups"))
        assertTrue(service.removals.subs == 1)
    }

    @Test
    fun closingAndSubscribingInsideAHandlerTakeEffectFromTheNextEvent() {
        val a = block(1)
        val b = block(2)
        var late: Subscription? = null
        lateinit var second: Subscription
        triggers.onBlockRemoved(owner, BlockKey.of(a)) { blk, cause, _ ->
            log += "first ${blk.x} $cause"
            second.close()
            // A watch on the next block of the same explosion waits for the next event.
            if (late == null) late = watch(b, "late")
        }
        second = watch(a, "second")
        fire(BlockExplodeEvent(a, a.state, mutableListOf(a, b), 1f, ExplosionResult.DESTROY))
        assertEquals(listOf("first 1 EXPLODE"), log, "the closed one is skipped, the new one waits")
        assertFalse(second.active)
        log.clear()
        fire(BlockBreakEvent(b, player))
        assertEquals(listOf("late 2,64,0 BREAK BlockBreakEvent"), log)

        log.clear()
        val self = arrayOfNulls<Subscription>(1)
        val c = block(3)
        self[0] = triggers.onBlockRemoved(owner, BlockKey.of(c)) { _, cause, _ -> log += "once $cause"; self[0]!!.close() }
        fire(BlockBurnEvent(c, null))
        fire(BlockDestroyEvent(c, server.createBlockData(Material.AIR), c.blockData, 0, true))
        assertEquals(listOf("once BURN"), log, "closed in its first call")
    }

    // ---------------------------------------------------------------- Paper 26.2 server paths (review fixes)

    @Test
    fun entityBreaksThatGoThroughTheServersDestroyComeFromTheDestroyEvent() {
        // Wither, ravager, villager, sheep on short grass...: EntityChangeBlockEvent to air, then Level.destroyBlock
        // fires BlockDestroyEvent, which a protection plugin can still cancel.
        val wither = mock(Wither::class.java)
        val a = block(1)
        val b = block(2)
        watch(a)
        watch(b)
        server.pluginManager.registerEvents(object : Listener {
            @EventHandler(priority = EventPriority.LOWEST)
            fun protect(e: BlockDestroyEvent) { if (e.block.x == 2) e.isCancelled = true }
        }, owner)
        for (blk in listOf(a, b)) {
            fire(EntityChangeBlockEvent(wither, blk, server.createBlockData(Material.AIR)))
            fire(BlockDestroyEvent(blk, server.createBlockData(Material.AIR), blk.blockData, 0, true))
        }
        assertEquals(listOf("w 1,64,0 DESTROY BlockDestroyEvent"), log, "once, from the destroy; the protected one never")

        // The paths that remove the block themselves: a falling block, an enderman, eating the last slice of cake.
        log.clear()
        val sand = block(3, Material.SAND)
        val held = block(4, Material.GRASS_BLOCK)
        val cake = block(5, Material.CAKE)
        for (blk in listOf(sand, held, cake)) watch(blk)
        fire(EntityChangeBlockEvent(mock(FallingBlock::class.java), sand, server.createBlockData(Material.AIR)))
        fire(EntityChangeBlockEvent(mock(Enderman::class.java), held, server.createBlockData(Material.AIR)))
        fire(EntityChangeBlockEvent(player, cake, server.createBlockData(Material.AIR)))
        assertEquals(
            listOf("w 3,64,0 ENTITY EntityChangeBlockEvent", "w 4,64,0 ENTITY EntityChangeBlockEvent",
                "w 5,64,0 ENTITY EntityChangeBlockEvent"),
            log,
        )
    }

    @Test
    fun tntIsReportedOnlyWhenItsPrimingGoesAhead() {
        val zombie = world.spawnEntity(Location(world, 0.0, 64.0, 0.0), EntityType.ZOMBIE)
        val tnt = block(1, Material.TNT)
        val stone = block(2)
        watch(tnt)
        watch(stone)
        var cancelPrime = true
        server.pluginManager.registerEvents(object : Listener {
            @EventHandler(priority = EventPriority.LOWEST)
            fun guard(e: TNTPrimeEvent) { if (cancelPrime) e.isCancelled = true }
        }, owner)
        // The explosion lists the TNT; Paper then asks TNTPrimeEvent, and a cancelled one leaves the TNT in place.
        fire(EntityExplodeEvent(zombie, tnt.location, mutableListOf(tnt, stone), 1f, ExplosionResult.DESTROY))
        fire(TNTPrimeEvent(tnt, TNTPrimeEvent.PrimeCause.EXPLOSION, zombie, null))
        // Fire: BlockBurnEvent first, then TNTPrimeEvent(FIRE). A projectile: EntityChangeBlockEvent, then PROJECTILE.
        fire(BlockBurnEvent(tnt, null))
        fire(TNTPrimeEvent(tnt, TNTPrimeEvent.PrimeCause.FIRE, null, null))
        fire(EntityChangeBlockEvent(mock(Arrow::class.java), tnt, server.createBlockData(Material.AIR)))
        fire(TNTPrimeEvent(tnt, TNTPrimeEvent.PrimeCause.PROJECTILE, null, null))
        assertEquals(listOf("w 2,64,0 EXPLODE EntityExplodeEvent"), log, "TNT that stays is never reported")

        log.clear()
        cancelPrime = false
        for (c in TNTPrimeEvent.PrimeCause.entries) { fire(TNTPrimeEvent(tnt, c, null, null)); server.scheduler.performOneTick() }
        assertEquals(
            listOf(
                "w 1,64,0 PRIME TNTPrimeEvent", "w 1,64,0 PRIME TNTPrimeEvent", "w 1,64,0 EXPLODE TNTPrimeEvent",
                "w 1,64,0 ENTITY TNTPrimeEvent", "w 1,64,0 PRIME TNTPrimeEvent",
            ),
            log,
            "REDSTONE, PLAYER, EXPLOSION, PROJECTILE, DISPENSER; FIRE waits for Paper's old event, BLOCK_BREAK was the break",
        )
    }

    @Test
    fun fireReportsTntOnlyOncePapersOldPrimeEventLetsItGo() {
        // FireBlock: BlockBurnEvent, Bukkit TNTPrimeEvent(FIRE), then maybe fire replaces the TNT, then Paper's old
        // TNTPrimeEvent(FIRE), which keeps the TNT when cancelled unless fire already replaced it.
        val tnt = block(1, Material.TNT)
        watch(tnt)
        fun burn(cancelOld: Boolean, replacedByFire: Boolean) {
            fire(BlockBurnEvent(tnt, null))
            fire(TNTPrimeEvent(tnt, TNTPrimeEvent.PrimeCause.FIRE, null, null))
            if (replacedByFire) tnt.type = Material.FIRE
            fire(oldPrime(tnt, OldPrime.PrimeReason.FIRE).also { (it as Cancellable).isCancelled = cancelOld })
            tnt.type = Material.TNT
            server.scheduler.performOneTick()
        }
        burn(cancelOld = true, replacedByFire = false)
        assertEquals(emptyList<String>(), log, "the TNT stays")
        burn(cancelOld = false, replacedByFire = false)
        burn(cancelOld = true, replacedByFire = true)
        assertEquals(listOf("w 1,64,0 BURN TNTPrimeEvent", "w 1,64,0 BURN TNTPrimeEvent"), log)
    }

    @Test
    fun anEnderDragonRemovesItsListAtYieldZeroAndLetsTheOldPrimeEventDecideOtherwise() {
        val dragon = mock(EnderDragon::class.java)
        val tnt = block(1, Material.TNT)
        val stone = block(2)
        watch(tnt)
        watch(stone)
        // Yield 0 (the dragon's default): Paper removes every listed block directly, TNT included.
        fire(EntityExplodeEvent(dragon, tnt.location, mutableListOf(tnt, stone), 0f, ExplosionResult.DESTROY))
        assertEquals(listOf("w 1,64,0 EXPLODE EntityExplodeEvent", "w 2,64,0 EXPLODE EntityExplodeEvent"), log)

        // A plugin changed the yield: Paper's old TNTPrimeEvent(EXPLOSION) decides each listed block.
        log.clear()
        server.scheduler.performOneTick()
        fire(EntityExplodeEvent(dragon, tnt.location, mutableListOf(tnt, stone), 0.5f, ExplosionResult.DESTROY))
        assertEquals(emptyList<String>(), log, "not from the list")
        fire(oldPrime(tnt, OldPrime.PrimeReason.EXPLOSION))
        fire(oldPrime(stone, OldPrime.PrimeReason.EXPLOSION).also { (it as Cancellable).isCancelled = true })
        assertEquals(listOf("w 1,64,0 EXPLODE TNTPrimeEvent"), log, "the kept stone isn't reported")
    }

    @Test
    fun breaksPaperRefusesAfterTheEventAreNotReported() {
        val structure = block(1, Material.STRUCTURE_BLOCK)
        val command = block(2, Material.COMMAND_BLOCK)
        val stone = block(3)
        for (b in listOf(structure, command, stone)) watch(b)
        player.gameMode = org.bukkit.GameMode.CREATIVE
        fire(BlockBreakEvent(structure, player))
        fire(BlockBreakEvent(command, player))
        player.gameMode = org.bukkit.GameMode.SPECTATOR
        fire(BlockBreakEvent(stone, player))
        assertEquals(emptyList<String>(), log, "game-master blocks need creative and op; spectators break nothing")

        player.gameMode = org.bukkit.GameMode.CREATIVE
        player.addAttachment(owner, "minecraft.commandblock", true)
        fire(BlockBreakEvent(command, player))
        fire(BlockBreakEvent(structure, player))
        player.isOp = true
        fire(BlockBreakEvent(structure, player))
        player.gameMode = org.bukkit.GameMode.SURVIVAL
        fire(BlockBreakEvent(stone, player))
        assertEquals(listOf("w 2,64,0 BREAK BlockBreakEvent", "w 1,64,0 BREAK BlockBreakEvent", "w 3,64,0 BREAK BlockBreakEvent"), log,
            "a command block with minecraft.commandblock; a structure block only as op")
    }

    @Test
    fun anExplodingBedOrAnchorReportsItsAlreadyRemovedSourceEvenWhenTheBlastIsCancelled() {
        val foot = block(1, Material.RED_BED)
        val head = block(2, Material.RED_BED)
        // Paper's snapshot of the clicked half (the foot, facing east: the head is at x + 1).
        val bed = mock(Bed::class.java)
        `when`(bed.part).thenReturn(Bed.Part.FOOT)
        `when`(bed.facing).thenReturn(BlockFace.EAST)
        val snapshot = mock(org.bukkit.block.BlockState::class.java)
        `when`(snapshot.type).thenReturn(Material.RED_BED)
        `when`(snapshot.blockData).thenReturn(bed)
        val near = block(3)
        for (b in listOf(foot, head, near)) watch(b)
        // Paper removes the clicked half and (if it is the matching half) the other, then fires the event.
        foot.type = Material.AIR
        head.type = Material.AIR
        val cancelled = BlockExplodeEvent(foot, snapshot, mutableListOf(near), 1f, ExplosionResult.DESTROY).also { it.isCancelled = true }
        fire(cancelled)
        assertEquals(listOf("w 1,64,0 EXPLODE BlockExplodeEvent"), log,
            "the proven source only: nothing shows the other half was a bed; not the cancelled blast's blocks")

        log.clear()
        val anchor = block(5, Material.RESPAWN_ANCHOR)
        val anchorState = anchor.state
        watch(anchor)
        anchor.type = Material.AIR
        fire(BlockExplodeEvent(anchor, anchorState, mutableListOf(near), 1f, ExplosionResult.DESTROY))
        assertEquals(listOf("w 5,64,0 EXPLODE BlockExplodeEvent", "w 3,64,0 EXPLODE BlockExplodeEvent"), log)
    }

    @Test
    fun aRetractingPistonReportsItsHeadFromTheBasesOwnFacing() {
        fun facing(m: Material, f: BlockFace) = (server.createBlockData(m) as Directional).also { it.facing = f }
        val base = block(0, Material.PISTON)
        base.blockData = facing(Material.PISTON, BlockFace.EAST)
        val head = block(1, Material.PISTON_HEAD)
        head.blockData = facing(Material.PISTON_HEAD, BlockFace.EAST)
        val pulled = block(2)
        watch(head)
        watch(pulled, "pulled")
        // A plain retract: Paper passes the piston's facing, the head still in place.
        fire(BlockPistonRetractEvent(base, emptyList(), BlockFace.EAST))
        assertEquals(listOf("w 1,64,0 PISTON BlockPistonRetractEvent"), log)

        // Cancelled before anything moved: the head stays and nothing is reported.
        log.clear()
        fire(BlockPistonRetractEvent(base, emptyList(), BlockFace.EAST).also { it.isCancelled = true })
        assertEquals(emptyList<String>(), log)

        // A sticky pull: the base is a moving piston by now, moveBlocks has cleared the head, and Paper passes the
        // opposite direction. The head is reported even when the pull is cancelled (it isn't put back).
        server.scheduler.performOneTick()
        base.type = Material.MOVING_PISTON
        base.blockData = facing(Material.MOVING_PISTON, BlockFace.EAST)
        head.type = Material.AIR
        fire(BlockPistonRetractEvent(base, listOf(pulled), BlockFace.WEST))
        server.scheduler.performOneTick()
        fire(BlockPistonRetractEvent(base, listOf(pulled), BlockFace.WEST).also { it.isCancelled = true })
        assertEquals(
            listOf("w 1,64,0 PISTON BlockPistonRetractEvent", "pulled 2,64,0 PISTON BlockPistonRetractEvent",
                "w 1,64,0 PISTON BlockPistonRetractEvent"),
            log,
            "a cancelled pull reports the cleared head, not the blocks it kept",
        )

        log.clear()
        head.type = Material.PISTON_HEAD
        head.blockData = facing(Material.PISTON_HEAD, BlockFace.WEST)
        fire(BlockPistonRetractEvent(base, emptyList(), BlockFace.EAST))
        head.type = Material.STONE
        fire(BlockPistonRetractEvent(base, emptyList(), BlockFace.EAST))
        base.type = Material.PISTON
        base.blockData = facing(Material.PISTON, BlockFace.EAST)
        head.type = Material.AIR
        fire(BlockPistonRetractEvent(base, emptyList(), BlockFace.EAST))
        assertEquals(emptyList<String>(), log, "only this piston's head, and air only during a sticky pull")
    }

    @Test
    fun explosionEntriesAreResolvedInTheExplosionsWorld() {
        val other = server.addSimpleWorld("other")
        val stoneHere = block(1)
        val tntHere = block(2, Material.TNT)
        other.getBlockAt(1, 64, 0).type = Material.TNT
        other.getBlockAt(2, 64, 0).type = Material.STONE
        val seen = ArrayList<String>()
        for (b in listOf(stoneHere, tntHere)) {
            triggers.onBlockRemoved(owner, BlockKey.of(b)) { blk, cause, _ -> seen += "${blk.world.name} ${blk.x} ${blk.type} $cause" }
        }
        // A plugin added the other world's blocks: Paper applies their coordinates here.
        fire(BlockExplodeEvent(stoneHere, stoneHere.state,
            mutableListOf<Block>(other.getBlockAt(1, 64, 0), other.getBlockAt(2, 64, 0)), 1f, ExplosionResult.DESTROY))
        assertEquals(listOf("world 1 STONE EXPLODE"), seen, "this world's stone; this world's TNT waits for its priming")
    }

    @Test
    fun blocksAnExplosionCannotDestroyAreNotReported() {
        val zombie = world.spawnEntity(Location(world, 0.0, 64.0, 0.0), EntityType.ZOMBIE)
        val kept = listOf(Material.BEDROCK, Material.BARRIER, Material.END_PORTAL_FRAME, Material.STRUCTURE_BLOCK, Material.AIR)
            .mapIndexed { i, m -> block(i + 1, m) }
        val stone = block(10)
        for (b in kept + stone) watch(b)
        fire(EntityExplodeEvent(zombie, stone.location, (kept + stone).toMutableList(), 1f, ExplosionResult.DESTROY))
        assertEquals(listOf("w 10,64,0 EXPLODE EntityExplodeEvent"), log, "Paper's isDestroyable keeps the others; air is nothing")

        // An ender dragon at yield 0 removes what it lists with no such check.
        log.clear()
        fire(EntityExplodeEvent(mock(EnderDragon::class.java), stone.location, mutableListOf(kept[0]), 0f, ExplosionResult.DESTROY))
        assertEquals(listOf("w 1,64,0 EXPLODE EntityExplodeEvent"), log)
    }

    @Test
    fun spreadingOntoABlockThatTurnsItIntoAnotherBlockReportsSpread() {
        val stone = block(1)
        val air = block(2, Material.AIR)
        val already = block(3, Material.SCULK)
        for (b in listOf(stone, air, already)) watch(b)
        val source = block(0, Material.SCULK)
        val sculk = block(9, Material.SCULK).state
        fire(BlockSpreadEvent(stone, source, sculk))
        fire(BlockSpreadEvent(air, source, sculk))   // spreading into air removes nothing
        fire(BlockSpreadEvent(already, source, sculk))  // same material: nothing changes
        fire(BlockSpreadEvent(stone, source, sculk).also { it.isCancelled = true })
        assertEquals(listOf("w 1,64,0 SPREAD BlockSpreadEvent"), log)
    }

    @Test
    fun anAcceptedPlacementOverABlockReportsReplace() {
        val spot = block(1, Material.SHORT_GRASS)
        watch(spot)
        val grass = spot.state
        val against = block(1, y = 63)
        val item = org.bukkit.inventory.ItemStack(Material.STONE)
        // Paper places first, then fires the event (and puts the old block back when it is refused).
        spot.type = Material.STONE
        fun place(canBuild: Boolean, cancel: Boolean = false, replaced: org.bukkit.block.BlockState = grass) = fire(
            BlockPlaceEvent(spot, replaced, against, item, player, canBuild, org.bukkit.inventory.EquipmentSlot.HAND)
                .also { it.isCancelled = cancel },
        )
        place(canBuild = false)
        place(canBuild = true, cancel = true)
        place(canBuild = true, replaced = block(20, Material.AIR).state)
        assertEquals(emptyList<String>(), log, "refused, cancelled, or placed into air")
        place(canBuild = true)
        assertEquals(listOf("w 1,64,0 REPLACE BlockPlaceEvent"), log, "the handler sees the placed stone")

        // A door over two flowers: each replaced position.
        log.clear()
        val low = block(5, Material.POPPY)
        val high = block(5, Material.DANDELION, y = 65)
        watch(low)
        watch(high)
        val states = listOf(low.state, high.state)
        low.type = Material.OAK_DOOR
        high.type = Material.OAK_DOOR
        fire(org.bukkit.event.block.BlockMultiPlaceEvent(states, against, item, player, true))
        assertEquals(listOf("w 5,64,0 REPLACE BlockMultiPlaceEvent", "w 5,65,0 REPLACE BlockMultiPlaceEvent"), log)
    }

    @Test
    fun aRecedingFluidReportsFluid() {
        val water = block(1, Material.WATER)
        watch(water)
        fire(FluidLevelChangeEvent(water, server.createBlockData(Material.WATER)))   // another level: still water
        fire(FluidLevelChangeEvent(water, server.createBlockData(Material.AIR)).also { it.isCancelled = true })
        assertEquals(emptyList<String>(), log)
        fire(FluidLevelChangeEvent(water, server.createBlockData(Material.AIR)))
        assertEquals(listOf("w 1,64,0 FLUID FluidLevelChangeEvent"), log)
    }

    @Test
    fun aRailOrWireThatLosesItsSupportIsReportedOnTheNextTick() {
        // Paper 26.2: BaseRailBlock, RedStoneWireBlock and DiodeBlock remove themselves in neighborChanged
        // (dropResources + removeBlock) with no event, right after their support goes.
        val support = block(1)
        val rail = block(1, Material.RAIL, y = 65)
        val wireSupport = block(3)
        val wire = block(3, Material.REDSTONE_WIRE, y = 65)
        val torch = block(5, Material.TORCH, y = 65)
        val torchSupport = block(5)
        for (b in listOf(rail, wire, torch)) watch(b)
        fire(BlockBreakEvent(support, player))
        fire(BlockDestroyEvent(wireSupport, server.createBlockData(Material.AIR), wireSupport.blockData, 0, true))
        fire(BlockBreakEvent(torchSupport, player))
        assertEquals(emptyList<String>(), log, "nothing yet: the neighbours are checked next tick")
        rail.type = Material.AIR      // what the server does in neighborChanged
        // The wire stays (a plugin put the support back, say): checked, not reported.
        server.scheduler.performOneTick()
        assertEquals(listOf("w 1,65,0 DESTROY BlockBreakEvent"), log, "the rail, with the event that took its support")
        assertEquals(2L, counter("block-removed.neighbour-checks"), "the torch reports itself (BlockDestroyEvent)")
        assertEquals(1L, counter("block-removed.neighbour-removals"))

        // An ascending rail also rests on the side it rises to; a flat rail beside a removed block doesn't.
        log.clear()
        val side = block(7)
        val ascending = block(8, Material.POWERED_RAIL)
        ascending.blockData = (server.createBlockData(Material.POWERED_RAIL) as Rail).also { it.shape = Rail.Shape.ASCENDING_WEST }
        val flat = block(7, Material.RAIL, z = 1)
        watch(ascending)
        watch(flat)
        val before = counter("block-removed.neighbour-checks")
        fire(BlockBreakEvent(side, player))
        assertEquals(before + 1, counter("block-removed.neighbour-checks"), "only the ascending rail is queued")
        ascending.type = Material.AIR
        server.scheduler.performOneTick()
        assertEquals(listOf("w 8,64,0 DESTROY BlockBreakEvent"), log)
    }

    @Test
    fun aNeighbourReportedByItsOwnEventIsNotReportedAgain() {
        val support = block(1)
        val rail = block(1, Material.RAIL, y = 65)
        watch(rail)
        val zombie = world.spawnEntity(Location(world, 0.0, 64.0, 0.0), EntityType.ZOMBIE)
        // One explosion takes both: the rail is in the list itself.
        fire(EntityExplodeEvent(zombie, support.location, mutableListOf(support, rail), 1f, ExplosionResult.DESTROY))
        rail.type = Material.AIR
        support.type = Material.AIR
        server.scheduler.performOneTick()
        assertEquals(listOf("w 1,65,0 EXPLODE EntityExplodeEvent"), log)

        // A cancelled break schedules nothing.
        log.clear()
        support.type = Material.STONE
        rail.type = Material.RAIL
        fire(BlockBreakEvent(support, player).also { it.isCancelled = true })
        rail.type = Material.AIR
        server.scheduler.performOneTick()
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun aWatchMadeDuringAMultiPlacementWaitsForTheNextEvent() {
        val low = block(5, Material.POPPY)
        val high = block(5, Material.DANDELION, y = 65)
        triggers.onBlockRemoved(owner, BlockKey.of(low)) { b, cause, _ ->
            log += "low ${b.y} $cause"
            watch(high, "late")
        }
        val states = listOf(low.state, high.state)
        low.type = Material.OAK_DOOR
        high.type = Material.OAK_DOOR
        val against = block(5, y = 63)
        fire(org.bukkit.event.block.BlockMultiPlaceEvent(states, against, org.bukkit.inventory.ItemStack(Material.OAK_DOOR), player, true))
        assertEquals(listOf("low 64 REPLACE"), log)
    }

    @Test
    fun aBlockFormingInItsPlaceReportsForm() {
        val powder = block(1, Material.WHITE_CONCRETE_POWDER)
        val lava = block(2, Material.LAVA)
        val water = block(3, Material.WATER)
        val copper = block(4, Material.COPPER_BLOCK)
        val air = block(5, Material.AIR)
        for (b in listOf(powder, lava, water, copper, air)) watch(b)
        fun state(m: Material) = block(30 + m.ordinal % 50, m, y = 100).state
        fire(BlockFormEvent(powder, state(Material.WHITE_CONCRETE)))
        fire(BlockFormEvent(lava, state(Material.OBSIDIAN)))
        fire(BlockFormEvent(water, state(Material.WHITE_CONCRETE)))   // powder being placed into water: REPLACE's job
        fire(BlockFormEvent(copper, state(Material.EXPOSED_COPPER)))  // weathering keeps the block
        fire(BlockFormEvent(air, state(Material.SNOW)))
        fire(BlockFormEvent(water, state(Material.ICE)).also { it.isCancelled = true })
        assertEquals(listOf("w 1,64,0 FORM BlockFormEvent", "w 2,64,0 FORM BlockFormEvent"), log)
        fire(org.bukkit.event.block.EntityBlockFormEvent(player, water, state(Material.FROSTED_ICE)))
        assertEquals("w 3,64,0 FORM EntityBlockFormEvent", log.last())
    }

    @Test
    fun aSpongeReportsWhatItSoaksUp() {
        val water = block(1, Material.WATER)
        val kelp = block(2, Material.KELP)
        val stairs = block(3, Material.OAK_STAIRS)
        for (b in listOf(water, kelp, stairs)) watch(b)
        val sponge = block(0, Material.SPONGE)
        fun absorbed() = listOf(stateAt(water, Material.AIR), stateAt(kelp, Material.AIR), stateAt(stairs, Material.OAK_STAIRS))
        fire(org.bukkit.event.block.SpongeAbsorbEvent(sponge, absorbed()).also { it.isCancelled = true })
        assertEquals(emptyList<String>(), log)
        fire(org.bukkit.event.block.SpongeAbsorbEvent(sponge, absorbed()))
        assertEquals(listOf("w 1,64,0 ABSORB SpongeAbsorbEvent", "w 2,64,0 ABSORB SpongeAbsorbEvent"), log,
            "the stairs only lose their water")
    }

    /** A state at [b]'s position with type [m] (as Paper's absorb list holds it), without changing the world. */
    private fun stateAt(b: Block, m: Material): org.bukkit.block.BlockState {
        val s = mock(org.bukkit.block.BlockState::class.java)
        `when`(s.x).thenReturn(b.x)
        `when`(s.y).thenReturn(b.y)
        `when`(s.z).thenReturn(b.z)
        `when`(s.type).thenReturn(m)
        return s
    }

    @Test
    fun aSubscriptionGetsOneDeliveryOfACausePerTick() {
        // Found on Paper 26.2: a sponge placed with Block.setType(SPONGE, true) into settled water fires two
        // SpongeAbsorbEvents with overlapping positions in the same tick.
        val water = block(1, Material.WATER)
        watch(water)
        val sponge = block(0, Material.SPONGE)
        fire(org.bukkit.event.block.SpongeAbsorbEvent(sponge, listOf(stateAt(water, Material.AIR))))
        fire(org.bukkit.event.block.SpongeAbsorbEvent(sponge, listOf(stateAt(water, Material.AIR))))
        fire(BlockBurnEvent(water, null))   // another cause in the same tick still comes
        fire(org.bukkit.event.block.SpongeAbsorbEvent(sponge, listOf(stateAt(water, Material.AIR))))   // ABSORB, BURN, ABSORB
        assertEquals(listOf("w 1,64,0 ABSORB SpongeAbsorbEvent", "w 1,64,0 BURN BlockBurnEvent"), log)
        server.scheduler.performOneTick()
        fire(org.bukkit.event.block.SpongeAbsorbEvent(sponge, listOf(stateAt(water, Material.AIR))))
        assertEquals("w 1,64,0 ABSORB SpongeAbsorbEvent", log.last(), "the next tick is a new removal")
        assertEquals(3, log.size)
    }

    @Test
    fun aQueuedNeighbourCheckUsesTheWatchesOfItsSupportsRemoval() {
        val support = block(1)
        val rail = block(1, Material.RAIL, y = 65)
        val early = triggers.onBlockRemoved(owner, BlockKey.of(rail), recorder("early"))
        val closing = triggers.onBlockRemoved(owner, BlockKey.of(rail), recorder("closed"))
        fire(BlockBreakEvent(support, player))
        watch(rail, "late")   // made after the support went: waits for the next removal
        closing.close()
        rail.type = Material.AIR
        server.scheduler.performOneTick()
        assertEquals(listOf("early 1,65,0 DESTROY BlockBreakEvent"), log)
        assertTrue(early.active)
    }

    @Test
    fun anExplosionQueuesNeighbourChecksOnlyForWhatItReallyTakes() {
        val zombie = world.spawnEntity(Location(world, 0.0, 64.0, 0.0), EntityType.ZOMBIE)
        val tnt = block(1, Material.TNT)
        val bedrock = block(3, Material.BEDROCK)
        val stone = block(5)
        val rails = listOf(tnt, bedrock, stone).map { block(it.x, Material.RAIL, y = 65) }
        for (r in rails) watch(r)
        fire(EntityExplodeEvent(zombie, stone.location, mutableListOf(tnt, bedrock, stone), 1f, ExplosionResult.DESTROY))
        assertEquals(1L, counter("block-removed.neighbour-checks"), "TNT waits for its prime event; bedrock stays")
        for (r in rails) r.type = Material.AIR
        server.scheduler.performOneTick()
        assertEquals(listOf("w 5,65,0 DESTROY EntityExplodeEvent"), log)
    }

    @Test
    fun burningTntChecksItsNeighboursOnlyWhenItsPrimingLetsItGo() {
        val tnt = block(1, Material.TNT)
        val rail = block(1, Material.RAIL, y = 65)
        watch(rail)
        fire(BlockBurnEvent(tnt, null))
        fire(TNTPrimeEvent(tnt, TNTPrimeEvent.PrimeCause.FIRE, null, null))
        assertEquals(0L, counter("block-removed.neighbour-checks"), "Paper's old prime event still decides")
        fire(oldPrime(tnt, OldPrime.PrimeReason.FIRE).also { (it as Cancellable).isCancelled = true })
        assertEquals(0L, counter("block-removed.neighbour-checks"), "kept TNT")
        fire(oldPrime(tnt, OldPrime.PrimeReason.FIRE))
        assertEquals(1L, counter("block-removed.neighbour-checks"))
        rail.type = Material.AIR
        server.scheduler.performOneTick()
        assertEquals(listOf("w 1,65,0 DESTROY TNTPrimeEvent"), log)
    }

    @Test
    fun aBucketScoopingUpASourceReportsBucket() {
        val water = block(1, Material.WATER)
        val lava = block(2, Material.LAVA)
        val snow = block(3, Material.POWDER_SNOW)
        val stairs = block(4, Material.OAK_STAIRS)   // waterlogged: only its water goes
        for (b in listOf(water, lava, snow, stairs)) watch(b)
        fun fill(b: Block, cancel: Boolean = false) = fire(
            PlayerBucketFillEvent(player, b, b, BlockFace.UP, Material.BUCKET, org.bukkit.inventory.ItemStack(Material.BUCKET),
                org.bukkit.inventory.EquipmentSlot.HAND).also { it.isCancelled = cancel },
        )
        fill(water, cancel = true)
        fill(stairs)
        assertEquals(emptyList<String>(), log)
        for (b in listOf(water, lava, snow)) fill(b)
        assertEquals(listOf("w 1,64,0 BUCKET PlayerBucketFillEvent", "w 2,64,0 BUCKET PlayerBucketFillEvent",
            "w 3,64,0 BUCKET PlayerBucketFillEvent"), log)
    }

    @Test
    fun aTeleportingDragonEggReportsItsOldPosition() {
        val egg = block(1, Material.DRAGON_EGG)
        val target = block(1, Material.AIR, y = 70)
        watch(egg)
        watch(target)
        fire(BlockFromToEvent(egg, target))
        assertEquals(listOf("w 1,64,0 TELEPORT BlockFromToEvent"), log)
    }

    @Test
    fun aPositionListedTwiceInOneEventIsDeliveredOnceAtItsFirstPlace() {
        val a = block(1)
        val b = block(2)
        watch(a)
        watch(b)
        fire(BlockExplodeEvent(a, a.state, mutableListOf(b, a, b, a), 1f, ExplosionResult.DESTROY))
        assertEquals(listOf("w 2,64,0 EXPLODE BlockExplodeEvent", "w 1,64,0 EXPLODE BlockExplodeEvent"), log)
    }

    @Test
    fun ownerDisableWorldUnloadAndCloseRemoveWatches() {
        val here = block(1)
        val instance = server.addSimpleWorld("instance")
        val there = triggers.onBlockRemoved(owner, BlockKey.of(instance.getBlockAt(0, 64, 0)), recorder("there"))
        val sub = watch(here)
        server.unloadWorld(instance, false)
        assertFalse(there.active)
        assertTrue(sub.active)
        server.pluginManager.disablePlugin(owner)
        assertFalse(sub.active)
        fire(BlockBreakEvent(here, player))
        assertEquals(emptyList<String>(), log)
        assertEquals(0, triggers.stats().count("block-removed"))
        assertEquals(0, (triggers as TriggerService).removals.subs)
    }

    @Test
    fun aThrowingHandlerDoesNotStopOthersAndLabelsAndChecksApply() {
        val b = block(1)
        triggers.onBlockRemoved(owner, BlockKey.of(b)) { _, _, _ -> error("boom") }
        watch(b, "ok")
        fire(BlockBurnEvent(b, null))
        assertEquals(listOf("ok 1,64,0 BURN BlockBurnEvent"), log)
        assertEquals(1L, counter("handler.errors"))

        val labelled = triggers.labelled("owner:anchor").onBlockRemoved(owner, BlockKey.of(b), recorder("l"))
        assertEquals("owner:anchor", labelled.label)
        val explicit = triggers.onBlockRemoved(owner, BlockKey.of(b), recorder("e"), BlockRemovedOptions.builder().label("owner:e").build())
        assertEquals("owner:e", explicit.label)
        assertTrue(labelled.id.startsWith("Owner:block-removed#"))
        assertThrows(IllegalArgumentException::class.java) {
            triggers.onBlockRemoved(owner, BlockKey(world.uid, 0, 5000, 0), recorder("x"))
        }
        val other = MockBukkit.createMockPlugin("Other")
        server.pluginManager.disablePlugin(other)
        assertThrows(IllegalArgumentException::class.java) { triggers.onBlockRemoved(other, BlockKey.of(b), recorder("x")) }
    }
}
