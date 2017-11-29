/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package xyz.scarabya.eazypipe;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 *
 * @author a.patriarca
 */
public class RunnersMap
{
    private final Map<Integer, PipeRunner> pipeRunners;

    public RunnersMap()
    {
        this.pipeRunners = new ConcurrentHashMap();
    }
    
    public PipeRunner getPipeRunner(int runnerId)
    {
        return pipeRunners.get(runnerId);
    }
    
    protected Thread getThread(int runnerId)
    {
        return pipeRunners.get(runnerId).getThread();
    }
    
    protected ThreadPipe getThreadPipe(int runnerId)
    {
        return pipeRunners.get(runnerId).getThreadPipe();
    }
    
    protected void addRunner(int runnerId, PipeRunner runner)
    {
        pipeRunners.put(runnerId, runner);
    }
    
    protected Collection<PipeRunner> getPipeRunners()
    {
        return pipeRunners.values();
    }
    
    protected void stopRunner(int runnerId)
    {
        pipeRunners.get(runnerId).getThreadPipe().stopThread();
    }
    
    protected boolean isRunnerRunning(int runnerId)
    {
        return pipeRunners.get(runnerId).getThread().isAlive();
    }
    
    protected void removeRunner(int runnerId)
    {
        pipeRunners.remove(runnerId);
    }
    
}
