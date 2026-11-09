package com.yuelengm.nhkantan.market.order;

import static org.junit.Assert.*;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public class RequestJournalTest {

    @Test
    public void duplicateReplyIsReplayedAndEvictionNeverAllowsSecondExecution() {
        RequestJournal<Integer> journal = new RequestJournal<>(1);
        UUID owner = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        assertEquals(Integer.valueOf(1), journal.execute(owner, 1, calls::incrementAndGet));
        assertEquals(Integer.valueOf(1), journal.execute(owner, 1, calls::incrementAndGet));
        journal.execute(owner, 2, calls::incrementAndGet);
        try {
            journal.execute(owner, 1, calls::incrementAndGet);
            fail("Evicted request must remain rejected");
        } catch (IllegalArgumentException expected) {
            assertEquals(2, calls.get());
        }
        RequestJournal<Integer> restored = new RequestJournal<>(1);
        restored.restore(journal.checkpoint());
        try {
            restored.execute(owner, 2, calls::incrementAndGet);
            fail("Restart must not reset request sequence");
        } catch (IllegalArgumentException expected) {
            assertEquals(2, calls.get());
        }
        assertEquals(Integer.valueOf(3), restored.execute(owner, 3, calls::incrementAndGet));
    }
}
