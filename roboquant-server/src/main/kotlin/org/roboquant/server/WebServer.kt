/*
 * Copyright 2020-2023 Neural Layer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.roboquant.server

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.runBlocking
import org.roboquant.Roboquant
import org.roboquant.common.TimeSpan
import org.roboquant.common.Timeframe
import org.roboquant.feeds.Feed
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.set


internal val runs = ConcurrentHashMap<String, RunInfo>()

/**
 * Create a server with credentials. The website will be protected with digest authentication.
 */
class WebServer(username: String, password: String, port:Int = 8080, host:String = "127.0.01") {

    /**
     * Create server instance without credentials, so anybody can see the runs and pause them.
     */
    constructor(port:Int = 8080, host:String = "127.0.01") : this("", "", port, host)

    private var runCounter = 0
    private val server :  NettyApplicationEngine = embeddedServer(
        Netty,
        port = port,
        host = host,
        module = {
            if (username.isNotEmpty()) this.setupSecure(username, password) else this.setup()
        }
    ).start(wait = false)



    fun stop() {
        server.stop()
    }


    @Synchronized
    fun getRunName() : String {
        return "run-${runCounter++}"
    }

    fun run(roboquant: Roboquant, feed: Feed, timeframe: Timeframe, warmup: TimeSpan = TimeSpan.ZERO) = runBlocking {
        runAsync(roboquant, feed, timeframe, warmup)
    }

    /**
     * Start a new run and make core metrics available to the webserver. You can start multiple runs in the same
     * webserver instance. Each run will have its unique name.
     */
    suspend fun runAsync(roboquant: Roboquant, feed: Feed, timeframe: Timeframe, warmup: TimeSpan = TimeSpan.ZERO) {
        val run = getRunName()
        val metric = WebMetric()
        val rq = roboquant.copy(metrics = roboquant.metrics + metric, policy = PausablePolicy(roboquant.policy))
        runs[run] = RunInfo(metric, rq, feed, timeframe, warmup)
        rq.runAsync(feed, timeframe, run, warmup)
    }

}
