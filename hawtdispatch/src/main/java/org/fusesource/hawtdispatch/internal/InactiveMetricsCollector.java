/**
 *  Copyright (C) 2009-2010, FuseSource Corp.  All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fusesource.hawtdispatch.internal;

import org.fusesource.hawtdispatch.Metrics;

/**
 *
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 *
 */
final public class InactiveMetricsCollector implements MetricsCollector {

    public static final InactiveMetricsCollector INSTANCE = new InactiveMetricsCollector();

    public Runnable track(Runnable runnable) {
        return runnable;
    }

    public Metrics metrics() {
        return null;
    }


}