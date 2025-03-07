/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package wvlet.airframe.http.netty

import io.netty.buffer.Unpooled
import io.netty.channel.{ChannelFutureListener, ChannelHandlerContext, SimpleChannelInboundHandler}
import io.netty.handler.codec.http.*
import wvlet.airframe.http.HttpMessage.{Request, Response}
import wvlet.airframe.http.internal.RPCResponseFilter
import wvlet.airframe.http.{
  Http,
  HttpHeader,
  HttpMethod,
  HttpServerException,
  HttpStatus,
  RPCException,
  RPCStatus,
  ServerAddress,
  ServerSentEvent
}
import wvlet.airframe.rx.{Cancelable, OnCompletion, OnError, OnNext, Rx, RxRunner}
import wvlet.log.LogSupport

import java.net.InetSocketAddress
import scala.jdk.CollectionConverters.*
import NettyRequestHandler.toNettyResponse

import java.io.ByteArrayOutputStream

class NettyRequestHandler(config: NettyServerConfig, dispatcher: NettyBackend.Filter)
    extends SimpleChannelInboundHandler[FullHttpRequest]
    with LogSupport {

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
    warn(cause)
    ctx.close()
  }

  override def channelReadComplete(ctx: ChannelHandlerContext): Unit = {
    ctx.flush()
  }

  override def channelRead0(ctx: ChannelHandlerContext, msg: FullHttpRequest): Unit = {
    try {
      var req: wvlet.airframe.http.HttpMessage.Request = msg.method().name().toUpperCase match {
        case HttpMethod.GET     => Http.GET(msg.uri())
        case HttpMethod.POST    => Http.POST(msg.uri())
        case HttpMethod.PUT     => Http.PUT(msg.uri())
        case HttpMethod.DELETE  => Http.DELETE(msg.uri())
        case HttpMethod.PATCH   => Http.PATCH(msg.uri())
        case HttpMethod.TRACE   => Http.request(wvlet.airframe.http.HttpMethod.TRACE, msg.uri())
        case HttpMethod.OPTIONS => Http.request(wvlet.airframe.http.HttpMethod.OPTIONS, msg.uri())
        case HttpMethod.HEAD    => Http.request(wvlet.airframe.http.HttpMethod.HEAD, msg.uri())
        case _ =>
          throw RPCStatus.INVALID_REQUEST_U1.newException(s"Unsupported HTTP method: ${msg.method()}")
      }

      // Set remote address for logging purpose
      ctx.channel().remoteAddress() match {
        case x: InetSocketAddress =>
          // TODO This address might be IPv6
          req = req.withRemoteAddress(ServerAddress(s"${x.getHostString}:${x.getPort}"))
        case _ =>
      }

      // Read request headers
      msg.headers().names().asScala.map { x =>
        req = req.withHeader(x, msg.headers().get(x))
      }

      // Read request body
      var bodyBuf: ByteArrayOutputStream = null
      val requestBody                    = msg.content()
      while (requestBody.isReadable) {
        // the returned size is greater than 0 when isReadable = true
        val size = requestBody.readableBytes()
        if (bodyBuf == null) {
          bodyBuf = new ByteArrayOutputStream(size)
        }
        requestBody.readBytes(bodyBuf, size)
      }
      if (bodyBuf != null && bodyBuf.size() > 0) {
        req = req.withContent(bodyBuf.toByteArray)
      }

      // Dispatch the request and get an async response, Rx[Response]
      val rxResponse: Rx[Response] = dispatcher.apply(
        req,
        NettyBackend.newContext { (request: Request) =>
          Rx.single(Http.response(HttpStatus.NotFound_404))
        }
      )

      RxRunner.run(rxResponse) {
        case OnNext(v) =>
          val resp          = v.asInstanceOf[Response]
          val nettyResponse = toNettyResponse(resp)
          writeResponse(msg, ctx, nettyResponse)

          if (resp.isContentTypeEventStream && resp.message.isEmpty) {
            // Read SSE stream
            val c = RxRunner.run(resp.events) {
              case OnNext(e: ServerSentEvent) =>
                val event = e.toContent
                val buf   = Unpooled.copiedBuffer(event.getBytes("UTF-8"))
                ctx.writeAndFlush(new DefaultHttpContent(buf))
              case _ =>
                val f = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
                f.addListener(ChannelFutureListener.CLOSE)
            }
          }
        case OnError(ex) =>
          // This path manages unhandled exceptions
          val resp          = RPCStatus.INTERNAL_ERROR_I0.newException(ex.getMessage, ex).toResponse
          val nettyResponse = toNettyResponse(resp)
          writeResponse(msg, ctx, nettyResponse)
        case OnCompletion =>
      }
    } catch {
      case e: RPCException =>
        writeResponse(msg, ctx, toNettyResponse(e.toResponse))
    } finally {
      // Need to clean up the TLS in case the same thread is reused for the next request
      NettyBackend.clearThreadLocal()
    }
  }

  private def writeResponse(req: HttpRequest, ctx: ChannelHandlerContext, resp: DefaultHttpResponse): Unit = {
    val isEventStream =
      Option(resp.headers())
        .flatMap(h => Option(h.get(HttpHeader.ContentType)))
        .exists(_.contains("text/event-stream"))

    val keepAlive: Boolean =
      HttpStatus.ofCode(resp.status().code()).isSuccessful && (HttpUtil.isKeepAlive(req) || isEventStream)

    if (keepAlive) {
      if (!req.protocolVersion().isKeepAliveDefault) {
        resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
      }
    } else {
      resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
    }
    val f = ctx.writeAndFlush(resp)
    if (!keepAlive) {
      f.addListener(ChannelFutureListener.CLOSE)
    }
  }

}

object NettyRequestHandler extends LogSupport {
  def toNettyResponse(response: Response): DefaultHttpResponse = {
    val r = if (response.isContentTypeEventStream && response.message.isEmpty) {
      val res = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(response.statusCode))
      res.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream")
      res.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
      res.headers().set(HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_CACHE)
      res.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
      res
    } else if (response.message.isEmpty) {
      val res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(response.statusCode))
      // Need to set the content length properly to return the response in Netty
      HttpUtil.setContentLength(res, 0)
      res
    } else {
      val contents = response.message.toContentBytes
      val buf      = Unpooled.wrappedBuffer(contents)
      val res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(response.statusCode), buf)
      HttpUtil.setContentLength(res, contents.size)
      res
    }
    val h = r.headers()
    response.header.entries.foreach { e =>
      h.set(e.key, e.value)
    }
    r
  }
}
