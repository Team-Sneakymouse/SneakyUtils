# Virtual targets

Some things a weapon should hit are not entities: SneakyHorde's figures are drawn with packets and exist on the
server only as numbers. `World.getNearbyEntities`, ray traces and `EntityDamageEvent` never see them.
`com.sneakyutils.targets` is the one place weapons and such targets meet, so a weapon is written once and every
plugin with virtual targets is hit by it.

Server thread only.

## A weapon

Arrows, tridents, fireballs and real explosions reach virtual targets by themselves (their owner follows them).
A weapon that finds its own targets calls `VirtualTargets` as well, next to what it does to entities:

| The weapon | Call |
|---|---|
| A shot through everything in a line (cut the length at the first wall yourself) | `VirtualTargets.damageLine(from, direction, length, radius, damage, source)` |
| A shot that stops in the first thing it hits | `VirtualTargets.damageFirst(...)`: where it hit, or null |
| A burst that hurts everything within a radius the same | `VirtualTargets.damageSphere(center, radius, damage, source)` |
| A blast with vanilla falloff, made without a real explosion | `VirtualTargets.damageExplosion(center, power, source)` (4 = TNT) |
| Where the crosshair lands (homing, a cursor) | `VirtualTargets.firstAlong(from, direction, length, radius)` |

The counts and locations returned are for the weapon's own effects. With no plugin registered every call returns at
once with 0 or null, so there is nothing to check first. A call that makes no sense (a direction with no length, a
length or radius that is not a finite number, NaN damage) hits nothing.

A weapon with a rule of its own for what a projectile deals (a bow that fells monsters in one shot) listens for
`VirtualTargetDamageEvent` and changes `damage`, or cancels the hit. It is called for every hit on a virtual
target, whatever caused it, with the `projectile` when there was one.

## A plugin with virtual targets

Extend `VirtualTargetProvider`, override what the targets can take, and `VirtualTargets.register(plugin, provider)`
while they exist. It is dropped when the plugin is disabled. `firstAlong` is how a first-hit shot finds its target
among every plugin's, so a provider that takes `damageFirst` must answer `firstAlong` too. For every hit, call
`VirtualTargetDamageEvent.fire(...)` first and deal what it returns (NaN: the hit was cancelled).
