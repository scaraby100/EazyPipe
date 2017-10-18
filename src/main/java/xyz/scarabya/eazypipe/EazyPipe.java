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
    
    private final Pipeable pipe;
    private final Map<Integer, ThreadPipe> threadPipe;
    private final PipeLink pipeLink;
    private int threadIdCounter;
    private final Map<Integer, Boolean> isReady;
    private final EazyPipe prevEazyPipe;
    private final long eazyPipeId;
    private boolean isStopRequestDone;
    
    private EazyPipe(EazyPipe prevEazyPipe, PipeLink pipeLink, Pipeable newPipe, long eazyPipeId)
    {
        pipe = newPipe;
        this.prevEazyPipe = prevEazyPipe;
        threadPipe = new ConcurrentHashMap();
        isReady = new ConcurrentHashMap();
        this.pipeLink = new PipeLink(pipeLink);
        threadIdCounter = pipe.thread;
        this.eazyPipeId = eazyPipeId;
        for (int i = 0; i < pipe.thread; i++)
        {
            isReady.put(i, false);
            threadPipe.put(i, new ThreadPipe(pipe, this.pipeLink, i));
            runThread(i);
        }            
    }
    
    protected EazyPipe(Pipeable newPipe)
    {
        pipe = newPipe;
        this.prevEazyPipe = null;
        threadPipe = new ConcurrentHashMap();
        isReady = new ConcurrentHashMap();
        this.pipeLink = new PipeLink();
        threadIdCounter = pipe.thread;
        eazyPipeId = 0;
        for (int i = 0; i < pipe.thread; i++)
        {
            isReady.put(i, false);
            threadPipe.put(i, new ThreadPipe(pipe, this.pipeLink, i));
            runThread(i);
        }
    }
    
    private EazyPipe()
    {
        pipeLink = null;
        pipe = null;
        isReady = null;
        prevEazyPipe = null;
        threadPipe = null;
        eazyPipeId = 0;
    }
    
    public final EazyPipe runPipe(Pipeable nextPipeable)
    {
        return new EazyPipe(this, pipeLink, nextPipeable, eazyPipeId+1);
    }
    
    private void runThread(final int threadId)
    {
        Thread thread = new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    //System.out.println("Calling " + pipe.method + " from class " +pipe.object.getClass().toString());
                    //System.out.println("pipe.object = " + pipe.object.toString() + ";  threadId = " +threadId + "; threadPipe.get(threadId) = "+ threadPipe.get(threadId).toString());
                    pipe.object.getClass().getDeclaredMethod(pipe.method, ThreadPipe.class).invoke(pipe.object, threadPipe.get(threadId));
                    isReady.put(threadId, true);
                    if(pipe.callbackObject != null && areAllReady())
                    {
                        pipe.callbackObject.getClass().getDeclaredMethod(pipe.callbackMethod).invoke(pipe.object);
                    }
                }
                catch (NoSuchMethodException ex)
                {
                    Logger.getLogger(EazyPipe.class.getName()).log(Level.SEVERE, null, ex);
                }
                catch (SecurityException ex)
                {
                    Logger.getLogger(EazyPipe.class.getName()).log(Level.SEVERE, null, ex);
                }
                catch (IllegalAccessException ex)
                {
                    Logger.getLogger(EazyPipe.class.getName()).log(Level.SEVERE, null, ex);
                }
                catch (IllegalArgumentException ex)
                {
                    Logger.getLogger(EazyPipe.class.getName()).log(Level.SEVERE, null, ex);
                }
                catch (InvocationTargetException ex)
                {
                    Logger.getLogger(EazyPipe.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
            
            private boolean areAllReady()
            {
                for(boolean ready : isReady.values())
                    if(!ready) return false;
                return true;
            }
        };
        thread.start();
    }
    
    public final ConcurrentLinkedQueue getOutput()
    {
        return pipeLink.getOutputChannel();
    }
    
    protected long getInputSize()
    {
        if (pipeLink.hasInputChannel())
            return pipeLink.getInputChannelSize();
        else
            return 1;
    }
    
    protected EazyPipe getPrevEazyPipe()
    {
        return prevEazyPipe;
    }
    
    protected boolean isStoppable()
    {
        return pipe.optimizable && threadIdCounter > 1;
    }
    
    protected boolean isOptimizable()
    {
        return pipe.optimizable && (getInputSize() > threadIdCounter);
    }
    
    protected void removeThread() throws InterruptedException
    {
        int actualThreadId = threadIdCounter -1;
        threadPipe.get(actualThreadId).stopThread();
        while(!isReady.get(actualThreadId))
        {
            pause(100);
        }
        threadPipe.remove(actualThreadId);
        isReady.remove(actualThreadId);
        threadIdCounter--;
        System.out.println("Stopped thread "+actualThreadId+" of " +pipe.method + " method from class " +pipe.object.getClass().toString());
    }
    
    protected void pause(int time) throws InterruptedException
    {
        Thread.sleep(time);
    }
    
    protected void addThread()
    {
        isReady.put(threadIdCounter, false);
        threadPipe.put(threadIdCounter, new ThreadPipe(pipe, this.pipeLink, threadIdCounter));
        runThread(threadIdCounter);
        threadIdCounter++;
        System.out.println("Added thread "+threadIdCounter+" of " +pipe.method + " method from class " +pipe.object.getClass().toString());
    }
    
    protected String whoAmI()
    {
        return "EazyPipe ID: "+eazyPipeId;
    }
    
    protected String whatAmIRunning()
    {
        return pipe.method + " method from class " +pipe.object.getClass().getSimpleName() + " with " + threadIdCounter + " thread(s)";
    }
    
}
