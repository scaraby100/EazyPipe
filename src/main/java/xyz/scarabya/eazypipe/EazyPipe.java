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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author Alessandro Patriarca
 */
public class EazyPipe {
    
    private final Pipeable pipe;
    
    private EazyPipe(Pipeable prevPipe, Pipeable newPipe)
    {
        pipe = newPipe;
        if(prevPipe != null)
            pipe.linkPipe(prevPipe);
        for (int i = 0; i < pipe.thread; i++)
            runThread();
    }
    
    public EazyPipe()
    {
        pipe = null;
    }
    
    public final EazyPipe runChain(Pipeable nextPipeable)
    {
        return new EazyPipe(pipe, nextPipeable);
    }
    
    private void runThread()
    {
        Thread thread = new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    pipe.object.getClass().getDeclaredMethod(pipe.method, Pipeable.class).invoke(pipe.object, pipe);
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
        };
        thread.start();
    }
    
    public final ConcurrentLinkedQueue getOutput()
    {
        return pipe.channelOut;
    }    
}
