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
package xyz.scarabya.eazypipe.utils;

/**
 *
 * @author Alessandro Patriarca
 */
public class EazyUtils
{
    public static double pointsTrend(final Point[] points, double initialValue)
    {
        double area = 0;
        long diffT;
        double sumVal;
        for(int i=1; i<points.length; i++)
        {
            diffT = points[i].t - points[i-1].t;
            sumVal = points[i].value + points[i-1].value - (2 * initialValue);
            area += sumVal * diffT / 2;
        }        
        return area;
    }
}
