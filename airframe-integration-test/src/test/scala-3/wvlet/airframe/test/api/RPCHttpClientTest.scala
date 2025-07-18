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
package wvlet.airframe.test.api

import wvlet.airframe.Design
import wvlet.airframe.http.{Http, RPCMethod, RxRouter}
import wvlet.airframe.http.client.SyncClient
import wvlet.airframe.http.netty.{Netty, NettyServer}
import wvlet.airframe.surface.Surface
import wvlet.airspec.AirSpec
import wvlet.airframe.http.HttpHeader.MediaType

/**
  * RPCHttpClient test using local Netty server instead of external httpbin.org
  */
class RPCHttpClientTest extends AirSpec:

  override protected def design: Design =
    Design.newDesign
      .add(
        Netty.server
          .withRouter(RxRouter.of[MockServer])
          .design
      )
      .bind[SyncClient].toProvider { (server: NettyServer) =>
        Http.client.newSyncClient(server.localAddress)
      }

  case class TestRequest(id: Int, name: String)
  case class TestResponse(url: String, headers: Map[String, Any])

  test("Create an RPCSyncClient") { (client: SyncClient) =>
    val m        = RPCMethod("/post", "example.Api", "test", Surface.of[TestRequest], Surface.of[TestResponse])
    val response = client.rpc[TestRequest, TestResponse](m, TestRequest(1, "test"))

    // Test message
    debug(response)
    response.headers.get("Content-Type") shouldBe Some(MediaType.ApplicationJson)
  }
