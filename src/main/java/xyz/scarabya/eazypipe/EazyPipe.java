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
    private ThreadPipe threadPipe[];
    private final PipeLink pipeLink;
    private int threadIdCounter;
    private final Map<Integer, Boolean> isReady;
    
    private EazyPipe(PipeLink pipeLink, Pipeable newPipe)
    {
        pipe = newPipe;
        threadPipe = new ThreadPipe[pipe.thread];
        isReady = new ConcurrentHashMap();
        this.pipeLink = new PipeLink(pipeLink);
        
        for (int i = 0; i < pipe.thread; i++)
        {
            int threadIndex = threadIdCounter;
            threadIdCounter++;
            isReady.put(threadIndex, false);
            threadPipe[i] = new ThreadPipe(pipe, pipeLink, threadIndex);
            runThread(i);
        }
            
    }
    
    public EazyPipe()
    {
        pipe = null;
        pipeLink = new PipeLink();
        isReady = null;        
    }
    
    public final EazyPipe runPipe(Pipeable nextPipeable)
    {
        return new EazyPipe(pipeLink, nextPipeable);
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
                    //final ThreadPipe threadPipe = new ThreadPipe(pipe, threadIndex);
                    System.out.println("Calling " + pipe.method + " from class " +pipe.object.getClass().toString());
                    System.out.println("pipe.object = " + pipe.object.toString() + ";  threadId = " +threadId + "; threadPipe[threadId] = "+ threadPipe[threadId].toString());
                    pipe.object.getClass().getDeclaredMethod(pipe.method, ThreadPipe.class).invoke(pipe.object, threadPipe[threadId]);
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
        return threadPipe[0].pipeLink.getOutputChannel();
    }
    
    public final void startAutoThreadManager()
    {
        
    }
}
