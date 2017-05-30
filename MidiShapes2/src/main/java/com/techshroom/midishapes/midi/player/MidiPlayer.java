/*
 * This file is part of UnplannedDescent, licensed under the MIT License (MIT).
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

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.AbstractIterator;
import com.google.common.collect.Sets;
import com.techshroom.midishapes.midi.MidiFile;
import com.techshroom.midishapes.midi.event.MidiEvent;

/**
 * Asynchronous MIDI file player.
 */
public class MidiPlayer {

    private static final Logger LOGGER = LoggerFactory.getLogger(MidiPlayer.class);

    private final ScheduledExecutorService pool;
    private final MidiEngine engine = new MidiEngine();
    private final Set<Object> midiEventListeners = Collections.synchronizedSet(Sets.newIdentityHashSet());
    private volatile MidiFile midiFile;
    private volatile MidiSoundPlayer sounds = MidiSoundPlayer.getDefault();
    private volatile boolean looping;

    @Inject
    MidiPlayer(ScheduledExecutorService pool) {
        this.pool = pool;
    }

    public void setSounds(MidiSoundPlayer sounds) {
        this.sounds = sounds;
    }

    public MidiSoundPlayer getSounds() {
        return sounds;
    }

    public void addMidiEventListener(Object object) {
        midiEventListeners.add(object);
    }

    public void removeMidiEventListener(Object object) {
        midiEventListeners.remove(object);
    }

    public void setLooping(boolean looping) {
        LOGGER.debug("Looping = {}", looping);
        this.looping = looping;
    }

    public void play(MidiFile file) {
        stop();

        midiFile = file;
        engine.start(file.getTimingData(), sounds, reduceTracks(file), midiEventListeners);
    }

    /**
     * Turns the separate tracks into a single iterator sorted by tick.
     */
    private Iterator<MidiEvent> reduceTracks(MidiFile file) {
        return new AbstractIterator<MidiEvent>() {

            private int[] indexes = new int[file.getTracks().size()];

            @Override
            protected MidiEvent computeNext() {
                int trackToIncrement = -1;
                MidiEvent next = null;
                for (int i = 0; i < file.getTracks().size(); i++) {
                    MidiEvent atList = listGet(i);
                    if (atList == null) {
                        continue;
                    }

                    if (next == null) {
                        next = atList;
                        trackToIncrement = i;
                    } else {
                        if (next.getTick() > atList.getTick()) {
                            next = atList;
                            trackToIncrement = i;
                        }
                    }
                }
                if (next != null && trackToIncrement != -1) {
                    indexes[trackToIncrement]++;
                }
                if (next == null && looping) {
                    // play same file after 2 seconds
                    pool.schedule(() -> {
                        if (midiFile != null) {
                            play(midiFile);
                        }
                    }, 2, TimeUnit.SECONDS);
                }
                return next == null ? endOfData() : next;
            }

            @Nullable
            private MidiEvent listGet(int index) {
                int listIndex = indexes[index];
                List<MidiEvent> list = file.getTracks().get(index).getEvents();
                if (listIndex >= list.size()) {
                    return null;
                }
                return list.get(listIndex);
            }
        };
    }

    public boolean isRunning() {
        return midiFile != null;
    }

    public void stop() {
        engine.stop();
        midiFile = null;
    }

}
