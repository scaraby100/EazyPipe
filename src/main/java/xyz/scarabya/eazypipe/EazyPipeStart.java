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
public class EazyPipeStart
{
    public static final EazyPipe runPipe(Pipeable nextPipeable)
    {
        return new EazyPipe(nextPipeable);
    }
}
