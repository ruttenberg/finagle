package com.twitter.finagle.http.codec

import com.twitter.finagle.dispatch.GenSerialClientDispatcher
import com.twitter.finagle.http.filter.HttpNackFilter
import com.twitter.finagle.transport.Transport
import com.twitter.finagle.Dtab
import com.twitter.util.{Throw, Future, Promise, Return}
import org.jboss.netty.handler.codec.http.{HttpHeaders, HttpRequest, HttpResponse}


/**
 * Client dispatcher which additionally encodes Dtabs and performs response
 * casting.
 */
private[finagle] class DtabHttpDispatcher(
  trans: Transport[Any, Any]
) extends GenSerialClientDispatcher[HttpRequest, HttpResponse, Any, Any](trans) {

  import GenSerialClientDispatcher.wrapWriteException

  protected def dispatch(req: HttpRequest, p: Promise[HttpResponse]): Future[Unit] = {
    HttpDtab.clear(req)
    HttpDtab.write(Dtab.local, req)

    if (!req.isChunked && !HttpHeaders.isContentLengthSet(req)) {
      val len = req.getContent().readableBytes
      // Only set the content length if we are sure there is content. This
      // behavior complies with the specification that user agents should not
      // set the content length header for messages without a payload body.
      if (len > 0) HttpHeaders.setContentLength(req, len)
    }

    trans.write(req) rescue(wrapWriteException) flatMap { _ =>
      trans.read() flatMap {
        case res: HttpResponse if HttpNackFilter.isNack(res) =>
          p.updateIfEmpty(Throw(HttpClientDispatcher.NackFailure))
          Future.Done

        case res: HttpResponse =>
          p.updateIfEmpty(Return(res))
          Future.Done

        case invalid =>
          Future.exception(
            new IllegalArgumentException("invalid message \"%s\"".format(invalid)))
      }
    }
  }
}
