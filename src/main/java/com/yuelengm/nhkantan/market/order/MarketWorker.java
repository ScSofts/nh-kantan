package com.yuelengm.nhkantan.market.order;

import java.util.PriorityQueue;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** One bounded command mailbox and one event loop; no Minecraft objects may be captured by commands. */
public final class MarketWorker implements AutoCloseable {

    private final ArrayBlockingQueue<Runnable> commands;
    private final ScheduledExecutorService executor;
    private final PriorityQueue<Event> events = new PriorityQueue<>();
    private final int commandBudget;
    private final int eventBudget;
    private volatile boolean closed;
    private volatile Thread writerThread;
    private volatile Throwable failure;
    private volatile boolean active;
    private volatile long gameTick;
    private volatile long lastClockNanos;
    private long logicalMillis;
    private long previousNanos = System.nanoTime();
    private long eventSequence;

    private static final class Event implements Comparable<Event> {

        private long deadline;
        private final long sequence;
        private final Random random;
        private final long interval;
        private final double jitter;
        private final double probability;
        private final Runnable action;

        private Event(long sequence, long seed, long interval, double jitter, double probability, Runnable action) {
            this.sequence = sequence;
            this.random = new Random(seed);
            this.interval = interval;
            this.jitter = jitter;
            this.probability = probability;
            this.action = action;
        }

        private long delay() {
            return Math.max(1L, (long) (interval * (1 + jitter * (2 * random.nextDouble() - 1))));
        }

        @Override
        public int compareTo(Event other) {
            int time = Long.compare(deadline, other.deadline);
            return time == 0 ? Long.compare(sequence, other.sequence) : time;
        }
    }

    public MarketWorker(int capacity, int commandBudget, int eventBudget, long intervalMillis) {
        if (capacity < 1 || commandBudget < 1 || eventBudget < 1 || intervalMillis < 1) {
            throw new IllegalArgumentException("Worker budgets and interval must be positive");
        }
        commands = new ArrayBlockingQueue<>(capacity);
        this.commandBudget = commandBudget;
        this.eventBudget = eventBudget;
        executor = Executors.newSingleThreadScheduledExecutor(action -> {
            Thread thread = new Thread(action, "nhkantan-market");
            thread.setDaemon(true);
            writerThread = thread;
            return thread;
        });
        executor.scheduleWithFixedDelay(this::pump, 0, intervalMillis, TimeUnit.MILLISECONDS);
    }

    /** Called by the lifecycle adapter; the supplied tick must be cumulative, not world day time. */
    public void clock(boolean running, long cumulativeTick) {
        gameTick = cumulativeTick;
        lastClockNanos = System.nanoTime();
        active = running;
    }

    /** Only call inside a submitted command/event. */
    public long marketMillis() {
        return logicalMillis;
    }

    public long gameTick() {
        return gameTick;
    }

    public <T> CompletableFuture<T> submit(Supplier<T> command) {
        CompletableFuture<T> result = new CompletableFuture<>();
        synchronized (commands) {
            if (closed || failure != null || !commands.offer(() -> {
                try {
                    if (failure != null) throw new IllegalStateException("Market worker failed", failure);
                    result.complete(command.get());
                } catch (Exception e) {
                    result.completeExceptionally(e);
                }
            })) result.completeExceptionally(new RejectedExecutionException("Market unavailable or busy"));
        }
        return result;
    }

    /** Registers a recurring independently jittered event in writer order. */
    public CompletableFuture<Void> schedule(long seed, long interval, double jitter, double probability,
        Runnable action) {
        if (interval < 1 || interval > 86400000
            || !Double.isFinite(jitter)
            || jitter < 0
            || jitter > 1
            || !Double.isFinite(probability)
            || probability < 0
            || probability > 1) {
            throw new IllegalArgumentException("Invalid randomized schedule");
        }
        Supplier<Void> register = () -> {
            if (action == null || closed || failure != null)
                throw new IllegalStateException("Invalid event registration");
            Event event = new Event(eventSequence++, seed, interval, jitter, probability, action);
            event.deadline = logicalMillis + Math.max(1L, (long) (event.random.nextDouble() * interval));
            events.add(event);
            return null;
        };
        if (Thread.currentThread() == writerThread) return CompletableFuture.completedFuture(register.get());
        return submit(register);
    }

    private void pump() {
        try {
            long now = System.nanoTime();
            long elapsed = Math.min(1000, Math.max(0, TimeUnit.NANOSECONDS.toMillis(now - previousNanos)));
            previousNanos = now;
            boolean running = active && now - lastClockNanos <= TimeUnit.MILLISECONDS.toNanos(250);
            if (running && !closed && failure == null) logicalMillis += elapsed;
            for (int i = 0; i < commandBudget; i++) {
                Runnable command = commands.poll();
                if (command == null) break;
                command.run();
            }
            if (!running || closed || failure != null) return;
            for (int i = 0; i < eventBudget && !events.isEmpty() && events.peek().deadline <= logicalMillis; i++) {
                Event event = events.remove();
                if (event.random.nextDouble() < event.probability) event.action.run();
                // Preserve independent event ordering; never catch up every missed interval after a stall.
                event.deadline = logicalMillis + event.delay();
                events.add(event);
            }
        } catch (Throwable e) {
            failure = e; // Fail closed instead of silently losing a scheduled executor task.
            active = false;
        }
    }

    public Throwable failure() {
        return failure;
    }

    /** Drain accepted commands before shutdown. The caller must not hold a lock needed by a command. */
    @Override
    public void close() {
        synchronized (commands) {
            if (closed) return;
            closed = true;
        }
        active = false;
        executor.execute(() -> {
            Runnable command;
            while ((command = commands.poll()) != null) command.run();
        });
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                throw new IllegalStateException("Market worker did not stop");
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread()
                .interrupt();
            throw new IllegalStateException("Interrupted stopping market", e);
        }
        if (!commands.isEmpty()) {
            throw new IllegalStateException("Market mailbox did not drain");
        }
    }
}
