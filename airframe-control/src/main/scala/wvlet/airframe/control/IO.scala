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
package wvlet.airframe.control
import java.io.{ByteArrayOutputStream, File, InputStream, OutputStream}
import java.net.URL
import java.nio.charset.StandardCharsets

import wvlet.airframe.control.Control.withResource

/**
  */
object IO {

  def readAsString(f: File): String = {
    readAsString(f.toURI.toURL)
  }

  def readAsString(url: URL): String = {
    withResource(url.openStream()) { in => readAsString(in) }
  }

  def readAsString(in: InputStream): String = {
    new String(readFully(in), StandardCharsets.UTF_8)
  }

  def readFully(in: InputStream): Array[Byte] = {
    val byteArray =
      if (in == null) {
        Array.emptyByteArray
      } else {
        withResource(new ByteArrayOutputStream) { b =>
          val buf = new Array[Byte](8192)
          withResource(in) { src =>
            var readBytes = 0
            while ({
              readBytes = src.read(buf);
              readBytes != -1
            }) {
              b.write(buf, 0, readBytes)
            }
          }
          b.toByteArray
        }
      }
    byteArray
  }

  /**
    * Copy data from an InputStream to an OutputStream
    *
    * @param in
    *   the input stream to read from
    * @param out
    *   the output stream to write to
    * @throws NullPointerException
    *   if in or out is null
    */
  def copy(in: InputStream, out: OutputStream): Unit = {
    if (in == null) {
      throw new NullPointerException("InputStream cannot be null")
    }
    if (out == null) {
      throw new NullPointerException("OutputStream cannot be null")
    }

    withResource(in) { src =>
      val buf       = new Array[Byte](8192)
      var readBytes = 0
      while ({
        readBytes = src.read(buf)
        readBytes != -1
      }) {
        out.write(buf, 0, readBytes)
      }
      out.flush()
    }
  }

}
