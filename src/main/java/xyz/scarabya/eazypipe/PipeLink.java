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
public class PipeLink
{
    private final ConcurrentLinkedQueue channelIn;
    private final ConcurrentLinkedQueue channelOut;

    protected PipeLink(PipeLink prevPipeLink)
    {
        this.channelIn = prevPipeLink == null ? null : prevPipeLink.channelOut;
        this.channelOut = new ConcurrentLinkedQueue();
    }

    private PipeLink()
    {
        this.channelIn = null;
        this.channelOut = null;
    }

    protected void output(Object objectToAdd)
    {
        channelOut.add(objectToAdd);
    }

    protected Object input()
    {
        return channelIn.poll();
    }

    protected ConcurrentLinkedQueue getOutputChannel()
    {
        return channelOut;
    }

    protected Object pollOutput()
    {
        return channelOut.poll();
    }

    protected long getOutputChannelSize()
    {
        return channelOut.size();
    }

    protected long getInputChannelSize()
    {
        return channelIn.size();
    }

    protected boolean hasInputChannel()
    {
        return channelIn != null;
    }

}
