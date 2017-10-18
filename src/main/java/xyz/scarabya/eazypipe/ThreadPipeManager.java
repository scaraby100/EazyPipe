/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package xyz.scarabya.eazypipe;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author a.patriarca
 */
public class ThreadPipeManager
{
    public static final void startAutoManager(final EazyPipe eazyPipe)
    {
        Thread thread = new Thread()
        {
            @Override
            public void run()
            {
                while(true)
                {
                    EazyPipe[] scoreBoard = new EazyPipe[1];
                    int i = 0;
                    scoreBoard[i] = eazyPipe;
                    EazyPipe prevEazyPipe = scoreBoard[i].getPrevEazyPipe();                    
                    while(prevEazyPipe != null)
                    {
                        scoreBoard = addToSortedScoreboard(scoreBoard, prevEazyPipe);
                        prevEazyPipe = prevEazyPipe.getPrevEazyPipe();                    
                    }
                    System.out.println("Calculated stats:");
                    i = 0;
                    for(EazyPipe item: scoreBoard)
                    {
                        System.out.print((i+1)+ ")" + item.whoAmI() + " running "+item.whatAmIRunning());
                        System.out.println("; queue size: "+item.getInputSize());
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
                while(i < scoreBoard.length-1 && !optimized)
                {
                    if(scoreBoard[i].isStoppable())
                    {
                        int j = scoreBoard.length-1;
                        while(j > i && !optimized)
                        {
                            if(scoreBoard[j].isOptimizable())
                            {
                                scoreBoard[i].removeThread();
                                scoreBoard[j].addThread();
                                optimized = true;
                            }
                            j--;
                        }
                    }
                    i++;
                }
                Thread.sleep(100);
                return optimized;
            }
            
            private EazyPipe[] addToSortedScoreboard(EazyPipe[] actualScoreBoard, EazyPipe newItem)
            {
                EazyPipe[] newScoreBoard = new EazyPipe[actualScoreBoard.length+1];
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
                if(!added)
                    newScoreBoard[j] = newItem;
                return newScoreBoard;
            }
        };
        thread.start();
    }
}
