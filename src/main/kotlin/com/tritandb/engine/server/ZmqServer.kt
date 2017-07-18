package com.tritandb.engine.server

import com.lmax.disruptor.EventFactory
import com.lmax.disruptor.EventHandler
import com.lmax.disruptor.dsl.Disruptor
import com.lmax.disruptor.util.DaemonThreadFactory
import com.natpryce.konfig.*
import com.tritandb.engine.experimental.timestampC.CompressorTs
import com.tritandb.engine.experimental.valueC.CompressorFpDeltaDelta
import com.tritandb.engine.query.op.RangeFlatChunk
import com.tritandb.engine.tsc.Compressor
import com.tritandb.engine.tsc.CompressorFlat
import com.tritandb.engine.tsc.CompressorFlatChunk
import com.tritandb.engine.tsc.data.DisruptorEvent
import com.tritandb.engine.tsc.data.EventProtos.TritanEvent.EventType.*
import com.tritandb.engine.tsc.data.EventProtos.TritanEvent
import com.tritandb.engine.util.BitByteBufferWriter
import com.tritandb.engine.util.BitOutput
import org.zeromq.ZMQ
import java.io.File
import java.io.OutputStream
import kotlin.system.measureTimeMillis


/**
 * TritanDb
 * Created by eugene on 17/05/2017.
 */
class ZmqServer(val config:Configuration) {

//    data class FileCompressor(val compressor: Compressor)

    private object server : PropertyGroup() {
        val port by intType
        val host by stringType
        val bufferSize by intType
        val dataDir by stringType
    }

//    private val C:MutableMap<String, FileCompressor> = mutableMapOf()
    private val C:MutableMap<String, Compressor> = mutableMapOf()

    private val handler: EventHandler<DisruptorEvent> = EventHandler({ (tEvent), _, _ ->
        when(tEvent.type) {
            INSERT -> {
                if(tEvent.hasRows()) {
                    val firstRow = tEvent.rows.getRow(0)
                    val c = GetCompressor(tEvent.name,firstRow.timestamp,firstRow.valueCount)
                    for (row in tEvent.rows.rowList) {
                        c.addRow(row.timestamp, row.valueList)
                    }
                }
            }
            QUERY -> {
                if(tEvent.hasRows()) {
                    val firstRow = tEvent.rows.getRow(0)
                    val bw = File("${config[server.dataDir]}/query_output.txt").bufferedWriter()
                    println(measureTimeMillis { for(r in RangeFlatChunk("${config[server.dataDir]}/${tEvent.name}.tsc").run(firstRow.timestamp,firstRow.getValue(0))) {
                        bw.append(r.timestamp.toString())
                        for(value in r.values)
                            bw.append(",$value")
                        bw.newLine()
                    } })
                }
            }
            INSERT_META -> {
                println(tEvent.name)
            }
            CLOSE -> {
                val c = C.getValue(tEvent.name)
                c.close()
                C.remove(tEvent.name)
                println("closed")
            }
            else -> {
            }
        }
    })

    private fun GetCompressor(name: String, timestamp: Long, valueCount: Int): Compressor {
        if(C.containsKey(name))
            return C.getValue(name)
        else {
//            val o:OutputStream = File("${config[server.dataDir]}/${name}.tsc").outputStream()
//            val b:BitOutput = BitByteBufferWriter(o)
//            val b:BitOutput = BitWriter(o)
//            val c: Compressor = CompressorFlat(timestamp,b,valueCount)
            val c: Compressor = CompressorFlatChunk("${config[server.dataDir]}/${name}.tsc",valueCount,4096 * 16)
            C.put(name,c)
            return c
        }
    }


    fun start() {
        val bufferSize = config[server.bufferSize] // Specify the size of the ring buffer, must be power of 2.
        val disruptor = Disruptor(EventFactory ({ DisruptorEvent() }), bufferSize, DaemonThreadFactory.INSTANCE)

        disruptor.handleEventsWith(handler)
        disruptor.start() // Start the Disruptor, starts all threads running

        // Get the ring buffer from the Disruptor to be used for publishing.
        val ringBuffer = disruptor.ringBuffer

        val context = ZMQ.context(1)
        val receiver = context.socket(ZMQ.PULL)
        //tcp://localhost:5700
        receiver.bind("${config[server.host]}:${config[server.port]}")
        while (!Thread.currentThread().isInterrupted) {
            val msg = receiver.recv()
            ringBuffer.publishEvent { event, _ -> event.value = TritanEvent.parseFrom(msg)}
        }
    }
}