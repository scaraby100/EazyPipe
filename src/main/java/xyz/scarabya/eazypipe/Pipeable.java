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
public class Pipeable
{
    protected final Object object;
    protected final String method;
    protected final Object args;
    protected final int nThread;
    protected final Object callbackObject;
    protected final String callbackMethod;
    protected final boolean optimizable;

    public Pipeable(Object object, String method)
    {
        this.object = object;
        this.method = method;
        this.args = null;
        this.nThread = 1;
        this.callbackObject = null;
        this.callbackMethod = null;
        this.optimizable = false;
    }

    public Pipeable(Object object, String method, Object args)
    {
        this.object = object;
        this.method = method;
        this.args = args;
        this.nThread = 1;
        this.callbackObject = null;
        this.callbackMethod = null;
        this.optimizable = false;
    }

    public Pipeable(Object object, String method, int thread)
    {
        this.object = object;
        this.method = method;
        this.args = null;
        this.nThread = thread;
        this.callbackObject = null;
        this.callbackMethod = null;
        this.optimizable = false;
    }

    public Pipeable(Object object, String method, int thread, Object args)
    {
        this.object = object;
        this.method = method;
        this.args = args;
        this.nThread = thread;
        this.callbackObject = null;
        this.callbackMethod = null;
        this.optimizable = false;
    }

    public Pipeable(Object object, String method, Object callbackObject, String callbackMethod)
    {
        this.object = object;
        this.method = method;
        this.args = null;
        this.nThread = 1;
        this.callbackObject = callbackObject;
        this.callbackMethod = callbackMethod;
        this.optimizable = false;
    }

    public Pipeable(Object object, String method, Object args, Object callbackObject, String callbackMethod)
    {
        this.object = object;
        this.method = method;
        this.args = args;
        this.nThread = 1;
        this.callbackObject = callbackObject;
        this.callbackMethod = callbackMethod;
        this.optimizable = false;
    }

    public Pipeable(Object object, String method, int thread, Object callbackObject, String callbackMethod)
    {
        this.object = object;
        this.method = method;
        this.args = null;
        this.nThread = thread;
        this.callbackObject = callbackObject;
        this.callbackMethod = callbackMethod;
        this.optimizable = false;
    }

    public Pipeable(Object object, String method, int thread, Object args, Object callbackObject, String callbackMethod)
    {
        this.object = object;
        this.method = method;
        this.args = args;
        this.nThread = thread;
        this.callbackObject = callbackObject;
        this.callbackMethod = callbackMethod;
        this.optimizable = false;
    }

    public Pipeable(Object object, String method, boolean optimizable)
    {
        this.object = object;
        this.method = method;
        this.args = null;
        this.nThread = 1;
        this.callbackObject = null;
        this.callbackMethod = null;
        this.optimizable = optimizable;
    }

    public Pipeable(Object object, String method, Object args, boolean optimizable)
    {
        this.object = object;
        this.method = method;
        this.args = args;
        this.nThread = 1;
        this.callbackObject = null;
        this.callbackMethod = null;
        this.optimizable = optimizable;
    }

    public Pipeable(Object object, String method, int thread, boolean optimizable)
    {
        this.object = object;
        this.method = method;
        this.args = null;
        this.nThread = thread;
        this.callbackObject = null;
        this.callbackMethod = null;
        this.optimizable = optimizable;
    }

    public Pipeable(Object object, String method, int thread, Object args, boolean optimizable)
    {
        this.object = object;
        this.method = method;
        this.args = args;
        this.nThread = thread;
        this.callbackObject = null;
        this.callbackMethod = null;
        this.optimizable = optimizable;
    }

    public Pipeable(Object object, String method, Object callbackObject, String callbackMethod, boolean optimizable)
    {
        this.object = object;
        this.method = method;
        this.args = null;
        this.nThread = 1;
        this.callbackObject = callbackObject;
        this.callbackMethod = callbackMethod;
        this.optimizable = optimizable;
    }

    public Pipeable(Object object, String method, Object args, Object callbackObject, String callbackMethod, boolean optimizable)
    {
        this.object = object;
        this.method = method;
        this.args = args;
        this.nThread = 1;
        this.callbackObject = callbackObject;
        this.callbackMethod = callbackMethod;
        this.optimizable = optimizable;
    }

    public Pipeable(Object object, String method, int thread, Object callbackObject, String callbackMethod, boolean optimizable)
    {
        this.object = object;
        this.method = method;
        this.args = null;
        this.nThread = thread;
        this.callbackObject = callbackObject;
        this.callbackMethod = callbackMethod;
        this.optimizable = optimizable;
    }

    public Pipeable(Object object, String method, int thread, Object args, Object callbackObject, String callbackMethod, boolean optimizable)
    {
        this.object = object;
        this.method = method;
        this.args = args;
        this.nThread = thread;
        this.callbackObject = callbackObject;
        this.callbackMethod = callbackMethod;
        this.optimizable = optimizable;
    }

    public Object args()
    {
        return args;
    }
}
