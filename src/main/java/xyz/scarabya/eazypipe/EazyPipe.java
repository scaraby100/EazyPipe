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
import java.util.LinkedList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;
import xyz.scarabya.eazypipe.utils.EazyUtils;
import xyz.scarabya.eazypipe.utils.Point;

/**
 *
 * @author Alessandro Patriarca
 */
public class EazyPipe {
    
    private final Pipeable pipe;
    private final RunnersMap runnersMap;
    private final PipeLink pipeLink;
    private final EazyPipe prevEazyPipe;
    private final long eazyPipeId;
    private int threadIdCounter;
    private int optimizeScore;
    private int optimizeOperations;
    
    private EazyPipe(EazyPipe prevEazyPipe, PipeLink pipeLink, Pipeable newPipe, long eazyPipeId)
    {
        pipe = newPipe;
        this.prevEazyPipe = prevEazyPipe;
        runnersMap = new RunnersMap();
        this.pipeLink = new PipeLink(pipeLink);
        threadIdCounter = pipe.nThread;
        this.eazyPipeId = eazyPipeId;
        for (int i = 0; i < pipe.nThread; i++)
            runnersMap.addRunner(i, runThread());
    }
    
    protected EazyPipe(Pipeable newPipe)
    {
        this(null, null, newPipe, 0);
    }
    
    private EazyPipe()
    {
        pipeLink = null;
        pipe = null;
        prevEazyPipe = null;
        runnersMap = null;
        eazyPipeId = 0;
    }
    
    public final EazyPipe runPipe(Pipeable nextPipeable)
    {
        return new EazyPipe(this, pipeLink, nextPipeable, eazyPipeId+1);
    }
    
    private PipeRunner runThread()
    {
        final ThreadPipe threadPipe = new ThreadPipe(pipe, this.pipeLink);
        Thread thread = new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    pipe.object.getClass().getDeclaredMethod(pipe.method, ThreadPipe.class).invoke(pipe.object, threadPipe);
                    if(pipe.callbackObject != null && areAllReady())
                    {
                        pipe.callbackObject.getClass().getDeclaredMethod(pipe.callbackMethod).invoke(pipe.callbackObject);
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
                for(PipeRunner runnerToCheck : runnersMap.getPipeRunners())
                    if(!runnerToCheck.isRunning()) return false;
                return true;
            }
        };
        thread.start();
        return new PipeRunner(thread, threadPipe);
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
        return pipe.optimizable && (getInputSize() > threadIdCounter)
                && (optimizeOperations<2 || optimizeScore>2);
    }
    
    protected int stopThread() throws InterruptedException
    {
        int actualThreadId = threadIdCounter -1;
        runnersMap.stopRunner(actualThreadId);
        System.out.println("Sended stop signal to thread "+actualThreadId+" of " +pipe.method + " method from class " +pipe.object.getClass().toString());
        return actualThreadId;
    }
    
    protected boolean isStopped(int threadId)
    {
        return runnersMap.isRunnerRunning(threadId);
    }
    
    protected void completeThreadRemove(int threadId)
    {
        runnersMap.removeRunner(threadId);
        threadIdCounter--;
        System.out.println("Stopped thread "+threadId+" of " +pipe.method + " method from class " +pipe.object.getClass().toString());
    }
    
    protected void addThread()
    {        
        long initialQueueSize = getInputSize();
        runnersMap.addRunner(threadIdCounter, runThread());
        threadIdCounter++;
        System.out.println("Added thread "+threadIdCounter+" of " +pipe.method + " method from class " +pipe.object.getClass().toString());
        checkForProgess(initialQueueSize);
    }
    
    protected void setOptimizeScore(int score)
    {
        optimizeScore = score;
    }
    
    protected String whoAmI()
    {
        return "EazyPipe ID: "+eazyPipeId;
    }
    
    protected String whatAmIRunning()
    {
        return pipe.method + " method from class " +pipe.object.getClass().getSimpleName() + " with " + threadIdCounter + " thread(s)";
    }
    
    protected void checkForProgess(long initialQueueSize)
    {
        final long startMeasure = System.currentTimeMillis();
        long currentTime = System.currentTimeMillis();
        final LinkedList<Point> points = new LinkedList();
        while(currentTime - startMeasure < 1000)
        {
            points.add(new Point(currentTime, getInputSize()));
            currentTime = System.currentTimeMillis();
        }        
        final double score = EazyUtils.pointsTrend(
                points.toArray(new Point[points.size()]), initialQueueSize);
        optimizeOperations++;
        optimizeScore = score > 0 ? 1 : -1;
    }
    
}
