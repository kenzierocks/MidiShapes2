/*
 * This file is part of MidiShapes2, licensed under the MIT License (MIT).
 *
 * Copyright (c) TechShroom Studios <https://techshroom.com>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.techshroom.midishapes.midi.player;

import static com.google.common.base.Preconditions.checkState;

import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.techshroom.midishapes.midi.MidiTiming;
import com.techshroom.midishapes.midi.event.MidiEvent;
import com.techshroom.midishapes.midi.event.StartEvent;
import com.techshroom.midishapes.midi.event.StopEvent;
import com.techshroom.unplanned.core.util.time.Timer;

class MidiEngine implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(MidiEngine.class);

    private final Thread thread = new Thread(this, "MidiEngine");
    private volatile boolean running;
    private volatile long startMillis;
    private final ReadWriteLock runningLock = new ReentrantReadWriteLock();
    private final Condition runningCondition = runningLock.writeLock().newCondition();
    private final AtomicReference<MidiEventChain> chain = new AtomicReference<>();
    private final AtomicReference<MidiTiming> timing = new AtomicReference<>();
    private final AtomicReference<Iterator<MidiEvent>> stream = new AtomicReference<>();

    MidiEngine() {
        thread.setDaemon(true);
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    void start(MidiTiming timing, MidiEventChain chain, Iterator<MidiEvent> stream) {
        checkState(thread.isAlive(), "CRITICAL ERROR, MidiEngine thread is DEAD!");
        this.timing.set(timing);
        this.chain.set(chain);
        this.stream.set(stream);
        setRunning(true);
    }

    void stop() {
        setRunning(false);
        thread.interrupt();
        chain.set(null);
        timing.set(null);
        stream.set(null);
    }

    public long getStartMillis() {
        return startMillis;
    }

    private void setRunning(boolean running) {
        runningLock.writeLock().lock();
        try {
            this.running = running;
            runningCondition.signal();
        } finally {
            runningLock.writeLock().unlock();
        }
    }

    private boolean isRunning() {
        runningLock.readLock().lock();
        try {
            return running;
        } finally {
            runningLock.readLock().unlock();
        }
    }

    private void awaitRunning() throws InterruptedException {
        if (!this.running) {
            runningLock.writeLock().lock();
            try {
                while (!this.running) {
                    try {
                        runningCondition.await();
                    } catch (InterruptedException e) {
                        if (!this.running) {
                            // standard stop procedures
                            continue;
                        }
                        throw e;
                    }
                }
            } finally {
                runningLock.writeLock().unlock();
            }
        }
    }

    @Override
    public void run() {
        while (true) {
            try {
                awaitRunning();

                try {
                    playMidiStream();
                } catch (EarlyReturnError returned) {
                    // thread may be interrupted to stop a running track
                    if (running && Thread.interrupted()) {
                        throw new InterruptedException();
                    }
                }
            } catch (InterruptedException e) {
                // time to die!
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                LOGGER.warn("error in MIDI stream player", e);
            } finally {
                stop();
            }
        }
    }

    /**
     * Check if the thread should return to waiting to run.
     */
    private void checkIfShouldReturn() {
        if (!isRunning() || Thread.interrupted()) {
            throw EarlyReturnError.getInstance();
        }
    }

    private void playMidiStream() {
        // save stream to improve performance
        final Iterator<MidiEvent> stream = this.stream.get();
        final MidiTiming timing = this.timing.get();
        final MidiEventChain chain = this.chain.get();
        // add extra wait before actual start
        long offsetMillis = 200 - timing.getMillisecondOffset(timing.getOffsetTicks());
        final long startMillis = this.startMillis = getCurrentMillis() + offsetMillis;
        int lastEventTick = timing.getOffsetTicks();
        long lastEventCompleteMillis = startMillis;
        try {
            chain.sendEventToNext(StartEvent.create(Integer.MIN_VALUE, 0, 0, startMillis));
            while (stream.hasNext()) {
                checkIfShouldReturn();
                MidiEvent next = stream.next();
                waitForEvent(lastEventTick, next.getTick() - lastEventTick, timing, lastEventCompleteMillis);
                checkIfShouldReturn();
                chain.sendEventToNext(next);
                lastEventTick = next.getTick();
                lastEventCompleteMillis = getCurrentMillis();
            }

            // wait for a second before closing, in case there is any echo, etc.
            // left over after all notes
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } finally {
            chain.sendEventToNext(StopEvent.INSTANCE);
        }
    }

    public long getCurrentMillis() {
        return Timer.getInstance().getValue(TimeUnit.MILLISECONDS);
    }

    private void waitForEvent(int tickBase, int tickDelta, MidiTiming timing, long lastMillis) {
        long lastMillisEstimate = timing.getMillisecondOffset(tickBase);
        long nextMillisEstimate = timing.getMillisecondOffset(tickBase + tickDelta);
        long realDelay = nextMillisEstimate - lastMillisEstimate;
        long eventMillis = lastMillis + realDelay;
        long millisDiff = eventMillis - getCurrentMillis();
        if (millisDiff > 0) {
            // wait, then churn to be accurate
            try {
                Thread.sleep(Math.max(millisDiff - 5, 0));
            } catch (InterruptedException e) {
                throw EarlyReturnError.getInstance();
            }
            while ((eventMillis - getCurrentMillis()) > 0) {
                // churn
            }
        }
    }

}
