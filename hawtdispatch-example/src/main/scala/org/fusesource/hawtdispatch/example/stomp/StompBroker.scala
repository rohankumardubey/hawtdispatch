/**
 * Copyright (C) 2009, Progress Software Corporation and/or its
 * subsidiaries or affiliates.  All rights reserved.
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
 */
package org.fusesource.hawtdispatch.example.stomp

import java.nio.channels.SelectionKey._
import org.fusesource.hawtdispatch.ScalaSupport._

import java.nio.channels.{SocketChannel, ServerSocketChannel}
import java.net.{InetAddress, InetSocketAddress}

import java.util.{LinkedList}
import buffer._
import AsciiBuffer._
import org.fusesource.hawtdispatch.example.stomp.Stomp._
import org.fusesource.hawtdispatch.example.{Router, Route}
import collection.mutable.HashMap

object StompBroker {
  def main(args:Array[String]) = {
    println("Starting stomp broker...")
    val broker = new StompBroker();
    println("Startup complete.")
    System.in.read
    println("Shutting down...")
    broker.close
    println("Shutdown complete.")
  }
}
class StompBroker extends Queued {

  type HeaderMap = collection.mutable.Map[AsciiBuffer, AsciiBuffer]

  trait Consumer extends QueuedRetained {
    def deliver(headers:HeaderMap, content:Buffer)
  }

  val router = new Router[AsciiBuffer,Consumer](createSerialQueue("router"))

  val queue = createSerialQueue("broker")

    // Create the nio server socket...
  val channel = ServerSocketChannel.open();
  channel.configureBlocking(false);
  channel.socket().bind(address("0.0.0.0", 61613), 10);

  def address(host: String, port: Int): InetSocketAddress = {
    return new InetSocketAddress(ip(host), port)
  }

  def ip(host: String): InetAddress = {
    return InetAddress.getByName(host)
  }

  // Create a source attached to the server socket to deal with new connections...
  val accept_source = createSource(channel, OP_ACCEPT, queue);
  accept_source.setEventHandler(^{
    var socket = channel.accept();
    socket.configureBlocking(false);
    var connection = new StompConnection(socket)
  });

  accept_source.setCancelHandler(^{
    channel.close();
  });

  accept_source.resume();

  def close = {
    accept_source.cancel
    queue.release
  }

  class StompConnection(val socket:SocketChannel) extends Queued {

    val queue = createSerialQueue("connection")
    val wireFormat = new StompWireFormat()
    val outbound = new LinkedList[StompFrame]()
    var read_suspended = false;

    def send(frame:StompFrame) = {
      outbound.addLast(frame);
      if( outbound.size == 1 ) {
        write_source.resume
      }
    }
    val write_source = createSource(socket, OP_WRITE, queue);
    write_source.setEventHandler(^{
      val drained = wireFormat.drain_to_socket(socket) {
        outbound.poll
      }
      // Once drained, we don't need write events..
      if( drained ) {
        write_source.suspend
      }
    });

    val read_source = createSource(socket, OP_READ, queue);
    read_source.setEventHandler(^{
      wireFormat.read_socket(socket) {
        frame:StompFrame=>
          on_frame(frame)
          // this lets the wireformat know if we need to stop reading frames.
          read_suspended
      }
      if( read_suspended ) {
        read_suspended = false
        read_source.suspend
      }
    });
    read_source.setCancelHandler(^{
      socket.close();
    });
    read_source.resume();


    def close = {
      write_source.cancel
      read_source.cancel
      queue.release
    }

    def on_frame(frame:StompFrame) = {
      frame match {
        case StompFrame(Commands.CONNECT, headers, _) =>
          on_stomp_connect(headers)
        case StompFrame(Commands.SEND, headers, content) =>
          on_stomp_send(headers, content)
        case StompFrame(Commands.SUBSCRIBE, headers, content) =>
          on_stomp_subscribe(headers)
        case StompFrame(unknown, _, _) =>
          die("Unsupported STOMP command: "+unknown);
      }
    }

    def on_stomp_connect(headers:HeaderMap) = {
      send(StompFrame(Responses.CONNECTED))
    }

    class Producer(var sendRoute:Route[AsciiBuffer,Consumer]) extends QueuedRetained {
      override val queue = StompConnection.this.queue
      def send(headers:HeaderMap, content:Buffer) = ^ {
      } ->: queue
    }

    var producerRoute:Route[AsciiBuffer, Consumer]=null

    def on_stomp_send(headers:HeaderMap, content:Buffer) = {
      headers.get(Headers.Send.DESTINATION) match {
        case Some(dest)=>
          // create the producer route...
          if( producerRoute==null || producerRoute.destination!= dest ) {

            // clean up the previous producer..
            if( producerRoute!=null ) {
              router.disconnect(producerRoute)
              producerRoute=null
            }

            // don't process frames until we are connected..
            read_suspended = true
            router.connect(dest, queue) {
              read_source.resume
              route:Route[AsciiBuffer, Consumer] =>
              producerRoute = route
              send_via_route(producerRoute, headers, content)
            }
          } else {
            // we can re-use the existing producer route
            send_via_route(producerRoute, headers, content)
          }
        case None=>
          die("destination not set.")
      }
    }

    def send_via_route(route:Route[AsciiBuffer, Consumer], headers:HeaderMap, content:Buffer) = {
      route.targets.foreach(consumer=>{
        consumer.deliver(headers, content)
      })
    }

    def on_stomp_subscribe(headers:HeaderMap) = {
      headers.get(Headers.Subscribe.DESTINATION) match {
        case Some(dest)=>

          class SimpleConsumer(override val queue:DispatchQueue) extends Consumer {
            override def deliver(headers:HeaderMap, content:Buffer) = ^ {
              send(StompFrame(Responses.MESSAGE, headers, content))
            } ->: queue
          }
          
          router.bind(dest, new SimpleConsumer(queue) :: Nil)
          // TODO: track consumer so he can be 'unbound'

        case None=>
          die("destination not set.")
      }

    }

    private def die(msg:String) = {
      read_suspended = true
      send(StompFrame(Responses.ERROR, new HashMap(), ascii(msg)))
      ^ {
        close
      } ->: queue
    }


  }

}

