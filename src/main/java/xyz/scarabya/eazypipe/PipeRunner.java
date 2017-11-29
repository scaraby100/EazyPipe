/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package xyz.scarabya.eazypipe;

/**
 *
 * @author a.patriarca
 */
public class PipeRunner
{
    private final ThreadPipe threadPipe;
    private final Thread thread;
    
    protected PipeRunner(Thread thread, ThreadPipe threadPipe)
    {
        this.thread = thread;
        this.threadPipe = threadPipe;
    }
    
    public ThreadPipe getThreadPipe()
    {
        return threadPipe;
    }
    
    public Thread getThread()
    {
        return thread;
    }
}
