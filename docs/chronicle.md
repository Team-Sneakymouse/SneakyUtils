# Chronicle events

The admins read afterwards what happened on the server between streams: who levelled up, where each Dragon Orb
went, who said what to whom. Plugins report those moments with `ChronicleEvent` (`com.sneakyutils.signals`), and the
recorder plugin, SneakyChronicle, stores them. A plugin depends only on SneakyUtils for this. On a server without the
recorder a report does nothing.

```java
ChronicleEvent.of("sneakylegendarys.orb_placed").actor(player).thing("orb", 3).detail("where", "shrine").fire();
```

```kotlin
ChronicleEvent.of("sneakycrime.pickpocket").actor(thief).target(victim).thing("item", itemId).detail("gold", 40).fire()
```

## When to report

Report a moment an admin would want in a summary of what a character did:

- a unique or important thing changed state or hands (an orb, a deed, a pony);
- a player did something to another player (an arrest, a theft, an item used on them, a plot member added);
- a character reached something (a level, a prestige, a quest, a win);
- a player said or typed something in character.

Do not report what repeats many times a minute for one player: XP gains, movement, single hits, ticks of a timer.
Report the outcome once it is known (`fight_won`, not every blow). A report can't be taken back, so report after the
thing happened, not before a check that can still refuse it.

Nothing has to be agreed beforehand: add the call where the moment happens, in the plugin that owns it.

## The parts of a report

| Call | Meaning |
|---|---|
| `of(type)` | What happened: `<owner>.<what_happened>`, lower case with underscores. The owner is your plugin's id (`sneakyleveling.level_up`) or a shared subject (`chat.message`, `voice.utterance`). **The id is a contract:** readers match on it, so never rename one, and keep its details' meaning stable. |
| `actor(player)` | Who did it. The recorder stores the account and the character that account wears at that moment, with the character's name. Leave it out when nobody did. |
| `actorCharacter(characterId)` | The character who did it, by SneakyCharacterManager character id, when you hold no online player (an action stored against a character). |
| `actorNamed("weather")` | A fixed actor that is not a player: `weather`, `server`, `npc:banker`. Lower case. |
| `target(player)`, `targetCharacter(id)`, `targetNamed(name)` | Who it was done to. Call once per target. |
| `thing(kind, id)` | A named thing with a history of its own that someone will search for: `thing("orb", 3)`, `thing("plot", plotId)`, `thing("item", sneakyItemsId)`. Use the same kind and id every time. |
| `detail(key, value)` | A fact about this one event: a string, whole number, decimal or boolean. Keys are lower case with underscores. |
| `text(text)` | What a player said or typed. See [Speech](#speech). |
| `heardBy(accounts)` | Who heard or saw the text, when you know. |
| `time(start)`, `until(end)` | When it happened if that is not now, and when it ended for something with a duration. |
| `at(location)` | Where it happened if that is not where the actor stands (an orb respawning at its shrine). |
| `fire()` | Sends the report. Nothing is reported without it. |

The recorder adds what every event needs and no reporter should work out: the server, the world and block position,
the SneakyPlots plot and zones there, and the characters behind the accounts.

A thing or a detail? A thing is what the event is about, and the same thing appears in many events. A detail only
describes this event. The orb is a thing; whether it was placed on a shrine or the ground is a detail.

Limits: 32 targets, things and details each; detail text is cut at 1024 characters and `text` at 8192.

## Speech

Put what a player said or typed in `text`, never in a detail. The recorder never stores out-of-character lines or
speech in private places, and it can only apply those rules to `text`. Say who heard it with `heardBy` when your
plugin decides that (a chat channel's hearers, the other end of a phone call). `heardBy(emptyList())` means nobody
heard it; not calling it means you don't know, and the recorder may work it out.

For speech with a duration, give both ends, to the millisecond if you can: video is cut on these times.

```java
ChronicleEvent.of("voice.utterance").actor(player).time(started).until(ended).text(transcript).fire();
```

## Threads and failures

- A report works on any thread. Off the server thread, use the calls that take a UUID (`actorAccount`,
  `targetAccount`, `at(world, x, y, z)`) and read nothing from a live `Player`.
- A report never throws and never waits for the database. A report with a malformed type is dropped; a malformed
  thing, detail key or name is left out of the report. Each logs one warning for as long as the server runs, so
  watch the log the first time a new report fires on a server with the recorder.
- With no recorder, `of` returns a builder that ignores everything. Its arguments are still evaluated, so wrap a
  report whose arguments are costly to build in `if (ChronicleEvent.hasListeners())`.

## Listening

Only a recorder listens (`@EventHandler` on `ChronicleEvent`). The event is asynchronous when it was reported off
the server thread, so check `isAsynchronous` before touching Bukkit state, and return quickly: the listener runs
inside the reporting plugin's code. `id` is made when the event is reported and is its stable reference.
