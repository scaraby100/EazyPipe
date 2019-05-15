/*
 * Copyright 2017 Alessandro Patriarca.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package xyz.scarabya.eazypipe;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author Alessandro Patriarca
 */
public class EazyPipe {

    private final Pipeable pipeable;
    private final Map<Integer, Thread> pipeRunners;
    private final PipeLink pipeLink;
    private final EazyPipe prevEazyPipe;
    private final long eazyPipeId;

    private EazyPipe(EazyPipe prevEazyPipe, PipeLink pipeLink, Pipeable pipeable, long eazyPipeId) {
        this.pipeable = pipeable;
        this.prevEazyPipe = prevEazyPipe;
        this.pipeLink = new PipeLink(pipeLink);
        this.eazyPipeId = eazyPipeId;
        pipeRunners = new ConcurrentHashMap();
        for (int i = 0; i < pipeable.nThread; i++) {
            pipeRunners.put(i, runThread());
        }
    }

    protected EazyPipe(Pipeable newPipe) {
        this(null, null, newPipe, 0);
    }

    public final EazyPipe runPipe(Pipeable nextPipeable) {
        return new EazyPipe(this, pipeLink, nextPipeable, eazyPipeId + 1);
    }

    private Thread runThread() {
        final ThreadPipe threadPipe = new ThreadPipe(pipeable, this.pipeLink);
        final Thread thread = new Thread() {
            @Override
            public void run() {
                try {
                    pipeable.object.getClass().getDeclaredMethod(pipeable.method, ThreadPipe.class).invoke(pipeable.object, threadPipe);
                    if (pipeable.callbackObject != null && areAllReady()) {
                        pipeable.callbackObject.getClass().getDeclaredMethod(pipeable.callbackMethod).invoke(pipeable.callbackObject);
                    }
                } catch (NoSuchMethodException ex) {
                    Logger.getLogger(EazyPipe.class.getName()).log(Level.SEVERE, null, ex);
                } catch (SecurityException ex) {
                    Logger.getLogger(EazyPipe.class.getName()).log(Level.SEVERE, null, ex);
                } catch (IllegalAccessException ex) {
                    Logger.getLogger(EazyPipe.class.getName()).log(Level.SEVERE, null, ex);
                } catch (IllegalArgumentException ex) {
                    Logger.getLogger(EazyPipe.class.getName()).log(Level.SEVERE, null, ex);
                } catch (InvocationTargetException ex) {
                    Logger.getLogger(EazyPipe.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        };
        thread.start();
        return thread;
    }

    public boolean areAllReady() {
        for (Thread runnerToCheck : pipeRunners.values()) {
            if (runnerToCheck.isAlive()) {
                return false;
            }
        }
        return true;
    }

    public final ConcurrentLinkedQueue getOutput() {
        return pipeLink.getOutputChannel();
    }

    protected EazyPipe getPrevEazyPipe() {
        return prevEazyPipe;
    }

    protected String whoAmI() {
        return "EazyPipe ID: " + eazyPipeId;
    }

    protected String whatAmIRunning() {
        return pipeable.method + " method from class " + pipeable.object.getClass().getSimpleName();
    }
}
