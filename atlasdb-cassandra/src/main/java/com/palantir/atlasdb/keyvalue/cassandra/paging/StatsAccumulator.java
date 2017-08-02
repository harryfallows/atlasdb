/*
 * Copyright 2017 Palantir Technologies, Inc. All rights reserved.
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.atlasdb.keyvalue.cassandra.paging;

// Replate this with StatsAccumulator from Guava 20.0 if we ever bump the dependency.
public class StatsAccumulator {
    private double mean = 0.0;
    private double sumOfDeltaSquares = 0.0;
    private long count = 0;

    long count() {
        return count;
    }

    void add(double value) {
        count += 1;
        double delta = value - mean;
        mean += delta / count;
        sumOfDeltaSquares += delta * (value - mean);
    }

    double mean() {
        return mean;
    }

    double populationStandardDeviation() {
        return Math.sqrt(Math.max(0.0, sumOfDeltaSquares) / count);
    }
}
