package io.github.hi.neason.half.agent;

import io.github.hi.neason.half.agent.state.TurnOptions;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

class TurnOptionsTest {
    @Test
    void defaultsToEightCallsAndRepresentsUnlimitedExplicitly() {
        assertEquals(OptionalInt.of(8), TurnOptions.defaults().maxModelCalls());
        assertEquals(OptionalInt.of(3), TurnOptions.limited(3).maxModelCalls());
        assertEquals(OptionalInt.empty(), TurnOptions.unlimited().maxModelCalls());
    }

    @Test
    void rejectsNonPositiveLimitsThroughFactoryAndCanonicalConstructor() {
        for (int limit : new int[] {0, -1, Integer.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> TurnOptions.limited(limit));
            assertThrows(IllegalArgumentException.class, () -> new TurnOptions(OptionalInt.of(limit)));
        }
        assertThrows(NullPointerException.class, () -> new TurnOptions(null));
    }
}
