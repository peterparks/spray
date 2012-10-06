package cc.spray.can.spdy

import cc.spray.can.parsing.{IntermediateState, ParsingState}
import java.nio.ByteBuffer
import java.util.zip.{InflaterInputStream, Inflater, Deflater, DeflaterInputStream}
import java.io.ByteArrayInputStream
import annotation.tailrec

class FrameHeaderParser(inflater: Inflater) extends ReadXBytesAndThen(8) {
  def finished(bytes: Array[Byte]): ParsingState = {
    val Array(_, _, _, _, _, l1, l2, l3) = bytes
    new FrameDataReader(inflater, bytes, Conversions.u3be(l1, l2, l3))
  }
}

class FrameDataReader(inflater: Inflater, header: Array[Byte], length: Int) extends ReadXBytesAndThen(length) {
  import Conversions._

  def finished(dataBytes: Array[Byte]): ParsingState = {
    val Array(h1, h2, h3, h4, flagsB, _*) = header
    val flags = b2i(flagsB)

    if ((h1 & 0x80) != 0) { // control frame
      val version = u2be(h1, h2) & 0x7fff // strip off control frame bit

      if (version != 2) {
        println("Got unsupported version "+version)
        FrameParsingError(ErrorCodes.UNSUPPORTED_VERSION)
      }
      else {
        import ControlFrameTypes._
        import Flags._

        val tpe = u2be(h3, h4)
        println("Got control frame "+tpe)

        tpe match {
          case SYN_STREAM =>
            val streamId = u4be(dataBytes(0), dataBytes(1), dataBytes(2), dataBytes(3)) & 0x7fffffff
            val associatedTo = u4be(dataBytes(4), dataBytes(5), dataBytes(6), dataBytes(7)) & 0x7fffffff
            val prio = (b2i(dataBytes(8)) & 0xc0) >> 6

            val headers = readHeaders(inflater, dataBytes.drop(10))

            SynStream(streamId, associatedTo, prio, FLAG_FIN(flags), FLAG_UNIDIRECTIONAL(flags), headers)

          case SYN_REPLY =>
            val streamId = u4be(dataBytes(0), dataBytes(1), dataBytes(2), dataBytes(3)) & 0x7fffffff
            val headers = readHeaders(inflater, dataBytes.drop(6))

            SynReply(streamId, FLAG_FIN(flags), headers)

          case RST_STREAM =>
            val streamId = u4be(dataBytes(0), dataBytes(1), dataBytes(2), dataBytes(3)) & 0x7fffffff
            val statusCode = u4be(dataBytes(4), dataBytes(5), dataBytes(6), dataBytes(7))

            assert(flags == 0)

            RstStream(streamId, statusCode)

          case SETTINGS =>
            val numSettings = u4be(dataBytes(0), dataBytes(1), dataBytes(2), dataBytes(3))

            def readSetting(idx: Int): Setting = {
              val offset = 4 + idx * 8
              val id = u3le(dataBytes(offset), dataBytes(offset + 1), dataBytes(offset + 2))
              val flags = b2i(dataBytes(offset + 3))
              val value = u4be(dataBytes(offset + 4), dataBytes(offset + 5), dataBytes(offset + 6), dataBytes(offset + 7))

              Setting(id, flags, value)
            }

            val settings = (0 until numSettings).map(readSetting)

            Settings(FLAG_SETTINGS_CLEAR_PREVIOUSLY_PERSISTED_SETTINGS(flags), settings)

          case PING =>
            val id = u4be(dataBytes(0), dataBytes(1), dataBytes(2), dataBytes(3))
            Ping(id, header ++ dataBytes)
          case _ =>
            FrameParsingError(ErrorCodes.PROTOCOL_ERROR)
        }
      }
    } else { // data frame
      val streamId = u4be(h1, h2, h3, h4)
      DataFrame(streamId, flags, length, dataBytes)
    }
  }
}

sealed trait FrameFinished extends ParsingState

sealed trait ControlFrame extends FrameFinished

case class SynStream(streamId: Int, associatedTo: Int, priority: Int, fin: Boolean, unidirectional: Boolean, keyValues: Map[String, String]) extends ControlFrame
case class SynReply(streamId: Int, fin: Boolean, keyValues: Map[String, String]) extends ControlFrame
case class RstStream(streamId: Int, statusCode: Int) extends ControlFrame
case class Ping(pingId: Int, rawData: Array[Byte]) extends ControlFrame

case class Setting(id: Int, flags: Int, value: Int)
case class Settings(clearPersistedSettings: Boolean, settings: Seq[Setting]) extends ControlFrame

//case class ControlFrame(version: Int, tpe: Int, flags: Int, length: Int, data: Array[Byte]) extends FrameFinished
case class DataFrame(streamId: Int, flags: Int, length: Int, data: Array[Byte]) extends FrameFinished
case class FrameParsingError(errorCode: Int) extends FrameFinished

object ControlFrameTypes {
  val SYN_STREAM = 1
  val SYN_REPLY = 2
  val RST_STREAM = 3
  val SETTINGS = 4
  val NOOP = 5
  val PING = 6
  val GOAWAY = 7
  val HEADERS = 8
}
object Flags {
  import Conversions.flag

  val FLAG_FIN = flag(0x01)
  val FLAG_UNIDIRECTIONAL = flag(0x02)

  val FLAG_SETTINGS_CLEAR_PREVIOUSLY_PERSISTED_SETTINGS = flag(0x01)
}
object ErrorCodes {
  val PROTOCOL_ERROR = 1
  val UNSUPPORTED_VERSION = 4
}

abstract class ReadXBytesAndThen(var numBytes: Int) extends IntermediateState {
  val buffer = new Array[Byte](numBytes)

  def finished(bytes: Array[Byte]): ParsingState

  def read(buf: ByteBuffer): ParsingState = {
    val toRead = math.min(numBytes, buf.remaining)
    buf.get(buffer, buffer.length - numBytes, toRead)

    val remaining = numBytes - toRead
    if (remaining > 0) {
      numBytes = remaining
      this
    } else
      finished(buffer)
  }

  override def toString: String = "Parser %s at position %d of %d" format (getClass.getSimpleName, buffer.length - numBytes, buffer.length)
}

object Conversions {
  def b2i(b1: Byte): Int = b1 & 0xff

  def u2be(b1: Byte, b2: Byte): Int =
    (b2i(b1) << 8) | b2i(b2)

  def u3be(b1: Byte, b2: Byte, b3: Byte): Int =
    (u2be(b1, b2) << 8) | b2i(b3)

  def u4be(b1: Byte, b2: Byte, b3: Byte, b4: Byte): Int =
    (u3be(b1, b2, b3) << 8) | b2i(b4)

  def u3le(b1: Byte, b2: Byte, b3: Byte): Int =
    (((b3 << 8) | b2) << 8) | b1

  def flag(flag: Int): Int => Boolean = cand => (cand & flag) == flag

  lazy val dictionary = ("""
    |optionsgetheadpostputdeletetraceacceptaccept-charsetaccept-encodingaccept-
    |languageauthorizationexpectfromhostif-modified-sinceif-matchif-none-matchi
    |f-rangeif-unmodifiedsincemax-forwardsproxy-authorizationrangerefererteuser
    |-agent10010120020120220320420520630030130230330430530630740040140240340440
    |5406407408409410411412413414415416417500501502503504505accept-rangesageeta
    |glocationproxy-authenticatepublicretry-afterservervarywarningwww-authentic
    |ateallowcontent-basecontent-encodingcache-controlconnectiondatetrailertran
    |sfer-encodingupgradeviawarningcontent-languagecontent-lengthcontent-locati
    |oncontent-md5content-rangecontent-typeetagexpireslast-modifiedset-cookieMo
    |ndayTuesdayWednesdayThursdayFridaySaturdaySundayJanFebMarAprMayJunJulAugSe
    |pOctNovDecchunkedtext/htmlimage/pngimage/jpgimage/gifapplication/xmlapplic
    |ation/xhtmltext/plainpublicmax-agecharset=iso-8859-1utf-8gzipdeflateHTTP/1
    |.1statusversionurl""".stripMargin.replaceAll("\n", "")+'\0').getBytes("ASCII")

  def readHeaders(inflater: Inflater, data: Array[Byte]): Map[String, String] = {
    def dump(data: Array[Byte]) =
      println("Data "+data.length+" is "+data.map(_ formatted "%02x").mkString(" "))

    inflater.setInput(data)

    dump(data)


    val buf = new Array[Byte](1000)
    val read = inflater.inflate(buf)
    if (read == 0 && inflater.needsDictionary()) {
      println("Need dictionary")
      inflater.setDictionary(dictionary)
    }

    var cur = read
    //while(!inflater.finished()) {
      val read2 = inflater.inflate(buf, cur, 1000 - cur)
      cur += read
    //}
    println("Finished "+inflater.finished())

    val is = new ByteArrayInputStream(buf)
    dump(buf)


    def u2(): Int = u2be(is.read.toByte, is.read.toByte)
    def bytes(num: Int): Array[Byte] = {
      val res = new Array[Byte](num)

      var cur = 0
      while (cur < num) {
        //println("Trying to read %d:%d for %d" format (cur, num-cur, num))
        val read = is.read(res, cur, num - cur)
        cur += read
      }
      res
    }
    def utf8StringOfLength(length: Int): String =
      new String(bytes(length), "utf8")
    def utf8String(): String =
      utf8StringOfLength(u2())

    val entries = u2()
    println("Should read %d entries" format entries)

    @tailrec def readEntries(remaining: Int, current: Seq[(String, String)]): Seq[(String, String)] = {
      if (remaining > 0) {
        val key = utf8String()
        val value = utf8String()

        readEntries(remaining - 1, current :+ (key, value))
      } else
        current
    }
    readEntries(entries, Nil).toMap
  }
}