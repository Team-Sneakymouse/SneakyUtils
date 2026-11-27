package com.sneakyutils.signals;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sneakyutils.SneakyUtils;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/** The report call as a Java plugin writes it: every overload resolves and nothing needs a Kotlin type. */
class ChronicleEventJavaTest {
    private ServerMock server;

    public static final class Recorder implements Listener {
        final List<ChronicleEvent> heard = new ArrayList<>();

        @EventHandler
        public void on(ChronicleEvent event) {
            heard.add(event);
        }
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        MockBukkit.load(SneakyUtils.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void reportsFromJava() {
        Recorder recorder = new Recorder();
        server.getPluginManager().registerEvents(recorder, MockBukkit.createMockPlugin("Recorder"));
        Player player = server.addPlayer("p");
        UUID character = UUID.randomUUID();
        int orb = 3;
        long gold = 50L;

        ChronicleEvent.of("sneakycasino.bet_won")
                .actor(player)
                .targetCharacter(character)
                .thing("orb", orb)
                .thing("table", 7L)
                .thing("game", "roulette")
                .detail("gold", gold)
                .detail("multiplier", 2.5)
                .detail("jackpot", false)
                .detail("streak", 4)
                .time(Instant.now())
                .heardBy(List.of(player.getUniqueId()))
                .at(player.getLocation())
                .fire();

        ChronicleEvent event = recorder.heard.get(0);
        assertEquals(player.getUniqueId(), event.getActor().getAccount());
        assertEquals(character, event.getTargets().get(0).getCharacter());
        assertEquals(List.of("orb:3", "table:7", "game:roulette"), event.getThings().stream().map(Object::toString).toList());
        assertEquals(50L, event.getDetails().get("gold"));
        assertEquals(4L, event.getDetails().get("streak"));
        assertEquals(player.getWorld().getUID(), event.getPlace().getWorld());
    }
}
