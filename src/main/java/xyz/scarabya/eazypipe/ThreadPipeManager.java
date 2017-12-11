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

import java.util.LinkedList;
import java.util.logging.Level;
import java.util.logging.Logger;
import xyz.scarabya.eazypipe.utils.EazyUtils;
import xyz.scarabya.eazypipe.utils.Point;

/**
 *
 * @author Alessandro Patriarca
 */
public class ThreadPipeManager
{
    private static boolean runAutoManager;
    
    public static final void startAutoManager(final EazyPipe eazyPipe)
    {
        runAutoManager = true;
        Thread thread = new Thread()
        {
            @Override
            public void run()
            {
                while (runAutoManager)
                {
                    EazyPipe[] scoreBoard = new EazyPipe[1];
                    int i = 0;
                    scoreBoard[i] = eazyPipe;
                    EazyPipe prevEazyPipe = scoreBoard[i].getPrevEazyPipe();
                    while (prevEazyPipe != null)
                    {
                        scoreBoard = addToSortedScoreboard(scoreBoard, prevEazyPipe);
                        prevEazyPipe = prevEazyPipe.getPrevEazyPipe();
                    }
                    System.out.println("Calculated stats:");
                    i = 0;
                    for (EazyPipe item : scoreBoard)
                    {
                        System.out.print((i + 1) + ")" + item.whoAmI() + " running " + item.whatAmIRunning());
                        System.out.println("; queue size: " + item.getInputSize());
                        i++;
                    }
                    try
                    {
                        optimize(scoreBoard);
                    }
                    catch (InterruptedException ex)
                    {
                        Logger.getLogger(ThreadPipeManager.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }
            }

            private boolean optimize(EazyPipe[] scoreBoard) throws InterruptedException
            {
                int i = 0;
                boolean optimized = false;
                while (i < scoreBoard.length - 1 && !optimized)
                {
                    if (scoreBoard[i].isStoppable())
                    {
                        int j = scoreBoard.length - 1;
                        while (j > i && !optimized)
                        {
                            if (scoreBoard[j].isOptimizable())
                            {
                                int stoppingId = scoreBoard[i].stopThread();
                                while (!scoreBoard[i].isStopped(stoppingId))
                                    sleep(100);
                                scoreBoard[i].completeThreadRemove(stoppingId);
                                scoreBoard[j].addThread();
                                optimized = true;
                            }
                            j--;
                        }
                    }
                    i++;
                }
                return optimized;
            }

            private EazyPipe[] addToSortedScoreboard(EazyPipe[] actualScoreBoard, EazyPipe newItem)
            {
                EazyPipe[] newScoreBoard = new EazyPipe[actualScoreBoard.length + 1];
                long newItemSize = newItem.getInputSize();
                int j = 0;
                boolean added = false;
                for (EazyPipe actualScore : actualScoreBoard)
                {
                    if ((!added && actualScore.getInputSize() >= newItemSize))
                    {
                        newScoreBoard[j] = newItem;
                        j++;
                        added = true;
                    }
                    newScoreBoard[j] = actualScore;
                    j++;
                }
                if (!added)
                    newScoreBoard[j] = newItem;
                return newScoreBoard;
            }
        };
        thread.start();
    }
    
    private static int checkForProgess(EazyPipe buffed, long initialQueueSize)
    {
        final long startMeasure = System.currentTimeMillis();
        long currentTime = System.currentTimeMillis();
        final LinkedList<Point> points = new LinkedList();
        while(currentTime - startMeasure < 1000)
        {
            points.add(new Point(currentTime, buffed.getInputSize()));
            currentTime = System.currentTimeMillis();
        }        
        final double score = EazyUtils.pointsTrend(
                points.toArray(new Point[points.size()]), initialQueueSize);
        return score > 0 ? 1 : -1;
    }
    
    public void stopAutoManager()
    {
        runAutoManager = false;
    }
}
