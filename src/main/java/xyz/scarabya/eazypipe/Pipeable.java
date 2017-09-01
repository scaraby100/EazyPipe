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

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 *
 * @author Alessandro Patriarca
 */
public class Pipeable {
    protected ConcurrentLinkedQueue channelIn;
    protected final ConcurrentLinkedQueue channelOut;
    protected final Object object;
    protected final String method;

    public Pipeable(Object object, String method) {
        this.channelIn = null;
        this.channelOut = new ConcurrentLinkedQueue();
        this.object = object;
        this.method = method;
    }
    
    protected void linkPipe(Pipeable inputPipe)
    {
        this.channelIn = inputPipe.channelOut;
    }
    
    public void output(Object objectToAdd)
    {
        channelOut.add(objectToAdd);
    }
    
    public Object input()
    {
        return channelIn.poll();
    }
}
