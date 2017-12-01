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

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 *
 * @author Alessandro Patriarca
 */
public class RunnersMap
{
    private final Map<Integer, PipeRunner> pipeRunners;

    public RunnersMap()
    {
        this.pipeRunners = new ConcurrentHashMap();
    }

    protected PipeRunner getPipeRunner(int runnerId)
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
        pipeRunners.get(runnerId).stopThread();
    }

    protected boolean isRunnerRunning(int runnerId)
    {
        return pipeRunners.get(runnerId).isThreadRunning();
    }

    protected void removeRunner(int runnerId)
    {
        pipeRunners.remove(runnerId);
    }

}
