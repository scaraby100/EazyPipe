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

/**
 *
 * @author Alessandro Patriarca
 */
public class ThreadPipe {

    private final PipeLink pipeLink;
    private final Object args;

    protected ThreadPipe(Pipeable originalPipe, PipeLink pipeLink) {
        this.pipeLink = pipeLink;
        this.args = originalPipe.args;
    }

    public void output(Object objectToAdd) {
        pipeLink.output(objectToAdd);
    }

    public Object input() {
        return pipeLink.input();
    }

    public void done() {
        pipeLink.closeOut();
    }

    public boolean run() {
        return pipeLink.isOpenIn() || !pipeLink.inputIsEmpty();
    }

    public Object args() {
        return args;
    }
}
