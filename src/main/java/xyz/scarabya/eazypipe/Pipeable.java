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
public class Pipeable {

    protected final Object object;
    protected final String method;
    protected final Object args;
    protected final int nThread;
    protected final Object callbackObject;
    protected final String callbackMethod;

    public Pipeable(Object object, String method) {
        this(object, method, 1, null, null, null);
    }

    public Pipeable(Object object, String method, Object args) {
        this(object, method, 1, args, null, null);
    }

    public Pipeable(Object object, String method, int thread) {
        this(object, method, thread, null, null, null);
    }

    public Pipeable(Object object, String method, int thread, Object args) {
        this(object, method, thread, args, null, null);
    }

    public Pipeable(Object object, String method, Object callbackObject, String callbackMethod) {
        this(object, method, 1, null, callbackObject, callbackMethod);
    }

    public Pipeable(Object object, String method, Object args, Object callbackObject, String callbackMethod) {
        this(object, method, 1, args, callbackObject, callbackMethod);
    }

    public Pipeable(Object object, String method, int thread, Object callbackObject, String callbackMethod) {
        this(object, method, thread, null, callbackObject, callbackMethod);
    }

    public Pipeable(Object object, String method, int thread, Object args, Object callbackObject, String callbackMethod) {
        this.object = object;
        this.method = method;
        this.args = args;
        this.nThread = thread;
        this.callbackObject = callbackObject;
        this.callbackMethod = callbackMethod;
    }

    public Object args() {
        return args;
    }
}
