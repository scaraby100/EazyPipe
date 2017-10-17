/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package xyz.scarabya.eazypipe;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 *
 * @author a.patriarca
 */
public class PipeLink
{
    private final ConcurrentLinkedQueue channelIn;
    private final ConcurrentLinkedQueue channelOut;

    protected PipeLink(PipeLink prevPipeLink)
    {
        this.channelIn = prevPipeLink.channelOut;
        this.channelOut = new ConcurrentLinkedQueue();
    }
    
    protected PipeLink()
    {
        this.channelIn = null;
        this.channelOut = new ConcurrentLinkedQueue();
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
